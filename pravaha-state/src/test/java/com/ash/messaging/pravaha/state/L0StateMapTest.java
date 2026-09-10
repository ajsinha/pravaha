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

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hot state tier.
 *
 * <p>An open-addressed table fails in two ways that a chained one does not, and both are silent.
 * Clearing a removed slot breaks the probe chain, so keys that collided with it and landed behind
 * become unreachable while still occupying space -- a lookup returns "absent" for a key that is
 * plainly there. And tombstones that do not count towards the load factor let a table fill with
 * them until every lookup is a linear scan, which is a performance collapse rather than an error.
 *
 * <p>{@link #behavesLikeAHashMapUnderAMillionRandomOperations} is the one that would catch either.
 */
class L0StateMapTest {

    private MemoryAccess access;
    private MemoryRegion keyScratch;
    private MemoryRegion valueScratch;
    private MemoryRegion outScratch;

    @BeforeEach
    void setUp() {
        access = MemoryAccess.best();
        keyScratch = access.allocate(64);
        valueScratch = access.allocate(64);
        outScratch = access.allocate(64);
    }

    @AfterEach
    void tearDown() {
        keyScratch.close();
        valueScratch.close();
        outScratch.close();
    }

    private MemoryRegion key(long value) {
        keyScratch.putLong(0, value);
        return keyScratch;
    }

    private MemoryRegion value(long value) {
        valueScratch.putLong(0, value);
        return valueScratch;
    }

    @Test
    void storesAndReadsBackAValue() {
        try (L0StateMap map = new L0StateMap(access, 8, 8, 16)) {
            map.put(key(42), 0, value(4200), 0);

            assertThat(map.get(key(42), 0, outScratch, 0)).isTrue();
            assertThat(outScratch.getLong(0)).isEqualTo(4200);
            assertThat(map.size()).isEqualTo(1);
        }
    }

    @Test
    void anAbsentKeyLeavesTheDestinationUntouched() {
        // A caller that ignores the boolean and reads the buffer anyway gets whatever was there
        // before, not a zero that looks like a real value.
        try (L0StateMap map = new L0StateMap(access, 8, 8, 16)) {
            outScratch.putLong(0, 0xDEAD);

            assertThat(map.get(key(99), 0, outScratch, 0)).isFalse();
            assertThat(outScratch.getLong(0)).isEqualTo(0xDEAD);
        }
    }

    @Test
    void puttingTwiceReplacesRatherThanDuplicating() {
        try (L0StateMap map = new L0StateMap(access, 8, 8, 16)) {
            map.put(key(1), 0, value(10), 0);
            map.put(key(1), 0, value(20), 0);

            assertThat(map.size()).isEqualTo(1);
            map.get(key(1), 0, outScratch, 0);
            assertThat(outScratch.getLong(0)).isEqualTo(20);
        }
    }

    @Test
    void removingAKeyDoesNotHideTheOnesBehindIt() {
        // The failure that makes open addressing subtle. Clearing a slot instead of marking it
        // breaks the probe chain, and keys that collided with the removed one become unreachable --
        // a lookup says "absent" for a key that is plainly still in the table.
        try (L0StateMap map = new L0StateMap(access, 8, 8, 8)) {
            // Enough keys that some must collide in an eight-slot table.
            for (long k = 0; k < 5; k++) {
                map.put(key(k), 0, value(k * 100), 0);
            }
            for (long k = 0; k < 5; k++) {
                assertThat(map.containsKey(key(k), 0)).isTrue();
            }

            map.remove(key(2), 0);

            assertThat(map.containsKey(key(2), 0)).isFalse();
            for (long k : new long[] {0, 1, 3, 4}) {
                assertThat(map.containsKey(key(k), 0))
                        .as("key %d must still be reachable after a neighbour was removed", k)
                        .isTrue();
                map.get(key(k), 0, outScratch, 0);
                assertThat(outScratch.getLong(0)).isEqualTo(k * 100);
            }
        }
    }

    @Test
    void aRemovedSlotIsReusedRatherThanLeaked() {
        try (L0StateMap map = new L0StateMap(access, 8, 8, 16)) {
            for (long round = 0; round < 1_000; round++) {
                map.put(key(round), 0, value(round), 0);
                map.remove(key(round), 0);
            }

            assertThat(map.size()).isZero();
            assertThat(map.capacity())
                    .as("a thousand insert/remove pairs must not grow the table indefinitely")
                    .isLessThanOrEqualTo(64);
        }
    }

    @Test
    void theTableGrowsAndKeepsEveryKeyReachable() {
        try (L0StateMap map = new L0StateMap(access, 8, 8, 4)) {
            for (long k = 0; k < 10_000; k++) {
                map.put(key(k), 0, value(k * 3), 0);
            }

            assertThat(map.size()).isEqualTo(10_000);
            assertThat(map.resizes()).isPositive();
            for (long k = 0; k < 10_000; k++) {
                assertThat(map.get(key(k), 0, outScratch, 0))
                        .as("key %d survived the resizes", k)
                        .isTrue();
                assertThat(outScratch.getLong(0)).isEqualTo(k * 3);
            }
        }
    }

    @Test
    void sequentialKeysDoNotClusterIntoOneProbeChain() {
        // Keys whose low bits are regular -- sequential ids, millisecond timestamps -- are the
        // common case, and an unfinalised hash turns them into a linear scan. Degrades gradually,
        // never errors, which is why it needs a number rather than an assertion of correctness.
        try (L0StateMap map = new L0StateMap(access, 8, 8, 1024)) {
            for (long k = 0; k < 5_000; k++) {
                map.put(key(k * 1024), 0, value(k), 0);
            }
            for (long k = 0; k < 5_000; k++) {
                map.containsKey(key(k * 1024), 0);
            }

            assertThat(map.averageProbes())
                    .as("clustered keys still probe close to once: %s", map)
                    .isLessThan(3.0);
        }
    }

    @Test
    void everyLiveEntryIsVisitedExactlyOnce() {
        try (L0StateMap map = new L0StateMap(access, 8, 8, 16)) {
            for (long k = 0; k < 500; k++) {
                map.put(key(k), 0, value(k), 0);
            }
            for (long k = 0; k < 500; k += 3) {
                map.remove(key(k), 0);
            }

            Map<Long, Long> visited = new HashMap<>();
            map.forEach((region, keyOffset, valueOffset) -> assertThat(
                            visited.put(region.getLong(keyOffset), region.getLong(valueOffset)))
                    .as("an entry was visited twice")
                    .isNull());

            assertThat(visited).hasSize(map.size());
            visited.forEach((k, v) -> assertThat(v).isEqualTo(k));
        }
    }

    @Test
    void behavesLikeAHashMapUnderAMillionRandomOperations() {
        // The check that would catch either silent failure: a broken probe chain shows up as a key
        // the model has and the map does not, and tombstone accumulation shows up as probes per
        // lookup climbing without bound.
        Map<Long, Long> model = new HashMap<>();
        Random random = new Random(20260910L);

        try (L0StateMap map = new L0StateMap(access, 8, 8, 64)) {
            for (int i = 0; i < 1_000_000; i++) {
                if (i % 50_000 == 0) {
                    // Checked as it goes, not only at the end. Seeding "tombstones do not count
                    // towards the load factor" degrades the table to a linear scan, and a million
                    // operations against a linear scan does not fail -- it hangs, which reads in CI
                    // as an infrastructure problem and gets retried rather than read. Third time
                    // this project has learned that; this one fails in seconds.
                    assertThat(map.averageProbes())
                            .as("probes per lookup after %d operations: %s", i, map)
                            .isLessThan(8.0);
                }
                long k = random.nextInt(20_000);
                switch (random.nextInt(4)) {
                    case 0, 1 -> {
                        long v = random.nextLong();
                        map.put(key(k), 0, value(v), 0);
                        model.put(k, v);
                    }
                    case 2 -> {
                        assertThat(map.remove(key(k), 0)).isEqualTo(model.remove(k) != null);
                    }
                    default -> {
                        boolean found = map.get(key(k), 0, outScratch, 0);
                        assertThat(found).as("key %d", k).isEqualTo(model.containsKey(k));
                        if (found) {
                            assertThat(outScratch.getLong(0)).isEqualTo(model.get(k));
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
    void misconfigurationIsRefused() {
        assertThatThrownBy(() -> new L0StateMap(access, 0, 8, 16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new L0StateMap(access, 8, 0, 16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new L0StateMap(access, 8, 8, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
