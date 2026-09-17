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
 * correctly, and {@code COUNT(DISTINCT ...)} is refused by name rather than silently denied the
 * overflow tier it was asked for.
 *
 * <p>{@link SlicedAggregateStateTest} and {@link CountDistinctTest} are the tests that must keep
 * passing unchanged through this: they are what proves the off-heap and on-heap paths this class
 * chooses between still answer identically to before spilling existed at all.
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
    void countDistinctWithOverflowConfiguredIsRefusedAtConstruction(@TempDir Path dir) {
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir)) {
            assertThatThrownBy(() -> new SlicedAggregateState(
                            new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                            new SlicedAggregateState.Kind[] {
                                SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.COUNT_DISTINCT
                            },
                            1_000,
                            overflow,
                            8))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-3023")
                    .hasMessageContaining("COUNT(DISTINCT");
        }
    }

    @Test
    void countDistinctWithNoOverflowStillWorksExactlyAsBefore() {
        // The on-heap path is untouched code; this just confirms the 5-arg constructor's null
        // branch reaches it exactly the way the 3-arg constructor always has.
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
