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
package com.ash.messaging.pravaha.registry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.SqlErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCAN-1 at the registry: over a source that repeats rows, a query whose answer depends on how many
 * times a row arrived is refused before a feed or a sink opens ({@code PRV-2042}), and a keyed view
 * of the rows is admitted -- and is right, however many copies arrive.
 */
class RepeatingSourceRegistrationTest {

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("id", Types.int64())
            .field("status", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final StreamSchema PAYMENTS = StreamSchema.builder("payments")
            .field("id", Types.int64())
            .field("method", Types.string())
            .field("paid", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private SinkDeliveryTest.RecordingSinks sinks;
    private RowArena arena;

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        sinks = new SinkDeliveryTest.RecordingSinks();
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
        }
        arena.close();
    }

    /** A feed factory that attaches nothing and says which streams' sources repeat rows. */
    static SourceFeedFactory repeating(String... streams) {
        Set<String> repeat = Set.of(streams);
        return new SourceFeedFactory() {
            @Override
            public SourceFeed open(
                    String queryName,
                    com.ash.messaging.pravaha.runtime.exec.QueryExecution execution,
                    List<String> sourceStreams,
                    Runnable afterDelivery,
                    Map<String, String> resumeFrom) {
                return SourceFeed.NONE;
            }

            @Override
            public Optional<String> repeatingSource(String stream) {
                return repeat.contains(stream) ? Optional.of("cassandra") : Optional.empty();
            }
        };
    }

    private QueryRegistry registryFeedingFrom(SourceFeedFactory feeds) {
        return new QueryRegistry(new ViewCatalog(), ORDERS, PAYMENTS)
                .writingTo(sinks)
                .feedingFrom(feeds);
    }

    @Test
    void anAggregateOverARepeatingSourceIsRefusedAndNothingIsRegistered() {
        registry = registryFeedingFrom(repeating("orders"));

        assertThatThrownBy(() -> registry.register(
                        "totals", "SELECT COUNT(*) AS n, SUM(amount) AS total FROM orders", List.of(0), DANA))
                .isInstanceOf(PravahaException.class)
                .satisfies(e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(SqlErrors.SOURCE_REPEATS_ROWS))
                .hasMessageContaining("stream 'orders'")
                .hasMessageContaining("`deletes: detect`");
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void theSameAggregateOverAnExactChangelogIsAdmitted() {
        // deletes: detect on the binding: the source answers that it does not repeat.
        registry = registryFeedingFrom(repeating());

        registry.register("totals", "SELECT COUNT(*) AS n, SUM(amount) AS total FROM orders", List.of(0), DANA);

        assertThat(registry.names()).contains("totals");
    }

    @Test
    void aJoinWithOneRepeatingSideIsRefused() {
        registry = registryFeedingFrom(repeating("payments"));

        assertThatThrownBy(() -> registry.register(
                        "paid",
                        "SELECT o.id, o.amount, p.paid FROM orders o JOIN payments p ON o.id = p.id",
                        List.of(0),
                        DANA))
                .hasMessageContaining("PRV-2042")
                .hasMessageContaining("stream 'payments'")
                .hasMessageContaining("join");
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aProjectionToAnAppendOnlySinkIsRefusedBeforeTheSinkOpens() {
        registry = registryFeedingFrom(repeating("orders"));
        sinks.bind("audit", SinkCapabilities.appendOnly());

        assertThatThrownBy(() -> registry.registerWritingTo(
                        "order_rows", "SELECT id, status, amount FROM orders", List.of(0), DANA, "audit"))
                .hasMessageContaining("PRV-2042")
                .hasMessageContaining("sink 'audit'");
        assertThat(sinks.opened()).isZero();
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void aKeyedViewOverARepeatingSourceHoldsEachRowOnceAsTheStoreDoes() {
        registry = registryFeedingFrom(repeating("orders"));
        sinks.bind("orders_table", new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), false, true, 0));
        RegisteredQuery rows = registry.registerWritingTo(
                "order_rows", "SELECT id, status, amount FROM orders", List.of(0), DANA, "orders_table");

        // Three passes of a two-row table, the second row updated before the third: what a
        // deletes: ignore scan delivers, every copy at +1 and nothing retracted.
        for (int pass = 0; pass < 3; pass++) {
            push(rows, 1L, "OPEN", 10L);
            push(rows, 2L, pass < 2 ? "OPEN" : "PAID", 20L);
            rows.commit();
        }

        assertThat(rows.view().scan())
                .as("one row per key, with the values the store holds now")
                .extracting(java.util.Arrays::asList)
                .containsExactlyInAnyOrder(List.of(1L, "OPEN", 10L), List.of(2L, "PAID", 20L));
        assertThat(rows.view().get(2L).values())
                .as("a keyed read returns the current row")
                .hasValueSatisfying(values -> assertThat(values).containsExactly(2L, "PAID", 20L));
        assertThat(sinks.sink("orders_table").rows())
                .as("the upsert sink is written the same row again, which overwrites it")
                .containsOnly("+[1, OPEN, 10]", "+[2, OPEN, 20]", "+[2, PAID, 20]");
    }

    @Test
    void aJournalledAggregateComesBackAsARefusalWhenItsSourceNowRepeats(@TempDir Path directory) {
        // A deployment upgraded with an aggregate already registered over a deletes: ignore binding:
        // recovery reports it by code rather than restoring a total that grows on every pass.
        Path journal = directory.resolve("registry.journal");
        try (QueryRegistry before = new QueryRegistry(
                        new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, ORDERS)
                .journalTo(new RegistryJournal(journal))
                .feedingFrom(repeating())) {
            before.register("totals", "SELECT COUNT(*) AS n FROM orders", List.of(0), DANA);
            before.register("order_rows", "SELECT id, status FROM orders", List.of(0), DANA);
        }

        try (QueryRegistry after = new QueryRegistry(
                        new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, ORDERS)
                .journalTo(new RegistryJournal(journal))
                .feedingFrom(repeating("orders"))) {
            QueryRegistry.Recovery recovery = after.recover(id -> Optional.of(DANA));

            assertThat(recovery.recovered()).containsExactly("order_rows");
            assertThat(recovery.refused()).singleElement().satisfies(refusal -> {
                assertThat(refusal.query()).isEqualTo("totals");
                assertThat(refusal.code()).contains(SqlErrors.SOURCE_REPEATS_ROWS);
                assertThat(refusal.reason()).contains("`deletes: detect`");
            });
        }
    }

    private void push(RegisteredQuery query, long id, String status, long amount) {
        RowLayout layout = RowLayout.of(ORDERS);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id);
        writer.setString(1, status);
        writer.setLong(2, amount);
        writer.weight(1L).eventTimestampNanos(1L).sequence(1L).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("orders", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}
