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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a subscription ends, and whether the client can tell one ending from another (STRM-12,
 * STRM-14).
 *
 * <p>Three different events used to be one signal on the wire. An administrative drop, a node
 * shutting down and the client's own {@code close()} all arrived as {@code listener.completed()} --
 * "this stream is finished" -- and only the last of the three is an ending a client should accept.
 * A drop is the destruction of the thing the client asked to watch; a shutdown is a query that is
 * journalled, comes back {@code RUNNING} and moves on without the subscriber that stopped.
 *
 * <p>Worse, where a computation is shared, a drop did not end the stream at all: the name and its
 * view went, {@code removeName} returned false because another name held the computation, and the
 * subscriber went on receiving rows under a name a read refused as nonexistent (STRM-14).
 *
 * <p>Nothing here measures a duration. Each test waits for a stream to end, with a generous bound,
 * and asserts on what it ended with.
 */
@Timeout(120)
class SubscriptionEndingTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    @SuppressWarnings("NullAway.Init") /* a test sets it before reading it */
    private BufferAllocator allocator;

    @SuppressWarnings("NullAway.Init") /* a test sets it before reading it */
    private PravahaFlightServer server;

    @SuppressWarnings("NullAway.Init") /* a test sets it before reading it */
    private FlightClient client;

    @SuppressWarnings("NullAway.Init") /* a test sets it before reading it */
    private QueryRegistry registry;

    @AfterEach
    void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (registry != null) {
            registry.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    private void start() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        ViewCatalog views = new ViewCatalog();
        SecurityPolicy permissive = (principal, view) -> AccessDecision.allow();
        registry = new QueryRegistry(views, permissive, AuditSink.NONE, SCHEMA);
        server = new PravahaFlightServer(views, allocator)
                .hosting(registry)
                .authorizedBy(permissive, AuditSink.NONE)
                .start("localhost", 0);
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();
    }

    /** Subscribes on its own thread and records how the stream ended. */
    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    private AtomicReference<String> subscribeUntilItEnds(String view) {
        AtomicReference<String> ended = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try (FlightStream stream = client.getStream(new Ticket(ControlWire.subscribeTicket(view, List.of())))) {
                while (stream.next()) {
                    // Draining. What matters is how the loop exits.
                }
                ended.set("completed normally, no error");
            } catch (Exception e) {
                ended.set(String.valueOf(e.getMessage()));
            }
        });
        return ended;
    }

    private static boolean endedWithin(AtomicReference<String> ended, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (ended.get() != null) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /**
     * Waits until the subscription is really open on the engine's side.
     *
     * <p>Sleeping and then asserting that nothing has *ended* does not say the subscription
     * started: under load the client's `subscribe` can still be in `require(viewName)` when the
     * drop lands, and then it ends with `PRV-8002` -- the name is gone -- rather than with the
     * drop's own refusal. The test then reads as a defect in the ending path, which is the one
     * thing it is not. `subscriberCount()` is the engine's own answer to "is anybody watching",
     * and it is what the drop path itself consults.
     */
    private void awaitSubscriber(String name) throws InterruptedException {
        awaitSubscribers(name, 1);
    }

    /**
     * Waits until {@code count} subscriptions are open on the computation {@code name} belongs to.
     *
     * <p>The count is the <em>computation's</em>, not the name's, and two names can share one
     * computation -- which is the whole subject of STRM-14. Waiting for "at least one" therefore
     * returns as soon as the *other* name's subscriber opens, and the drop can still land before
     * this one's does; the test then waits for an ending that was never going to come. Every
     * caller says how many it started.
     */
    private void awaitSubscribers(String name, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (registry.find(name).map(q -> q.subscriberCount() >= count).orElse(false)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("fewer than " + count + " subscribers reached '" + name + "' within 30s");
    }

    @Test
    void strm12ADropReachesTheClientAsADropRatherThanAsACleanCompletion() throws Exception {
        start();
        registry.register("q65", "SELECT user_id, total FROM user_volume", List.of(0), DANA);

        AtomicReference<String> ended = subscribeUntilItEnds("q65");
        awaitSubscriber("q65");
        assertThat(ended.get()).as("it is running before the drop").isNull();

        registry.drop("q65");

        assertThat(endedWithin(ended, 30)).isTrue();
        assertThat(ended.get())
                .as("'completed normally, no error' is what a client's own close() looks like, and this "
                        + "is the administrative destruction of the thing it asked to watch")
                .doesNotContain("completed normally")
                .contains("PRV-8018")
                .contains("has been dropped");
    }

    @Test
    void strm12ANodeShuttingDownIsToldApartFromAQueryThatIsOver() throws Exception {
        start();
        registry.register("q72", "SELECT user_id, total FROM user_volume", List.of(0), DANA);

        AtomicReference<String> ended = subscribeUntilItEnds("q72");
        awaitSubscriber("q72");
        assertThat(ended.get()).isNull();

        // What a graceful shutdown does: the registry lets go of its queries while Flight drains
        // the calls in flight. The stream used to end with completed(), for a query that is
        // journalled and comes back RUNNING.
        registry.close();

        assertThat(endedWithin(ended, 30)).isTrue();
        assertThat(ended.get())
                .doesNotContain("completed normally")
                .contains("PRV-8019")
                .contains("shutting down");
    }

    @Test
    void strm14ASubscriberUnderADroppedNameStopsAndOneUnderTheSurvivingNameDoesNot() throws Exception {
        start();
        // Byte-identical SQL, so this is one computation with two names -- which is the whole point
        // of fingerprinting the plan, and the reason dropping one name must not stop the other.
        registry.register("q68", "SELECT user_id, total FROM user_volume", List.of(0), DANA);
        registry.register("q68b", "SELECT user_id, total FROM user_volume", List.of(0), DANA);
        assertThat(registry.size()).as("one computation").isEqualTo(1);

        AtomicReference<String> onDropped = subscribeUntilItEnds("q68");
        AtomicReference<String> onSurviving = subscribeUntilItEnds("q68b");
        // One computation, two names (that is the point of the test), so the count is shared:
        // wait for both subscribers rather than twice for "at least one".
        awaitSubscribers("q68", 2);
        assertThat(onDropped.get()).isNull();
        assertThat(onSurviving.get()).isNull();

        registry.drop("q68");

        assertThat(endedWithin(onDropped, 30))
                .as("the name it asked for is gone and so is its view; a read of q68 at this moment "
                        + "is refused, and two server answers to one name cannot contradict each other")
                .isTrue();
        assertThat(onDropped.get()).contains("PRV-8018").contains("q68");
        assertThat(onSurviving.get())
                .as("STRM-067's mirror case was already right and must stay right")
                .isNull();
    }
}
