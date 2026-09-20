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
package com.ash.messaging.pravaha.it.qa.perf;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate P2, measured on the machine that exists.
 *
 * <p>Gate P2 asks for Profile A at <strong>1.2 M records per second per lane</strong> and
 * <strong>at least 90 % scaling efficiency from one lane to eight</strong>, on 16 physical
 * homogeneous cores at 3 GHz or better. There is no such machine and there is not going to be one,
 * so the owner's instruction on 2026-09-19 was to measure here and name the machine. That is what
 * this does.
 *
 * <p><strong>What this harness does not do is move the target.</strong> The criteria below are
 * design section 5.2's, unchanged. ADR-042 restated the <em>product requirement</em> down to about
 * a thousand rows a second and kept both figures on the page; it explicitly did not close P2, and
 * a harness that quietly measured against the smaller number would be the thing this project keeps
 * saying it will not do. So the target here is 1.2 M, and a result below it is printed as NOT
 * REACHED with the number.
 *
 * <p>Three measurements, and the difference between them is the point:
 *
 * <ol>
 *   <li>{@link #profileAThroughOneLane()} -- the plan the gate names, running on one real lane:
 *       inbox handoff, batch loop, arena, the interpreted pipeline, sink dispatch. The closest
 *       thing to the gate's own sentence that this codebase can be asked for.
 *   <li>{@link #profileAThroughTheRegistry()} -- the same query as SQL, registered, fed and served.
 *       What a customer's row actually costs, which is strictly more than the lane's number.
 *   <li>{@link #profileAScalingFromOneLaneToEight()} -- the same pipeline at 1, 2, 4 and 8 lanes.
 * </ol>
 *
 * <p><strong>It measures the interpreted pipeline, because that is what a registered query runs.
 * </strong> {@code AdaptiveStage} and {@code StageUpgradeService} are reachable from no production
 * caller -- finding C-5 records that -- so the roughly 10x that {@code ProfileABenchmark} measures
 * for generated code is not available to a query somebody registers today, and a gate figure that
 * assumed it would be a figure about code nothing runs.
 *
 * <p><strong>Not part of the default build.</strong> Named {@code *IT}, which the root POM's
 * surefire configuration excludes, because a throughput measurement that gates a pull request is a
 * flaky test. Run it on purpose:
 *
 * <pre>
 * ./mvnw -o -pl pravaha-it test -Dtest=ProfileAGateIT \
 *     -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Knobs: {@code pravaha.gate.p2.rows} (rows per timed pass), {@code .passes} (timed passes) and
 * {@code .warmups}.
 */
@Timeout(3600)
final class ProfileAGateIT {

    /** Design section 5.2, NFR-2a, Profile A. Not ADR-042's restated product requirement. */
    private static final double THROUGHPUT_TARGET = 1_200_000d;

    private static final String THROUGHPUT_SOURCE = "design section 5.2 NFR-2a, Profile A, per lane-core";

    /** Design section 5.2, NFR-2b, narrowed to eight lanes by the wave-3 gate pack. */
    private static final double SCALING_TARGET = 0.90d;

    private static final String SCALING_SOURCE = "design section 5.2 NFR-2b as gate P2 states it, 1 -> 8 lanes";

    private static final long ROWS = Long.getLong("pravaha.gate.p2.rows", 4_000_000L);
    private static final int PASSES = Integer.getInteger("pravaha.gate.p2.passes", 5);
    private static final int WARMUPS = Integer.getInteger("pravaha.gate.p2.warmups", 2);

    /** Rows per lane in the scaling measurement: smaller, because eight lanes run eight of them. */
    private static final long SCALING_ROWS = Long.getLong("pravaha.gate.p2.scaling.rows", 2_000_000L);

    private static final int SCALING_PASSES = Integer.getInteger("pravaha.gate.p2.scaling.passes", 3);

    private static final int[] LANE_COUNTS = {1, 2, 4, 8};

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final Duration DRAIN = Duration.ofMinutes(5);

    @Test
    void profileAThroughOneLane() {
        MemoryAccess access = MemoryAccess.best();
        try (ProfileARows rows = ProfileARows.encode(access)) {
            double[] samples = new double[PASSES];
            for (int pass = 0; pass < WARMUPS + PASSES; pass++) {
                double rate = oneLanePass(rows, access);
                if (pass >= WARMUPS) {
                    samples[pass - WARMUPS] = rate;
                }
            }
            GateReading reading = new GateReading(
                    "Gate P2, Profile A end to end on one lane -- inbox handoff, batch loop, arena, "
                            + "interpreted filter and project, sink dispatch",
                    "rows/s",
                    THROUGHPUT_TARGET,
                    THROUGHPUT_SOURCE,
                    samples);
            System.out.print(reading.report(MachineState.now()));
            System.out.printf(
                    "    detail  : %,d rows a pass, pool of %,d distinct rows over %,d KiB, %d of them"
                            + " passing the predicate (%.1f %% selectivity, design section 28.4 asks"
                            + " for 10 %%)%n",
                    ROWS,
                    ProfileARows.POOL,
                    rows.workingSetBytes() / 1024,
                    rows.passing(),
                    100.0 * rows.passing() / ProfileARows.POOL);

            // The harness measured something. Every pass asserted its own row count; this is the
            // statement that the measurement itself exists and is a rate rather than a zero.
            assertThat(reading.best())
                    .as("a pass that moved no rows is not a fast pass")
                    .isGreaterThan(0);
        }
    }

    @Test
    void profileAThroughTheRegistry() {
        MemoryAccess access = MemoryAccess.best();
        try (ProfileARows rows = ProfileARows.encode(access)) {
            double[] samples = new double[PASSES];
            for (int pass = 0; pass < WARMUPS + PASSES; pass++) {
                double rate = registryPass(rows);
                if (pass >= WARMUPS) {
                    samples[pass - WARMUPS] = rate;
                }
            }
            GateReading reading = new GateReading(
                    "Gate P2, Profile A through the product path -- SQL planned, query registered, "
                            + "rows accepted, results applied to a served view",
                    "rows/s",
                    THROUGHPUT_TARGET,
                    THROUGHPUT_SOURCE,
                    samples);
            System.out.print(reading.report(MachineState.now()));
            System.out.printf("    detail  : the gate's sentence is about a lane; this is the same query with the"
                    + " registry, the planner's plan and a served view around it, and it is the"
                    + " figure a deployment would see%n");
            assertThat(reading.best()).isGreaterThan(0);
        }
    }

    @Test
    void profileAScalingFromOneLaneToEight() throws Exception {
        MemoryAccess access = MemoryAccess.best();
        Map<Integer, double[]> byLaneCount = new LinkedHashMap<>();
        try (ProfileARows rows = ProfileARows.encode(access)) {
            for (int lanes : LANE_COUNTS) {
                byLaneCount.put(lanes, new double[SCALING_PASSES]);
                // One warm-up pass per lane count, discarded.
                scalingPass(rows, access, lanes);
            }
            // Passes on the outside and lane counts on the inside, which is the arrangement
            // OperatorMetricsOverheadIT arrived at for the same reason. Running every pass of one
            // lane count before starting the next makes a load spike a *fixed bias* rather than
            // noise: whichever lane count happened to run while the machine was busy loses, and
            // the ratio between them is then a ratio between two different machines. This harness
            // reported 131 % efficiency at eight lanes on a box at load 110 -- a number produced
            // entirely by a depressed one-lane baseline -- before the loops were turned inside
            // out. Interleaving cannot make a scaling figure trustworthy on a shared machine, and
            // nothing can; it removes the one bias that was systematic.
            for (int pass = 0; pass < SCALING_PASSES; pass++) {
                for (int lanes : LANE_COUNTS) {
                    byLaneCount.get(lanes)[pass] = scalingPass(rows, access, lanes);
                }
            }
        }

        double oneLane = best(byLaneCount.get(1));
        MachineState state = MachineState.now();
        System.out.printf("%n  Gate P2, Profile A scaling -- one query per lane, one producer thread each%n");
        System.out.printf(
                "    %-6s %-16s %-14s %-12s %s%n", "lanes", "rows/s (best)", "vs 1 lane", "efficiency", "samples");
        double[] efficiencies = new double[LANE_COUNTS.length];
        for (int i = 0; i < LANE_COUNTS.length; i++) {
            int lanes = LANE_COUNTS[i];
            double rate = best(byLaneCount.get(lanes));
            double ratio = rate / oneLane;
            efficiencies[i] = ratio / lanes;
            System.out.printf(
                    "    %-6d %,-16.0f %-14s %-12s %s%n",
                    lanes,
                    rate,
                    String.format("%.2fx", ratio),
                    String.format("%.0f %%", efficiencies[i] * 100),
                    samplesOf(byLaneCount.get(lanes)));
        }

        GateReading reading = new GateReading(
                "Gate P2, scaling efficiency at eight lanes against one",
                "% of linear",
                SCALING_TARGET * 100,
                SCALING_SOURCE,
                new double[] {efficiencies[LANE_COUNTS.length - 1] * 100});
        System.out.print(reading.report(state));
        System.out.printf("    CAVEAT  : this machine has 12 physical cores of two different designs (Zen 5 and%n"
                + "              Zen 5c), SMT2, and a frequency envelope shared across them. The%n"
                + "              harness needs two threads per lane -- a producer and the lane --%n"
                + "              so eight lanes is sixteen busy threads on twelve physical cores%n"
                + "              before anything else on the machine is counted. A scaling ratio%n"
                + "              measured here is a statement about the power envelope at least as%n"
                + "              much as about lane contention, and gate P2 says so. The number%n"
                + "              above is recorded, not defended.%n");
        if (state.loaded()) {
            System.out.printf(
                    "    WITHHELD: the machine was at load %.1f on %d processors while this ran. A ratio%n"
                            + "              between two arms measured on a machine that busy is not a scaling%n"
                            + "              figure in either direction -- a depressed one-lane baseline can push%n"
                            + "              it above 100 %% as easily as contention can push it below. The%n"
                            + "              verdict printed above must not be read, in either direction, and the%n"
                            + "              run should be repeated on a quiet machine before anything is recorded.%n",
                    state.loadAverage(), state.logicalProcessors());
        }

        assertThat(oneLane)
                .as("the one-lane arm must have moved rows for any ratio to mean anything")
                .isGreaterThan(0);
    }

    // ---------------------------------------------------------------- passes

    /** One timed pass of {@link #ROWS} rows through one lane, returning rows a second. */
    private static double oneLanePass(ProfileARows rows, MemoryAccess access) {
        CountingRowOutput sink = new CountingRowOutput(ProfileARows.outputSchema());
        try (QueryExecution execution =
                QueryExecution.start(ProfileARows.plan(), 1, laneConfig(rows), access, () -> sink, Map.of(), null)) {
            BinaryRowView view = new BinaryRowView(RowLayout.of(ProfileARows.schema()));
            long began = System.nanoTime();
            feed(execution, rows, view, ROWS);
            if (!execution.awaitQuiescent(DRAIN)) {
                throw new AssertionError("the lane did not drain within " + DRAIN + "; the pass is not a measurement");
            }
            long took = System.nanoTime() - began;
            requireWorkHappened(sink.committed(), ROWS, rows);
            return ROWS / (took / 1e9);
        }
    }

    /** One timed pass through the registry: planner, query, lane, served view. */
    private static double registryPass(ProfileARows rows) {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, ProfileARows.schema())) {
            RegisteredQuery query = registry.register("p2_profile_a", ProfileARows.sql(), List.of(0), DANA);
            BinaryRowView view = new BinaryRowView(RowLayout.of(ProfileARows.schema()));
            long began = System.nanoTime();
            for (long i = 0; i < ROWS; i++) {
                int index = (int) (i % ProfileARows.POOL);
                view.wrap(rows.region(), (int) rows.offset(index));
                while (!query.accept(view)) {
                    Thread.onSpinWait();
                }
            }
            if (!query.awaitApplied(DRAIN)) {
                throw new AssertionError("the query did not drain within " + DRAIN);
            }
            long took = System.nanoTime() - began;

            // Outside the clock: one watermark, which is what makes what the view holds readable.
            query.advanceWatermark(Long.MAX_VALUE / 4);
            if (query.rowsIn() != ROWS) {
                throw new AssertionError("the registry took " + query.rowsIn() + " rows of " + ROWS
                        + "; the pass fed less than it timed");
            }
            if (query.view().size() == 0) {
                throw new AssertionError("the served view is empty after " + ROWS
                        + " rows, so this pass timed a query that answered nothing");
            }
            return ROWS / (took / 1e9);
        }
    }

    /**
     * One timed pass at {@code lanes} lanes: one single-lane query per lane, one producer each.
     *
     * <p>Separate executions rather than one execution with several lanes, because {@code
     * QueryExecution.accept} posts to lane zero and a multi-lane execution is fed by partitioned
     * pumps. One query per lane is also the arrangement the product has: {@code QueryRegistry}
     * gives a query its own lane unless multiplexing is turned on.
     *
     * @return aggregate rows a second across all lanes
     */
    private static double scalingPass(ProfileARows rows, MemoryAccess access, int lanes) throws Exception {
        List<QueryExecution> executions = new ArrayList<>(lanes);
        List<CountingRowOutput> sinks = new ArrayList<>(lanes);
        List<Thread> producers = new ArrayList<>(lanes);
        CountDownLatch ready = new CountDownLatch(lanes);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < lanes; i++) {
                CountingRowOutput sink = new CountingRowOutput(ProfileARows.outputSchema());
                sinks.add(sink);
                executions.add(QueryExecution.start(
                        ProfileARows.plan(), 1, laneConfig(rows), access, () -> sink, Map.of(), null));
            }
            for (int i = 0; i < lanes; i++) {
                QueryExecution execution = executions.get(i);
                Thread producer = new Thread(
                        () -> {
                            BinaryRowView view = new BinaryRowView(RowLayout.of(ProfileARows.schema()));
                            ready.countDown();
                            try {
                                go.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            feed(execution, rows, view, SCALING_ROWS);
                        },
                        "gate-p2-producer-" + i);
                producer.setDaemon(true);
                producers.add(producer);
                producer.start();
            }
            if (!ready.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("producers did not start");
            }
            long began = System.nanoTime();
            go.countDown();
            for (Thread producer : producers) {
                producer.join(DRAIN.toMillis());
            }
            for (QueryExecution execution : executions) {
                if (!execution.awaitQuiescent(DRAIN)) {
                    throw new AssertionError("a lane did not drain within " + DRAIN);
                }
            }
            long took = System.nanoTime() - began;
            for (CountingRowOutput sink : sinks) {
                requireWorkHappened(sink.committed(), SCALING_ROWS, rows);
            }
            return lanes * SCALING_ROWS / (took / 1e9);
        } finally {
            for (QueryExecution execution : executions) {
                execution.close();
            }
        }
    }

    // ---------------------------------------------------------------- shared

    private static void feed(QueryExecution execution, ProfileARows rows, BinaryRowView view, long count) {
        for (long i = 0; i < count; i++) {
            int index = (int) (i % ProfileARows.POOL);
            view.wrap(rows.region(), (int) rows.offset(index));
            while (!execution.accept(view)) {
                // A full inbox is backpressure, which is the steady state this measurement wants
                // to sit in: the producer's rate becomes the lane's drain rate.
                Thread.onSpinWait();
            }
        }
    }

    /**
     * The assertion that stops this being a benchmark of nothing.
     *
     * <p>A throughput harness whose sink is never called reports a superb figure for an empty
     * stream, an optimised-away loop or a query that never started. The expected count is exact
     * rather than approximate: the pool is fixed, its selectivity is counted at encode time, and
     * the row count is a whole number of pools.
     */
    private static void requireWorkHappened(long committed, long rowsFed, ProfileARows rows) {
        long expected = rows.emittedBy(rowsFed);
        if (committed != expected) {
            throw new AssertionError("the pipeline emitted " + committed + " rows and should have emitted " + expected
                    + " (" + rowsFed + " rows fed, " + rows.passing() + " of every " + ProfileARows.POOL
                    + " passing the predicate); the pass did not do the work it was timed for");
        }
    }

    private static LaneConfig laneConfig(ProfileARows rows) {
        int cell = Math.max(256, Integer.highestOneBit(rows.widestRow() - 1) * 2);
        return LaneConfig.defaults()
                .withInbox(4096, cell)
                .withBatchSize(LaneConfig.DEFAULT_BATCH_SIZE)
                .withWaitStrategy(WaitStrategy.Kind.SPIN_THEN_YIELD)
                .withArena(1 << 20, 4)
                .withThreads("gate-p2-lane", true);
    }

    private static double best(double[] samples) {
        double best = 0;
        for (double sample : samples) {
            best = Math.max(best, sample);
        }
        return best;
    }

    private static String samplesOf(double[] samples) {
        StringBuilder out = new StringBuilder();
        for (double sample : samples) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(String.format("%,.0f", sample));
        }
        return out.toString();
    }
}
