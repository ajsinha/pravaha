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
package com.ash.messaging.pravaha.state.spill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.state.RowStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-044's byte quota and the free-space check: a spill that would pass the node's budget, or not fit
 * the disk, is refused by code before a file exists -- and the store that asked is left exactly as it
 * was, able to carry on once room comes back.
 */
class SpillQuotaTest {

    private static final int SLAB = 1 << 16;

    private static long filesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    void theQuotaCountsEverySlabMappedAndRefusesThePastItByCode(@TempDir Path dir) throws IOException {
        try (MappedFileMemoryAccess access = new MappedFileMemoryAccess(dir, 3L * SLAB)) {
            List<MemoryRegion> slabs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                slabs.add(access.allocate(SLAB));
            }
            assertThat(access.bytesMapped()).isEqualTo(3L * SLAB);

            assertThatThrownBy(() -> access.allocate(SLAB))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4005")
                    .hasMessageContaining("pravaha.state.spill.max-bytes");
            assertThat(filesIn(dir)).as("refused before a file was created").isEqualTo(3);
            assertThat(access.bytesMapped()).as("a refusal reserves nothing").isEqualTo(3L * SLAB);

            // A closed region gives its bytes back, and the next slab fits.
            slabs.remove(0).close();
            assertThat(access.bytesMapped()).isEqualTo(2L * SLAB);
            assertThat(access.slabsReleased()).isEqualTo(1);
            slabs.add(access.allocate(SLAB));
            assertThat(filesIn(dir)).isEqualTo(3);
            slabs.forEach(MemoryRegion::close);
            assertThat(access.bytesMapped()).isZero();
        }
    }

    @Test
    void aSlabTheDiskCannotHoldIsRefusedBeforeItIsCreated(@TempDir Path dir) throws IOException {
        AtomicLong free = new AtomicLong(SLAB + 1);
        try (MappedFileMemoryAccess access = new MappedFileMemoryAccess(dir, 0, directory -> free.get())) {
            MemoryRegion first = access.allocate(SLAB);
            free.set(SLAB - 1);

            assertThatThrownBy(() -> access.allocate(SLAB))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4006")
                    .hasMessageContaining(dir.toString())
                    .hasMessageContaining("pravaha.state.spill.max-bytes");
            assertThat(filesIn(dir)).isEqualTo(1);
            assertThat(access.bytesMapped())
                    .as("the refused slab's quota reservation is given back")
                    .isEqualTo(SLAB);
            first.close();
        }
    }

    @Test
    void aStoreRefusedByTheQuotaStopsWithTheQuotasCodeAndCarriesOnWhenRoomReturns(@TempDir Path dir) {
        try (MappedFileMemoryAccess access = new MappedFileMemoryAccess(dir, 2L * SLAB);
                RowStore store = new RowStore(MemoryAccess.best(), SLAB, 1, access, 64)) {
            List<Long> handles = new ArrayList<>();
            assertThatThrownBy(() -> {
                        while (true) {
                            handles.add(store.allocate(1000));
                        }
                    })
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-4005");
            assertThat(store.overflowSlabsLive())
                    .as("two overflow slabs fit the quota; the third was refused, not half-made")
                    .isEqualTo(2);
            long live = store.liveBlocks();
            for (int i = 0; i < handles.size(); i += 2) {
                store.release(handles.get(i));
            }
            // Room inside the store comes back through its free list, and nothing new is mapped.
            for (int i = 0; i < 10; i++) {
                store.allocate(1000);
            }
            assertThat(store.liveBlocks()).isEqualTo(live - (handles.size() + 1) / 2 + 10);
            assertThat(access.bytesMapped()).isEqualTo(2L * SLAB);
        }
    }

    @Test
    void aNegativeQuotaIsRefused(@TempDir Path dir) {
        assertThatThrownBy(() -> new MappedFileMemoryAccess(dir, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
