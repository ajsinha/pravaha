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
package com.ash.messaging.pravaha.runtime.window;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-044: {@code COUNT(DISTINCT)} in off-heap, spillable state answers exactly what the on-heap
 * {@code HashMap<Object, Long>} per accumulator it replaced answered -- under random inserts and
 * retractions, hopping windows, spilling, slice discard and a checkpoint round trip in the middle.
 *
 * <p>The reference below is that on-heap model, written out plainly: per {@code (group, slice)} a row
 * count and, per distinct column, a map from value to how many times it is present, with a value
 * whose count reaches zero removed and a retraction of an absent value leaving nothing behind. A
 * window's distinct count is the size of the union of its slices' maps. The state under test is built
 * with a two-slice ceiling and a mapped overflow tier, so nearly all of it is on disk.
 *
 * <p>{@link #groupsSharingOneDigestAreNeverMerged} runs the same property with the digest
 * <strong>constructed to collide</strong> (W8-14): the forty groups are given four digests between
 * them, so every group shares its digest with nine others, and the answer must still be each group's
 * own. A 128-bit collision cannot be found by search; it does not need to be, because the state takes
 * the digest from its caller and so can be handed one.
 *
 * <p>Seeded, and the seed is in every assertion's description: a failure names the run that produced
 * it. {@code -Dpravaha.distinct.seed=N} adds one more seed to the fixed ones.
 */
class DistinctValueCountsPropertyTest {

    private static final long SECOND = 1_000_000_000L;
    private static final SlicedAggregateState.Kind[] KINDS = {
        SlicedAggregateState.Kind.COUNT,
        SlicedAggregateState.Kind.COUNT_DISTINCT,
        SlicedAggregateState.Kind.COUNT_DISTINCT,
        SlicedAggregateState.Kind.SUM
    };
    private static final SlicedWindows WINDOWS = new SlicedWindows(WindowSpec.hopping(30 * SECOND, 10 * SECOND));

    /** The on-heap model: per (group, slice), rows and each distinct column's value counts. */
    private static final class Reference {
        final Map<List<Long>, Long> rows = new HashMap<>();
        final Map<List<Long>, List<Map<Object, Long>>> distinct = new HashMap<>();
        final Map<List<Long>, Long> sums = new HashMap<>();

        void apply(long group, long slice, Object text, Long number, long amount, long weight) {
            List<Long> key = List.of(group, slice);
            rows.merge(key, weight, Long::sum);
            sums.merge(key, amount * weight, Long::sum);
            List<Map<Object, Long>> columns =
                    distinct.computeIfAbsent(key, k -> List.of(new HashMap<>(), new HashMap<>()));
            Object[] values = {text, number};
            for (int c = 0; c < 2; c++) {
                if (values[c] == null) {
                    continue;
                }
                long remaining = columns.get(c).merge(values[c], weight, Long::sum);
                if (remaining <= 0) {
                    columns.get(c).remove(values[c]);
                }
            }
        }

        /** group -> [rows, distinct text, distinct number, sum], for groups whose rows do not cancel. */
        Map<Long, List<Long>> fire(long windowEnd) {
            List<Long> slices = WINDOWS.slicesOfWindowEnding(windowEnd);
            Map<Long, long[]> totals = new TreeMap<>();
            Map<Long, List<Set<Object>>> unions = new HashMap<>();
            rows.forEach((key, count) -> {
                if (!slices.contains(key.get(1))) {
                    return;
                }
                long group = key.get(0);
                long[] total = totals.computeIfAbsent(group, g -> new long[2]);
                total[0] += count;
                total[1] += sums.get(key);
                List<Set<Object>> union = unions.computeIfAbsent(group, g -> List.of(new HashSet<>(), new HashSet<>()));
                for (int c = 0; c < 2; c++) {
                    union.get(c).addAll(distinct.get(key).get(c).keySet());
                }
            });
            Map<Long, List<Long>> result = new TreeMap<>();
            totals.forEach((group, total) -> {
                if (total[0] != 0) {
                    List<Set<Object>> union = unions.get(group);
                    result.put(
                            group,
                            List.of(
                                    total[0],
                                    (long) union.get(0).size(),
                                    (long) union.get(1).size(),
                                    total[1]));
                }
            });
            return result;
        }

        void discard(long watermark) {
            rows.keySet().removeIf(key -> WINDOWS.lastWindowEndFor(key.get(1)) <= watermark);
            distinct.keySet().removeIf(key -> WINDOWS.lastWindowEndFor(key.get(1)) <= watermark);
            sums.keySet().removeIf(key -> WINDOWS.lastWindowEndFor(key.get(1)) <= watermark);
        }

        /** Every value currently present, per group, slice and column -- which is what one insert must be retracting. */
        List<Object[]> presentValues() {
            List<Object[]> present = new ArrayList<>();
            distinct.forEach((key, columns) -> columns.get(0)
                    .forEach((value, count) -> present.add(new Object[] {key.get(0), key.get(1), value})));
            return present;
        }
    }

    private static Map<Long, List<Long>> fired(SlicedAggregateState state, long windowEnd) {
        Map<Long, List<Long>> result = new TreeMap<>();
        for (SlicedAggregateState.WindowResult r : state.fire(windowEnd)) {
            result.put((Long) r.keyValues()[0], List.of(r.count(), r.values()[1], r.values()[2], r.values()[3]));
        }
        return result;
    }

    private static void update(
            SlicedAggregateState state,
            boolean collide,
            long group,
            long slice,
            Object text,
            Long number,
            long amount,
            long weight) {
        Object[] distinctValues = {null, text, number, null};
        boolean[] present = {true, text != null, number != null, true};
        long[] values = {0, 0, number == null ? 0 : number, amount};
        // Colliding: ten groups to a digest, so a state keyed by the digest alone merges them.
        long keyHigh = collide ? group % 4 : group;
        long keyLow = collide ? 0x5EED : group * 0x9E3779B97F4A7C15L;
        state.update(keyHigh, keyLow, new Object[] {group}, slice + SECOND, values, present, distinctValues, weight);
    }

    private static List<Long> seeds() {
        List<Long> seeds = new ArrayList<>(List.of(1L, 7L, 42L, 20260919L));
        String extra = System.getProperty("pravaha.distinct.seed");
        if (extra != null) {
            seeds.add(Long.parseLong(extra));
        }
        return seeds;
    }

    @ParameterizedTest(name = "run {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void offHeapSpilledDistinctCountsMatchTheOnHeapModel(int run, @TempDir Path dir) throws Exception {
        property(run, dir, false);
    }

    @ParameterizedTest(name = "run {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void groupsSharingOneDigestAreNeverMerged(int run, @TempDir Path dir) throws Exception {
        property(run, dir, true);
    }

    private static void property(int run, Path dir, boolean collide) throws Exception {
        List<Long> seeds = seeds();
        if (run >= seeds.size()) {
            return;
        }
        long seed = seeds.get(run);
        Random random = new Random(seed);
        Reference reference = new Reference();
        int groups = 40;
        int slices = 12;
        long lastWindowEnd = (slices + 2) * 10L * SECOND;

        SlicedAggregateState state = null;
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir.resolve("a"))) {
            state = new SlicedAggregateState(WINDOWS, KINDS, 2, overflow, 256);
            for (int op = 0; op < 20_000; op++) {
                long group = random.nextInt(groups);
                long slice = random.nextInt(slices) * 10L * SECOND;
                // Mostly repeated values, so counts above one and retractions that leave a value
                // present are ordinary rather than rare.
                String text = random.nextInt(10) == 0 ? null : "user-" + random.nextInt(60);
                Long number = random.nextInt(10) == 0 ? null : (long) random.nextInt(25) - 5;
                long amount = random.nextInt(100);
                int dice = random.nextInt(10);
                if (dice < 2) {
                    // Retract something that is present, so the last occurrence going is exercised.
                    List<Object[]> present = reference.presentValues();
                    if (!present.isEmpty()) {
                        Object[] victim = present.get(random.nextInt(present.size()));
                        group = (Long) victim[0];
                        slice = (Long) victim[1];
                        text = (String) victim[2];
                    }
                    update(state, collide, group, slice, text, number, amount, -1);
                    reference.apply(group, slice, text, number, amount, -1);
                } else if (dice == 2) {
                    // A retraction of whatever was drawn, present or not.
                    update(state, collide, group, slice, text, number, amount, -1);
                    reference.apply(group, slice, text, number, amount, -1);
                } else {
                    long weight = random.nextInt(8) == 0 ? 2 : 1;
                    update(state, collide, group, slice, text, number, amount, weight);
                    reference.apply(group, slice, text, number, amount, weight);
                }

                if (op == 10_000) {
                    // A checkpoint in the middle, restored into a fresh state over a fresh
                    // directory: everything after this point runs against the restored copy.
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (DataOutputStream out = new DataOutputStream(bytes)) {
                        state.writeTo(out);
                    }
                    state.close();
                    state = new SlicedAggregateState(
                            WINDOWS, KINDS, 2, new MappedFileMemoryAccess(dir.resolve("b")), 256);
                    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                        state.readFrom(in);
                    }
                }
            }

            assertThat(state.hasSpilled())
                    .as("seed %d: a two-slice ceiling must put this state in the overflow tier", seed)
                    .isTrue();
            assertThat(Files.list(dir.resolve("b")).count())
                    .as("seed %d: the restored state spilled into its own directory", seed)
                    .isPositive();
            for (long end = 10 * SECOND; end <= lastWindowEnd; end += 10 * SECOND) {
                assertThat(fired(state, end))
                        .as("seed %d, window ending %ds", seed, end / SECOND)
                        .isEqualTo(reference.fire(end));
            }

            long watermark = 60 * SECOND;
            state.discardSlicesEndingBefore(watermark, 0);
            reference.discard(watermark);
            for (long end = 10 * SECOND; end <= lastWindowEnd; end += 10 * SECOND) {
                assertThat(fired(state, end))
                        .as(
                                "seed %d, after discarding to %ds, window ending %ds",
                                seed, watermark / SECOND, end / SECOND)
                        .isEqualTo(reference.fire(end));
            }
        } finally {
            if (state != null) {
                state.close();
            }
        }
    }
}
