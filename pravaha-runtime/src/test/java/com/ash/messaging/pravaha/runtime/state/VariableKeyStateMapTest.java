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
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VariableKeyStateMap}, the deleted {@code L0StateMap}'s replacement (ADR-039 item 4,
 * W8-12). The test this class exists to pass is {@link #handlesStringKeysOfDifferentWidths}: a
 * fixed-width map cannot key on a {@code GROUP BY} column that is a {@code STRING} at all, and this
 * one must.
 *
 * <p>{@link #behavesLikeAHashMapUnderAMillionRandomOperations} follows {@code L0StateMapTest}'s own
 * shape closely -- the two silent failure modes an open-addressed table can have (a broken probe
 * chain, tombstones that never get swept) do not care whether the key is eight bytes or eighty.
 */
class VariableKeyStateMapTest {

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

    /** Writes a UTF-8 string into the shared scratch region and returns its length. */
    private int stringKey(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        scratch.putBytes(0, bytes, 0, bytes.length);
        return bytes.length;
    }

    @Test
    void storesAndReadsBackAVariableWidthKey() {
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 16, 4096, 4)) {
            int length = stringKey("user_42");
            long handle = map.getOrCreate(scratch, 0, length, 8);
            map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), 4200L);

            long found = map.find(scratch, 0, length);
            assertThat(found).isEqualTo(handle);
            assertThat(map.valueRegionOf(found).getLong(map.valueOffsetOf(found)))
                    .isEqualTo(4200L);
            assertThat(map.size()).isEqualTo(1);
        }
    }

    @Test
    void anAbsentKeyIsNotFound() {
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 16, 4096, 4)) {
            int length = stringKey("nobody");
            assertThat(map.find(scratch, 0, length)).isEqualTo(ArenaHandle.NULL);
        }
    }

    @Test
    void handlesStringKeysOfDifferentWidths() {
        // The specific case a fixed-width map -- L0StateMap, deleted for exactly this reason --
        // cannot represent at all: GROUP BY on a STRING column has no fixed width. "a", "ab", "abc"
        // and so on, up to a genuinely long key, in one table.
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 16, 4096, 8)) {
            String[] keys = {"a", "ab", "abc", "user_1", "a much longer grouping key than the others, deliberately"};
            long[] handles = new long[keys.length];
            for (int i = 0; i < keys.length; i++) {
                int length = stringKey(keys[i]);
                handles[i] = map.getOrCreate(scratch, 0, length, 8);
                map.valueRegionOf(handles[i]).putLong(map.valueOffsetOf(handles[i]), i);
            }
            for (int i = 0; i < keys.length; i++) {
                int length = stringKey(keys[i]);
                long found = map.find(scratch, 0, length);
                assertThat(found).as("key '%s'", keys[i]).isEqualTo(handles[i]);
                assertThat(map.valueRegionOf(found).getLong(map.valueOffsetOf(found)))
                        .as("key '%s'", keys[i])
                        .isEqualTo(i);
            }
            assertThat(map.size()).isEqualTo(keys.length);
        }
    }

    @Test
    void aKeyThatIsAPrefixOfAnotherIsAThirdDistinctEntry() {
        // The failure mode specific to byte-comparing variable-width keys: "ab" is not "a" plus a
        // trailing byte to this map, and a length check that only rejected on a *shorter* stored key
        // would still confuse "a" (found) with "ab" (also found) if the fingerprint happened to
        // collide, unless the length is compared before the bytes are.
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 16, 4096, 4)) {
            int lenA = stringKey("a");
            long handleA = map.getOrCreate(scratch, 0, lenA, 8);
            int lenAB = stringKey("ab");
            long handleAB = map.getOrCreate(scratch, 0, lenAB, 8);

            assertThat(handleAB).isNotEqualTo(handleA);
            assertThat(map.size()).isEqualTo(2);
            assertThat(map.find(scratch, 0, stringKey("a"))).isEqualTo(handleA);
            assertThat(map.find(scratch, 0, stringKey("ab"))).isEqualTo(handleAB);
        }
    }

    @Test
    void getOrCreateReturnsTheExistingHandleRatherThanDuplicating() {
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 16, 4096, 4)) {
            int length = stringKey("k");
            long first = map.getOrCreate(scratch, 0, length, 8);
            map.valueRegionOf(first).putLong(map.valueOffsetOf(first), 10);

            long second = map.getOrCreate(scratch, 0, stringKey("k"), 8);
            assertThat(second).isEqualTo(first);
            assertThat(map.size()).isEqualTo(1);
            assertThat(map.valueRegionOf(second).getLong(map.valueOffsetOf(second)))
                    .isEqualTo(10);
        }
    }

    @Test
    void aNewEntrysValueBytesAreZeroedEvenWhenTheBlockIsRecycled() {
        // RowStore does not clear a released block; a caller that assumed a fresh entry's value
        // started at zero would silently seed an accumulator from a previous key's leftover bytes.
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 16, 4096, 4)) {
            long first = map.getOrCreate(scratch, 0, stringKey("one"), 8);
            map.valueRegionOf(first).putLong(map.valueOffsetOf(first), 0xDEADBEEFL);
            map.remove(scratch, 0, stringKey("one"));

            long second = map.getOrCreate(scratch, 0, stringKey("two"), 8);
            assertThat(map.valueRegionOf(second).getLong(map.valueOffsetOf(second)))
                    .as("a recycled block's value bytes must not leak the previous key's data")
                    .isZero();
        }
    }

    @Test
    void removingAKeyDoesNotHideTheOnesBehindIt() {
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 16, 4096, 8)) {
            long[] handles = new long[5];
            for (int k = 0; k < 5; k++) {
                handles[k] = map.getOrCreate(scratch, 0, stringKey("key-" + k), 8);
                map.valueRegionOf(handles[k]).putLong(map.valueOffsetOf(handles[k]), k * 100L);
            }

            assertThat(map.remove(scratch, 0, stringKey("key-2"))).isTrue();

            assertThat(map.find(scratch, 0, stringKey("key-2"))).isEqualTo(ArenaHandle.NULL);
            for (int k : new int[] {0, 1, 3, 4}) {
                long found = map.find(scratch, 0, stringKey("key-" + k));
                assertThat(found).as("key-%d must still be reachable", k).isNotEqualTo(ArenaHandle.NULL);
                assertThat(map.valueRegionOf(found).getLong(map.valueOffsetOf(found)))
                        .isEqualTo(k * 100L);
            }
        }
    }

    @Test
    void aRemovedEntryIsReusedRatherThanLeaked() {
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 16, 4096, 16)) {
            for (long round = 0; round < 1_000; round++) {
                String key = "round-" + round;
                long handle = map.getOrCreate(scratch, 0, stringKey(key), 8);
                map.remove(scratch, 0, stringKey(key));
                assertThat(handle).isNotEqualTo(ArenaHandle.NULL);
            }

            assertThat(map.size()).isZero();
            assertThat(map.capacity())
                    .as("a thousand insert/remove pairs must not grow the index indefinitely")
                    .isLessThanOrEqualTo(64);
            assertThat(map.reuses())
                    .as("the key/value store must actually be reusing released blocks")
                    .isPositive();
        }
    }

    @Test
    void theTableGrowsAndKeepsEveryKeyReachable() {
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 4, 65536, 16)) {
            for (long k = 0; k < 10_000; k++) {
                long handle = map.getOrCreate(scratch, 0, stringKey("k" + k), 8);
                map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), k * 3);
            }

            assertThat(map.size()).isEqualTo(10_000);
            assertThat(map.resizes()).isPositive();
            for (long k = 0; k < 10_000; k++) {
                long found = map.find(scratch, 0, stringKey("k" + k));
                assertThat(found).as("key k%d survived the resizes", k).isNotEqualTo(ArenaHandle.NULL);
                assertThat(map.valueRegionOf(found).getLong(map.valueOffsetOf(found)))
                        .isEqualTo(k * 3);
            }
        }
    }

    @Test
    void everyLiveEntryIsVisitedExactlyOnce() {
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 16, 4096, 8)) {
            for (long k = 0; k < 500; k++) {
                map.getOrCreate(scratch, 0, stringKey("v" + k), 8);
            }
            for (long k = 0; k < 500; k += 3) {
                map.remove(scratch, 0, stringKey("v" + k));
            }

            Map<String, Long> visited = new HashMap<>();
            map.forEach(handle -> {
                MemoryRegion keyRegion = map.keyRegionOf(handle);
                int keyOffset = map.keyOffsetOf(handle);
                int keyLength = map.keyLengthOf(handle);
                byte[] bytes = new byte[keyLength];
                keyRegion.getBytes(keyOffset, bytes, 0, keyLength);
                String key = new String(bytes, StandardCharsets.UTF_8);
                assertThat(visited.put(key, handle))
                        .as("entry '%s' was visited twice", key)
                        .isNull();
            });

            assertThat(visited).hasSize(map.size());
        }
    }

    @Test
    void behavesLikeAHashMapUnderAMillionRandomOperations() {
        // The same check L0StateMapTest ran, over string keys of varying width instead of fixed
        // eight-byte longs -- the case the deleted class could not have expressed at all.
        Map<String, Long> model = new HashMap<>();
        Random random = new Random(20260916L);

        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 64, 1 << 16, 64)) {
            for (int i = 0; i < 1_000_000; i++) {
                if (i % 50_000 == 0) {
                    assertThat(map.averageProbes())
                            .as("probes per lookup after %d operations: %s", i, map)
                            .isLessThan(8.0);
                }
                String key = "key-" + random.nextInt(20_000);
                int length = stringKey(key);
                switch (random.nextInt(4)) {
                    case 0, 1 -> {
                        long value = random.nextLong();
                        long handle = map.getOrCreate(scratch, 0, length, 8);
                        map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), value);
                        model.put(key, value);
                    }
                    case 2 -> assertThat(map.remove(scratch, 0, length)).isEqualTo(model.remove(key) != null);
                    default -> {
                        long handle = map.find(scratch, 0, length);
                        boolean found = handle != ArenaHandle.NULL;
                        assertThat(found).as("key %s", key).isEqualTo(model.containsKey(key));
                        if (found) {
                            assertThat(map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle)))
                                    .isEqualTo(model.get(key));
                        }
                    }
                }
            }

            assertThat(map.size()).isEqualTo(model.size());
            assertThat(map.averageProbes())
                    .as("probes per lookup must stay bounded across a million operations: %s", map)
                    .isLessThan(4.0);
        }
    }

    @Test
    void measuredBytesPerEntryAgainstAnOnHeapHashMap() {
        // ADR-036's whole argument is measured cost per query, and a claim without a number does not
        // belong in it. This does not assert a threshold -- the honest number depends on the JVM,
        // the key length distribution and java.util.HashMap's own internals across versions -- it
        // prints what this run measured, which is what a reviewer deciding whether this is worth
        // wiring in needs to see.
        int entries = 50_000;
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 1024, 1 << 20, 64)) {
            for (int i = 0; i < entries; i++) {
                String key = "user_" + i;
                long handle = map.getOrCreate(scratch, 0, stringKey(key), 16);
                map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), i);
                map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle) + 8, i * 2L);
            }

            // Two off-heap figures, not one, because they answer different questions.
            // "Reserved" is what the OS has actually handed out right now, slab rounding included --
            // the honest total footprint of this run. "Live" divides only by bytes actually holding
            // an entry, which is the fairer per-entry marginal cost: RowStore's slab is carved ahead
            // of demand, so a reserved-only figure penalises this map for capacity it has not filled
            // yet, and that penalty shrinks as more entries land in the same slabs.
            double reservedPerEntry = map.bytesPerEntry();
            double livePerEntry = (double) (map.indexBytesAllocated() + map.dataBytesLive()) / entries;

            // java.util.HashMap<String, long[]>'s own footprint for the same shape: one Node object
            // (16-byte header + hash + key ref + value ref + next ref = 32B on a compressed-oops
            // heap), one String object per key (16B header + 3 fields, plus the backing byte[] --
            // Java 9+ compact strings store Latin-1 as one byte per char), one long[2] object per
            // value (16B header + 8B length-padding + 16B payload), and the table array's own slot
            // (8B, at a typical load factor of 0.75 that is roughly 1.33 slots/entry). This is an
            // estimate of what the alternative costs, not a measurement of a live HashMap -- but
            // every term is named so it can be checked, and none of these bytes are collector-
            // invisible the way this map's off-heap bytes are.
            long approxHashMapPerEntry = 32 // Node
                    + (16 + 8 + ("user_" + entries).getBytes(StandardCharsets.UTF_8).length) // String
                    + 32 // long[2] value object
                    + (long) Math.ceil(8 * 1.33); // table slot at 0.75 load factor

            System.out.println("VariableKeyStateMap: " + entries + " entries -- index "
                    + map.indexBytesAllocated() + "B, store live " + map.dataBytesLive() + "B / reserved "
                    + map.dataBytesReserved() + "B (" + map.reuses() + " reuses, " + map.resizes()
                    + " resizes)");
            System.out.println("  " + String.format("%.1f", livePerEntry) + " bytes/entry (live data), "
                    + String.format("%.1f", reservedPerEntry) + " bytes/entry (including unfilled slab "
                    + "capacity) -- vs an estimated ~" + approxHashMapPerEntry
                    + " bytes/entry for java.util.HashMap<String, long[2]>, none of which is "
                    + "collector-invisible the way this map's bytes are");

            // Reported for a reviewer's judgement, not gated on: whether the live-data figure beats
            // the on-heap estimate depends on RowStore's 64-byte minimum block size relative to the
            // key and value width, which is a real, worth-stating limit rather than something to
            // paper over with a threshold tuned to this one shape.
            assertThat(map.size()).isEqualTo(entries);
        }
    }

    /**
     * ADR-044: a spilled map that churned compacts its overflow slabs, and every key it still holds is
     * found, by the same probe, with its value -- the slots were rewritten with the moved handles, and
     * no slot moved, since the fingerprints did not change.
     */
    @Test
    void compactionGivesBackSparseOverflowSlabsAndEveryKeyIsStillFound(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Random random = new Random(20260919);
        try (var overflow = new com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess(dir);
                VariableKeyStateMap map = new VariableKeyStateMap(access, 64, 1 << 14, 2, overflow, 512)) {
            Map<String, Long> reference = new HashMap<>();
            for (int i = 0; i < 30_000; i++) {
                String key = "user-" + i + "-" + "x".repeat(random.nextInt(40));
                long handle = map.getOrCreate(scratch, 0, stringKey(key), Long.BYTES);
                map.valueRegionOf(handle).putLong(map.valueOffsetOf(handle), i * 7L);
                reference.put(key, i * 7L);
            }
            assertThat(map.hasSpilled()).isTrue();
            for (String key : new java.util.ArrayList<>(reference.keySet())) {
                if (random.nextInt(6) != 0) {
                    assertThat(map.remove(scratch, 0, stringKey(key))).isTrue();
                    reference.remove(key);
                }
            }
            long reservedBefore = map.spillStatistics().overflowBytesReserved();
            assertThat(map.needsCompaction(0.5)).isTrue();

            int released = map.compactOverflow(0.5);

            assertThat(released).isPositive();
            assertThat(map.spillStatistics().overflowBytesReserved()).isLessThan(reservedBefore / 2);
            try (var files = java.nio.file.Files.list(dir)) {
                assertThat(files.count() * (1 << 14))
                        .isEqualTo(map.spillStatistics().overflowBytesReserved());
            }
            assertThat(map.size()).isEqualTo(reference.size());
            reference.forEach((key, value) -> {
                long handle = map.find(scratch, 0, stringKey(key));
                assertThat(handle).as(key).isNotEqualTo(ArenaHandle.NULL);
                assertThat(map.valueRegionOf(handle).getLong(map.valueOffsetOf(handle)))
                        .as(key)
                        .isEqualTo(value);
            });
            int[] visited = {0};
            map.forEach(handle -> visited[0]++);
            assertThat(visited[0]).isEqualTo(reference.size());
        }
    }

    @Test
    void misconfigurationIsRefused() {
        assertThatThrownBy(() -> new VariableKeyStateMap(access, 1, 4096, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyWiderThanASlabIsRefusedRatherThanCorrupted() {
        try (VariableKeyStateMap map = new VariableKeyStateMap(access, 16, 128, 2)) {
            byte[] tooWide = new byte[256];
            MemoryRegion big = access.allocate(256);
            try {
                big.putBytes(0, tooWide, 0, tooWide.length);
                assertThatThrownBy(() -> map.getOrCreate(big, 0, tooWide.length, 8))
                        .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class);
            } finally {
                big.close();
            }
        }
    }
}
