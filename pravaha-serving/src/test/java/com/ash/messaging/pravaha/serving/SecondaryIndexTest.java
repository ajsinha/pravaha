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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A view's equality index over a column outside its key (ADR-055): kept in the view's own commit,
 * used by a read that pins the column with {@code =} or {@code IN}, and never an answer different
 * from the scan's.
 */
class SecondaryIndexTest {

    /** Keyed by {@code account}; {@code region} and {@code tier} are outside the key. */
    private static final StreamSchema SCHEMA = StreamSchema.builder("accounts")
            .field("account", Types.string())
            .field("region", Types.string())
            .field("tier", Types.int32())
            .field("balance", Types.int64())
            .build();

    private static final int REGION = 1;
    private static final int TIER = 2;

    private ServedView view;
    private ViewCatalog catalog;

    @BeforeEach
    void setUp() {
        view = new ServedView("accounts", SCHEMA, List.of(0), 100_000);
        catalog = new ViewCatalog().register(view);
    }

    private void put(String account, String region, int tier, long balance, long at) {
        view.applyValues(new Object[] {account, region, tier, balance}, 1, at);
    }

    private List<Object[]> rows(String sql) {
        return new ViewQuery(catalog).execute(sql).rows();
    }

    private static List<String> accounts(List<Object[]> rows) {
        return rows.stream().map(row -> (String) row[0]).sorted().toList();
    }

    @Test
    void anEqualityReadOnAnIndexedColumnProbesAndDoesNotScan() {
        view.index(REGION);
        put("a1", "eu", 1, 10, 1);
        put("a2", "us", 1, 20, 1);
        put("a3", "eu", 2, 30, 1);
        view.commit(1);

        assertThat(accounts(rows("SELECT account FROM accounts WHERE region = 'eu'")))
                .containsExactly("a1", "a3");
        assertThat(view.indexLookups()).isEqualTo(1);
        assertThat(view.scans()).as("three rows were not walked to find two").isZero();
    }

    @Test
    void anInListIsOneProbePerValueAndAValueTwiceIsProbedOnce() {
        view.index(TIER);
        for (int i = 0; i < 12; i++) {
            put("a" + i, "eu", i % 4, i, 1);
        }
        view.commit(1);

        List<Object[]> found = rows("SELECT account FROM accounts WHERE tier IN (1, 3, 3)");

        assertThat(accounts(found)).hasSize(6).doesNotHaveDuplicates();
        assertThat(view.indexLookups()).isEqualTo(1);
        assertThat(view.scans()).isZero();
    }

    @Test
    void theRestOfTheWhereClauseStillFilters() {
        view.index(REGION);
        put("a1", "eu", 1, 10, 1);
        put("a2", "eu", 2, 20, 1);
        view.commit(1);

        assertThat(accounts(rows("SELECT account FROM accounts WHERE region = 'eu' AND balance > 15")))
                .containsExactly("a2");
        assertThat(view.indexLookups()).isEqualTo(1);
    }

    @Test
    void aColumnNobodyIndexedStillScans() {
        view.index(REGION);
        put("a1", "eu", 1, 10, 1);
        view.commit(1);

        assertThat(rows("SELECT account FROM accounts WHERE tier = 1")).hasSize(1);
        assertThat(view.indexLookups()).isZero();
        assertThat(view.scans()).isEqualTo(1);
    }

    @Test
    void anOrAcrossTwoColumnsOrALiteralThatDoesNotFitScans() {
        view.index(TIER);
        put("a1", "eu", 1, 10, 1);
        view.commit(1);

        assertThat(rows("SELECT account FROM accounts WHERE tier = 1 OR region = 'us'"))
                .hasSize(1);
        assertThat(rows("SELECT account FROM accounts WHERE tier = 5000000000")).isEmpty();
        assertThat(rows("SELECT account FROM accounts WHERE tier <> 2")).hasSize(1);
        assertThat(view.indexLookups()).isZero();
        assertThat(view.scans()).isEqualTo(3);
    }

    @Test
    void theWholeKeyStillProbesTheKeyFirst() {
        view.index(REGION);
        put("a1", "eu", 1, 10, 1);
        view.commit(1);

        assertThat(rows("SELECT balance FROM accounts WHERE account = 'a1' AND region = 'eu'"))
                .hasSize(1);
        assertThat(view.pointLookups()).isEqualTo(1);
        assertThat(view.indexLookups()).isZero();
    }

    @Test
    void anUpdateThatChangesTheIndexedColumnMovesTheRowInTheSameCommit() {
        view.index(REGION);
        put("a1", "eu", 1, 10, 1);
        view.commit(1);
        // An update is a retraction and an insert. The retraction here carries a region that was
        // never the row's, which is what makes it a test: the index must not trust it.
        view.applyValues(new Object[] {"a1", "nowhere", 1, 10L}, -1, 2);
        put("a1", "us", 1, 10, 2);

        assertThat(rows("SELECT account FROM accounts WHERE region = 'eu'"))
                .as("not visible until the commit, in the index as in the view")
                .hasSize(1);
        view.commit(2);

        assertThat(rows("SELECT account FROM accounts WHERE region = 'eu'")).isEmpty();
        assertThat(accounts(rows("SELECT account FROM accounts WHERE region = 'us'")))
                .containsExactly("a1");
        assertThat(view.indexEntries(REGION)).isEqualTo(1);
    }

    @Test
    void aRetractedKeyLeavesTheIndex() {
        view.index(REGION);
        put("a1", "eu", 1, 10, 1);
        put("a2", "eu", 1, 10, 1);
        view.commit(1);
        view.applyValues(new Object[] {"a1", "eu", 1, 10L}, -1, 2);
        view.commit(2);

        assertThat(accounts(rows("SELECT account FROM accounts WHERE region = 'eu'")))
                .containsExactly("a2");
        assertThat(view.indexEntries(REGION)).isEqualTo(1);
    }

    @Test
    void anEvictedRowLeavesTheIndex() {
        ServedView aging = new ServedView("aging", SCHEMA, List.of(0), 100, Retention.ofAge(Duration.ofNanos(10)));
        aging.index(REGION);
        aging.applyValues(new Object[] {"old", "eu", 1, 1L}, 1, 0);
        aging.commit(0);
        aging.applyValues(new Object[] {"new", "eu", 1, 1L}, 1, 1_000);
        aging.commit(1_000);

        List<Object> left = aging.committedWith(REGION, List.of("eu")).stream()
                .map(row -> row[0])
                .toList();
        assertThat(left).containsExactly("new");
        assertThat(aging.indexEntries(REGION)).isEqualTo(1);
    }

    @Test
    void anIndexDeclaredOnAViewWithRowsIsBuiltFromThemAtOnce() {
        put("a1", "eu", 1, 10, 1);
        view.commit(1);
        view.index(REGION);
        view.index(REGION);

        assertThat(view.indexedColumns()).containsExactly(REGION);
        assertThat(view.indexEntries(REGION)).isEqualTo(1);
        assertThat(view.equalityIndexBuilds())
                .as("declaring it twice builds it once")
                .isEqualTo(1);
        assertThat(view.indexEntries(TIER)).isZero();
    }

    @Test
    void aRestoreRebuildsTheIndexBeforeTheFirstRead() {
        view.index(REGION);
        put("a1", "eu", 1, 10, 1);
        put("a2", "us", 1, 10, 1);
        view.commit(1);
        byte[] snapshot = view.snapshot();

        ServedView restored = new ServedView("accounts", SCHEMA, List.of(0), 100_000);
        restored.index(REGION);
        restored.applyValues(new Object[] {"stale", "eu", 1, 1L}, 1, 0);
        restored.commit(0);
        restored.restore(snapshot);

        assertThat(restored.indexEntries(REGION)).isEqualTo(2);
        assertThat(restored.committedWith(REGION, List.of("eu")).stream()
                        .map(row -> row[0])
                        .toList())
                .as("the stale row the restore replaced is gone from the index too")
                .containsExactly("a1");
    }

    @Test
    void aViewKeepsAtMostFourIndexesAndAColumnItDoesNotHaveIsRefused() {
        StreamSchema wide = StreamSchema.builder("wide")
                .field("k", Types.string())
                .field("c1", Types.string())
                .field("c2", Types.string())
                .field("c3", Types.string())
                .field("c4", Types.string())
                .field("c5", Types.string())
                .build();
        ServedView many = new ServedView("wide", wide, List.of(0), 10);
        for (int column = 1; column <= ServedView.MAX_EQUALITY_INDEXES; column++) {
            many.index(column);
        }

        assertThatThrownBy(() -> many.index(5))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2074")
                .hasMessageContaining("c5");
        assertThatThrownBy(() -> many.index(6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> many.committedWith(0, List.of("x"))).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The equivalence, generated: inserts, retractions and updates that move rows between values,
     * with commits, a restore and an eviction in between, and after every commit each value's
     * probe must be exactly the rows a scan filters to. Deterministic per seed.
     */
    @Test
    void theIndexAnswersExactlyWhatTheScanAnswersWhateverHappensToTheView() {
        for (long seed = 2026092701L; seed < 2026092701L + 20; seed++) {
            Random random = new Random(seed);
            ServedView generated =
                    new ServedView("gen", SCHEMA, List.of(0), 100_000, Retention.ofAge(Duration.ofNanos(400)));
            generated.index(REGION);
            List<String> regions = List.of("eu", "us", "ap");
            long at = 0;
            for (int step = 0; step < 300; step++) {
                String account = "a" + random.nextInt(30);
                String region = random.nextInt(10) == 0 ? null : regions.get(random.nextInt(regions.size()));
                long weight = random.nextInt(4) == 0 ? -1 : 1;
                generated.applyValues(new Object[] {account, region, 1, (long) step}, weight, at);
                at += random.nextInt(5);
                if (random.nextInt(7) == 0) {
                    generated.commit(at);
                    for (String each : regions) {
                        assertThat(sorted(generated.committedWith(REGION, List.of(each))))
                                .as("seed %d, step %d, region %s", seed, step, each)
                                .isEqualTo(sorted(scanFor(generated, each)));
                    }
                }
                if (random.nextInt(60) == 0) {
                    ServedView restored =
                            new ServedView("gen", SCHEMA, List.of(0), 100_000, Retention.ofAge(Duration.ofNanos(400)));
                    restored.index(REGION);
                    restored.restore(generated.snapshot());
                    generated = restored;
                }
            }
        }
    }

    private static List<Object[]> scanFor(ServedView view, String region) {
        List<Object[]> kept = new ArrayList<>();
        for (Object[] row : view.scan()) {
            if (region.equals(row[REGION])) {
                kept.add(row);
            }
        }
        return kept;
    }

    private static List<List<Object>> sorted(List<Object[]> rows) {
        List<List<Object>> lists = new ArrayList<>();
        for (Object[] row : rows) {
            lists.add(java.util.Arrays.asList(row));
        }
        lists.sort(Comparator.comparing(Object::toString));
        return lists;
    }
}
