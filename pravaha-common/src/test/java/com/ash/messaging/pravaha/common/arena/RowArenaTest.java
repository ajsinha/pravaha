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
package com.ash.messaging.pravaha.common.arena;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.MutableSlice;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowArenaTest {

    private static final int SLAB = 4096;

    private static RowArena arena() {
        return new RowArena(MemoryAccess.best(), SLAB, 4);
    }

    @Test
    void allocationsAreEightByteAlignedAndDoNotOverlap() {
        try (RowArena a = arena()) {
            long h1 = a.allocate(13);
            long h2 = a.allocate(1);
            long h3 = a.allocate(64);

            assertThat(a.offsetOf(h1) % 8).isZero();
            assertThat(a.offsetOf(h2) % 8).isZero();
            assertThat(a.offsetOf(h3) % 8).isZero();
            assertThat(a.offsetOf(h2)).isGreaterThanOrEqualTo(a.offsetOf(h1) + 13);
            assertThat(a.offsetOf(h3)).isGreaterThanOrEqualTo(a.offsetOf(h2) + 1);
        }
    }

    @Test
    void resetIsConstantTimeRegardlessOfHowManyRowsWereAllocated() {
        try (RowArena a = arena()) {
            for (int i = 0; i < 200; i++) {
                a.allocate(16);
            }
            assertThat(a.bytesInUse()).isPositive();
            a.reset();
            assertThat(a.bytesInUse()).isZero();
            // Reuse must hand back the same space, or the arena would grow without bound.
            assertThat(a.offsetOf(a.allocate(16))).isZero();
        }
    }

    @Test
    void resetToAMarkRewindsOnlyPastIt() {
        try (RowArena a = arena()) {
            a.allocate(64);
            long mark = a.mark();
            long used = a.bytesInUse();
            for (int i = 0; i < 10; i++) {
                a.allocate(32);
            }
            a.resetTo(mark);
            assertThat(a.bytesInUse()).isEqualTo(used);
        }
    }

    @Test
    void growsIntoANewSlabWhenTheCurrentOneIsFull() {
        try (RowArena a = arena()) {
            assertThat(a.slabCount()).isOne();
            long first = a.allocate(SLAB - 8);
            long second = a.allocate(64);

            assertThat(ArenaHandle.slab(first)).isZero();
            assertThat(ArenaHandle.slab(second)).isEqualTo(1);
            assertThat(a.slabCount()).isEqualTo(2);
            assertThat(a.offsetOf(second)).isZero();
        }
    }

    @Test
    void exhaustionReturnsTheNullSentinelRatherThanThrowing() {
        // Running out of arena is a backpressure signal the lane handles, not an exceptional
        // condition (design section 13.5). Throwing here would turn a routine flow-control event into
        // an error path on the hot path.
        try (RowArena a = new RowArena(MemoryAccess.best(), 512, 2)) {
            assertThat(a.allocate(512)).isNotEqualTo(ArenaHandle.NULL);
            assertThat(a.allocate(512)).isNotEqualTo(ArenaHandle.NULL);
            assertThat(a.allocate(512)).isEqualTo(ArenaHandle.NULL);
            // Still usable afterwards: reset recovers everything.
            a.reset();
            assertThat(a.allocate(512)).isNotEqualTo(ArenaHandle.NULL);
        }
    }

    @Test
    void slabsAreRetainedAcrossResetsRatherThanFreed() {
        // The steady state is a lane cycling the same few megabytes forever. Freeing and
        // reallocating slabs every batch would hand the work straight back to the allocator.
        try (RowArena a = arena()) {
            a.allocate(SLAB - 8);
            a.allocate(64);
            assertThat(a.slabCount()).isEqualTo(2);
            a.reset();
            assertThat(a.slabCount()).isEqualTo(2);
            assertThat(a.bytesAllocated()).isEqualTo(2L * SLAB);
        }
    }

    @Test
    void trimGivesBackTheTailOfTheMostRecentAllocation() {
        try (RowArena a = arena()) {
            long h = a.allocate(256);
            long afterFull = a.bytesInUse();
            a.trimTo(h, 40);
            assertThat(a.bytesInUse()).isLessThan(afterFull).isEqualTo(a.offsetOf(h) + 40L);
        }
    }

    @Test
    void trimIgnoresAnythingButTheMostRecentSlab() {
        try (RowArena a = arena()) {
            long old = a.allocate(64);
            a.allocate(SLAB - 8);
            long used = a.bytesInUse();
            a.trimTo(old, 8);
            assertThat(a.bytesInUse()).isEqualTo(used);
        }
    }

    @Test
    void highWaterMarkSurvivesResetBecauseCapacityPlanningNeedsThePeak() {
        try (RowArena a = arena()) {
            for (int i = 0; i < 100; i++) {
                a.allocate(64);
            }
            long peak = a.highWaterMark();
            assertThat(peak).isPositive();
            a.reset();
            assertThat(a.bytesInUse()).isZero();
            assertThat(a.highWaterMark()).isEqualTo(peak);
        }
    }

    @Test
    void rejectsARowLargerThanASlabWithAnActionableMessage() {
        try (RowArena a = arena()) {
            assertThatThrownBy(() -> a.allocate(SLAB + 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds the slab size")
                    .hasMessageContaining("pravaha.lane.arena.slab-bytes"); // a real setting since ADR-036
        }
    }

    @Test
    void rejectsNonPositiveSizes() {
        try (RowArena a = arena()) {
            assertThatThrownBy(() -> a.allocate(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> a.allocate(-1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RowArena(MemoryAccess.best(), 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RowArena(MemoryAccess.best(), 64, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void useAfterCloseIsRejected() {
        RowArena a = arena();
        a.close();
        a.close(); // idempotent
        assertThatThrownBy(() -> a.allocate(8)).isInstanceOf(IllegalStateException.class);
        assertThat(a.toString()).contains("closed");
    }

    @Test
    void handlesRoundTripThroughTheirPackedEncoding() {
        assertThat(ArenaHandle.slab(ArenaHandle.of(3, 4096))).isEqualTo(3);
        assertThat(ArenaHandle.offset(ArenaHandle.of(3, 4096))).isEqualTo(4096);
        assertThat(ArenaHandle.offset(ArenaHandle.of(0, Integer.MAX_VALUE))).isEqualTo(Integer.MAX_VALUE);
        assertThat(ArenaHandle.describe(ArenaHandle.NULL)).contains("null");
        assertThat(ArenaHandle.describe(ArenaHandle.of(2, 64)))
                .contains("slab=2")
                .contains("offset=64");
    }

    @Test
    void rowsWrittenAcrossSlabsAllReadBackCorrectly() {
        // The end-to-end shape: allocate, write, read, reset. Spanning slabs is the case where a
        // handle-decoding mistake would show up as one batch reading another's memory.
        StreamSchema schema = StreamSchema.builder("t")
                .field("id", Types.int64())
                .field("name", Types.string())
                .build();
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);

        int rows = 200;
        int rowBytes = layout.rowSize(32);
        int slabBytes = 2048;
        // Sized from the row size rather than guessed, and asserted, so the test cannot quietly
        // start passing for the wrong reason if the layout changes width.
        int maxSlabs = (int) Math.ceilDiv((long) rows * rowBytes, slabBytes) + 2;

        try (RowArena a = new RowArena(MemoryAccess.best(), slabBytes, maxSlabs)) {
            List<Long> handles = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                long h = a.allocate(rowBytes);
                assertThat(h)
                        .as(
                                "row %d of %d (%d bytes each) should fit in %d slabs of %dB",
                                i, rows, rowBytes, maxSlabs, slabBytes)
                        .isNotEqualTo(ArenaHandle.NULL);
                MemoryRegion region = a.regionOf(h);
                writer.begin(region, a.offsetOf(h));
                writer.setLong(0, i).setString(1, "row-" + i).weight(1L).commit();
                a.trimTo(h, writer.sizeSoFar());
                handles.add(h);
            }

            assertThat(a.slabCount()).isGreaterThan(1);
            for (int i = 0; i < rows; i++) {
                long h = handles.get(i);
                view.wrap(a.regionOf(h), a.offsetOf(h));
                assertThat(view.getLong(0)).isEqualTo(i);
                assertThat(view.getString(1)).isEqualTo("row-" + i);
                assertThat(view.utf8Equals(1, ("row-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .isTrue();
            }
            assertThat(new MutableSlice().isEmpty()).isTrue();
        }
    }
}
