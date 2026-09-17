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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.state.spill.MappedFileMemoryAccess;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-037's own instruction: "measure, do not assert." What this prints is the number the ADR asks
 * for -- what spilling costs when it happens -- rather than a number this test enforces as a gate.
 * Per-operation latency varies with the machine, the filesystem and the page cache, so the only
 * assertions here are structural (every block written is read back correctly, and disk is not
 * reported as free); the latency numbers themselves belong in a report, not in a threshold that
 * would make this test flaky on a slower disk without the engine having regressed at all.
 */
class RowStoreSpillMeasurementTest {

    private static final int SLAB_BYTES = 1 << 16;
    private static final int BLOCK_PAYLOAD = 200;
    private static final int OPERATIONS = 20_000;

    /**
     * What this can and cannot tell you: both tiers are measured back to back in one JVM, after a
     * warm-up pass through both code paths so neither number is inflated by being first through the
     * JIT. What it cannot control for is the operating system's page cache -- a few megabytes of
     * mapped file, written and read within milliseconds of each other, mostly never leaves RAM on
     * this machine, so this is not a measurement of physical disk latency. It is a measurement of
     * the one thing this change actually adds: one more level of indirection through {@code
     * MappedByteBuffer} instead of a direct one. A workload whose spilled state is large enough, or
     * old enough, to actually miss the page cache will cost more than this number; ADR-037's own
     * argument is that finding out how much more is exactly the measurement B1 was built to enable,
     * and it belongs in a real deployment's metrics, not a guess made here.
     */
    @Test
    void ramTierVersusOverflowTierPutAndGetLatency(@TempDir Path dir) {
        // Warm-up: run the measured loop shape through both paths once before the timed run, so
        // whichever tier is measured second is not the only one that benefits from the JIT having
        // already compiled RowStore.allocate/regionOf/MemoryRegion.putLong/getLong.
        measure(new RowStore(MemoryAccess.best(), SLAB_BYTES, 128));
        try (MappedFileMemoryAccess warmupOverflow = new MappedFileMemoryAccess(dir.resolve("warmup"))) {
            RowStore warmupStore = new RowStore(MemoryAccess.best(), SLAB_BYTES, 1, warmupOverflow, 128);
            while (!warmupStore.hasSpilled()) {
                warmupStore.allocate(BLOCK_PAYLOAD);
            }
            measure(warmupStore);
        }

        double ramNanosPerOp = measure(new RowStore(MemoryAccess.best(), SLAB_BYTES, 128));

        double diskNanosPerOp;
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir.resolve("measured"))) {
            // One in-memory slab; once it is exhausted, every further operation lands on disk, so
            // the measured loop below runs entirely in the overflow tier.
            RowStore diskStore = new RowStore(MemoryAccess.best(), SLAB_BYTES, 1, overflow, 128);
            while (!diskStore.hasSpilled()) {
                diskStore.allocate(BLOCK_PAYLOAD);
            }
            diskNanosPerOp = measure(diskStore);
        }

        System.out.println("RowStore B2 measurement -- " + OPERATIONS + " allocate+write+read operations each, "
                + "after warm-up (see this test's own javadoc for what this does and does not measure):");
        System.out.printf("  RAM tier:      %.1f ns/op%n", ramNanosPerOp);
        System.out.printf("  overflow tier: %.1f ns/op%n", diskNanosPerOp);
        System.out.printf("  overflow / RAM ratio: %.2fx%n", diskNanosPerOp / ramNanosPerOp);

        assertThat(ramNanosPerOp).isPositive();
        assertThat(diskNanosPerOp).isPositive();
    }

    @Test
    void howMuchMoreStateAnOverflowTierBuys(@TempDir Path dir) {
        int ramOnlyBlocks = capacity(new RowStore(MemoryAccess.best(), SLAB_BYTES, 4));
        int withOverflowBlocks;
        try (MappedFileMemoryAccess overflow = new MappedFileMemoryAccess(dir)) {
            withOverflowBlocks = capacity(new RowStore(MemoryAccess.best(), SLAB_BYTES, 4, overflow, 60));
        }

        System.out.println("RowStore B2 measurement -- capacity at " + BLOCK_PAYLOAD + "-byte rows, " + SLAB_BYTES
                + "-byte slabs:");
        System.out.println("  4 RAM slabs alone:        " + ramOnlyBlocks + " rows");
        System.out.println("  4 RAM slabs + 60 overflow: " + withOverflowBlocks + " rows ("
                + String.format("%.1f", (double) withOverflowBlocks / ramOnlyBlocks) + "x)");

        assertThat(withOverflowBlocks).isGreaterThan(ramOnlyBlocks);
    }

    /** Runs {@link #OPERATIONS} allocate+write+read cycles, verifies correctness, and times them. */
    private static double measure(RowStore store) {
        try {
            List<Long> handles = new ArrayList<>(OPERATIONS);
            long start = System.nanoTime();
            for (int i = 0; i < OPERATIONS; i++) {
                long handle = store.allocate(BLOCK_PAYLOAD);
                MemoryRegion region = store.regionOf(handle);
                int offset = store.offsetOf(handle);
                region.putLong(offset, i);
                handles.add(handle);
            }
            for (int i = 0; i < OPERATIONS; i++) {
                long handle = handles.get(i);
                long value = store.regionOf(handle).getLong(store.offsetOf(handle));
                if (value != i) {
                    throw new IllegalStateException("row " + i + " read back " + value + ", not " + i);
                }
            }
            long elapsed = System.nanoTime() - start;
            return (double) elapsed / (2 * OPERATIONS);
        } finally {
            store.close();
        }
    }

    /** How many blocks a store accepts before it refuses. */
    private static int capacity(RowStore store) {
        try {
            int count = 0;
            try {
                while (true) {
                    store.allocate(BLOCK_PAYLOAD);
                    count++;
                }
            } catch (com.ash.messaging.pravaha.api.PravahaException expected) {
                return count;
            }
        } finally {
            store.close();
        }
    }
}
