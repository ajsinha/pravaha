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
package com.ash.messaging.pravaha.serving;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two access paths B8 added to a view read (design section 17.2): a hash probe on the whole
 * key, and the ordered index over the key's last column that {@code RANGE (column)} declares.
 *
 * <p>Two things have to be true, and they are different things. The <strong>answer</strong> must
 * not depend on which path a read takes -- that is what {@code ViewIndexEquivalenceTest} is for --
 * and the path must actually be taken, which is what this proves with the view's own counters. A
 * test that only checked the rows would pass with the index never built, which is exactly the
 * failure worth catching: an optimisation that silently does not happen looks like a correct
 * engine and reads like a slow one.
 */
class ViewIndexTest {

    /** {@code (user_id, window_end)}: probed by the first, ordered by the second. */
    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("window_end", Types.int64())
            .field("total", Types.int64())
            .build();

    private ServedView view;
    private ViewCatalog catalog;

    @BeforeEach
    void setUp() {
        view = new ServedView("user_volume", SCHEMA, List.of(0, 1), 100_000);
        catalog = new ViewCatalog().register(view);
        for (String user : List.of("u1", "u2", "u3")) {
            for (long window = 1000; window <= 10_000; window += 1000) {
                view.applyValues(new Object[] {user, window, window / 10}, 1, window);
            }
        }
        view.commit(10_000);
    }

    private List<Object[]> rows(String sql) {
        return new ViewQuery(catalog).execute(sql).rows();
    }

    // ------------------------------------------------------------------ the point lookup

    @Test
    void aLookupByTheWholeKeyIsAHashProbeAndNotAScan() {
        List<Object[]> found = rows("SELECT total FROM user_volume WHERE user_id = 'u2' AND window_end = 5000");

        assertThat(found).hasSize(1);
        assertThat(found.get(0)[0]).isEqualTo(500L);
        assertThat(view.pointLookups()).isEqualTo(1);
        assertThat(view.scans()).as("thirty rows were not walked to find one").isZero();
    }

    @Test
    void aLookupByTheWholeKeyThatMatchesNothingIsStillAProbe() {
        assertThat(rows("SELECT total FROM user_volume WHERE user_id = 'nobody' AND window_end = 5000"))
                .isEmpty();
        assertThat(view.pointLookups()).isEqualTo(1);
        assertThat(view.scans()).isZero();
    }

    @Test
    void halfAKeyIsNotAProbe() {
        // The prefix alone is every window for that user, and the index is only consulted for a
        // range: a prefix-only read would have to find rows whose ordered column is null, and
        // those are deliberately not in it.
        assertThat(rows("SELECT window_end FROM user_volume WHERE user_id = 'u2'"))
                .hasSize(10);
        assertThat(view.pointLookups()).isZero();
        assertThat(view.rangeLookups()).isZero();
        assertThat(view.scans()).isEqualTo(1);
    }

    @Test
    void aPredicateOnAColumnOutsideTheKeyScans() {
        // Design section 17.2's last row, unchanged: secondary predicate, best effort, scan+filter.
        assertThat(rows("SELECT user_id FROM user_volume WHERE total = 500")).hasSize(3);
        assertThat(view.scans()).isEqualTo(1);
        assertThat(view.indexedRows())
                .as("nothing asked for a range, so nothing was indexed")
                .isZero();
    }

    // ------------------------------------------------------------------ the ordered index

    @Test
    void aRangeOverTheKeysLastColumnIsAnsweredFromTheIndex() {
        List<Object[]> found = rows("SELECT window_end FROM user_volume "
                + "WHERE user_id = 'u2' AND window_end >= 3000 AND window_end < 6000");

        assertThat(found.stream().map(row -> row[0]).toList()).containsExactly(3000L, 4000L, 5000L);
        assertThat(view.rangeLookups()).isEqualTo(1);
        assertThat(view.scans()).isZero();
        assertThat(view.indexBuilds()).isEqualTo(1);
        assertThat(view.indexedRows())
                .as("one entry per committed row, and no copies")
                .isEqualTo(30);
    }

    @Test
    void bothBoundsAreHonouredAndSoIsTheirInclusivity() {
        assertThat(rows("SELECT window_end FROM user_volume "
                                + "WHERE user_id = 'u1' AND window_end > 3000 AND window_end <= 5000")
                        .stream()
                        .map(row -> row[0])
                        .toList())
                .containsExactly(4000L, 5000L);
        assertThat(rows("SELECT window_end FROM user_volume WHERE user_id = 'u1' AND window_end >= 9000").stream()
                        .map(row -> row[0])
                        .toList())
                .containsExactly(9000L, 10_000L);
        assertThat(rows("SELECT window_end FROM user_volume WHERE user_id = 'u1' AND window_end < 3000").stream()
                        .map(row -> row[0])
                        .toList())
                .containsExactly(1000L, 2000L);
        assertThat(view.rangeLookups()).isEqualTo(3);
        assertThat(view.indexBuilds()).as("built once, then maintained").isEqualTo(1);
    }

    @Test
    void theIndexIsBuiltOnceAndThenMaintainedByEveryCommit() {
        rows("SELECT window_end FROM user_volume WHERE user_id = 'u1' AND window_end > 0");
        assertThat(view.indexedRows()).isEqualTo(30);

        view.applyValues(new Object[] {"u1", 11_000L, 1100L}, 1, 11_000);
        view.commit(11_000);

        assertThat(view.indexedRows()).isEqualTo(31);
        assertThat(rows("SELECT window_end FROM user_volume WHERE user_id = 'u1' AND window_end > 10000").stream()
                        .map(row -> row[0])
                        .toList())
                .containsExactly(11_000L);
        assertThat(view.indexBuilds()).isEqualTo(1);
    }

    @Test
    void aRetractionTakesItsIndexEntryWithIt() {
        rows("SELECT window_end FROM user_volume WHERE user_id = 'u1' AND window_end > 0");

        view.applyValues(new Object[] {"u1", 5000L, 500L}, -1, 11_000);
        view.commit(11_000);

        assertThat(view.indexedRows()).isEqualTo(29);
        assertThat(rows("SELECT window_end FROM user_volume "
                                + "WHERE user_id = 'u1' AND window_end >= 4000 AND window_end <= 6000")
                        .stream()
                        .map(row -> row[0])
                        .toList())
                .as("the index must not answer with a row the view no longer holds")
                .containsExactly(4000L, 6000L);
    }

    @Test
    void anUpdateThatChangesNothingInTheKeyLeavesOneEntry() {
        rows("SELECT window_end FROM user_volume WHERE user_id = 'u1' AND window_end > 0");

        view.applyValues(new Object[] {"u1", 5000L, 999L}, 1, 11_000);
        view.commit(11_000);

        assertThat(view.indexedRows()).isEqualTo(30);
        List<Object[]> found = rows("SELECT total FROM user_volume "
                + "WHERE user_id = 'u1' AND window_end >= 5000 AND window_end <= 5000");
        assertThat(found).hasSize(1);
        assertThat(found.get(0)[0]).isEqualTo(999L);
    }

    @Test
    void anEvictedRowLeavesTheIndexWithIt() {
        ServedView aging = new ServedView(
                "aging", SCHEMA, List.of(0, 1), 100_000, Retention.ofAge(java.time.Duration.ofNanos(3000)));
        ViewCatalog agingCatalog = new ViewCatalog().register(aging);
        for (long window = 1000; window <= 5000; window += 1000) {
            aging.applyValues(new Object[] {"u1", window, window}, 1, window);
        }
        aging.commit(5000);
        new ViewQuery(agingCatalog).execute("SELECT window_end FROM aging WHERE user_id = 'u1' AND window_end > 0");
        assertThat(aging.indexedRows()).isEqualTo(aging.size());

        aging.applyValues(new Object[] {"u1", 6000L, 6000L}, 1, 6000);
        aging.commit(6000);

        assertThat(aging.indexedRows())
                .as("what retention forgets, the index forgets")
                .isEqualTo(aging.size());
        assertThat(new ViewQuery(agingCatalog)
                        .execute("SELECT window_end FROM aging WHERE user_id = 'u1' AND window_end > 0")
                        .size())
                .isEqualTo(aging.size());
    }

    @Test
    void aRestoreDropsTheIndexAndTheNextRangeRebuildsIt() {
        rows("SELECT window_end FROM user_volume WHERE user_id = 'u1' AND window_end > 0");
        byte[] snapshot = view.snapshot();

        ServedView restored = new ServedView("user_volume", SCHEMA, List.of(0, 1), 100_000);
        ViewCatalog restoredCatalog = new ViewCatalog().register(restored);
        restored.restore(snapshot);

        assertThat(restored.indexedRows()).isZero();
        assertThat(new ViewQuery(restoredCatalog)
                        .execute("SELECT window_end FROM user_volume "
                                + "WHERE user_id = 'u1' AND window_end >= 3000 AND window_end < 6000")
                        .size())
                .isEqualTo(3);
        assertThat(restored.indexBuilds()).isEqualTo(1);
        assertThat(restored.indexedRows()).isEqualTo(30);
    }

    @Test
    void aNullInTheOrderedColumnIsInNoRangeAndInNoIndexEntry() {
        StreamSchema nullable = StreamSchema.builder("sparse")
                .field("user_id", Types.string())
                .field("window_end", Types.int64().withNullable(true))
                .build();
        ServedView sparse = new ServedView("sparse", nullable, List.of(0, 1), 1000);
        ViewCatalog sparseCatalog = new ViewCatalog().register(sparse);
        sparse.applyValues(new Object[] {"u1", 100L}, 1, 1);
        sparse.applyValues(new Object[] {"u1", null}, 1, 1);
        sparse.commit(1);

        ViewQuery.Result ranged = new ViewQuery(sparseCatalog)
                .execute("SELECT window_end FROM sparse WHERE user_id = 'u1' AND window_end > 0");

        assertThat(ranged.size())
                .as("NULL > 0 is UNKNOWN, which WHERE reads as false")
                .isEqualTo(1);
        assertThat(sparse.indexedRows()).isEqualTo(1);
        assertThat(sparse.size()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ a single-column key

    @Test
    void aSingleColumnKeyRangesWithNoPrefixToProbe() {
        StreamSchema single = StreamSchema.builder("ticks")
                .field("tick_at", Types.int64())
                .field("price", Types.int64())
                .build();
        ServedView ticks = new ServedView("ticks", single, List.of(0), 1000);
        ViewCatalog tickCatalog = new ViewCatalog().register(ticks);
        for (long at = 1; at <= 100; at++) {
            ticks.applyValues(new Object[] {at, at * 2}, 1, at);
        }
        ticks.commit(100);

        ViewQuery.Result result =
                new ViewQuery(tickCatalog).execute("SELECT tick_at FROM ticks WHERE tick_at >= 40 AND tick_at < 45");

        assertThat(result.rows().stream().map(row -> row[0]).toList()).containsExactly(40L, 41L, 42L, 43L, 44L);
        assertThat(ticks.rangeLookups()).isEqualTo(1);
        assertThat(ticks.scans()).isZero();
    }

    @Test
    void aNarrowerIntegerKeyIsProbedAtItsOwnWidth() {
        // The view holds an Integer for an INT32 column and the predicate's literal is a long. A
        // probe built at the predicate's width would miss every row -- silently, since the filter
        // would then never see one.
        StreamSchema narrow = StreamSchema.builder("narrow")
                .field("id", Types.int32())
                .field("v", Types.int32())
                .build();
        ServedView small = new ServedView("narrow", narrow, List.of(0), 1000);
        ViewCatalog narrowCatalog = new ViewCatalog().register(small);
        for (int id = 1; id <= 20; id++) {
            small.applyValues(new Object[] {id, id * 3}, 1, id);
        }
        small.commit(20);

        assertThat(new ViewQuery(narrowCatalog)
                        .execute("SELECT v FROM narrow WHERE id = 7")
                        .rows()
                        .get(0)[0])
                .isEqualTo(21);
        assertThat(small.pointLookups()).isEqualTo(1);
        assertThat(new ViewQuery(narrowCatalog)
                        .execute("SELECT id FROM narrow WHERE id >= 18")
                        .size())
                .isEqualTo(3);
        assertThat(small.rangeLookups()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the bound-parameter path

    @Test
    void aPreparedStatementWithItsKeyBoundIsTheSameProbe() {
        // This is the shape the REST view read and the PostgreSQL gateway's extended protocol take:
        // the key arrives as a bound value rather than as text. ADR-032 binds at plan-build time,
        // so the value is a literal in the predicate by the time the access path looks at it -- and
        // a path that only recognised written literals would leave both of those surfaces scanning.
        ViewQuery query = new ViewQuery(catalog);
        ViewQuery.Prepared statement = query.prepare(
                "SELECT total FROM user_volume WHERE user_id = ? AND window_end = ?",
                com.ash.messaging.pravaha.security.Principal.ANONYMOUS);

        ViewQuery.Result result = query.execute(
                statement,
                com.ash.messaging.pravaha.sql.plan.BoundParameters.of("u3", 7000L),
                com.ash.messaging.pravaha.security.Principal.ANONYMOUS);

        assertThat(result.rows())
                .singleElement()
                .satisfies(row -> assertThat(row[0]).isEqualTo(700L));
        assertThat(view.pointLookups()).isEqualTo(1);
        assertThat(view.scans()).isZero();
    }

    @Test
    void aPreparedRangeWithItsBoundsBoundWalksTheIndex() {
        ViewQuery query = new ViewQuery(catalog);
        ViewQuery.Prepared statement = query.prepare(
                "SELECT window_end FROM user_volume WHERE user_id = ? AND window_end >= ? AND window_end < ?",
                com.ash.messaging.pravaha.security.Principal.ANONYMOUS);

        ViewQuery.Result result = query.execute(
                statement,
                com.ash.messaging.pravaha.sql.plan.BoundParameters.of("u3", 3000L, 6000L),
                com.ash.messaging.pravaha.security.Principal.ANONYMOUS);

        assertThat(result.rows().stream().map(row -> row[0]).toList()).containsExactly(3000L, 4000L, 5000L);
        assertThat(view.rangeLookups()).isEqualTo(1);
        assertThat(view.scans()).isZero();
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void theIndexIsMaintainedUnderTheSameLockAsTheCommit() throws Exception {
        // The view's monitor is what orders a commit against a reader (the bug that killed a feed
        // after 181,248 of 200,000 rows). The index is maintained inside it, so this must neither
        // throw nor answer with a row the view does not hold.
        ServedView busy = new ServedView("busy", SCHEMA, List.of(0, 1), 200_000);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                for (long window = 1; window <= 4000; window++) {
                    busy.applyValues(new Object[] {"u" + (window % 4), window, window}, 1, window);
                    if (window % 16 == 0) {
                        busy.commit(window);
                    }
                }
                busy.commit(4000);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        Thread reader = new Thread(() -> {
            try {
                for (int i = 0; i < 400; i++) {
                    for (Object[] row : busy.committedRange(new Object[] {"u1"}, 0L, true, 4001L, false)) {
                        if (!"u1".equals(row[0])) {
                            throw new AssertionError("the index answered with another prefix's row");
                        }
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        writer.start();
        reader.start();
        writer.join();
        reader.join();

        assertThat(failure.get()).isNull();
        assertThat(busy.indexedRows()).isEqualTo(busy.size());
    }

    // ------------------------------------------------------------------ the index agrees with the view

    /**
     * Seeded property: after an arbitrary run of inserts, updates and retractions, every entry the
     * index holds is a row the view holds, and every row the view holds with an orderable ordered
     * column is in the index. Deterministic, so a failure reproduces.
     */
    @Test
    void theIndexAndTheViewAgreeAfterAnyRunOfChanges() {
        Random random = new Random(20260919L);
        ServedView churn = new ServedView("churn", SCHEMA, List.of(0, 1), 100_000);
        churn.applyValues(new Object[] {"u0", 1L, 1L}, 1, 1);
        churn.commit(1);
        churn.committedRange(new Object[] {"u0"}, null, true, null, true);

        for (int batch = 1; batch <= 400; batch++) {
            int changes = 1 + random.nextInt(8);
            for (int i = 0; i < changes; i++) {
                String user = "u" + random.nextInt(5);
                long window = random.nextInt(40);
                long weight = random.nextInt(4) == 0 ? -1 : 1;
                churn.applyValues(new Object[] {user, window, (long) random.nextInt(1000)}, weight, batch);
            }
            churn.commit(batch);

            List<Object[]> fromIndex = new ArrayList<>();
            for (int u = 0; u < 5; u++) {
                fromIndex.addAll(churn.committedRange(new Object[] {"u" + u}, null, true, null, true));
            }
            assertThat(fromIndex)
                    .as("batch " + batch + ", seed 20260919")
                    .hasSize(churn.size())
                    .containsExactlyInAnyOrderElementsOf(churn.scan());
        }
    }
}
