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

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.catalog.Catalog;
import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.CatalogService;
import com.ash.messaging.pravaha.catalog.ObjectKind;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A registration reads its inputs as its registrant is shown them (ADR-059 §4): the row filter and masks
 * of each input go into the plan, into the fingerprint, and on into every query built on the view.
 */
@Timeout(60)
class NarrowedRegistrationTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("card", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal OPS = new Principal("ops", "acme", Set.of("admin"), Map.of());
    private static final Principal ANA = new Principal("ana", "acme", Set.of("analyst"), Map.of("region", "EU"));
    private static final Principal BOB = new Principal("bob", "acme", Set.of("analyst"), Map.of("region", "US"));

    private static final String ALL = "SELECT user_id, region, card, amount FROM txn";

    private Catalog catalog;
    private QueryRegistry registry;
    private ContinuousQueryStatements statements;
    private AuditSink.InMemory audit;
    private RowArena arena;
    private long clock = 1_000_000_000L;

    @BeforeEach
    void setUp() {
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        audit = new AuditSink.InMemory();
        catalog = Catalog.inMemory(Clock.systemUTC());
        catalog.infrastructure(() -> Map.<ObjectKind, Collection<String>>of(ObjectKind.STREAM, List.of("txn")));
        CatalogService service = new CatalogService(catalog, audit);
        CatalogPolicy policy = new CatalogPolicy(service, List.of());
        registry = new QueryRegistry(new ViewCatalog(), policy, audit, TXN);
        statements = new ContinuousQueryStatements(registry, policy, audit);
        sql(OPS, "GRANT CREATE ON NAMESPACE default TO ROLE analyst");
        sql(OPS, "GRANT BUILD_ON ON STREAM txn TO ROLE analyst");
        sql(OPS, "CREATE ROW FILTER region_scope AS region = session_attribute('region') EXCEPT ROLE admin");
        sql(OPS, "CREATE MASK card_hidden ON COLUMN card AS 'XXXX' EXCEPT ROLE admin");
        sql(OPS, "ALTER STREAM txn SET POLICY region_scope");
        sql(OPS, "ALTER STREAM txn SET POLICY card_hidden");
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    private void sql(Principal who, String text) {
        statements.execute(ContinuousStatements.recognize(text).orElseThrow(), who);
    }

    @Test
    void twoRegistrantsNarrowedDifferentlyGetTwoComputationsEachHoldingWhatTheyMaySee() {
        RegisteredQuery ana = registry.register("ana_txn", ALL, List.of(0), ANA);
        RegisteredQuery bob = registry.register("bob_txn", ALL, List.of(0), BOB);
        RegisteredQuery ops = registry.register("ops_txn", ALL, List.of(0), OPS);
        assertThat(ana.view()).isNotSameAs(bob.view());
        assertThat(ana.fingerprint()).isNotEqualTo(bob.fingerprint());
        assertThat(ana.fingerprint()).isNotEqualTo(ops.fingerprint());

        for (RegisteredQuery query : List.of(ana, bob, ops)) {
            txn(query, "u1", "EU", "4111", 10);
            txn(query, "u2", "US", "4222", 20);
            query.commit();
        }
        awaitRows(ana, List.of("[u1, EU, XXXX, 10]"));
        awaitRows(bob, List.of("[u2, US, XXXX, 20]"));
        awaitRows(ops, List.of("[u1, EU, 4111, 10]", "[u2, US, 4222, 20]"));
        assertThat(audit.events())
                .anyMatch(e ->
                        e.action().equals("register:narrowed") && e.target().equals("txn"));
    }

    @Test
    void theSameNarrowingSharesOneComputation() {
        Principal eve = new Principal("eve", "acme", Set.of("analyst"), Map.of("region", "EU"));
        RegisteredQuery ana = registry.register("ana_txn", ALL, List.of(0), ANA);
        RegisteredQuery again = registry.register("eve_txn", ALL, List.of(0), eve);
        assertThat(again.fingerprint()).isEqualTo(ana.fingerprint());
    }

    @Test
    void aMaskedColumnIsNotAKeyNorComparedInARegistration() {
        assertThatThrownBy(() -> registry.register("by_card", "SELECT card, amount FROM txn", List.of(0), ANA))
                .hasMessageContaining("PRV-7006")
                .hasMessageContaining("a key column");
        assertThatThrownBy(() -> registry.register(
                        "per_card", "SELECT user_id, amount FROM txn WHERE card = '4111'", List.of(0), ANA))
                .hasMessageContaining("PRV-7006")
                .hasMessageContaining("a filter operand");
        // The exempt registrant may, and a claim the registrant lacks is refused by name.
        registry.register("by_card", "SELECT card, amount FROM txn", List.of(0), OPS);
        Principal noRegion = new Principal("cy", "acme", Set.of("analyst"), Map.of());
        assertThatThrownBy(() -> registry.register("cy_txn", ALL, List.of(0), noRegion))
                .hasMessageContaining("PRV-7039");
    }

    @Test
    void aQueryOverAMaskedViewCarriesTheMaskIntoItsOwnAnswer() {
        RegisteredQuery all = registry.register("all_txn", ALL, List.of(0), OPS);
        sql(OPS, "CREATE MASK amount_zero ON COLUMN amount AS 0 * amount EXCEPT ROLE admin");
        sql(OPS, "ALTER VIEW all_txn SET POLICY amount_zero");
        sql(OPS, "GRANT BUILD_ON ON VIEW all_txn TO ROLE analyst");

        RegisteredQuery mine = registry.register("ana_amounts", "SELECT user_id, amount FROM all_txn", List.of(0), ANA);
        RegisteredQuery theirs =
                registry.register("ops_amounts", "SELECT user_id, amount FROM all_txn", List.of(0), OPS);
        txn(all, "u1", "EU", "4111", 10);
        txn(all, "u2", "US", "4222", 20);
        all.commit();
        awaitRows(mine, List.of("[u1, 0]", "[u2, 0]"));
        awaitRows(theirs, List.of("[u1, 10]", "[u2, 20]"));
        // The view's own policy, not its source's: ana was not shown the EU rows only here, because the
        // view's owner built it unfiltered and the view carries no row filter of its own.
    }

    private void txn(RegisteredQuery query, String user, String region, String card, long amount) {
        long ts = clock += 1_000_000L;
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user)
                .setString(1, region)
                .setString(2, card)
                .setLong(3, amount)
                .setLong(4, ts);
        writer.weight(1).eventTimestampNanos(ts).sequence(ts).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private static void awaitRows(RegisteredQuery query, List<String> expected) {
        Supplier<List<String>> actual = () -> {
            List<String> rows = new ArrayList<>();
            for (Object[] row : query.view().scan()) {
                rows.add(Arrays.toString(row));
            }
            rows.sort(null);
            return rows;
        };
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!actual.get().equals(expected) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(actual.get()).isEqualTo(expected);
        assertThat(Optional.ofNullable(query.view())).isPresent();
    }
}
