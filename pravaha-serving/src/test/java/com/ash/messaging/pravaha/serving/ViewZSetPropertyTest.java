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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A view is a Z-set of rows shown one row per key (VIEWW-1).
 *
 * <p>The rule the view keeps, stated once so the model below and the class agree on it: a key is
 * present exactly while the weights of its rows sum positive, and when several rows with that key
 * are present at once the key shows the one that most recently gained weight. A retraction takes
 * weight from the row it names; the key then shows whichever of its rows is still present, never
 * the row just withdrawn.
 *
 * <p>The model is the Z-set itself, kept per key as rows in the order they last gained weight. Every
 * schedule jqwik makes is well formed -- a retraction only of a row that is there -- because that is
 * what a lane sends. Commits, latest reads, and a checkpoint taken and restored into a fresh view
 * in the middle are all in the schedule, since the committed map, the overlay and the snapshot are
 * three places the rows live.
 */
class ViewZSetPropertyTest {

    static final StreamSchema SCHEMA = StreamSchema.builder("zv")
            .field("k", Types.string())
            .field("v", Types.int64())
            .build();

    enum Kind {
        INSERT,
        RETRACT,
        COMMIT,
        RESTORE
    }

    record Step(Kind kind, int key, int value, int weight) {}

    static ServedView view() {
        return new ServedView("zv", SCHEMA, List.of(0), 10_000);
    }

    // ------------------------------------------------------------------ named examples

    @Test
    void aRetractionOfTheOlderOfTwoRowsLeavesTheNewerShowing() {
        // The finding's own case: A, then B, then A withdrawn. B is the row still there.
        ServedView view = view();
        view.applyValues(new Object[] {"k", 1L}, 1, 1);
        view.applyValues(new Object[] {"k", 2L}, 1, 2);
        view.applyValues(new Object[] {"k", 1L}, -1, 3);
        view.commit(3);

        assertThat(view.get("k").values().orElseThrow()[1])
                .as("A was withdrawn; the row with this key still present is B")
                .isEqualTo(2L);
    }

    @Test
    void aRetractionOfTheNewerOfTwoRowsBringsTheOlderBack() {
        ServedView view = view();
        view.applyValues(new Object[] {"k", 1L}, 1, 1);
        view.applyValues(new Object[] {"k", 2L}, 1, 2);
        view.commit(2);
        assertThat(view.get("k").values().orElseThrow()[1]).isEqualTo(2L);

        view.applyValues(new Object[] {"k", 2L}, -1, 3);
        view.commit(3);

        assertThat(view.get("k").values().orElseThrow()[1])
                .as("B was withdrawn, so the key shows A, which is still present")
                .isEqualTo(1L);
    }

    @Test
    void bothRowsSurviveACheckpoint() {
        ServedView view = view();
        view.applyValues(new Object[] {"k", 1L}, 1, 1);
        view.applyValues(new Object[] {"k", 2L}, 1, 2);
        view.commit(2);
        ServedView restored = view();
        restored.restore(view.snapshot());
        assertThat(restored.get("k").values().orElseThrow()[1]).isEqualTo(2L);

        restored.applyValues(new Object[] {"k", 2L}, -1, 3);
        restored.commit(3);

        assertThat(restored.get("k").values().orElseThrow()[1])
                .as("the restored view knew A was still there behind B")
                .isEqualTo(1L);
    }

    @Test
    void aSubscribersSnapshotCarriesEveryRowOfAKeyWithItsOwnWeight() {
        ServedView view = view();
        view.applyValues(new Object[] {"k", 1L}, 1, 1);
        view.applyValues(new Object[] {"k", 2L}, 2, 2);
        view.commit(2);

        assertThat(view.committedRows())
                .extracting(change -> Arrays.asList(change.values()), ViewChange::weight)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(List.of("k", 1L), 1L),
                        org.assertj.core.groups.Tuple.tuple(List.of("k", 2L), 2L));
    }

    // ------------------------------------------------------------------ the property

    @Provide
    Arbitrary<List<Step>> schedules() {
        Arbitrary<Step> step = Combinators.combine(
                        Arbitraries.of(Kind.class),
                        Arbitraries.integers().between(0, 2),
                        Arbitraries.integers().between(0, 3),
                        Arbitraries.integers().between(1, 3))
                .as(Step::new);
        return step.list().ofMinSize(1).ofMaxSize(80);
    }

    @Property(tries = 2000)
    void theViewShowsTheZSetOneRowPerKey(@ForAll("schedules") List<Step> schedule) {
        ServedView view = view();
        // key -> (row value -> weight), rows in the order they last gained weight.
        Map<String, LinkedHashMap<Long, Long>> latest = new HashMap<>();
        Map<String, LinkedHashMap<Long, Long>> committed = new HashMap<>();
        long position = 0;

        for (Step step : schedule) {
            String key = "k" + step.key();
            switch (step.kind()) {
                case INSERT -> {
                    long value = step.value();
                    view.applyValues(new Object[] {key, value}, step.weight(), ++position);
                    LinkedHashMap<Long, Long> rows = latest.computeIfAbsent(key, k -> new LinkedHashMap<>());
                    long now = rows.getOrDefault(value, 0L) + step.weight();
                    rows.remove(value);
                    rows.put(value, now);
                }
                case RETRACT -> {
                    LinkedHashMap<Long, Long> rows = latest.get(key);
                    if (rows == null || rows.isEmpty()) {
                        continue;
                    }
                    List<Long> present = new ArrayList<>(rows.keySet());
                    long value = present.get(step.value() % present.size());
                    long weight = Math.min(step.weight(), rows.get(value));
                    view.applyValues(new Object[] {key, value}, -weight, ++position);
                    long left = rows.get(value) - weight;
                    if (left == 0) {
                        rows.remove(value);
                    } else {
                        rows.put(value, left);
                    }
                }
                case COMMIT -> {
                    view.commit(position);
                    committed = copy(latest);
                }
                case RESTORE -> {
                    view.commit(position);
                    committed = copy(latest);
                    ServedView fresh = view();
                    fresh.restore(view.snapshot());
                    view = fresh;
                }
            }
            for (int k = 0; k <= 2; k++) {
                assertThat(shown(view.get(new Consistency.Latest(), java.time.Duration.ZERO, "k" + k)))
                        .as("latest read of k%d after %s", k, step)
                        .isEqualTo(expected(latest.get("k" + k)));
                assertThat(shown(view.get(new Consistency.Consistent(), java.time.Duration.ZERO, "k" + k)))
                        .as("consistent read of k%d after %s", k, step)
                        .isEqualTo(expected(committed.get("k" + k)));
            }
        }
        view.commit(position);
        Map<List<Object>, Long> zset = new HashMap<>();
        for (ViewChange change : view.committedRows()) {
            zset.merge(Arrays.asList(change.values()), change.weight(), Long::sum);
        }
        Map<List<Object>, Long> model = new HashMap<>();
        latest.forEach((key, rows) -> rows.forEach((value, weight) -> model.put(List.of(key, value), weight)));
        assertThat(zset).as("a subscriber's snapshot is the Z-set itself").isEqualTo(model);
    }

    private static Long shown(ViewResult result) {
        return result.values().map(values -> (Long) values[1]).orElse(null);
    }

    /** The row a key shows: the last to gain weight among those still present, or none. */
    private static Long expected(SequencedMap<Long, Long> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Long shown = null;
        for (Map.Entry<Long, Long> row : rows.entrySet()) {
            if (row.getValue() > 0) {
                shown = row.getKey();
            }
        }
        return shown;
    }

    @SuppressWarnings("NonApiType") // the callers' maps hold LinkedHashMaps, and generics are invariant
    private static Map<String, LinkedHashMap<Long, Long>> copy(Map<String, LinkedHashMap<Long, Long>> rows) {
        Map<String, LinkedHashMap<Long, Long>> copy = new HashMap<>();
        rows.forEach((key, values) -> copy.put(key, new LinkedHashMap<>(values)));
        return copy;
    }
}
