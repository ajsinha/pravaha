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
package com.ash.messaging.pravaha.it.qa.state;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.ingest.PartitionedIngestPump;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8-2 and W8-3 -- a checkpoint taken while rows are flowing has to name one point in the stream.
 *
 * <p>Every existing test in this area quiesces the query first, feeds nothing, takes the checkpoint
 * and restores it. That exercises the serialisation and nothing else: with no producer running there
 * is no difference between a cut and a lower bound, and no difference between reading a source's
 * offset before the snapshot or after it. The two cases here keep the sources running across the
 * checkpoint, which is the only condition under which the word "aligned" means anything.
 *
 * <p><strong>What a consistent cut has to satisfy.</strong> A checkpoint records operator state and
 * source offsets. Restoring it means loading the state and seeking each source to its offset, so the
 * rows the state accounts for and the rows the offset excludes must be the same rows. One row of
 * disagreement in one direction is a row counted twice; one in the other is a row counted never.
 * Both are silent -- the query reports RUNNING over either.
 */
@Timeout(300)
class AlignedCheckpointBarrierTest extends StateTestSupport {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    // -----------------------------------------------------------------------------------------
    // W8-1: one lane. The state and the offset must name the same row.
    // -----------------------------------------------------------------------------------------

    @Test
    void aCheckpointTakenMidStreamAccountsForExactlyTheRowsItsOffsetExcludes() throws Exception {
        int total = 40_000;

        // The truth: every row, once, no checkpoint anywhere.
        long correct;
        try (RawExecution control = rawWindowed()) {
            IngestPump pump =
                    control.execution.pumpInto(0, new CountingTxnReader(0, total), BackpressurePolicy.defaults());
            pumpToEnd(pump, control.execution, total);
            assertThat(control.execution.awaitQuiescent(PATIENCE)).isTrue();
            control.advanceWatermark(11_000_000_000L);
            correct = sum(control.emitted);
            assertThat(correct).isEqualTo((long) total);
        }

        // The run that is checkpointed, with the source still going when the checkpoint is taken.
        Checkpoint taken;
        RawExecution a = rawWindowed();
        AtomicBoolean stopPumping = new AtomicBoolean();
        IngestPump pumpA = a.execution.pumpInto(0, new CountingTxnReader(0, total), BackpressurePolicy.defaults());
        Thread feeding = Thread.ofPlatform().daemon().start(() -> {
            while (!stopPumping.get() && pumpA.rowsPumped() < total) {
                pumpA.pumpOnce(64);
            }
        });
        try {
            awaitAtLeast(pumpA, total / 4);
            // Mid-flight. Nothing is quiesced, nothing is paused: the feeding thread above is
            // inside pumpOnce for most of this call's duration.
            taken = a.execution.checkpoint(1, Duration.ofSeconds(20));
        } finally {
            stopPumping.set(true);
            feeding.join(PATIENCE.toMillis());
        }
        int resumeFrom = Integer.parseInt(taken.offsets().get("partition-0"));
        assertThat(resumeFrom)
                .as("the checkpoint has to have been taken while the source was genuinely mid-stream")
                .isBetween(1, total - 1);
        a.abort();

        // Restore, seek the source to the recorded offset -- which is what QueryRegistry.restoreFrom
        // and PluginSourceFeeds.open do between them for a real node -- and finish the stream.
        try (RawExecution b = rawWindowed()) {
            b.execution.restore(taken, PATIENCE);
            IngestPump pumpB =
                    b.execution.pumpInto(0, new CountingTxnReader(resumeFrom, total), BackpressurePolicy.defaults());
            pumpToEnd(pumpB, b.execution, total - resumeFrom);
            assertThat(b.execution.awaitQuiescent(PATIENCE)).isTrue();
            b.advanceWatermark(11_000_000_000L);
            b.execution.checkHealth();

            assertThat(sum(b.emitted))
                    .as(
                            "restored state covering rows [0,%d) plus a replay of [%d,%d) has to come to %d "
                                    + "exactly. Less means the offset ran ahead of the snapshot and the rows "
                                    + "between them were never counted; more means the snapshot ran ahead of the "
                                    + "offset and they were counted twice",
                            resumeFrom, resumeFrom, total, total)
                    .isEqualTo(correct);
        }
    }

    // -----------------------------------------------------------------------------------------
    // W8-3: several lanes. One cut across all of them, and every source's offset in it.
    // -----------------------------------------------------------------------------------------

    @Test
    void aMultiLaneCheckpointRecordsEverySourceAndCutsThemAtOnePoint() throws Exception {
        int total = 20_000;

        // A join with only its left side fed. Every txn row is unique and none of them matches
        // anything yet, so each one is held in the join state of whichever lane owns its key --
        // which makes "how many rows does this checkpoint's state account for" something the
        // restored query will say out loud, by emitting exactly one row per held row once the
        // right side arrives.
        RawJoin a = rawJoin2();
        AtomicBoolean stopPumping = new AtomicBoolean();
        PartitionedIngestPump pumpA =
                a.execution.pumpPartitionedInto("txn", new UniqueTxnReader(0, total), BackpressurePolicy.defaults());
        Thread feeding = Thread.ofPlatform().daemon().start(() -> {
            while (!stopPumping.get() && pumpA.rowsPumped() < total) {
                pumpA.pumpOnce(64);
            }
        });
        Checkpoint taken;
        try {
            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (pumpA.rowsPumped() < total / 4) {
                a.execution.checkHealth();
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("the shuffling pump delivered nothing");
                }
                Thread.onSpinWait();
            }
            taken = a.execution.checkpoint(1, Duration.ofSeconds(20));
        } finally {
            stopPumping.set(true);
            feeding.join(PATIENCE.toMillis());
        }

        assertThat(taken.operatorState()).containsKeys("lane-0", "lane-1");
        assertThat(taken.offsets())
                .as("a shuffling pump's offset was left out of every checkpoint it appeared in, so a "
                        + "multi-lane query restored its state and then had nothing to rewind its source with")
                .containsKey("shuffled-partition-0");
        int held = Integer.parseInt(taken.offsets().get("shuffled-partition-0"));
        assertThat(held).isBetween(1, total - 1);
        a.abort();

        // Restore, then hand the right side over. Every txn row the cut covered matches exactly one
        // lkp row and emits exactly once, so the output is a direct reading of what the two lanes'
        // snapshots between them were holding.
        try (RawJoin b = rawJoin2()) {
            b.execution.restore(taken, PATIENCE);
            PartitionedIngestPump lkp =
                    b.execution.pumpPartitionedInto("lkp", new LkpReader(total), BackpressurePolicy.defaults());
            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (lkp.rowsPumped() < total) {
                lkp.pumpOnce(64);
                b.execution.checkHealth();
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("the lkp side did not finish");
                }
            }
            assertThat(b.execution.awaitQuiescent(PATIENCE)).isTrue();
            b.execution.checkHealth();

            List<Long> amounts;
            synchronized (b.emitted) {
                amounts = b.emitted.stream().map(row -> row.asLong(1)).sorted().toList();
            }
            assertThat(amounts)
                    .as(
                            "the cut says %d rows had been read from the source; the two lanes' snapshots have "
                                    + "to hold those %d rows and no others, or the offset and the state describe "
                                    + "different moments and no restore from this checkpoint is correct",
                            held, held)
                    .hasSize(held);
            for (int i = 0; i < amounts.size(); i++) {
                assertThat(amounts.get(i)).as("row %d of the cut", i).isEqualTo((long) i);
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private static long sum(List<CapturingRowWriter.Captured> emitted) {
        synchronized (emitted) {
            return emitted.stream()
                    .filter(row -> "u1".equals(row.asString(0)))
                    .mapToLong(row -> row.asLong(1))
                    .sum();
        }
    }

    /**
     * Pumps until the reader has given up every row it holds.
     *
     * <p>Counted rather than waited out: {@code pumpOnce} returns zero both for "the source has
     * nothing" and for "this pump is paused because the lane is behind", and under a source this
     * dense the second happens constantly. A loop that stopped at a run of zeros would stop at a
     * backpressure pause and call it the end of the stream.
     */
    private static void pumpToEnd(IngestPump pump, QueryExecution execution, long rows) {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (pump.rowsPumped() < rows) {
            pump.pumpOnce(64);
            execution.checkHealth();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the source stalled at " + pump.rowsPumped() + " of " + rows + " rows");
            }
        }
    }

    private static void awaitAtLeast(IngestPump pump, long rows) {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (pump.rowsPumped() < rows) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the pump delivered " + pump.rowsPumped() + " rows, wanted " + rows);
            }
            Thread.onSpinWait();
        }
    }

    /** A two-lane join execution: {@code txn} left, {@code lkp} right, both fed by shuffling pumps. */
    private static RawJoin rawJoin2() {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(TXN_T, LKP).plan(JOIN_SQL));
        List<CapturingRowWriter.Captured> emitted = new ArrayList<>();
        QueryExecution execution = QueryExecution.start(
                plan,
                2,
                laneConfig(),
                MemoryAccess.best(),
                () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {
                    synchronized (emitted) {
                        emitted.add(row);
                    }
                }));
        return new RawJoin(execution, emitted);
    }

    private record RawJoin(QueryExecution execution, List<CapturingRowWriter.Captured> emitted)
            implements AutoCloseable {

        void abort() {
            execution.abort();
        }

        @Override
        public void close() {
            execution.close();
        }
    }

    /** {@code (u1, 1, 0)} rows, so a single window's SUM is the row count. Resumable by index. */
    private static final class CountingTxnReader implements PartitionReader {
        private int cursor;
        private final int end;

        CountingTxnReader(int from, int end) {
            this.cursor = from;
            this.end = end;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int n = 0;
            while (n < maxRecords && cursor < end) {
                RowWriter writer = sink.beginRow();
                writer.setString(0, "u1").setLong(1, 1L).setLong(2, 0L);
                writer.weight(1L).eventTimestampNanos(0L).sequence(cursor).commit();
                cursor++;
                n++;
            }
            return n;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset(Integer.toString(cursor));
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }

    /** {@code (u<i>, i, 0)}: a distinct key and a distinct amount per row, so the cut is countable. */
    private static final class UniqueTxnReader implements PartitionReader {
        private int cursor;
        private final int end;

        UniqueTxnReader(int from, int end) {
            this.cursor = from;
            this.end = end;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int n = 0;
            while (n < maxRecords && cursor < end) {
                RowWriter writer = sink.beginRow();
                writer.setString(0, "u" + cursor).setLong(1, cursor).setLong(2, 0L);
                writer.weight(1L).eventTimestampNanos(0L).sequence(cursor).commit();
                cursor++;
                n++;
            }
            return n;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset(Integer.toString(cursor));
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }

    /** One {@code lkp} row per key the txn side can produce. */
    private static final class LkpReader implements PartitionReader {
        private int cursor;
        private final int end;

        LkpReader(int end) {
            this.end = end;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int n = 0;
            while (n < maxRecords && cursor < end) {
                RowWriter writer = sink.beginRow();
                writer.setString(0, "u" + cursor).setString(1, "gold");
                writer.weight(1L).eventTimestampNanos(0L).sequence(cursor).commit();
                cursor++;
                n++;
            }
            return n;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset(Integer.toString(cursor));
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }
}
