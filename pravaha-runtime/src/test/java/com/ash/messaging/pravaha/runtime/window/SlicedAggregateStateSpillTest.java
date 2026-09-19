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
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-037 item B2's overflow tier for windowed aggregates: a slice ceiling that would otherwise be
 * refused keeps running once spilling is configured, a checkpoint taken mid-spill restores
 * correctly, and -- since ADR-044 -- {@code COUNT(DISTINCT ...)} spills with the rest instead of
 * being refused.
 *
 * <p>{@link SlicedAggregateStateTest} and {@link CountDistinctTest} are the tests that must keep
 * passing unchanged through this, and {@link DistinctValueCountsPropertyTest} holds the off-heap
 * distinct counts to the on-heap model they replaced.
 */
class SlicedAggregateStateSpillTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void withoutOverflowExceedingMaxSlicesIsRefused() {
        SlicedAggregateState state = new SlicedAggregateState(
                new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.COUNT},
                2);
        state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {0}, 1);
        state.update(2L, 2L * 31, new Object[] {2L}, SECOND, new long[] {0}, 1);

        assertThatThrownBy(() -> state.update(3L, 3L * 31, new Object[] {3L}, SECOND, new long[] {0}, 1))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3020");
        state.close();
    }

    @Test
    void withOverflowConfiguredMoreDistinctKeysThanMaxSlicesKeepRunning(@TempDir Path dir) {
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                SlicedAggregateState state = new SlicedAggregateState(
                        new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                        new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM
                        },
                        2,
                        overflow,
                        8)) {
            for (long key = 0; key < 2000; key++) {
                state.update(key, key * 31, new Object[] {key}, SECOND, new long[] {0, key * 10}, 1);
            }

            assertThat(state.hasSpilled())
                    .as("2000 groups do not fit a 2-slice ceiling without overflow")
                    .isTrue();
            assertThat(state.liveSlices()).isEqualTo(2000);

            List<SlicedAggregateState.WindowResult> results = state.fire(10 * SECOND);
            assertThat(results).hasSize(2000);
            for (SlicedAggregateState.WindowResult result : results) {
                long key = (Long) result.keyValues()[0];
                assertThat(result.values()).as("group %d", key).containsExactly(1, key * 10);
            }
        }
    }

    @Test
    void aCheckpointTakenWhileSpilledRestoresCorrectly(@TempDir Path dirA, @TempDir Path dirB) throws Exception {
        SlicedAggregateState.Kind[] kinds = {SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM};
        byte[] snapshot;
        try (MappedFileMemoryAccess overflowA = new MappedFileMemoryAccess(dirA);
                SlicedAggregateState original = new SlicedAggregateState(
                        new SlicedWindows(WindowSpec.tumbling(10 * SECOND)), kinds, 2, overflowA, 8)) {
            for (long key = 0; key < 2000; key++) {
                original.update(key, key * 31, new Object[] {key}, SECOND, new long[] {0, key * 10}, 1);
            }
            assertThat(original.hasSpilled()).isTrue();

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                original.writeTo(out);
            }
            snapshot = bytes.toByteArray();
        }

        try (MappedFileMemoryAccess overflowB = new MappedFileMemoryAccess(dirB);
                SlicedAggregateState restored = new SlicedAggregateState(
                        new SlicedWindows(WindowSpec.tumbling(10 * SECOND)), kinds, 2, overflowB, 8)) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot))) {
                restored.readFrom(in);
            }

            assertThat(restored.liveSlices()).isEqualTo(2000);
            List<SlicedAggregateState.WindowResult> results = restored.fire(10 * SECOND);
            assertThat(results).hasSize(2000);
            for (SlicedAggregateState.WindowResult result : results) {
                long key = (Long) result.keyValues()[0];
                assertThat(result.values()).as("group %d", key).containsExactly(1, key * 10);
            }

            // The restored state must still accept updates and retractions correctly, not just
            // answer a frozen fire() -- a checkpoint is a resume point, not a read-only snapshot.
            restored.update(0L, 0L * 31, new Object[] {0L}, SECOND, new long[] {0, 0L}, -1);
            assertThat(restored.fire(10 * SECOND).stream()
                            .filter(r -> ((Long) r.keyValues()[0]) == 0L)
                            .findFirst())
                    .as("the only insert for group 0 was just retracted")
                    .isEmpty();
        }
    }

    @Test
    void countDistinctSpillsInsteadOfBeingRefused(@TempDir Path dir) throws Exception {
        // It was refused here, by name, with the overflow tier configured: its distinct sets were
        // on the heap and had nowhere to spill. ADR-044 moved them into a RowStore.
        SlicedAggregateState.Kind[] kinds = {SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.COUNT_DISTINCT};
        byte[] snapshot;
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir.resolve("a"));
                SlicedAggregateState state = new SlicedAggregateState(
                        new SlicedWindows(WindowSpec.tumbling(10 * SECOND)), kinds, 2, overflow, 64)) {
            // Three groups, each seeing 3,000 distinct values twice: far more than a two-slice RAM
            // budget holds.
            for (long value = 0; value < 3_000; value++) {
                for (long group = 0; group < 3; group++) {
                    state.update(group, group * 31, new Object[] {group}, SECOND, new long[] {0, value}, 1);
                    state.update(group, group * 31, new Object[] {group}, 2 * SECOND, new long[] {0, value}, 1);
                }
            }
            assertThat(state.hasSpilled())
                    .as("9,000 distinct values do not fit the RAM tier")
                    .isTrue();
            assertThat(state.distinctValuesHeld()).isEqualTo(9_000);
            assertThat(state.fire(10 * SECOND))
                    .allSatisfy(result -> assertThat(result.values()).containsExactly(6_000, 3_000));

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                state.writeTo(out);
            }
            snapshot = bytes.toByteArray();
        }

        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir.resolve("b"));
                SlicedAggregateState restored = new SlicedAggregateState(
                        new SlicedWindows(WindowSpec.tumbling(10 * SECOND)), kinds, 2, overflow, 64)) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot))) {
                restored.readFrom(in);
            }
            assertThat(restored.fire(10 * SECOND))
                    .allSatisfy(result -> assertThat(result.values()).containsExactly(6_000, 3_000));
            // Both occurrences of value 7 in group 0 retracted: it goes, and only it.
            restored.update(0L, 0L, new Object[] {0L}, SECOND, new long[] {0, 7}, -1);
            restored.update(0L, 0L, new Object[] {0L}, 2 * SECOND, new long[] {0, 7}, -1);
            assertThat(restored.fire(10 * SECOND).stream()
                            .filter(r -> ((Long) r.keyValues()[0]) == 0L)
                            .findFirst()
                            .orElseThrow()
                            .values())
                    .containsExactly(5_998, 2_999);
        }
    }

    /**
     * ADR-044: the churn a windowed aggregate always has -- every watermark discards the slices it
     * passed -- leaves its overflow slabs sparse, and compaction gives them back without changing a
     * single answer or a single checkpointed byte.
     */
    @Test
    void discardedSlicesAreCompactedAwayAndNoAnswerChanges(@TempDir Path dir) throws Exception {
        SlicedAggregateState.Kind[] kinds = {
            SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM, SlicedAggregateState.Kind.COUNT_DISTINCT
        };
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(10 * SECOND));
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                SlicedAggregateState state = new SlicedAggregateState(windows, kinds, 2, overflow, 1024)) {
            for (long slice = 0; slice < 10; slice++) {
                for (long group = 0; group < 1_500; group++) {
                    long time = slice * 10 * SECOND + SECOND;
                    state.update(group, group * 31, new Object[] {group}, time, new long[] {0, group, group % 7}, 1);
                    state.update(group, group * 31, new Object[] {group}, time, new long[] {0, 1, group % 11}, 1);
                }
            }
            assertThat(state.hasSpilled()).isTrue();
            // A watermark past the first eight windows: eight tenths of the state goes.
            state.discardSlicesEndingBefore(80 * SECOND, 0);
            List<SlicedAggregateState.WindowResult> ninth = state.fire(90 * SECOND);
            List<SlicedAggregateState.WindowResult> tenth = state.fire(100 * SECOND);
            ByteArrayOutputStream before = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(before)) {
                state.writeTo(out);
            }
            long reservedBefore = state.spillStatistics().overflowBytesReserved();

            int released = state.compactIfFragmented(0.5);

            assertThat(released).isPositive();
            assertThat(state.spillStatistics().overflowBytesReserved()).isLessThan(reservedBefore / 2);
            // Slabs and the index's mapped slot-table segments alike: every file is counted as held.
            try (var files = java.nio.file.Files.list(dir)) {
                long bytes = 0;
                for (Path file : files.toList()) {
                    bytes += java.nio.file.Files.size(file);
                }
                assertThat(bytes).isEqualTo(state.spillStatistics().overflowBytesReserved());
            }
            assertThat(state.fire(90 * SECOND))
                    .usingRecursiveFieldByFieldElementComparator()
                    .isEqualTo(ninth);
            assertThat(state.fire(100 * SECOND))
                    .usingRecursiveFieldByFieldElementComparator()
                    .isEqualTo(tenth);
            assertThat(ninth)
                    .hasSize(1_500)
                    .allSatisfy(result -> assertThat(result.values()[2])
                            .isEqualTo((Long) result.keyValues()[0] % 7 == (Long) result.keyValues()[0] % 11 ? 1 : 2));
            ByteArrayOutputStream after = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(after)) {
                state.writeTo(out);
            }
            assertThat(after.toByteArray()).isEqualTo(before.toByteArray());
            assertThat(state.compactIfFragmented(0.5))
                    .as("nothing freed since: not asked again")
                    .isZero();
        }
    }

    @Test
    void aVersion1CheckpointIsRefusedByName() {
        // Version 1 kept each distinct set inside its accumulator and no non-null counts: its bytes
        // would parse as version 2's in the wrong places.
        byte[] versionOne = {0, 0, 0, 1, 0, 0, 0, 1};
        try (SlicedAggregateState state = new SlicedAggregateState(
                new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.COUNT},
                10)) {
            assertThatThrownBy(() -> state.readFrom(new DataInputStream(new ByteArrayInputStream(versionOne))))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("format version 1")
                    .hasMessageContaining("ADR-044");
        }
    }

    @Test
    void anAverageSurvivesACheckpoint() throws Exception {
        // Version 1 wrote the sums and not the non-null counts AVG divides by, so a restored window
        // averaged to zero.
        SlicedAggregateState.Kind[] kinds = {SlicedAggregateState.Kind.AVG};
        byte[] snapshot;
        try (SlicedAggregateState state =
                new SlicedAggregateState(new SlicedWindows(WindowSpec.tumbling(10 * SECOND)), kinds, 10)) {
            state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {10}, 1);
            state.update(1L, 31L, new Object[] {1L}, SECOND, new long[] {20}, 1);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                state.writeTo(out);
            }
            snapshot = bytes.toByteArray();
        }
        try (SlicedAggregateState restored =
                new SlicedAggregateState(new SlicedWindows(WindowSpec.tumbling(10 * SECOND)), kinds, 10)) {
            restored.readFrom(new DataInputStream(new ByteArrayInputStream(snapshot)));
            assertThat(restored.fire(10 * SECOND).get(0).values()).containsExactly(15);
        }
    }

    @Test
    void countDistinctWithNoOverflowStillWorksExactlyAsBefore() {
        // No overflow tier: the same off-heap state, refusing at its RAM ceiling instead of
        // spilling, and answering exactly as the on-heap sets did.
        try (SlicedAggregateState state = new SlicedAggregateState(
                new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.COUNT_DISTINCT},
                1_000,
                null,
                0)) {
            state.update(1L, 1L * 31, new Object[] {1L}, SECOND, new long[] {100}, 1);
            state.update(1L, 1L * 31, new Object[] {1L}, 2 * SECOND, new long[] {100}, 1);
            state.update(1L, 1L * 31, new Object[] {1L}, 3 * SECOND, new long[] {200}, 1);

            assertThat(state.fire(10 * SECOND).get(0).values()[0]).isEqualTo(2);
            assertThat(state.hasSpilled()).isFalse();
        }
    }
}
