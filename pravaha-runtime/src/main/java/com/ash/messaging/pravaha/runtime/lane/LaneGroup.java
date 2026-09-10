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
package com.ash.messaging.pravaha.runtime.lane;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;

/**
 * A query's lanes, and the routing that decides which one a key belongs to.
 *
 * <p>Keys are hashed into a fixed number of <strong>virtual partitions</strong> and virtual
 * partitions are assigned to lanes (design section 21.1). The indirection looks like ceremony at one
 * node and is the reason rescaling is tractable later: moving a lane's work means reassigning
 * virtual partitions, never rehashing a key, so a key's state moves as one piece and its ordering
 * survives.
 *
 * <p><strong>What is shared between lanes: nothing.</strong> Each lane builds its own inbox, arena,
 * thread and processor, and the only cross-lane object in this class is the assignment array, which
 * is written once at construction and read-only thereafter. That is not a tidiness argument -- it is
 * the entire basis for expecting eight lanes to run eight times as fast (design section 13.1), and
 * it is asserted rather than assumed by the lane-independence test.
 *
 * <p>Lane-to-lane exchange -- a lane forwarding a row to the lane that owns a key after a repartition
 * -- is P2-07 and not here yet. Today a producer routes with {@link #laneFor(long)} before it writes,
 * which is the common case and the one Profile A measures.
 */
public final class LaneGroup implements AutoCloseable {

    /** Design section 21.1's default. A power of two, so the key-to-partition step is a mask. */
    public static final int DEFAULT_VIRTUAL_PARTITIONS = 1024;

    private final List<Lane> lanes;
    private final int[] assignment;
    private final int partitionMask;

    /**
     * @param laneCount lanes to build. Design section 13.2 sizes this
     *     {@code min(physicalCores - 2, configured)}: two cores are left for GC, JIT and the OS,
     *     because saturating every core is the classic cause of a p99.9 cliff.
     * @param virtualPartitions rounded up to a power of two; immutable for the group's life
     */
    public LaneGroup(
            int laneCount,
            int virtualPartitions,
            LaneConfig config,
            MemoryAccess access,
            LaneProcessorFactory factory) {
        if (laneCount < 1) {
            throw new IllegalArgumentException("a query needs at least one lane, got " + laneCount);
        }
        int partitions = nextPowerOfTwo(virtualPartitions);
        if (partitions < laneCount) {
            throw new IllegalArgumentException("cannot spread " + partitions + " virtual partitions over " + laneCount
                    + " lanes; raise the partition count, which is fixed for the query's life");
        }
        this.partitionMask = partitions - 1;
        this.assignment = new int[partitions];

        List<List<Integer>> owned = new ArrayList<>(laneCount);
        for (int i = 0; i < laneCount; i++) {
            owned.add(new ArrayList<>());
        }
        for (int partition = 0; partition < partitions; partition++) {
            // Round robin. Even with a power-of-two partition count and any lane count, the largest
            // and smallest share differ by at most one partition.
            int lane = partition % laneCount;
            assignment[partition] = lane;
            owned.get(lane).add(partition);
        }

        List<Lane> built = new ArrayList<>(laneCount);
        try {
            for (int i = 0; i < laneCount; i++) {
                built.add(new Lane(i, config, access, toIntArray(owned.get(i)), factory));
            }
        } catch (RuntimeException e) {
            // A half-built group would leak an arena and an inbox per lane already constructed.
            built.forEach(Lane::close);
            throw e;
        }
        this.lanes = List.copyOf(built);
    }

    /** A group sized with the default 1024 virtual partitions. */
    public LaneGroup(int laneCount, LaneConfig config, MemoryAccess access, LaneProcessorFactory factory) {
        this(laneCount, DEFAULT_VIRTUAL_PARTITIONS, config, access, factory);
    }

    private static int nextPowerOfTwo(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("virtual partition count must be positive, got " + value);
        }
        int result = Integer.highestOneBit(value);
        return result == value ? value : result << 1;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    /**
     * The virtual partition a key hash belongs to.
     *
     * <p>The hash is finalised before it is masked. Taking the low bits of a caller's hash directly
     * would inherit whatever structure that hash has -- and a great many real keys hash to values
     * whose low bits are near-constant, which lands most of the traffic on one lane and turns an
     * eight-lane query into a one-lane query with seven idle threads.
     */
    public int virtualPartitionFor(long keyHash) {
        return (int) (finalise(keyHash) & partitionMask);
    }

    /** The lane a key hash routes to. */
    public int laneFor(long keyHash) {
        return assignment[virtualPartitionFor(keyHash)];
    }

    /** MurmurHash3's 64-bit finaliser: cheap, and it moves every input bit into every output bit. */
    private static long finalise(long hash) {
        long h = hash;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    public Lane lane(int index) {
        return lanes.get(index);
    }

    public List<Lane> lanes() {
        return lanes;
    }

    public int laneCount() {
        return lanes.size();
    }

    public int virtualPartitionCount() {
        return assignment.length;
    }

    /** Which lane owns a given virtual partition. What a rescaling operation will rewrite. */
    public int laneOfPartition(int partition) {
        return assignment[partition];
    }

    public void start() {
        lanes.forEach(Lane::start);
    }

    /** Waits for every lane to have nothing left to do, sharing one deadline between them. */
    public boolean awaitQuiescent(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Lane lane : lanes) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !lane.awaitQuiescent(Duration.ofNanos(remaining))) {
                return false;
            }
        }
        return true;
    }

    /** Rethrows the first lane failure, if any lane died. */
    public void checkHealth() {
        lanes.forEach(Lane::checkHealth);
    }

    public List<LaneMetrics> metrics() {
        return lanes.stream().map(Lane::metrics).toList();
    }

    /** Rows in and out across every lane, for the query-level rate. */
    public LaneMetrics totals() {
        long in = 0;
        long out = 0;
        long batches = 0;
        long idle = 0;
        long rejected = 0;
        long highWater = 0;
        double fill = 0;
        for (LaneMetrics m : metrics()) {
            in += m.rowsIn();
            out += m.rowsOut();
            batches += m.batches();
            idle += m.idleCycles();
            rejected += m.rejectedOffers();
            highWater += m.arenaHighWaterBytes();
            fill += m.inboxFill();
        }
        return new LaneMetrics(-1, in, out, batches, idle, rejected, highWater, fill / lanes.size());
    }

    /**
     * Stops every lane.
     *
     * <p>Each lane is closed even if an earlier one refused to stop, because leaving the rest
     * running would leak a thread and an arena apiece for a failure that has already been reported.
     */
    @Override
    public void close() {
        RuntimeException first = null;
        for (Lane lane : lanes) {
            try {
                lane.close();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    @Override
    public String toString() {
        return "LaneGroup[" + lanes.size() + " lanes, " + assignment.length + " virtual partitions]";
    }
}
