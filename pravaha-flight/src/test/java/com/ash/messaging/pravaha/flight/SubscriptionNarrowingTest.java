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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.arrow.flight.FlightProducer;
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
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ReadAdmission;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A subscription is shown what its principal may see (ADR-059 §4): the snapshot and every commit after
 * it filtered and masked, a tap filter on a masked column refused, and a changed policy ending the stream.
 */
@Timeout(60)
class SubscriptionNarrowingTest {

    private static final StreamSchema PAYMENTS = StreamSchema.builder("payments")
            .field("id", Types.string())
            .field("region", Types.string())
            .field("card", Types.string())
            .build();

    private static final Narrowing EU_MASKED =
            new Narrowing(Optional.of("region = 'EU'"), Map.of("card", "'XXXX'"), List.of());

    private final AtomicReference<Narrowing> narrowing = new AtomicReference<>(EU_MASKED);
    private BufferAllocator allocator;
    private ViewCatalog views;
    private QueryRegistry registry;
    private RowArena arena;
    private RegisteredQuery payments;

    @BeforeEach
    void setUp() {
        allocator = new RootAllocator();
        views = new ViewCatalog();
        SecurityPolicy policy = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }

            @Override
            public Narrowing narrowing(Principal principal, String object) {
                return object.equals("cards") ? java.util.Objects.requireNonNull(narrowing.get()) : Narrowing.NONE;
            }
        };
        registry = new QueryRegistry(views, policy, AuditSink.NONE, PAYMENTS);
        payments = registry.register("cards", "SELECT id, region, card FROM payments", List.of(0), Principal.ANONYMOUS);
        arena = new RowArena(MemoryAccess.best(), 1 << 16, 1);
        this.policy = policy;
    }

    private SecurityPolicy policy;

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
        allocator.close();
    }

    private void pay(String id, String region, String card) {
        arena.reset();
        RowLayout layout = RowLayout.of(PAYMENTS);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, id).setString(1, region).setString(2, card);
        writer.weight(1L).eventTimestampNanos(0).sequence(1).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        payments.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        payments.awaitApplied(Duration.ofSeconds(10));
        payments.commit();
    }

    private PravahaFlightSqlProducer producer() {
        return new PravahaFlightSqlProducer(
                        views,
                        allocator,
                        Location.forGrpcInsecure("localhost", 0),
                        policy,
                        AuditSink.NONE,
                        ReadAdmission.UNLIMITED,
                        Duration.ZERO)
                .withRegistry(registry);
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void theSnapshotAndEveryCommitAreFilteredAndMasked() throws Exception {
        pay("p1", "EU", "4111");
        pay("p2", "US", "4222");
        RegisteredQuery view = payments;
        assertThat(await(() -> view.view().size() == 2)).isTrue();
        try (PravahaFlightSqlProducer producer = producer()) {
            Rows listener = new Rows();
            Thread streaming = Thread.ofVirtual()
                    .start(() -> producer.getStream(
                            new SnapshotSubscriberBehindTest.Context(),
                            new Ticket(ControlWire.subscribeFromSnapshotTicket("cards", List.of())),
                            listener));
            assertThat(await(() -> listener.rows.contains("p1|EU|XXXX|1"))).isTrue();
            pay("p3", "EU", "4333");
            pay("p4", "US", "4444");
            assertThat(await(() -> listener.rows.contains("p3|EU|XXXX|1"))).isTrue();
            assertThat(listener.rows).noneMatch(r -> r.startsWith("p2") || r.startsWith("p4") || r.contains("4111"));

            // The policy changes: the stream ends, naming why, rather than change meaning half-way.
            narrowing.set(new Narrowing(Optional.empty(), Map.of("card", "'XXXX'"), List.of()));
            assertThat(await(() -> listener.error.get() != null)).isTrue();
            assertThat(java.util.Objects.requireNonNull(listener.error.get()).getMessage())
                    .contains("PRV-7007");
            streaming.join(Duration.ofSeconds(10).toMillis());
        }
    }

    @Test
    void aTapFilterOnAMaskedColumnIsRefused() throws Exception {
        try (PravahaFlightSqlProducer producer = producer()) {
            Rows listener = new Rows();
            producer.getStream(
                    new SnapshotSubscriberBehindTest.Context(),
                    new Ticket(ControlWire.subscribeTicket("cards", List.of("card", "4111"))),
                    listener);
            assertThat(listener.error.get()).isNotNull();
            assertThat(java.util.Objects.requireNonNull(listener.error.get()).getMessage())
                    .contains("PRV-7006");
        }
    }

    /** Records each written row as {@code id|region|card|weight}. */
    static final class Rows implements FlightProducer.ServerStreamListener {

        final List<String> rows = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        @SuppressWarnings("NullAway.Init") /* a test sets it before reading it */
        private VectorSchemaRoot root;

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
        public void start(VectorSchemaRoot root, DictionaryProvider dictionaries, IpcOption option) {
            this.root = root;
        }

        @Override
        public void putNext() {
            record();
        }

        @Override
        public void putNext(ArrowBuf metadata) {
            try (metadata) {
                record();
            }
        }

        private void record() {
            for (int row = 0; row < root.getRowCount(); row++) {
                List<String> cells = new ArrayList<>();
                for (int column = 0; column < root.getFieldVectors().size(); column++) {
                    cells.add(String.valueOf(root.getVector(column).getObject(row)));
                }
                rows.add(String.join("|", cells));
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
        public void completed() {}
    }
}
