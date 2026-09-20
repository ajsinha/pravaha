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
import java.util.concurrent.atomic.AtomicInteger;
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
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a remote subscriber may ask its buffer to do, and what it is told when it cannot keep up
 * (STRM-16, STRM-10, STRM-15).
 *
 * <p>Three findings, one hole. The ticket carried a view name and filter pairs and nothing else,
 * so every remote subscriber was {@code (10 000, CONFLATE)} whatever it asked for -- and
 * {@code CONFLATE}, by its own javadoc, is "wrong for anything maintaining its own aggregate from
 * the weights". Whatever it then lost reached an {@code AuditSink} once, at the end, and reached
 * the subscriber never. And the only thing between a stalled client and the node's heap was a
 * bound denominated in <em>batches</em>, which says nothing about memory.
 *
 * <p>Nothing here stalls a reader or measures a duration: the overflow is driven by a buffer of one
 * row and a commit of three, and the row bound is checked where the decision is made.
 */
@Timeout(120)
class SubscriptionOverflowTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightClient client;
    private QueryRegistry registry;
    private RegisteredQuery query;
    private RowArena arena;

    @AfterEach
    void stop() throws Exception {
        for (Ended ended : open) {
            FlightStream stream = ended.stream.get();
            if (stream != null && ended.how.get() == null) {
                try {
                    stream.cancel("the test is over", null);
                } catch (RuntimeException e) {
                    // Already gone. Cancelling something twice is not a failure.
                }
            }
            ended.finished.await(30, TimeUnit.SECONDS);
        }
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (registry != null) {
            registry.close();
        }
        if (arena != null) {
            arena.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    private void start() {
        allocator = new RootAllocator(Long.MAX_VALUE);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        ViewCatalog views = new ViewCatalog();
        SecurityPolicy permissive = (principal, view) -> AccessDecision.allow();
        registry = new QueryRegistry(views, permissive, AuditSink.NONE, SCHEMA);
        server = new PravahaFlightServer(views, allocator)
                .hosting(registry)
                .authorizedBy(permissive, AuditSink.NONE)
                .start("localhost", 0);
        query = registry.register("user_volume", "SELECT user_id, total FROM user_volume", List.of(0), DANA);
        client = FlightClient.builder(allocator, Location.forGrpcInsecure("localhost", server.port()))
                .build();
    }

    /** Feeds {@code rows} distinct keys through the query and commits them as one batch. */
    private void commit(int rows) {
        RowLayout layout = RowLayout.of(SCHEMA);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        for (int i = 0; i < rows; i++) {
            long handle = arena.allocate(layout.rowSize(256));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            writer.setString(0, "u" + i);
            writer.setLong(1, i);
            writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
            arena.trimTo(handle, writer.sizeSoFar());
            query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }
        // Applied on the lane's thread, so the commit waits for it rather than racing it.
        query.awaitApplied(java.time.Duration.ofSeconds(10));
        query.commit();
    }

    /**
     * Waits until the server has attached the subscription.
     *
     * <p>A subscription starts from now, so a commit made before it attaches is published to
     * nobody. Sleeping "long enough" makes that a race that passes on a quiet machine and fails on
     * a busy one; asking the query how many subscribers it has makes it a fact.
     */
    private void awaitAttached(int howMany) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (query.subscriberCount() >= howMany) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the subscription never attached");
    }

    /** Subscribes on its own thread, counting batches and recording how the stream ended. */
    private Ended subscribe(byte[] ticket) {
        Ended ended = new Ended();
        open.add(ended);
        Thread.ofVirtual().start(() -> {
            try (FlightStream stream = client.getStream(new Ticket(ticket))) {
                ended.stream.set(stream);
                while (stream.next()) {
                    ended.batches.incrementAndGet();
                    ControlWire.BatchMark mark = markOf(stream.getLatestMetadata());
                    ended.lastMark.set(mark);
                }
                ended.how.set("completed normally, no error");
            } catch (Exception e) {
                ended.how.set(String.valueOf(e.getMessage()));
            } finally {
                ended.finished.countDown();
            }
        });
        return ended;
    }

    private static ControlWire.BatchMark markOf(org.apache.arrow.memory.ArrowBuf metadata) {
        if (metadata == null || metadata.readableBytes() == 0) {
            return null;
        }
        byte[] bytes = new byte[(int) metadata.readableBytes()];
        metadata.getBytes(metadata.readerIndex(), bytes);
        return ControlWire.BatchMark.decode(bytes);
    }

    private static final class Ended {
        final AtomicInteger batches = new AtomicInteger();
        final AtomicReference<String> how = new AtomicReference<>();
        final AtomicReference<ControlWire.BatchMark> lastMark = new AtomicReference<>();
        final AtomicReference<FlightStream> stream = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
    }

    /**
     * Every subscription this test opened, so teardown can end the ones still running.
     *
     * <p>Cancelled, not just abandoned: a reader parked in {@code next()} is holding an Arrow
     * buffer, and closing the allocator under it is reported as a leak -- correctly, because at
     * that moment it genuinely is one. Cancelling unblocks the reader, which closes the stream on
     * its own thread, which is the only thread allowed to.
     */
    private final java.util.List<Ended> open = new java.util.ArrayList<>();

    private static boolean endedWithin(Ended ended, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (ended.how.get() != null) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void awaitBatches(Ended ended, int at, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline && ended.batches.get() < at) {
            Thread.sleep(50);
        }
    }

    @Test
    void strm16TheOverflowPolicyOnTheTicketIsTheOneTheSubscriptionGets() throws Exception {
        start();
        // A buffer of one row and a commit of three, so the policy has to decide twice. FAIL is
        // this subscriber's answer to falling behind -- it asked to be failed rather than lose a
        // change -- and until the ticket carried it there was no way for a remote client to say so:
        // ControlWire.subscribeTicket encoded ["subscribe", view, pairs...] and streamSubscription
        // passed SubscriptionOptions.DEFAULT.
        Ended failing = subscribe(
                ControlWire.subscribeTicket("user_volume", List.of(), new ControlWire.SubscriberPreference(1, "FAIL")));
        awaitAttached(1);
        commit(3);

        assertThat(endedWithin(failing, 30))
                .as("FAIL means this subscription ends rather than losing a change")
                .isTrue();
        assertThat(failing.how.get()).contains("PRV-8004").contains("asked to be failed");
    }

    @Test
    void strm16TheSameOverflowUnderTheDefaultPolicyKeepsTheSubscriptionRunning() throws Exception {
        start();
        // The control, and the property the fix must not cost: the same overflow under the default
        // conflates and the stream carries on. Without it, the test above would pass on a server
        // that failed every subscriber.
        Ended conflating = subscribe(ControlWire.subscribeTicket("user_volume", List.of()));
        awaitAttached(1);
        commit(3);
        awaitBatches(conflating, 1, 30);

        assertThat(conflating.batches.get()).isPositive();
        assertThat(conflating.how.get())
                .as("CONFLATE keeps the latest value per key and keeps going")
                .isNull();
    }

    @Test
    void strm10APlainSubscriptionsBatchesSayWhatItHasLost() throws Exception {
        start();
        // STRM-10. The channel is what was missing: a plain subscription's batches carried no
        // application metadata at all, so there was no place for a loss count to be and the only
        // zero-argument observables the SDK exposed were rows(), batches() and isClosed(). A
        // dashboard that had lost 58 400 of 59 700 rows looked exactly like one that had received
        // all of them. Here nothing is lost, and the assertion is that a client can now ask.
        Ended reading = subscribe(ControlWire.subscribeTicket("user_volume", List.of()));
        awaitAttached(1);
        commit(3);
        awaitBatches(reading, 1, 30);

        ControlWire.BatchMark mark = reading.lastMark.get();
        assertThat(mark)
                .as("a plain batch now carries a mark, which it never did")
                .isNotNull();
        assertThat(mark.kind()).isEqualTo(ControlWire.BatchMark.COMMIT);
        assertThat(mark.dropped()).as("this subscriber kept up").isZero();
        assertThat(mark.frontier())
                .as("and the frontier a plain subscription has never carried is still not carried")
                .isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void strm15TheHandoverIsBoundedInRowsAndNotOnlyInBatches() {
        // STRM-15. SUBSCRIPTION_HANDOVER_BATCHES = 64 is justified in the code as "small on
        // purpose", and 64 is small in batches. A batch is one commit, and a commit under a real
        // feed was measured at up to 2830 rows, so the queue held up to 64 x that in ViewChange
        // objects, their Object[] payloads and their string contents: one stalled subscriber took
        // the server's RSS from 1577 MB to 3262 MB, and ten would not have fit on the machine that
        // measured one. The constant was the only thing between a stalled client and the heap, and
        // it was denominated in the wrong unit.
        assertThat(PravahaFlightSqlProducer.handoverHasRoomFor(0, 2830))
                .as("an ordinary commit is admitted to an empty handover")
                .isTrue();

        // The measured commit size, 64 deep: what the batch bound alone allowed.
        long sixtyFourRealCommits = 64L * 2830L;
        assertThat(PravahaFlightSqlProducer.handoverHasRoomFor(sixtyFourRealCommits - 2830, 2830))
                .as("181 120 rows still fits, so the bound does not cost a subscriber that is merely slow")
                .isTrue();

        // And a wider query, where the same 64 batches is a quantity of memory nobody chose.
        assertThat(PravahaFlightSqlProducer.handoverHasRoomFor(63L * 50_000L, 50_000))
                .as("64 batches of 50 000 rows is 3.2 million rows, which is the 1.7 GB")
                .isFalse();
        assertThat(PravahaFlightSqlProducer.handoverHasRoomFor(250_000L, 1))
                .as("the bound is a bound")
                .isFalse();
    }
}
