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
package com.ash.messaging.pravaha.sdk.flight;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.Decimals;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLIGHTDECIMAL-1, through the Java SDK: a DECIMAL column read by a query, a changelog
 * subscription, an answer-following one and a snapshot, each exactly.
 *
 * <p>Every one of these was PRV-6100 before 2.1. The assertions use {@code BigDecimal.equals},
 * which compares the scale as well as the value, so a column that arrived as a float, or at the
 * wrong scale, fails here.
 */
@Timeout(120)
class JavaSdkDecimalTest {

    private static final StreamSchema PAYMENT = StreamSchema.builder("payment")
            .field("account", Types.string())
            .field("amount", Types.decimal(12, 2))
            .build();

    private QueryRegistry registry;
    private PravahaFlightServer server;
    private PravahaFlightClient client;
    private RowArena arena;

    @BeforeEach
    void start() {
        ViewCatalog views = new ViewCatalog();
        StreamSchema ledgerSchema = StreamSchema.builder("ledger")
                .field("entry_id", Types.string())
                .field("amount", Types.decimal(18, 8).withNullable(true))
                .build();
        ServedView ledger = new ServedView("ledger", ledgerSchema, List.of(0), 100);
        ledger.applyValues(new Object[] {"zero", new BigDecimal("0E-8")}, 1, 10);
        ledger.applyValues(new Object[] {"tiny", new BigDecimal("0.00000001")}, 1, 10);
        ledger.applyValues(new Object[] {"big", new BigDecimal("9999999999.99999999")}, 1, 10);
        ledger.applyValues(new Object[] {"none", null}, 1, 10);
        ledger.commit(10);
        views.register(ledger);
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, PAYMENT);
        server = new PravahaFlightServer(views).hosting(registry).start("localhost", 0);
        client = PravahaFlightClient.connect("grpc://localhost:" + server.port());
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void stop() {
        client.close();
        server.close();
        registry.close();
        arena.close();
    }

    @Test
    void aQueryReadsEveryDecimalExactlyAndPrintsItPlain() {
        List<String> seen = new ArrayList<>();
        try (QueryResult result = client.query("SELECT entry_id, amount FROM ledger")) {
            for (Row row : result) {
                String id = java.util.Objects.requireNonNull(row.getString("entry_id"), "entry_id");
                switch (id) {
                    case "zero" -> {
                        assertThat(row.getBigDecimal("amount")).isEqualTo(new BigDecimal("0E-8"));
                        assertThat(row.getString("amount"))
                                .as("plain, never exponent")
                                .isEqualTo("0.00000000");
                    }
                    case "tiny" -> {
                        assertThat(row.getBigDecimal("amount")).isEqualTo(new BigDecimal("0.00000001"));
                        assertThat(row.getString("amount")).isEqualTo("0.00000001");
                    }
                    case "big" -> {
                        assertThat(row.get("amount")).isEqualTo(new BigDecimal("9999999999.99999999"));
                        assertThat(row.getDouble("amount")).isEqualTo(1.0e10);
                    }
                    case "none" -> {
                        assertThat(row.getBigDecimal("amount")).isNull();
                        assertThat(row.isNull("amount")).isTrue();
                    }
                    default -> throw new AssertionError("unexpected row " + row);
                }
                seen.add(id);
            }
        }
        assertThat(seen).containsExactlyInAnyOrder("zero", "tiny", "big", "none");
    }

    @Test
    void theChangelogTheAnswerAndTheSnapshotCarryDecimalsExactly() throws Exception {
        client.register("balances", "SELECT account, amount FROM payment", List.of(0));
        RegisteredQuery query = registry.require("balances");
        apply(query, "a1", "10.50");

        Copy changelog = new Copy();
        Copy answer = new Copy();
        Copy snapshot = new Copy();
        Subscription plain = client.subscribe("balances", Map.of(), changelog::accept);
        Subscription following = client.subscribeToAnswer("balances", Map.of(), answer::accept);
        Subscription fromSnapshot = client.subscribeFromSnapshot("balances", snapshot::accept);
        List<Thread> readers = List.of(
                Thread.ofVirtual().start(plain::run),
                Thread.ofVirtual().start(following::run),
                Thread.ofVirtual().start(fromSnapshot::run));
        await(() -> query.subscriberCount() >= 3 && snapshot.rows().size() >= 1, "three subscriptions to attach");

        apply(query, "a1", "-0.05");
        apply(query, "a2", "9999999999.99");
        await(
                () -> changelog.rows().size() >= 2
                        && answer.rows().size() >= 2
                        && snapshot.rows().size() >= 2,
                "both commits on every subscription");
        plain.close();
        following.close();
        fromSnapshot.close();
        for (Thread reader : readers) {
            reader.join(5_000);
        }

        List<Object> replaced = List.of("a1", new BigDecimal("-0.05"));
        List<Object> added = List.of("a2", new BigDecimal("9999999999.99"));
        // The answer withdraws the replaced row by its exact value: a retraction that arrived as
        // 10.5 or 10.499999 would not cancel the 10.50 a subscriber holds.
        assertThat(answer.rows())
                .containsEntry(List.of("a1", new BigDecimal("10.50")), -1L)
                .containsEntry(replaced, 1L)
                .containsEntry(added, 1L);
        assertThat(changelog.rows()).containsKeys(replaced, added);
        assertThat(snapshot.first()).containsExactly(List.of("a1", new BigDecimal("10.50")));
        assertThat(snapshot.rows()).containsKeys(replaced, added);
        for (Copy copy : List.of(changelog, answer, snapshot)) {
            assertThat(copy.classes()).as("never a float on the way").containsOnly(BigDecimal.class);
        }
    }

    private void apply(RegisteredQuery query, String account, String amount) {
        arena.reset();
        RowLayout layout = RowLayout.of(PAYMENT);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        BigDecimal value = new BigDecimal(amount);
        writer.setString(0, account).setDecimal(1, Decimals.high(value, 2), Decimals.low(value, 2));
        writer.weight(1L).eventTimestampNanos(0).sequence(1).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
        assertThat(query.awaitApplied(Duration.ofSeconds(10))).isTrue();
        query.commit();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    /** Every row a subscription delivered, and the class each amount arrived as. */
    static final class Copy {

        private final Map<List<Object>, Long> rows = new java.util.HashMap<>();
        private final java.util.Set<Class<?>> classes = new java.util.HashSet<>();

        private @Nullable List<List<Object>> first;

        synchronized void accept(ChangeBatch batch) {
            List<List<Object>> these = new ArrayList<>();
            for (Row row : batch) {
                these.add(List.of(row.toArray()));
                rows.merge(List.of(row.toArray()), row.weight(), Long::sum);
                classes.add(java.util.Objects.requireNonNull(row.get("amount"), "amount")
                        .getClass());
            }
            rows.values().removeIf(weight -> weight == 0);
            if (first == null) {
                first = these;
            }
        }

        synchronized List<List<Object>> first() {
            List<List<Object>> seen = first;
            return seen == null ? List.of() : List.copyOf(seen);
        }

        synchronized Map<List<Object>, Long> rows() {
            return Map.copyOf(rows);
        }

        synchronized java.util.Set<Class<?>> classes() {
            return java.util.Set.copyOf(classes);
        }
    }
}
