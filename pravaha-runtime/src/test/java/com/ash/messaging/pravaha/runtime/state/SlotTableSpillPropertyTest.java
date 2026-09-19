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
package com.ash.messaging.pravaha.runtime.state;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-044: {@link VariableKeyStateMap}'s slot table in segments, and past its RAM budget in the
 * overflow tier, behaves exactly like an on-heap map -- through growth, tombstone sweeps, removals,
 * compaction of the store it points into, and a refusal part-way through a growth.
 *
 * <p>Segments here are 256 slots (4 KiB) and the RAM budget two of them, so a table of a few
 * thousand keys is mostly mapped files; the production segment is sixteen MiB, and nothing in the
 * table's code depends on the size.
 */
class SlotTableSpillPropertyTest {

    private static final int SEGMENT_SLOTS = 256;
    private static final long TABLE_RAM_BYTES = 2L * SEGMENT_SLOTS * 16;
    private static final int STORE_SLAB_BYTES = 1 << 14;

    private MemoryAccess access;
    private MemoryRegion scratch;

    @BeforeEach
    void setUp() {
        access = MemoryAccess.best();
        scratch = access.allocate(256);
    }

    @AfterEach
    void tearDown() {
        scratch.close();
    }

    private int key(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        scratch.putBytes(0, bytes, 0, bytes.length);
        return bytes.length;
    }

    /**
     * 200,000 random operations over 40,000 keys of varying width -- creates, updates, removals of
     * present and absent keys, lookups -- against a {@link HashMap}, with the store compacted every
     * 10,000 operations as a lane would between batches. The table grows from 64 slots to 64 Ki, its
     * RAM part never passes two segments, and every answer matches the model.
     */
    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {20260919L, 1L, 42L, 0xC0FFEEL})
    void aSpilledSlotTableAnswersLikeAnOnHeapMap(long seed, @TempDir Path dir) throws Exception {
        Random random = new Random(seed);
        Map<String, Long> model = new HashMap<>();
        List<String> everSeen = new ArrayList<>();
        int compactions = 0;
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir);
                VariableKeyStateMap map = new VariableKeyStateMap(
                        access, 64, STORE_SLAB_BYTES, 4, overflow, 100_000, TABLE_RAM_BYTES, SEGMENT_SLOTS)) {
            for (int op = 0; op < 200_000; op++) {
                // Growth, then churn over keys seen (tombstone sweeps at a spilled size), then a drain
                // that leaves the store's overflow slabs sparse enough to compact.
                boolean grow = op < 100_000;
                boolean drain = op >= 160_000;
                String k = grow || everSeen.isEmpty()
                        ? "k-" + random.nextInt(40_000) + "-" + "v".repeat(random.nextInt(24))
                        : everSeen.get(random.nextInt(everSeen.size()));
                int length = key(k);
                int choice = grow ? random.nextInt(3) : drain ? 2 + random.nextInt(3) : random.nextInt(5);
                switch (choice) {
                    case 0, 1 -> {
                        long value = random.nextLong();
                        long handle = map.getOrCreate(scratch, 0, length, Long.BYTES);
                        if (!model.containsKey(k)) {
                            assertThat(map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle)))
                                    .as("a new entry's value starts at zero")
                                    .isZero();
                            everSeen.add(k);
                        }
                        map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), value);
                        model.put(k, value);
                    }
                    case 2, 3 ->
                        assertThat(map.remove(scratch, 0, length))
                                .as("remove %s", k)
                                .isEqualTo(model.remove(k) != null);
                    default -> {
                        long handle = map.find(scratch, 0, length);
                        assertThat(handle != ArenaHandle.NULL).as("find %s", k).isEqualTo(model.containsKey(k));
                        if (handle != ArenaHandle.NULL) {
                            assertThat(map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle)))
                                    .isEqualTo(model.get(k));
                        }
                    }
                }
                assertThat(map.indexBytesAllocated() - map.indexBytesMapped())
                        .as("the table's RAM stays inside its budget")
                        .isLessThanOrEqualTo(TABLE_RAM_BYTES);
                if (op % 10_000 == 9_999) {
                    compactions += map.compactIfFragmented(0.5);
                }
            }

            assertThat(map.indexBytesMapped())
                    .as("the table must be mostly mapped for this to test anything")
                    .isGreaterThan(8 * TABLE_RAM_BYTES);
            assertThat(compactions).as("and its store compacted under it").isPositive();
            assertThat(map.size()).isEqualTo(model.size());
            for (Map.Entry<String, Long> entry : model.entrySet()) {
                long handle = map.find(scratch, 0, key(entry.getKey()));
                assertThat(handle).as(entry.getKey()).isNotEqualTo(ArenaHandle.NULL);
                assertThat(map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle)))
                        .isEqualTo(entry.getValue());
            }
            int[] visited = {0};
            map.forEach(handle -> visited[0]++);
            assertThat(visited[0]).isEqualTo(model.size());
            assertThat(VariableKeyStateMapTest.bytesOfFilesIn(dir))
                    .as("every mapped byte counted, slabs and table segments alike")
                    .isEqualTo(map.spillStatistics().overflowBytesReserved());
            assertThat(overflow.bytesMapped()).isEqualTo(map.spillStatistics().overflowBytesReserved());
        }
        try (var left = java.nio.file.Files.list(dir)) {
            assertThat(left).as("closing the map gives back every file").isEmpty();
        }
    }

    /** Without a tier, a table of many segments is all RAM and answers the same. */
    @Test
    void aTableOfManySegmentsInRamAnswersLikeAnOnHeapMap() {
        Random random = new Random(7);
        Map<Integer, Long> model = new HashMap<>();
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 2, STORE_SLAB_BYTES, 1_000, null, 0, 0, 64)) {
            for (int op = 0; op < 100_000; op++) {
                int k = random.nextInt(20_000);
                int length = key("n" + k);
                if (random.nextInt(3) == 0) {
                    assertThat(map.remove(scratch, 0, length)).isEqualTo(model.remove(k) != null);
                } else {
                    long handle = map.getOrCreate(scratch, 0, length, Long.BYTES);
                    map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), op);
                    model.put(k, (long) op);
                }
            }
            assertThat(map.capacity() / 64).as("segments").isGreaterThan(100);
            assertThat(map.indexBytesMapped()).isZero();
            for (Map.Entry<Integer, Long> entry : model.entrySet()) {
                long handle = map.find(scratch, 0, key("n" + entry.getKey()));
                assertThat(map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle)))
                        .isEqualTo(entry.getValue());
            }
        }
    }

    /**
     * A growth the node's spill quota cannot hold is refused with {@code PRV-4005}, and the map is
     * exactly as it was: every key it held is found with its value, the key that asked is absent,
     * and the map keeps answering and removing. The old table and the new one are both mapped while
     * the new one fills, so a growth needs room for both.
     */
    @Test
    void aGrowthTheQuotaCannotHoldIsRefusedAndTheMapIsUnchanged(@TempDir Path dir) {
        long quota = 64 * 1024;
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir, quota);
                VariableKeyStateMap map = new VariableKeyStateMap(
                        access, 64, STORE_SLAB_BYTES, 1_000, overflow, 1, TABLE_RAM_BYTES, SEGMENT_SLOTS)) {
            Map<String, Long> held = new HashMap<>();
            PravahaException refused = null;
            for (int i = 0; i < 100_000 && refused == null; i++) {
                String k = "q-" + i;
                int length = key(k);
                try {
                    long handle = map.getOrCreate(scratch, 0, length, Long.BYTES);
                    map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), i * 3L);
                    held.put(k, i * 3L);
                } catch (PravahaException e) {
                    refused = e;
                    assertThat(map.find(scratch, 0, key(k)))
                            .as("the key that asked")
                            .isEqualTo(ArenaHandle.NULL);
                }
            }

            assertThat(refused).isNotNull();
            assertThat(refused.errorCode().number()).isEqualTo(4005);
            assertThat(map.indexBytesMapped()).isPositive().isLessThanOrEqualTo(quota);
            assertThat(map.size()).isEqualTo(held.size());
            held.forEach((k, value) -> {
                long handle = map.find(scratch, 0, key(k));
                assertThat(handle).as(k).isNotEqualTo(ArenaHandle.NULL);
                assertThat(map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle)))
                        .isEqualTo(value);
            });
            assertThat(overflow.bytesMapped()).isEqualTo(map.indexBytesMapped());
            assertThat(map.remove(scratch, 0, key("q-0"))).isTrue();
            assertThat(map.find(scratch, 0, key("q-0"))).isEqualTo(ArenaHandle.NULL);
        }
    }

    @Test
    void aSegmentSizeThatIsNotAPowerOfTwoIsRefused() {
        assertThatThrownBy(() -> new VariableKeyStateMap(access, 64, 4096, 4, null, 0, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VariableKeyStateMap(access, 64, 4096, 4, null, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
