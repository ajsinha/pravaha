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
package com.ash.messaging.pravaha.benchmarks;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.ThreadParams;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.observe.CoverageAgent;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.runtime.lane.Lane;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.lane.LaneGroup;

/**
 * Does adding lanes add throughput?
 *
 * <p>This is the contention benchmark P2-06 is accepted against, and its subject is the lane
 * <em>machinery</em> -- the ingest handoff, the batch loop, the arena and the per-lane counters --
 * not the operators running on it. The processor here deliberately does almost nothing, because a
 * processor that did real work would dominate the measurement and hide exactly what is being asked
 * about: whether eight lanes interfere with each other.
 *
 * <p><strong>This is not the Profile A gate figure and must never be quoted as one.</strong> The
 * gate (design section 5.2) is 1.2 M records per second per lane through a real pipeline: source
 * decode, predicate evaluation, projection, sink dispatch. What this measures is the floor those
 * numbers sit on. The figure that matters here is the <em>ratio</em> -- rows per second at N lanes
 * against N times the figure at one -- which is what the 90 % scaling clause is about.
 *
 * <p>One JMH thread per lane, each writing only into its own lane's inbox -- the routed arrangement
 * of design section 13.1, where producers hash before they write and no two ever touch the same cell.
 * Run it as {@code -t N -p lanes=N}.
 *
 * <p><strong>There is deliberately no barrier between the threads.</strong> An earlier version of
 * this benchmark released a round through a phaser and waited for every lane to drain it, which
 * measured the <em>slowest</em> thread each round: on a machine with other work on it, one preempted
 * thread stalled the whole round, and the result looked exactly like lanes contending with each
 * other. It reported 33 % scaling efficiency at four lanes and the cause was the harness. A
 * barrier-free producer loop measures what the question actually is, and because the inbox is
 * bounded, a producer's steady-state rate is its lane's drain rate -- backpressure couples them
 * without a barrier having to.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(
        value = 1,
        jvmArgsAppend = {"-XX:+UseZGC"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@State(Scope.Benchmark)
public class LaneScalingBenchmark {

    private static final int ROW_BYTES = 64;

    /** Must equal the JMH thread count: run with {@code -t N -p lanes=N}. */
    @Param({"1", "2", "4", "8"})
    public int lanes;

    @Param({"SPIN_THEN_YIELD"})
    public String waitStrategy;

    private LaneGroup group;

    /** One scratch row per producer thread, so no two threads share the source buffer either. */
    @State(Scope.Thread)
    public static class Producer {
        MemoryRegion scratch;

        @Setup(Level.Trial)
        public void setUp() {
            scratch = MemoryAccess.best().allocate(ROW_BYTES);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            scratch.close();
        }
    }

    /** PERF-1: a benchmark run under a coverage agent measures the agent; refused, by name. */
    @Setup(Level.Trial)
    public void declineUnderACoverageAgent() {
        CoverageAgent.refuseToMeasure("LaneScalingBenchmark");
    }

    @Setup(Level.Trial)
    public void setUp() {
        MemoryAccess access = MemoryAccess.best();
        LaneConfig config = LaneConfig.defaults()
                .withInbox(4096, ROW_BYTES)
                .withBatchSize(512)
                .withWaitStrategy(WaitStrategy.Kind.valueOf(waitStrategy))
                .withArena(1 << 20, 2)
                .withThreads("bench-lane", true);

        // Sum one field. Enough that the batch cannot be optimised away, little enough that the
        // measurement is of the lane rather than of the arithmetic.
        group = new LaneGroup(lanes, config, access, context -> (region, offsets, count) -> {
            long sum = 0;
            for (int i = 0; i < count; i++) {
                sum += region.getLong((int) offsets[i]);
            }
            return sum == Long.MIN_VALUE ? 0 : count;
        });
        group.start();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        group.close();
    }

    /**
     * One row, posted to this thread's lane and processed by it.
     *
     * <p>The reported ops/s <em>is</em> rows per second. Scaling efficiency at N lanes is that
     * figure over N times the one-lane figure.
     */
    @SuppressWarnings("ThreadPriorityCheck") // yielding is the wait strategy being measured or offered
    @Benchmark
    public void oneRow(Producer producer, ThreadParams threads) {
        Lane lane = group.lane(threads.getThreadIndex() % lanes);
        producer.scratch.putLong(0, threads.getThreadIndex());
        int attempts = 0;
        while (!lane.offer(producer.scratch, 0, ROW_BYTES)) {
            // Full inbox: the lane is the limit, which is the steady state this benchmark wants to
            // sit in. Yielding rather than spinning keeps an oversubscribed box measuring lanes
            // rather than the scheduler.
            if (++attempts < 64) {
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
    }
}
