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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An access path may change what a read costs and may not change what it answers.
 *
 * <p>{@link ViewAccessPath} narrows a read to a hash probe or a run of the ordered index when it
 * can prove the rows it hands back are a superset of the rows the predicate keeps. The proof is an
 * argument in a comment; this is the test of it. The same rows are loaded into two views that
 * differ only in what they are keyed by -- one where the predicate's columns are the key, so the
 * probe and the index are used, and one keyed by an id nothing asks about, so every read is the
 * scan the engine did before B8 -- and several thousand generated predicates are put to both.
 *
 * <p>Seeded, so a failure reproduces from the seed printed with it.
 */
class ViewIndexEquivalenceTest {

    private static final long SEED = 20260919_08L;

    private static final StreamSchema SCHEMA = StreamSchema.builder("rows")
            .field("id", Types.int64())
            .field("user_id", Types.string())
            .field("window_end", Types.int64())
            .field("total", Types.int64())
            .build();

    /** Answers by the probe and the index: keyed by (user_id, window_end). */
    private ServedView indexed;

    /** Answers by a scan every time: keyed by an id no predicate below mentions. */
    private ServedView scanned;

    private ViewCatalog catalog;

    private void load(Random random, int users, int windows) {
        indexed = new ServedView("indexed", SCHEMA, List.of(1, 2), 100_000);
        scanned = new ServedView("scanned", SCHEMA, List.of(0), 100_000);
        catalog = new ViewCatalog().register(indexed).register(scanned);
        for (int u = 0; u < users; u++) {
            for (int w = 0; w < windows; w++) {
                // Gaps and repeats in the ordered column, so a bound falls between values as often
                // as it falls on one.
                long window = (long) w * 10 + random.nextInt(3);
                Object[] row = new Object[] {idOf(u, window), "u" + u, window, (long) random.nextInt(500)};
                indexed.applyValues(row.clone(), 1, window);
                scanned.applyValues(row.clone(), 1, window);
            }
        }
        indexed.commit(1_000_000);
        scanned.commit(1_000_000);
    }

    @Test
    void everyGeneratedPredicateAnswersTheSameWhicheverPathItTakes() {
        Random random = new Random(SEED);
        load(random, 6, 12);

        int viaIndex = 0;
        for (int i = 0; i < 3000; i++) {
            String where = predicate(random);
            List<Object[]> fromIndex = read("indexed", where);
            List<Object[]> fromScan = read("scanned", where);
            assertThat(keysOf(fromIndex))
                    .as("seed " + SEED + ", case " + i + ": WHERE " + where)
                    .containsExactlyInAnyOrderElementsOf(keysOf(fromScan));
            if (indexed.pointLookups() + indexed.rangeLookups() > viaIndex) {
                viaIndex = (int) (indexed.pointLookups() + indexed.rangeLookups());
            }
        }

        assertThat(viaIndex)
                .as("a test in which the index is never used proves nothing")
                .isGreaterThan(500);
        assertThat(scanned.pointLookups() + scanned.rangeLookups())
                .as("the control view must genuinely have scanned every time")
                .isZero();
        assertThat(scanned.scans()).isEqualTo(3000);
    }

    @Test
    void theSameHoldsWhileTheViewIsChangingUnderTheReads() {
        Random random = new Random(SEED + 1);
        load(random, 4, 8);
        read("indexed", "user_id = 'u0' AND window_end > 0");

        for (int batch = 0; batch < 200; batch++) {
            int changes = 1 + random.nextInt(6);
            for (int c = 0; c < changes; c++) {
                int user = random.nextInt(4);
                long window = random.nextInt(80);
                Object[] row = new Object[] {idOf(user, window), "u" + user, window, (long) random.nextInt(500)};
                long weight = random.nextInt(4) == 0 ? -1 : 1;
                indexed.applyValues(row.clone(), weight, window);
                scanned.applyValues(row.clone(), weight, window);
            }
            indexed.commit(1_000_000 + batch);
            scanned.commit(1_000_000 + batch);

            for (int q = 0; q < 5; q++) {
                String where = predicate(random);
                assertThat(keysOf(read("indexed", where)))
                        .as("seed " + (SEED + 1) + ", batch " + batch + ": WHERE " + where)
                        .containsExactlyInAnyOrderElementsOf(keysOf(read("scanned", where)));
            }
        }
    }

    /**
     * The id of the row (user, window) names.
     *
     * <p>The two views are keyed by different columns and must still hold the same rows, or the
     * comparison below is between two different tables rather than between two access paths. A key
     * that is a function of the other view's key is what makes them the same table.
     */
    private static long idOf(int user, long window) {
        return user * 1000L + window;
    }

    private List<Object[]> read(String view, String where) {
        return new ViewQuery(catalog)
                .execute("SELECT user_id, window_end, total FROM " + view + " WHERE " + where)
                .rows();
    }

    /** Rows as comparable strings; the two views hold different ids for the same row. */
    private static List<String> keysOf(List<Object[]> rows) {
        List<String> keys = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            keys.add(row[0] + "|" + row[1] + "|" + row[2]);
        }
        return keys;
    }

    /** A predicate over the key's columns and one outside it, in the shapes a reader writes. */
    private static String predicate(Random random) {
        return switch (random.nextInt(12)) {
            case 0 -> eq(random) + " AND " + windowEq(random);
            case 1 -> eq(random) + " AND " + bound(random);
            case 2 -> eq(random) + " AND " + bound(random) + " AND " + bound(random);
            case 3 -> eq(random) + " AND " + bound(random) + " AND total > " + random.nextInt(500);
            case 4 -> bound(random);
            case 5 -> eq(random);
            case 6 -> eq(random) + " AND (" + bound(random) + " OR " + windowEq(random) + ")";
            case 7 -> eq(random) + " AND NOT " + bound(random);
            case 8 -> eq(random) + " OR " + eq(random);
            case 9 -> windowEq(random) + " AND " + eq(random);
            case 10 -> eq(random) + " AND " + eq(random);
            default -> "total <= " + random.nextInt(500) + " AND " + bound(random);
        };
    }

    private static String eq(Random random) {
        return "user_id = 'u" + random.nextInt(8) + "'";
    }

    private static String windowEq(Random random) {
        return "window_end = " + random.nextInt(130);
    }

    private static String bound(Random random) {
        String op =
                switch (random.nextInt(4)) {
                    case 0 -> ">";
                    case 1 -> ">=";
                    case 2 -> "<";
                    default -> "<=";
                };
        return "window_end " + op + " " + random.nextInt(130);
    }
}
