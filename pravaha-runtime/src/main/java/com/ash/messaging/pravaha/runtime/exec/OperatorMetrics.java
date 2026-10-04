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

import java.util.function.LongSupplier;

import com.ash.messaging.pravaha.api.data.RowView;

/**
 * One operator's counters, written by the lane thread that owns it and read by anybody.
 *
 * <p>Until this existed the engine counted rows for a <em>query</em>, so the plan a console drew
 * was a picture with no numbers on it: an operator asking "which of these six boxes is the one
 * costing me" had the query's total and nothing else, and {@code GET
 * /api/v1/queries/{name}/plan} said so in a sentence rather than answering.
 *
 * <p><strong>Plain longs, published once per batch.</strong> The same arrangement {@code Lane}
 * uses and for the same reason: {@code rowsIn} is incremented once per row per operator, and a
 * volatile store at that rate on six boxes is not a rounding error. The lane-confined fields are
 * summed into the volatile ones by {@link #publish()}, which the pipeline calls at the end of every
 * batch -- so a reader sees a coherent set of numbers from one batch boundary rather than six
 * fields from six instants.
 *
 * <p><strong>Self time is sampled, and the sample is regular.</strong> One row in every {@link
 * OperatorClock#SAMPLE_EVERY} that enters the pipeline is timed at every operator on its path,
 * children subtracted, so {@link Snapshot#selfNanos()} is that operator's own work and not its subtree's.
 * A regular sample, not a random one: a row whose position is a multiple of 1,024 is not a random
 * row, and a workload whose cost happens to beat in step with that period would be measured wrong.
 * What it buys is that the untimed 1,023 rows pay one field read and a branch, which is the only
 * reason a per-operator timer is affordable at all. {@link Snapshot#sampledRows()} is published beside the
 * time so the ratio can be checked rather than assumed.
 *
 * <p><strong>Nothing here exists when measurement is off.</strong> {@link
 * InterpretedPipeline#measureOperators} decides at compile time whether the stages are wrapped at
 * all; with it off there is no wrapper, no counter and no branch (see {@code
 * pravaha.metrics.operators}).
 */
public final class OperatorMetrics {

    private final String nodeId;
    private final String operator;
    private final String detail;

    // Lane-confined: touched only by the thread pumping this pipeline.
    private long localRowsIn;
    private long localRowsOut;
    private long localSelfNanos;
    private long localSampledRows;

    // Published by publish(), read by whoever asks.
    private volatile long rowsIn;
    private volatile long rowsOut;
    private volatile long selfNanos;
    private volatile long sampledRows;

    /**
     * The watermark this operator has been advanced to, or {@link Long#MIN_VALUE} before the first
     * advance. Written on the lane thread, once per advance -- per tick, not per row.
     */
    private volatile long watermarkNanos = Long.MIN_VALUE;

    /**
     * Where this operator's state bytes come from, or null when it holds no state of its own.
     *
     * <p>Read at snapshot time from another thread, which is safe for exactly the reason {@code
     * QueryExecution.stateUsage} already is: every supplier here reads a counter the operator
     * maintains, never a walk of the state itself.
     */
    private volatile LongSupplier stateBytes;

    OperatorMetrics(String nodeId, String operator, String detail) {
        this.nodeId = nodeId;
        this.operator = operator;
        this.detail = detail;
    }

    /** Says where this operator's state bytes are read from. Called once, while compiling. */
    void holdsStateIn(LongSupplier bytes) {
        this.stateBytes = bytes;
    }

    /** Records the watermark this operator has been advanced to. */
    void reachedWatermark(long nanos) {
        if (nanos > watermarkNanos) {
            watermarkNanos = nanos;
        }
    }

    /** Copies the lane-confined counters into the fields other threads read. Once per batch. */
    void publish() {
        rowsIn = localRowsIn;
        rowsOut = localRowsOut;
        selfNanos = localSelfNanos;
        sampledRows = localSampledRows;
    }

    /**
     * Wraps the processor that consumes this operator's input, counting the rows that reach it.
     *
     * @param entry true for a scan, the point at which a row enters the pipeline and the sampling
     *     decision for that row is taken
     */
    RowProcessor entering(RowProcessor self, OperatorClock clock, boolean entry) {
        if (entry) {
            return row -> {
                localRowsIn++;
                clock.beginRow();
                try {
                    time(self, row, clock);
                } finally {
                    clock.endRow();
                }
            };
        }
        return row -> {
            localRowsIn++;
            time(self, row, clock);
        };
    }

    /** Wraps this operator's downstream, counting the rows it emits. */
    RowProcessor leaving(RowProcessor downstream) {
        return row -> {
            localRowsOut++;
            downstream.process(row);
        };
    }

    /**
     * Runs the stage, timing it when this row is one of the sampled ones.
     *
     * <p>The untimed path is a field read and a branch. The timed one takes the wall clock either
     * side, subtracts whatever nested operators charged, and charges its own inclusive time
     * upwards -- which is what makes the numbers add up to the pipeline rather than counting the
     * same nanoseconds once per level.
     */
    private void time(RowProcessor self, RowView row, OperatorClock clock) {
        if (!clock.sampling()) {
            self.process(row);
            return;
        }
        int slot = clock.push();
        long began = System.nanoTime();
        try {
            self.process(row);
        } finally {
            long took = System.nanoTime() - began;
            localSelfNanos += took - clock.pop(slot);
            localSampledRows++;
            clock.charge(took);
        }
    }

    /** This operator's numbers now. */
    public Snapshot snapshot() {
        LongSupplier bytes = stateBytes;
        long watermark = watermarkNanos;
        return new Snapshot(
                nodeId,
                operator,
                detail,
                rowsIn,
                rowsOut,
                bytes == null ? null : bytes.getAsLong(),
                watermark == Long.MIN_VALUE ? null : watermark,
                selfNanos,
                sampledRows);
    }

    /**
     * What one operator has done.
     *
     * @param nodeId the plan node it belongs to -- {@code n0} is the root, as everywhere else
     * @param operator its kind: {@code Scan}, {@code Filter}, {@code WindowedAggregate}
     * @param detail the engine's own label for it, as the text plan prints it
     * @param rowsIn rows handed to it. A join counts both sides here
     * @param rowsOut rows it emitted. Against {@code rowsIn} this is the operator's selectivity,
     *     which for a filter is the number that says whether it is worth pushing down
     * @param stateBytes bytes its state store holds, or null where the operator keeps no state of
     *     its own or keeps it on the heap, where there is no byte count to report
     * @param watermarkNanos the watermark it has been advanced to, or null before the first advance
     * @param selfNanos its own work on the sampled rows, children's time subtracted
     * @param sampledRows how many rows that time covers. {@code selfNanos / sampledRows} is the
     *     per-row cost; {@code sampledRows} against {@code rowsIn} is the sampling rate actually
     *     achieved
     */
    public record Snapshot(
            String nodeId,
            String operator,
            String detail,
            long rowsIn,
            long rowsOut,
            Long stateBytes,
            Long watermarkNanos,
            long selfNanos,
            long sampledRows) {

        /** Two lanes' copies of the same operator, added. */
        public Snapshot plus(Snapshot other) {
            return new Snapshot(
                    nodeId,
                    operator,
                    detail,
                    rowsIn + other.rowsIn,
                    rowsOut + other.rowsOut,
                    stateBytes == null && other.stateBytes == null
                            ? null
                            : orZero(stateBytes) + orZero(other.stateBytes),
                    // The lowest, because a query's watermark is the lowest its lanes have reached:
                    // taking the highest would say a window had closed everywhere when one lane
                    // had not seen it.
                    watermarkNanos == null || other.watermarkNanos == null
                            ? null
                            : Math.min(watermarkNanos, other.watermarkNanos),
                    selfNanos + other.selfNanos,
                    sampledRows + other.sampledRows);
        }

        private static long orZero(Long value) {
            return value == null ? 0 : value;
        }

        /** Nanoseconds of this operator's own work per row, or 0 before anything was sampled. */
        public double nanosPerRow() {
            return sampledRows == 0 ? 0 : (double) selfNanos / sampledRows;
        }
    }
}
