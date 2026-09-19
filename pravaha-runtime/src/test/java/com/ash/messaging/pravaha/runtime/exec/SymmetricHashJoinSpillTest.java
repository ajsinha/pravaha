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
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-037 item B2: a stream-to-stream join keeps running past its in-memory ceiling once given an
 * overflow tier, and a checkpoint taken while state is spilled restores it correctly -- one snapshot,
 * not a second durable thing beside it.
 *
 * <p>This is the case ADR-037 names directly: "an unwindowed join over two unbounded streams." One
 * side of a join holds every row that has not yet found a match and cannot be evicted (Z-set
 * semantics: a retraction whose insert was evicted can never be withdrawn), so it is exactly the
 * shape that outgrows a fixed row-store ceiling first.
 */
class SymmetricHashJoinSpillTest {

    private static final long SECOND = 1_000_000_000L;

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 64);
    private final List<Long> out = new ArrayList<>();

    @AfterEach
    void tearDown() {
        arena.close();
    }

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

    private static StreamSchema merged() {
        return StreamSchema.builder("joined")
                .field("l_id", Types.int64())
                .field("l_key", Types.string())
                .field("r_key", Types.string().withNullable(true))
                .field("r_value", Types.int64().withNullable(true))
                .build();
    }

    private JoinOperator plan() {
        return new JoinOperator(
                ScanOperator.of("left", left()),
                ScanOperator.of("right", right()),
                List.of(1),
                List.of(0),
                merged(),
                10_000_000);
    }

    /** One 1 MiB slab of join state, in whichever tier is asked for. Small enough that a few
     * thousand small rows exceed it, and the same size {@code SymmetricHashJoin} always uses. */
    private static final int STATE_SLAB_BYTES = 1 << 20;

    private void feedLeft(SymmetricHashJoin join, long id, String key) {
        RowLayout layout = RowLayout.of(left());
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id).setString(1, key);
        writer.weight(1L).eventTimestampNanos(id).sequence(id).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        join.leftInput().process(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    private void feedRight(SymmetricHashJoin join, String key, long value) {
        RowLayout layout = RowLayout.of(right());
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, key).setLong(1, value);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        join.rightInput().process(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    private RowProcessor collector() {
        return row -> out.add(row.getLong(0));
    }

    @Test
    void withoutAnOverflowTierEnoughDistinctKeysAreRefused() {
        try (SymmetricHashJoin join = new SymmetricHashJoin(plan(), arena, collector(), 1)) {
            assertThatThrownBy(() -> {
                        for (int i = 0; i < 40_000; i++) {
                            feedLeft(join, i, "key-" + i);
                        }
                    })
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4001");
        }
    }

    @Test
    void withAnOverflowTierTheSameVolumeKeepsRunning(@TempDir Path dir) {
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                SymmetricHashJoin join = new SymmetricHashJoin(plan(), arena, collector(), 1, overflow, 8)) {
            for (int i = 0; i < 40_000; i++) {
                feedLeft(join, i, "key-" + i);
            }
            assertThat(join.hasSpilled())
                    .as("40,000 distinct rows do not fit one 1 MiB slab")
                    .isTrue();
            assertThat(join.rowsHeldLeft()).isEqualTo(40_000);
        }
    }

    @Test
    void rowsHeldInTheOverflowTierStillMatchTheOtherSide(@TempDir Path dir) {
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                SymmetricHashJoin join = new SymmetricHashJoin(plan(), arena, collector(), 1, overflow, 8)) {
            for (int i = 0; i < 40_000; i++) {
                feedLeft(join, i, "key-" + i);
            }
            assertThat(join.hasSpilled()).isTrue();

            // A right row matching a key that landed in the overflow tier (spilling happens in
            // insertion order, so the earliest keys are the ones now on disk) must still produce a
            // pair -- proving the overflow tier is not merely storage, but state the join's own
            // matching logic reads correctly.
            feedRight(join, "key-5", 999L);

            assertThat(join.pairsEmitted()).isEqualTo(1);
            assertThat(out).containsExactly(5L);
        }
    }

    @Test
    void aCheckpointTakenWhileSpilledRestoresIntoAFreshJoinCorrectly(@TempDir Path spillDirA, @TempDir Path spillDirB)
            throws Exception {
        byte[] snapshot;
        try (MappedFileMemoryAccess overflowA = new MappedFileMemoryAccess(spillDirA);
                SymmetricHashJoin original = new SymmetricHashJoin(plan(), arena, collector(), 1, overflowA, 8)) {
            for (int i = 0; i < 40_000; i++) {
                feedLeft(original, i, "key-" + i);
            }
            assertThat(original.hasSpilled())
                    .as("the point of this test is a checkpoint taken while state is on disk")
                    .isTrue();

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                original.writeTo(out);
            }
            snapshot = bytes.toByteArray();
        }

        // A fresh join, a fresh RowArena, a fresh overflow directory: nothing is shared with the
        // join the snapshot was taken from, which is the point -- this is what recovery on a
        // restarted node looks like, not a reload of the same objects.
        try (RowArena freshArena = new RowArena(MemoryAccess.best(), 1 << 20, 64);
                MappedFileMemoryAccess overflowB = new MappedFileMemoryAccess(spillDirB);
                SymmetricHashJoin restored =
                        new SymmetricHashJoin(plan(), freshArena, restoredCollector(), 1, overflowB, 8)) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot))) {
                restored.readFrom(in);
            }

            assertThat(restored.rowsHeldLeft())
                    .as("every left row the original held, restored")
                    .isEqualTo(40_000);

            RowLayout layout = RowLayout.of(right());
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            long handle = freshArena.allocate(layout.rowSize(128));
            writer.begin(freshArena.regionOf(handle), freshArena.offsetOf(handle));
            writer.setString(0, "key-12345").setLong(1, 777L);
            writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
            freshArena.trimTo(handle, writer.sizeSoFar());
            restored.rightInput()
                    .process(new BinaryRowView(layout).wrap(freshArena.regionOf(handle), freshArena.offsetOf(handle)));

            assertThat(restored.pairsEmitted())
                    .as("a row restored from a spilled entry must still be matchable")
                    .isEqualTo(1);
        }
    }

    private RowProcessor restoredCollector() {
        return row -> out.add(row.getLong(0));
    }

    private void feedLeft(SymmetricHashJoin join, long id, String key, long weight) {
        RowLayout layout = RowLayout.of(left());
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id).setString(1, key);
        writer.weight(weight).eventTimestampNanos(id).sequence(id).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        join.leftInput().process(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    private static byte[] snapshot(SymmetricHashJoin join) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            join.writeTo(out);
        }
        return bytes.toByteArray();
    }

    private static long filesIn(Path dir) throws java.io.IOException {
        try (var files = java.nio.file.Files.list(dir)) {
            return files.count();
        }
    }

    /**
     * ADR-044: a join whose state churned -- most of what spilled has been retracted -- compacts its
     * overflow slabs back down, and every row it still holds is still found by the other side, from
     * a chain whose links compaction rewrote. Four rows share each key, so the chains are real ones,
     * and the retractions take rows out of the middle of them.
     */
    @Test
    void aChurnedJoinCompactsItsOverflowSlabsAndStillMatchesEveryRowItHolds(@TempDir Path dir) throws Exception {
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                SymmetricHashJoin join = new SymmetricHashJoin(plan(), arena, collector(), 1, overflow, 64)) {
            int rows = 60_000;
            int keys = rows / 4;
            for (int i = 0; i < rows; i++) {
                feedLeft(join, i, "key-" + (i % keys), 1);
                if (i % 5_000 == 4_999) {
                    arena.reset();
                }
            }
            long peakFiles = filesIn(dir);
            assertThat(join.overflowSlabsUsed()).isGreaterThan(4);

            // Retract every row but one in eight, taking rows from heads, middles and tails of chains.
            java.util.Set<Integer> kept = new java.util.TreeSet<>();
            for (int i = 0; i < rows; i++) {
                if (i % 8 == 3) {
                    kept.add(i);
                } else {
                    feedLeft(join, i, "key-" + (i % keys), -1);
                }
                if (i % 5_000 == 4_999) {
                    arena.reset();
                }
            }
            assertThat(join.rowsHeldLeft()).isEqualTo(kept.size());
            byte[] before = snapshot(join);

            int released = join.compactIfFragmented(0.5);

            assertThat(released).isPositive();
            assertThat(join.spillStatistics().slabsReleased()).isEqualTo(released);
            assertThat(filesIn(dir))
                    .as("files after compaction, from %d", peakFiles)
                    .isLessThan(peakFiles)
                    .isEqualTo(join.spillStatistics().overflowBytesReserved() / STATE_SLAB_BYTES);
            assertThat(snapshot(join))
                    .as("compaction moves rows, it does not change them or their order in a chain")
                    .isEqualTo(before);

            // Every row still held is matched by a right row with its key -- read back through bucket
            // heads and chain links that compaction rewrote.
            out.clear();
            for (int key = 0; key < keys; key++) {
                feedRight(join, "key-" + key, key);
                if (key % 5_000 == 4_999) {
                    arena.reset();
                }
            }
            assertThat(new java.util.TreeSet<>(out))
                    .isEqualTo(kept.stream()
                            .map(Integer::longValue)
                            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)));
            assertThat(out).hasSize(kept.size());
        }
    }
}
