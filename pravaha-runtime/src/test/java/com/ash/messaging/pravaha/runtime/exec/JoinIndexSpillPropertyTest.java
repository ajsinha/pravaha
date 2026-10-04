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
package com.ash.messaging.pravaha.runtime.exec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.JoinOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-044: a join whose key index's slot table is in the overflow tier matches exactly what an
 * on-heap model of its left side says it should -- through inserts, retractions, duplicate rows,
 * compaction between batches, and a checkpoint restored into a fresh join over a fresh spill
 * directory halfway through.
 *
 * <p>The join is given one slab of RAM ceiling, so each side's slot table may hold one MiB (65,536
 * slots) in RAM; past about 46,000 keys its next table is mapped. 70,000 keys take the left side's
 * table there, and it has to stay right while its rows, its index store and the table itself all live
 * in mapped files.
 */
class JoinIndexSpillPropertyTest {

    private static final int KEYS = 70_000;

    private static StreamSchema left() {
        return StreamSchema.builder("left")
                .field("id", Types.int64())
                .field("key", Types.string())
                .build();
    }

    private static StreamSchema right() {
        return StreamSchema.builder("right")
                .field("key", Types.string())
                .field("value", Types.int64())
                .build();
    }

    private static JoinOperator plan() {
        StreamSchema merged = StreamSchema.builder("joined")
                .field("l_id", Types.int64())
                .field("l_key", Types.string())
                .field("r_key", Types.string().withNullable(true))
                .field("r_value", Types.int64().withNullable(true))
                .build();
        return new JoinOperator(
                ScanOperator.of("left", left()),
                ScanOperator.of("right", right()),
                List.of(1),
                List.of(0),
                merged,
                Long.MAX_VALUE,
                Long.MAX_VALUE / 4);
    }

    /** Rows in through a lane-sized arena, reset between batches as a lane resets it. */
    private static final class Feed {
        final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 64);
        final RowLayout leftLayout = RowLayout.of(left());
        final RowLayout rightLayout = RowLayout.of(right());
        final BinaryRowWriter leftWriter = new BinaryRowWriter(leftLayout);
        final BinaryRowWriter rightWriter = new BinaryRowWriter(rightLayout);
        final BinaryRowView leftView = new BinaryRowView(leftLayout);
        final BinaryRowView rightView = new BinaryRowView(rightLayout);

        void leftRow(SymmetricHashJoin join, long id, int key, long weight) {
            long handle = arena.allocate(leftLayout.rowSize(64));
            leftWriter.begin(arena.regionOf(handle), arena.offsetOf(handle));
            leftWriter.setLong(0, id).setString(1, "key-" + key);
            leftWriter.weight(weight).eventTimestampNanos(0).sequence(id).commit();
            arena.trimTo(handle, leftWriter.sizeSoFar());
            join.leftInput().process(leftView.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }

        void rightRow(SymmetricHashJoin join, int key, long weight) {
            long handle = arena.allocate(rightLayout.rowSize(64));
            rightWriter.begin(arena.regionOf(handle), arena.offsetOf(handle));
            rightWriter.setString(0, "key-" + key).setLong(1, key);
            rightWriter.weight(weight).eventTimestampNanos(0).sequence(0).commit();
            arena.trimTo(handle, rightWriter.sizeSoFar());
            join.rightInput().process(rightView.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }
    }

    /** The model: for each key, each left id held and its weight. */
    private final Map<Integer, Map<Long, Long>> model = new HashMap<>();

    /** Ids the model holds, for picking one to retract. */
    private final List<long[]> held = new ArrayList<>();

    /** Where each held id sits in {@link #held}, so a retraction removes it in constant time. */
    private final Map<Long, Integer> heldAt = new HashMap<>();

    /** What the join emitted for the probe in progress: left id to summed weight. */
    private final Map<Long, Long> emitted = new TreeMap<>();

    private RowProcessor collector() {
        return row -> emitted.merge(row.getLong(0), row.weight(), Long::sum);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {20260919L, 3L, 77L, 0xBEEFL})
    void aJoinWithItsKeyIndexSpilledMatchesTheModel(long seed, @TempDir Path dirA, @TempDir Path dirB)
            throws Exception {
        Random random = new Random(seed);
        Feed feed = new Feed();
        byte[] checkpoint;
        long nextId = 0;
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dirA);
                SymmetricHashJoin join = new SymmetricHashJoin(plan(), feed.arena, collector(), 1, overflow, 4096)) {
            // Every key once, so the left index passes its RAM budget, then a random mix.
            for (int key = 0; key < KEYS; key++) {
                insert(join, feed, nextId++, key, 1);
                batchEnd(join, feed, key);
            }
            assertThat(join.indexTableBytesMapped())
                    .as("the left slot table is mapped")
                    .isPositive();
            nextId = mix(join, feed, random, nextId, 40_000);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                join.writeTo(out);
            }
            checkpoint = bytes.toByteArray();
        }
        feed.arena.close();

        Feed fresh = new Feed();
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dirB);
                SymmetricHashJoin restored =
                        new SymmetricHashJoin(plan(), fresh.arena, collector(), 1, overflow, 4096)) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(checkpoint))) {
                restored.readFrom(in);
            }
            assertThat(restored.indexTableBytesMapped())
                    .as("the restored index spills as the original did")
                    .isPositive();
            mix(restored, fresh, random, nextId, 40_000);

            // Every key probed: exactly the model's rows for it, at the model's weights.
            for (int key = 0; key < KEYS; key++) {
                assertProbe(restored, fresh, key);
                batchEnd(restored, fresh, key);
            }
            assertThat(restored.rowsHeldLeft()).as("distinct rows held").isEqualTo(held.size());
        } finally {
            fresh.arena.close();
        }
    }

    /** Random inserts (new rows, and more weight on held ones), retractions and probes. */
    private long mix(SymmetricHashJoin join, Feed feed, Random random, long nextId, int operations) {
        long id = nextId;
        for (int op = 0; op < operations; op++) {
            int choice = random.nextInt(10);
            if (choice < 4 || held.isEmpty()) {
                insert(join, feed, id++, random.nextInt(KEYS + KEYS / 4), 1);
            } else if (choice < 5) {
                long[] row = held.get(random.nextInt(held.size()));
                insert(join, feed, row[0], (int) row[1], 1);
            } else if (choice < 8) {
                int index = random.nextInt(held.size());
                long[] row = held.get(index);
                insert(join, feed, row[0], (int) row[1], -1);
            } else {
                assertProbe(join, feed, random.nextInt(KEYS + KEYS / 4));
            }
            batchEnd(join, feed, op);
        }
        return id;
    }

    private void insert(SymmetricHashJoin join, Feed feed, long id, int key, long weight) {
        feed.leftRow(join, id, key, weight);
        Map<Long, Long> ids = model.computeIfAbsent(key, k -> new HashMap<>());
        long now = ids.merge(id, weight, Long::sum);
        if (now == 0) {
            ids.remove(id);
            int at = java.util.Objects.requireNonNull(heldAt.remove(id));
            long[] last = held.remove(held.size() - 1);
            if (at < held.size()) {
                held.set(at, last);
                heldAt.put(last[0], at);
            }
        } else if (now == weight) {
            heldAt.put(id, held.size());
            held.add(new long[] {id, key});
        }
        emitted.clear();
    }

    /** A right row in and out again: each held left row is emitted once at +w and once at -w. */
    private void assertProbe(SymmetricHashJoin join, Feed feed, int key) {
        emitted.clear();
        feed.rightRow(join, key, 1);
        Map<Long, Long> in = new TreeMap<>(emitted);
        emitted.clear();
        feed.rightRow(join, key, -1);
        Map<Long, Long> out = new TreeMap<>(emitted);
        emitted.clear();
        Map<Long, Long> expected = new TreeMap<>(model.getOrDefault(key, Map.of()));
        assertThat(in).as("matches for key %d", key).isEqualTo(expected);
        Map<Long, Long> negated = new TreeMap<>();
        expected.forEach((id, weight) -> negated.put(id, -weight));
        assertThat(out).as("retracted matches for key %d", key).isEqualTo(negated);
    }

    /** Between batches: the arena is reset, and every so often the join compacts, as a lane does. */
    private static void batchEnd(SymmetricHashJoin join, Feed feed, int op) {
        if (op % 256 == 255) {
            feed.arena.reset();
        }
        if (op % 10_000 == 9_999) {
            join.compactIfFragmented(0.5);
        }
    }
}
