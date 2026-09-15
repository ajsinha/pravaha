/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.messaging.pravaha.flight;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a subscriber costs the server in threads.
 *
 * <p>A subscription is the longest-lived and least busy call this server serves: {@code
 * streamSubscription} parks on a handover queue for the life of the subscription, waking every 200ms
 * to re-check entitlement. Flight's default executor is a cached pool of <em>platform</em> threads,
 * so each of those parked subscribers was holding a megabyte of stack -- a thousand of them costing
 * a gigabyte before a row had moved, for threads that are almost always doing nothing.
 *
 * <p>The HTTP side of this node already runs on virtual threads. The data plane did not, which left
 * the surface that should scale most cheaply running on the most expensive threads available.
 *
 * <p>This test counts the server's own call threads rather than asserting a memory figure, because
 * the thread count is what the change controls and a heap number would measure the JVM's mood. The
 * assertion is on <em>shape</em>: platform threads must not grow with subscribers.
 */
@Timeout(120)
final class SubscriptionThreadCostTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private static final int SUBSCRIBERS = 40;

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private com.ash.messaging.pravaha.registry.QueryRegistry registry;
    private FlightClient client;

    @AfterEach
    void stop() {
        for (AutoCloseable closeable : List.of(client, server, registry, allocator)) {
            try {
                if (closeable != null) {
                    closeable.close();
                }
            } catch (Exception ignored) {
                // Teardown: one component refusing must not hide the assertion.
            }
        }
    }

    @Test
    void platformThreadsDoNotGrowWithTheNumberOfSubscribers() throws Exception {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ViewCatalog views = new ViewCatalog();
        registry = new com.ash.messaging.pravaha.registry.QueryRegistry(
                views, com.ash.messaging.pravaha.security.SecurityPolicy.PERMISSIVE, AuditSink.NONE, SCHEMA);
        server = new PravahaFlightServer(views, allocator).hosting(registry).start("localhost", 0);
        registry.register(
                "user_volume",
                "SELECT user_id, total FROM user_volume",
                List.of(0),
                new Principal("dana", "acme", Set.of("analyst"), Map.of()));
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();

        long before = flightCallThreads();

        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch open = new CountDownLatch(SUBSCRIBERS);
        for (int i = 0; i < SUBSCRIBERS; i++) {
            // Clients on virtual threads too, so the test harness is not itself the thing that runs
            // out of threads and calls it a result.
            Thread.ofVirtual().start(() -> {
                try (FlightStream stream =
                        client.getStream(new Ticket(ControlWire.subscribeTicket("user_volume", List.of())))) {
                    Schema ignored = stream.getSchema(); // the call is live once the schema arrives
                    open.countDown();
                    while (!stop.get() && stream.next()) {
                        // Parked on the server's side, which is the condition under test.
                    }
                } catch (Exception closing) {
                    open.countDown(); // counted either way; the assertion is about the server
                }
            });
        }

        assertThat(open.await(60, TimeUnit.SECONDS))
                .as("all %d subscriptions reached the server", SUBSCRIBERS)
                .isTrue();

        long during = flightCallThreads();
        stop.set(true);

        assertThat(during - before)
                .as(
                        "%d concurrent subscriptions added %d platform call threads. Flight's default "
                                + "executor is a cached platform pool, so before this each parked subscriber "
                                + "held a thread and a megabyte of stack; on virtual threads the carriers stay "
                                + "bounded by the scheduler's parallelism, not by the subscriber count",
                        SUBSCRIBERS, during - before)
                .isLessThan(SUBSCRIBERS / 2);
    }

    /**
     * Platform threads Flight's own executor has created.
     *
     * <p>Named, because counting every thread in the JVM would count the test's clients, the
     * registry's lanes and whatever the build is doing. Flight's default executor names its threads
     * {@code flight-server-default-executor-N}; a virtual-thread executor creates none of them, and
     * its carriers are {@code ForkJoinPool} workers shared with everything else -- which is the
     * point, and is why the assertion is that this number stays flat.
     */
    private static long flightCallThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("flight-server-default-executor"))
                .count();
    }
}
