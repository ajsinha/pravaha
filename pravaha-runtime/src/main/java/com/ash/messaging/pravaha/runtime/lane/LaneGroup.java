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

import org.jspecify.annotations.Nullable;

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
    private final @Nullable LaneExchange exchange;

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
        this(laneCount, virtualPartitions, config, access, factory, 1);
    }

    /**
     * A group whose lanes each have {@code inputs} inboxes.
     *
     * @param inputs one per query input: one for everything except a join, which has one per side
     */
    public LaneGroup(
            int laneCount,
            int virtualPartitions,
            LaneConfig config,
            MemoryAccess access,
            LaneProcessorFactory factory,
            int inputs) {
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

        // One lane owns every partition, so there is nothing to repartition and an exchange would
        // be N(N-1) = 0 rings of pure ceremony. Building it only when it can be used also keeps the
        // single-lane case -- the common one -- free of the memory an exchange costs.
        this.exchange = laneCount > 1
                ? new LaneExchange(laneCount, access, config.exchangeCells(), config.inboxCellBytes())
                : null;

        List<Lane> built = new ArrayList<>(laneCount);
        try {
            for (int i = 0; i < laneCount; i++) {
                built.add(new Lane(i, config, access, toIntArray(owned.get(i)), factory, exchange, inputs));
            }
        } catch (RuntimeException e) {
            // A half-built group would leak an arena and an inbox per lane already constructed.
            built.forEach(Lane::close);
            if (exchange != null) {
                exchange.close();
            }
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

    /**
     * Starts every lane on {@code runner} rather than on threads of their own (ADR-027).
     *
     * <p>A group's lanes may be spread across the runner's threads; they do not need to share one.
     * What they must not do is run concurrently *with themselves*, and a runner never steps one lane
     * from two threads.
     */
    public void startOn(LaneRunner runner) {
        lanes.forEach(lane -> lane.startOn(runner));
    }

    /** The exchange these lanes share, if there is more than one of them. */
    public java.util.Optional<LaneExchange> exchange() {
        return java.util.Optional.ofNullable(exchange);
    }

    /**
     * Waits for every lane to have nothing left to do, sharing one deadline between them.
     *
     * <p>The exchange is checked as well as the lanes, and checked <em>after</em> them: a lane can
     * be idle while rows it sent are still in flight to a peer, and calling that quiescent would let
     * a test assert on a result that has not finished arriving.
     */
    public boolean awaitQuiescent(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean lanesIdle = true;
            for (Lane lane : lanes) {
                if (!lane.awaitQuiescent(Duration.ofMillis(1))) {
                    lanesIdle = false;
                    break;
                }
            }
            if (lanesIdle && (exchange == null || exchange.inFlight() == 0)) {
                return true;
            }
            if (halted()) {
                // A lane that has stopped or failed will not make the group quiet (LIFE-067).
                return false;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
        }
        return false;
    }

    /** Whether a lane of this group has stopped or failed, so waiting for it to go quiet is over. */
    public boolean halted() {
        for (Lane lane : lanes) {
            if (lane.state() == Lane.State.STOPPED || lane.state() == Lane.State.FAILED) {
                return true;
            }
        }
        return false;
    }

    /** Rethrows the first lane failure, if any lane died. */
    public void checkHealth() {
        lanes.forEach(Lane::checkHealth);
    }

    /**
     * The first lane failure, without throwing it.
     *
     * <p>{@code checkHealth} asserts; this asks. Everything that wanted to *report* health rather
     * than fail on it had no way to, so nothing outside the CLI ever looked -- and a lane that died
     * left its query reporting RUNNING across every surface a server has.
     */
    public java.util.Optional<Throwable> failure() {
        return lanes.stream()
                .map(Lane::failure)
                .flatMap(java.util.Optional::stream)
                .findFirst();
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
        long exchanged = 0;
        LaneBackpressure.Snapshot blocked = LaneBackpressure.Snapshot.NONE;
        for (LaneMetrics m : metrics()) {
            in += m.rowsIn();
            out += m.rowsOut();
            batches += m.batches();
            idle += m.idleCycles();
            rejected += m.rejectedOffers();
            highWater += m.arenaHighWaterBytes();
            fill += m.inboxFill();
            exchanged += m.exchangedIn();
            blocked = blocked.plus(m.backpressure());
        }
        return new LaneMetrics(
                -1, in, out, batches, idle, rejected, highWater, fill / lanes.size(), exchanged, blocked);
    }

    /**
     * Every lane's backpressure as one figure: how often and how long a writer into this group had
     * no room, and whose writer it was.
     *
     * <p>The fraction is the sum of the lanes' blocked time over the longest lane's wall clock,
     * clamped at 1 -- so a query on four lanes with one of them blocked half the time reports about
     * 0.5 rather than 0.125. The question an operator is asking is "is any lane of this query the
     * limit", and an average over lanes answers a different one.
     */
    public LaneBackpressure.Snapshot backpressure() {
        LaneBackpressure.Snapshot total = LaneBackpressure.Snapshot.NONE;
        for (Lane lane : lanes) {
            total = total.plus(lane.backpressure().snapshot(lane.inboxDepth(), lane.inboxCells()));
        }
        return total;
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
        // After the lanes, never before: closing the exchange releases memory their threads may
        // still be reading from.
        if (exchange != null) {
            exchange.close();
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
