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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.FlightServerMiddleware;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.message.IpcOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A snapshot subscriber that cannot keep up is told so, not skipped past a commit (SUB-1).
 *
 * <p>The producer is driven directly, with a stream listener that holds the first write -- the
 * snapshot -- until the engine has committed more than the handover holds. A plain subscription
 * would drop the batches and carry on; a snapshot subscription's promise is every commit, so the
 * stream ends with {@code PRV-6105} and nothing after the gap is written.
 */
@Timeout(60)
class SnapshotSubscriberBehindTest {

    private static final StreamSchema TRADE =
            StreamSchema.builder("trade").field("trade_id", Types.string()).build();

    private BufferAllocator allocator;
    private ViewCatalog views;
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void setUp() {
        allocator = new RootAllocator();
        views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TRADE);
        arena = new RowArena(MemoryAccess.best(), 1 << 16, 1);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
        allocator.close();
    }

    private void feedAndCommit(RegisteredQuery query, String id) {
        arena.reset();
        RowLayout layout = RowLayout.of(TRADE);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, id);
        writer.weight(1L).eventTimestampNanos(0).sequence(1).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
        assertThat(query.awaitApplied(Duration.ofSeconds(10))).isTrue();
        query.commit();
    }

    @Test
    void aSnapshotSubscriberTooFarBehindIsEndedWithPrv6105AndNeverWrittenPastTheGap() throws Exception {
        RegisteredQuery query =
                registry.register("trades", "SELECT trade_id FROM trade", List.of(0), Principal.ANONYMOUS);
        try (PravahaFlightSqlProducer producer = new PravahaFlightSqlProducer(
                        views, allocator, Location.forGrpcInsecure("localhost", 0))
                .withRegistry(registry)) {
            HeldListener listener = new HeldListener();
            Thread streaming = Thread.ofPlatform()
                    .daemon()
                    .start(() -> producer.getStream(
                            new Context(),
                            new Ticket(ControlWire.subscribeFromSnapshotTicket("trades", List.of())),
                            listener));

            assertThat(listener.firstWrite.await(10, TimeUnit.SECONDS))
                    .as("the snapshot is being written")
                    .isTrue();
            for (int i = 0; i < 80; i++) {
                feedAndCommit(query, "T-" + i);
            }
            // Every commit handed to the stream's subscription before it is released. Delivery runs
            // on the subscription's own thread since STRM-8, so without this a loaded run released
            // the stream while some of the 80 were still in transit: it wrote one commit, then fell
            // behind -- correct behaviour, nothing written past the gap, but not the case under test,
            // which is a subscriber already too far behind when it is next able to write.
            assertThat(query.awaitSubscriptionsQuiet(Duration.ofSeconds(20)))
                    .as("the 80 commits reached the stream's subscription")
                    .isTrue();
            listener.release.countDown();
            streaming.join(Duration.ofSeconds(20).toMillis());

            assertThat(listener.error.get())
                    .as("the stream ended with an error")
                    .isNotNull();
            assertThat(listener.error.get().getMessage()).contains("PRV-6105");
            assertThat(listener.marks).as("only the snapshot was written").containsExactly("snapshot-end");
            assertThat(listener.completed).isZero();
        }
    }

    /** A stream listener whose first write waits, standing in for a subscriber that stopped reading. */
    static final class HeldListener implements FlightProducer.ServerStreamListener {

        final CountDownLatch firstWrite = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> marks = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile int completed;

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void setOnCancelHandler(Runnable handler) {}

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void start(VectorSchemaRoot root, DictionaryProvider dictionaries, IpcOption option) {}

        @Override
        public void putNext() {
            marks.add("none");
        }

        @Override
        public void putNext(ArrowBuf metadata) {
            try (metadata) {
                byte[] bytes = new byte[(int) metadata.readableBytes()];
                metadata.getBytes(0, bytes);
                String text = new String(bytes, StandardCharsets.UTF_8);
                marks.add(text.substring("pravaha:".length(), text.lastIndexOf(':')));
            }
            firstWrite.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void putMetadata(ArrowBuf metadata) {
            metadata.close();
        }

        @Override
        public void error(Throwable failure) {
            error.set(failure);
        }

        @Override
        public void completed() {
            completed++;
        }
    }

    /** An anonymous caller. */
    static final class Context implements FlightProducer.CallContext {

        @Override
        public String peerIdentity() {
            return "";
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public <T extends FlightServerMiddleware> T getMiddleware(FlightServerMiddleware.Key<T> key) {
            return null;
        }

        @Override
        public Map<FlightServerMiddleware.Key<?>, FlightServerMiddleware> getMiddleware() {
            return Map.of();
        }
    }
}
