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
package com.ash.messaging.pravaha.state;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store exists for one property: a join whose row count is flat must not grow.
 *
 * <p>Everything else here supports that. Blocks come back to their size class, a released block is
 * handed out again, and the reserved footprint stops climbing once the churn is in steady state --
 * which is exactly what a bump arena cannot do and why this is a separate thing.
 */
class RowStoreTest {

    private static final int SLAB = 1 << 16;

    /** SPILL-4: every live block once, freed ones not at all, in the order they lie in memory. */
    @Test
    void aWalkInMemoryOrderVisitsEveryLiveBlockOnceInAddressOrder() {
        try (RowStore store = new RowStore(MemoryAccess.best(), SLAB, 64)) {
            List<Long> live = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                live.add(store.allocate(i % 3 == 0 ? 40 : 300));
            }
            for (int i = 0; i < live.size(); i += 7) {
                store.release(live.get(i));
            }
            List<Long> kept = new ArrayList<>();
            for (int i = 0; i < live.size(); i++) {
                if (i % 7 != 0) {
                    kept.add(live.get(i));
                }
            }
            assertThat(store.slabCount()).as("the walk crosses slabs").isGreaterThan(1);

            List<Long> visited = new ArrayList<>();
            store.forEachLive(visited::add);

            assertThat(visited).containsExactlyInAnyOrderElementsOf(kept);
            assertThat(visited).as("slab, then offset: a handle's own order").isSorted();
        }
    }

    @Test
    void aBlockHoldsWhatIsWrittenToIt() {
        try (RowStore store = new RowStore(MemoryAccess.best(), SLAB, 4)) {
            long handle = store.allocate(200);
            MemoryRegion region = store.regionOf(handle);
            int offset = store.offsetOf(handle);

            region.putLong(offset, 0x0123456789ABCDEFL);
            region.putLong(offset + 192, -1L);

            assertThat(region.getLong(offset)).isEqualTo(0x0123456789ABCDEFL);
            assertThat(region.getLong(offset + 192)).isEqualTo(-1L);
            assertThat(store.capacityOf(handle)).isGreaterThanOrEqualTo(200);
        }
    }

    @Test
    void blocksDoNotOverlap() {
        // Two live blocks sharing bytes is the failure that shows up as one query's rows in
        // another's output, so it is worth proving rather than assuming.
        try (RowStore store = new RowStore(MemoryAccess.best(), SLAB, 4)) {
            List<Long> handles = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                long handle = store.allocate(100 + (i % 7) * 40);
                store.regionOf(handle).putInt(store.offsetOf(handle), i);
                handles.add(handle);
            }
            for (int i = 0; i < handles.size(); i++) {
                assertThat(store.regionOf(handles.get(i)).getInt(store.offsetOf(handles.get(i))))
                        .as("block %d was overwritten by another", i)
                        .isEqualTo(i);
            }
        }
    }

    @Test
    void aReleasedBlockIsHandedOutAgain() {
        try (RowStore store = new RowStore(MemoryAccess.best(), SLAB, 4)) {
            long first = store.allocate(100);
            store.release(first);
            long second = store.allocate(100);

            assertThat(second).isEqualTo(first);
            assertThat(store.reuses()).isEqualTo(1);
        }
    }

    @Test
    void steadyChurnDoesNotGrowTheFootprint() {
        // The point of the whole class. A join holding a constant number of rows while rows arrive
        // and retract must reserve a constant amount of memory.
        try (RowStore store = new RowStore(MemoryAccess.best(), SLAB, 64)) {
            List<Long> live = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                live.add(store.allocate(300));
            }
            long reservedWhenFull = store.bytesReserved();

            for (int round = 0; round < 50; round++) {
                for (int i = 0; i < live.size(); i++) {
                    store.release(live.get(i));
                    live.set(i, store.allocate(300));
                }
            }

            assertThat(store.bytesReserved()).isEqualTo(reservedWhenFull);
            assertThat(store.liveBlocks()).isEqualTo(500);
            assertThat(store.reuses()).isEqualTo(500L * 50);
        }
    }

    @Test
    void freeAndLiveBytesAccountForEachOther() {
        try (RowStore store = new RowStore(MemoryAccess.best(), SLAB, 8)) {
            List<Long> handles = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                handles.add(store.allocate(50 + i));
            }
            long total = store.bytesLive();

            for (int i = 0; i < 40; i++) {
                store.release(handles.get(i));
            }

            assertThat(store.bytesLive() + store.bytesFree()).isEqualTo(total);
            assertThat(store.liveBlocks()).isEqualTo(60);
        }
    }

    @Test
    void aBlockIsOnlyReusedForItsOwnSizeClass() {
        // Handing a 64-byte block to a request for 1000 bytes is the free-list bug that writes past
        // the end of a block and corrupts its neighbour.
        try (RowStore store = new RowStore(MemoryAccess.best(), SLAB, 4)) {
            long small = store.allocate(16);
            store.release(small);

            long large = store.allocate(1000);

            assertThat(large).isNotEqualTo(small);
            assertThat(store.capacityOf(large)).isGreaterThanOrEqualTo(1000);
            assertThat(store.reuses()).isZero();
        }
    }

    @Test
    void releasingTwiceIsRefusedRatherThanCorruptingTheFreeList() {
        try (RowStore store = new RowStore(MemoryAccess.best(), SLAB, 4)) {
            long handle = store.allocate(100);
            store.release(handle);

            assertThatThrownBy(() -> store.release(handle))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("released twice");

            // And the free list is still a list: the block comes back exactly once.
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < 10; i++) {
                assertThat(seen.add(store.allocate(100)))
                        .as("the same block was handed out twice")
                        .isTrue();
            }
        }
    }

    @Test
    void runningOutOfSlabsNamesTheCauseRatherThanTheSymptom() {
        try (RowStore store = new RowStore(MemoryAccess.best(), MIN_SLAB, 2)) {
            assertThatThrownBy(() -> {
                        for (int i = 0; i < 10_000; i++) {
                            store.allocate(200);
                        }
                    })
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4001")
                    .hasMessageContaining("bound it with a window");
        }
    }

    private static final int MIN_SLAB = 1 << 12;

    @Test
    void aRowTooLargeForASlabSaysWhatToDoAndInventsNoSetting() {
        // PF-3/DOCX-20. It used to say "raise state.slab.size for this query". There is no such
        // key and never was: the slab size here is a constant of the operator holding the store.
        // Advice naming a key nothing reads is worse than no advice, because it is followed.
        try (RowStore store = new RowStore(MemoryAccess.best(), MIN_SLAB, 4)) {
            assertThatThrownBy(() -> store.allocate(MIN_SLAB))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("state slab")
                    .hasMessageContaining("the row has to be narrower")
                    .hasMessageNotContaining("state.slab.size");
        }
    }

    @Test
    void aStoreRejectsSlabSizesItCannotIndex() {
        assertThatThrownBy(() -> new RowStore(MemoryAccess.best(), 1000, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("power of two");
    }

    // ------------------------------------------------------------------ ADR-037 item B2: overflow

    @Test
    void withNoOverflowTierGivenTheStoreBehavesExactlyAsBefore() {
        try (RowStore store = new RowStore(MemoryAccess.best(), MIN_SLAB, 2, null, 0)) {
            assertThat(store.hasSpilled()).isFalse();
            assertThat(store.overflowSlabsUsed()).isZero();
        }
    }

    @Test
    void anOverflowTierNeedsAtLeastOneSlab(@TempDir Path dir) {
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir)) {
            assertThatThrownBy(() -> new RowStore(MemoryAccess.best(), MIN_SLAB, 2, overflow, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overflow slab");
        }
    }

    @Test
    void aQueryThatWouldHaveBeenRefusedKeepsRunningOnceOverflowIsConfigured(@TempDir Path dir) {
        // Exactly the scenario ADR-037 names: two in-memory slabs are not enough for what this test
        // writes, and without an overflow tier this is runningOutOfSlabsNamesTheCauseRatherThanTheSymptom
        // above -- PRV-4001, query dead. With one it keeps accepting rows.
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                RowStore store = new RowStore(MemoryAccess.best(), MIN_SLAB, 2, overflow, 4)) {
            for (int i = 0; i < 80; i++) {
                store.allocate(200);
            }
            assertThat(store.hasSpilled())
                    .as("400 rows of 200 bytes each do not fit two 4 KiB slabs, so this must have spilled")
                    .isTrue();
            assertThat(store.overflowSlabsUsed()).isGreaterThan(0);
        }
    }

    @Test
    void aRowWrittenIntoAnOverflowSlabReadsBackCorrectly(@TempDir Path dir) {
        // The point of putting the overflow tier behind the same RowStore/MemoryRegion API: a
        // caller writes and reads a spilled row exactly as it does an in-memory one, with no
        // branch anywhere asking which tier a handle's slab came from.
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                RowStore store = new RowStore(MemoryAccess.best(), MIN_SLAB, 1, overflow, 4)) {
            List<Long> handles = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                long handle = store.allocate(64);
                store.regionOf(handle).putLong(store.offsetOf(handle), i);
                handles.add(handle);
            }
            assertThat(store.hasSpilled()).isTrue();
            for (int i = 0; i < handles.size(); i++) {
                long handle = handles.get(i);
                assertThat(store.regionOf(handle).getLong(store.offsetOf(handle)))
                        .as("row %d, possibly in the overflow tier", i)
                        .isEqualTo(i);
            }
        }
    }

    @Test
    void reachingBothCeilingsStillRefusesRatherThanGrowingWithoutBound(@TempDir Path dir) {
        // A second tier moves the ceiling; it does not remove it. Eviction remains off the table
        // (ADR-037), so exhausting both tiers must still fail loudly.
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                RowStore store = new RowStore(MemoryAccess.best(), MIN_SLAB, 1, overflow, 1)) {
            assertThatThrownBy(() -> {
                        for (int i = 0; i < 10_000; i++) {
                            store.allocate(200);
                        }
                    })
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4001")
                    .hasMessageContaining("overflow");
        }
    }

    @Test
    void blocksReleasedFromAnOverflowSlabAreReusedLikeAnyOther(@TempDir Path dir) {
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                RowStore store = new RowStore(MemoryAccess.best(), MIN_SLAB, 1, overflow, 2)) {
            List<Long> handles = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                handles.add(store.allocate(64));
            }
            assertThat(store.hasSpilled()).isTrue();
            long reusesBefore = store.reuses();
            for (long handle : handles) {
                store.release(handle);
            }
            for (int i = 0; i < 80; i++) {
                store.allocate(64);
            }
            assertThat(store.reuses()).isGreaterThan(reusesBefore);
        }
    }
}
