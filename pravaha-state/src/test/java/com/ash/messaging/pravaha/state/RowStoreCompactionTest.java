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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-044's slab compaction: a churning store's mapped files come back down to its live state, and
 * every value it holds survives the move.
 *
 * <p>The owner here is the simplest one there is: a table of handles, one per live key, each block
 * holding its key and a value derived from it, so a block that moved without its handle being
 * rewritten -- or was copied short, or was freed while live -- reads back as the wrong number.
 */
class RowStoreCompactionTest {

    private static final int SLAB = 1 << 16;

    /** A handle per key, and the owner contract: present each once, keep what comes back. */
    private static final class Owner implements RowStore.HandleOwner {
        final Map<Long, Long> handles = new HashMap<>();

        @Override
        public void relocateAll(RowStore.Relocation relocation) {
            handles.replaceAll((key, handle) -> relocation.relocate(handle));
        }
    }

    private static void write(RowStore store, long handle, long key, int payload) {
        var region = store.regionOf(handle);
        int offset = store.offsetOf(handle);
        region.putLong(offset, key);
        region.putLong(offset + payload - Long.BYTES, key * 31 + 7);
    }

    private static void assertHolds(RowStore store, Owner owner, int payload, String context) {
        owner.handles.forEach((key, handle) -> {
            var region = store.regionOf(handle);
            int offset = store.offsetOf(handle);
            assertThat(region.getLong(offset))
                    .as("%s: key %d, head", context, key)
                    .isEqualTo(key);
            assertThat(region.getLong(offset + payload - Long.BYTES))
                    .as("%s: key %d, tail", context, key)
                    .isEqualTo(key * 31 + 7);
        });
    }

    private static long filesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 7, 42, 20260919})
    void aChurningStoresFilesComeBackToItsLiveStateAndEveryValueSurvives(long seed, @TempDir Path dir)
            throws IOException {
        Random random = new Random(seed);
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                RowStore store = new RowStore(MemoryAccess.best(), SLAB, 2, overflow, 256)) {
            Owner owner = new Owner();
            long nextKey = 0;
            // Three sizes of block, so several size classes are live and freed at once.
            int[] payloads = {48, 200, 900};
            Map<Long, Integer> payloadOf = new HashMap<>();
            for (int i = 0; i < 20_000; i++) {
                int payload = payloads[random.nextInt(payloads.length)];
                long handle = store.allocate(payload);
                write(store, handle, nextKey, payload);
                owner.handles.put(nextKey, handle);
                payloadOf.put(nextKey, payload);
                nextKey++;
            }
            long peakFiles = filesIn(dir);
            assertThat(store.overflowSlabsLive())
                    .as("seed %d: the state spilled", seed)
                    .isGreaterThan(20);

            // Churn: nine in ten go, at random, the way a window's expiry or a join's retractions
            // leave a store -- every slab still carved, most of each one free.
            List<Long> keys = new ArrayList<>(owner.handles.keySet());
            for (long key : keys) {
                if (random.nextInt(10) != 0) {
                    store.release(java.util.Objects.requireNonNull(owner.handles.remove(key)));
                }
            }
            assertThat(filesIn(dir))
                    .as("seed %d: releasing blocks gives no file back by itself", seed)
                    .isEqualTo(peakFiles);
            assertThat(store.overflowFragmentation()).isGreaterThan(0.8);
            assertThat(store.needsCompaction(RowStore.DEFAULT_COMPACTION_THRESHOLD))
                    .isTrue();

            int released = store.compactOverflow(RowStore.DEFAULT_COMPACTION_THRESHOLD, owner);

            assertThat(released).as("seed %d", seed).isPositive();
            for (Map.Entry<Long, Long> entry : owner.handles.entrySet()) {
                long key = entry.getKey();
                int payload = java.util.Objects.requireNonNull(payloadOf.get(key));
                var region = store.regionOf(entry.getValue());
                assertThat(region.getLong(store.offsetOf(entry.getValue())))
                        .as("seed %d, key %d", seed, key)
                        .isEqualTo(key);
                assertThat(region.getLong(store.offsetOf(entry.getValue()) + payload - Long.BYTES))
                        .as("seed %d, key %d tail", seed, key)
                        .isEqualTo(key * 31 + 7);
            }
            // Near live size: the live overflow bytes, rounded up to whole slabs, plus the slab
            // being carved and one slab of size-class slack.
            long liveSlabs = (store.overflowBytesLive() + SLAB - 1) / SLAB;
            assertThat((long) store.overflowSlabsLive())
                    .as("seed %d: %s", seed, store)
                    .isLessThanOrEqualTo(liveSlabs + 2);
            assertThat(filesIn(dir))
                    .as("seed %d: a released slab's file is gone", seed)
                    .isEqualTo(store.overflowSlabsLive());
            assertThat(store.slabsReleased()).isEqualTo(released);
            assertThat(store.compactions()).isEqualTo(1);

            // The store carries on: released slots are reused, freed blocks are handed out, and
            // nothing already there is disturbed.
            for (int i = 0; i < 10_000; i++) {
                int payload = payloads[random.nextInt(payloads.length)];
                long handle = store.allocate(payload);
                write(store, handle, nextKey, payload);
                owner.handles.put(nextKey, handle);
                payloadOf.put(nextKey, payload);
                nextKey++;
            }
            for (Map.Entry<Long, Long> entry : owner.handles.entrySet()) {
                int payload = java.util.Objects.requireNonNull(payloadOf.get(entry.getKey()));
                assertThat(store.regionOf(entry.getValue()).getLong(store.offsetOf(entry.getValue()) + payload - 8))
                        .as("seed %d, key %d after refilling", seed, entry.getKey())
                        .isEqualTo(entry.getKey() * 31 + 7);
            }
            assertThat(store.liveBlocks()).isEqualTo(owner.handles.size());
        }
    }

    @Test
    void aStoreThatIsNotFragmentedEnoughIsLeftAlone(@TempDir Path dir) {
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                RowStore store = new RowStore(MemoryAccess.best(), SLAB, 1, overflow, 64)) {
            Owner owner = new Owner();
            for (long key = 0; key < 5_000; key++) {
                long handle = store.allocate(100);
                write(store, handle, key, 100);
                owner.handles.put(key, handle);
            }
            // One in five freed: every slab is still mostly live.
            for (long key = 0; key < 5_000; key += 5) {
                store.release(java.util.Objects.requireNonNull(owner.handles.remove(key)));
            }
            assertThat(store.needsCompaction(0.5)).isFalse();
            assertThat(store.compactOverflow(0.5, owner)).isZero();
            assertThat(store.compactions()).as("nothing was worth emptying").isZero();
            assertHolds(store, owner, 100, "untouched");
        }
    }

    @Test
    void aBlockThatCannotBePlacedStaysWhereItIsAndNothingIsLost(@TempDir Path dir) throws IOException {
        // The overflow ceiling is reached: there is nowhere outside the slabs being emptied to move
        // anything once the free blocks run out. Compaction must stop moving, keep those slabs, and
        // lose nothing.
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                RowStore store = new RowStore(MemoryAccess.best(), 4096, 1, overflow, 4)) {
            Owner owner = new Owner();
            long key = 0;
            while (true) {
                long handle;
                try {
                    handle = store.allocate(1000);
                } catch (com.ash.messaging.pravaha.api.PravahaException full) {
                    break;
                }
                write(store, handle, key, 1000);
                owner.handles.put(key++, handle);
            }
            assertThat(store.overflowSlabsLive()).isEqualTo(4);
            // Three of every four blocks freed in the first three overflow slabs, so they are sparse
            // and the fourth -- the one being carved -- is full; and one block freed in the RAM slab,
            // which is the only room there is: one relocation fits, the next does not.
            boolean freedInRam = false;
            for (Map.Entry<Long, Long> entry : new ArrayList<>(owner.handles.entrySet())) {
                int slab = ArenaHandle.slab(entry.getValue());
                if (slab >= 1 && slab <= 3 && entry.getKey() % 4 != 0) {
                    store.release(java.util.Objects.requireNonNull(owner.handles.remove(entry.getKey())));
                } else if (slab == 0 && !freedInRam) {
                    store.release(java.util.Objects.requireNonNull(owner.handles.remove(entry.getKey())));
                    freedInRam = true;
                }
            }
            int released = store.compactOverflow(0.5, owner);
            assertThat(released)
                    .as("one slab emptied into the one free RAM block; the other two had nowhere to go")
                    .isEqualTo(1);
            assertHolds(store, owner, 1000, "after a compaction that could not finish");
            assertThat(store.liveBlocks()).isEqualTo(owner.handles.size());
            assertThat(store.overflowSlabsLive()).isEqualTo(3);
            assertThat(filesIn(dir)).isEqualTo(3);
        }
    }

    @Test
    void anOwnerPresentingAReleasedHandleIsRefusedByName(@TempDir Path dir) {
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                RowStore store = new RowStore(MemoryAccess.best(), 4096, 1, overflow, 16)) {
            List<Long> handles = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                handles.add(store.allocate(100));
            }
            long stale = handles.get(handles.size() / 2);
            for (int i = 0; i < handles.size(); i++) {
                if (i % 8 != 0) {
                    store.release(handles.get(i));
                }
            }
            assertThatThrownBy(() -> store.compactOverflow(0.5, relocation -> relocation.relocate(stale)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not live");
        }
    }
}
