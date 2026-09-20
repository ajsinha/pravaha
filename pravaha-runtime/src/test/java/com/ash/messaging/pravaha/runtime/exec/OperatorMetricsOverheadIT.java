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
package com.ash.messaging.pravaha.runtime.exec;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6's cost, measured rather than asserted.
 *
 * <p>The question {@code pravaha.metrics.operators} exists to answer is how much the wrappers cost
 * on the row path, and the only honest way to set a default is to measure it on the machine the
 * project has. That is this development machine, which the owner named the reference machine on
 * 2026-09-19; the numbers it prints belong to it and to no other hardware, and
 * {@code docs/OPERATIONS.md} records them with the machine named.
 *
 * <p><strong>Not part of the default build.</strong> Named {@code *IT}, which the root POM's
 * surefire configuration excludes, because it is a throughput measurement and a throughput
 * measurement that gates a pull request is a flaky test. Run it on purpose:
 *
 * <pre>
 * ./mvnw -o -pl pravaha-runtime -am test -Dtest=OperatorMetricsOverheadIT \
 *     -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Knobs: {@code pravaha.operatormetrics.rows} (default 20,000,000 per timed pass) and
 * {@code .passes} (default 5 measured passes after 3 warm-up passes; the fastest of each arm is
 * reported, because the slowest passes are the machine's other work and not the engine's).
 *
 * <p>It measures the interpreted pipeline alone -- no lane, no inbox, no ingest -- on purpose. A
 * whole-query measurement would put the wrappers' cost next to the cost of moving rows into an
 * inbox and committing a view, and the answer would be "a few percent of something much larger",
 * which is true and useless for deciding whether the wrappers themselves are affordable. This is
 * the pessimistic number: the narrowest plan and the tightest loop the engine has.
 */
class OperatorMetricsOverheadIT {

    private static final long ROWS = Long.getLong("pravaha.operatormetrics.rows", 20_000_000L);
    private static final int PASSES = Integer.getInteger("pravaha.operatormetrics.passes", 5);
    private static final int WARMUPS = 3;

    @AfterEach
    void stopMeasuring() {
        InterpretedPipeline.measureOperators(false);
    }

    private static StreamSchema orders() {
        return StreamSchema.builder("orders")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .build();
    }

    private static StreamSchema justId() {
        return StreamSchema.builder("orders").field("id", Types.int64()).build();
    }

    /** {@code SELECT id FROM orders WHERE amount > 0}: three operators, every row passing. */
    private static PhysicalOperator plan() {
        return new ProjectOperator(
                new FilterOperator(
                        ScanOperator.of("orders", orders()),
                        new Predicate.CompareLong(1, "amount", Predicate.Op.GT, 0)),
                justId(),
                List.of(0));
    }

    @Test
    void measureTheCostOfPerOperatorCounters() {
        double off = bestRowsPerSecond(false);
        double on = bestRowsPerSecond(true);
        double cost = 1.0 - on / off;

        System.out.printf(
                "%n  pravaha.metrics.operators, %,d rows a pass, best of %d:%n"
                        + "    off : %,.0f rows/s%n"
                        + "    on  : %,.0f rows/s%n"
                        + "    cost: %.1f %%%n%n",
                ROWS, PASSES, off, on, cost * 100);

        // Not a gate on the number -- the point of the measurement is the number, and a threshold
        // here would turn a result into a flaky test on a loaded machine. What is asserted is that
        // both arms ran and produced a rate at all, so a measurement of zero cannot be read as
        // "free".
        assertThat(off).isGreaterThan(0);
        assertThat(on).isGreaterThan(0);
    }

    private static double bestRowsPerSecond(boolean measuring) {
        InterpretedPipeline.measureOperators(measuring);
        double best = 0;
        for (int pass = 0; pass < WARMUPS + PASSES; pass++) {
            double rate = onePass();
            if (pass >= WARMUPS) {
                best = Math.max(best, rate);
            }
        }
        return best;
    }

    private static double onePass() {
        long[] emitted = new long[1];
        RowOutput sink = () -> new CountingWriter(justId(), emitted);
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan(), sink)) {
            RowLayout layout = RowLayout.of(orders());
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            // One row, written once and fed a great many times: this measures the pipeline, and
            // re-encoding the row every iteration would measure the writer instead.
            long handle = arena.allocate(layout.rowSize(64));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            writer.setLong(0, 1L).setLong(1, 7L);
            writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
            BinaryRowView row = new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));

            // In batches of the lane's default size, ending and rewinding each one exactly as a
            // lane does -- which is also where the counters are published, so the publish is
            // inside the measurement rather than outside it.
            int batch = com.ash.messaging.pravaha.runtime.lane.LaneConfig.DEFAULT_BATCH_SIZE;
            long began = System.nanoTime();
            for (long i = 0; i < ROWS; i++) {
                pipeline.accept("orders", row);
                if ((i + 1) % batch == 0) {
                    pipeline.endOfBatch();
                    pipeline.resetArena();
                }
            }
            pipeline.endOfBatch();
            pipeline.resetArena();
            long took = System.nanoTime() - began;
            if (emitted[0] != ROWS) {
                throw new AssertionError("the pass lost rows: " + emitted[0] + " of " + ROWS);
            }
            emitted[0] = 0;
            return ROWS / (took / 1e9);
        }
    }

    /** Counts commits and nothing else, so the sink is not what is being timed. */
    private record CountingWriter(StreamSchema schema, long[] emitted) implements RowWriter {

        @Override
        public RowWriter setNull(int ordinal) {
            return this;
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            return this;
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            return this;
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            return this;
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            return this;
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            return this;
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            return this;
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            return this;
        }

        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            return this;
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            return this;
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            return this;
        }

        @Override
        public RowWriter weight(long weight) {
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long nanos) {
            return this;
        }

        @Override
        public RowWriter sequence(long sequence) {
            return this;
        }

        @Override
        public int commit() {
            emitted[0]++;
            return 0;
        }

        @Override
        public void abort() {}
    }
}
