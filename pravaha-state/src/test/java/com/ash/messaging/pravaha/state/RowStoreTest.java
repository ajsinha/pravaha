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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

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
    void aRowTooLargeForASlabSaysWhichSettingToRaise() {
        try (RowStore store = new RowStore(MemoryAccess.best(), MIN_SLAB, 4)) {
            assertThatThrownBy(() -> store.allocate(MIN_SLAB))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("state.slab.size");
        }
    }

    @Test
    void aStoreRejectsSlabSizesItCannotIndex() {
        assertThatThrownBy(() -> new RowStore(MemoryAccess.best(), 1000, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("power of two");
    }
}
