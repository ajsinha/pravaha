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
package com.ash.messaging.pravaha.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.plugin.filesystem.CollectingWriter;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSinkPlugin;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterQueue;
import com.ash.messaging.pravaha.runtime.dlq.FileDeadLetterQueue;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.sql.plan.SourcePushdown;

/**
 * Plans a query and runs it over delimited files.
 *
 * <p>Shared by the CLI's {@code run} command and its tests, and it is what makes the vertical slice
 * demonstrable in one call rather than a page of wiring.
 *
 * <p><strong>It runs on the lane runtime</strong>, not beside it. The pipeline used to be driven
 * from the calling thread, which worked and meant the shipped command exercised a different
 * execution path from the one the design is about -- so a regression in the lanes could not be
 * noticed by running the product.
 *
 * <p>One lane by default, and that is a choice rather than a limitation. A file is one partition and
 * output order is what {@code QUICKSTART.md} documents; more lanes would interleave results by
 * thread scheduling and make the documented output true only most of the time. The lane count is a
 * parameter of {@link #run} and is <em>not</em> a command-line flag: this javadoc used to offer
 * {@code --lanes}, which {@code RunCommand} has never parsed (P-7).
 */
public final class QueryRunner {

    /**
     * What a run produced.
     *
     * @param rowsRejected records the source could not decode, which went to the dead-letter queue.
     *     Always zero without {@code --dlq}, because without one a bad record still fails the run
     * @param deadLetterFailures entries the dead-letter queue could not write. Non-zero means the
     *     run's own record of what it discarded is incomplete, which is worth more attention than
     *     the discards themselves
     */
    public record Result(
            long rowsRead,
            long rowsWritten,
            long planMicros,
            long executeMicros,
            long rowsRejected,
            long deadLetterFailures) {}

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private QueryRunner() {}

    /** Plans and runs on a single lane, which keeps output order deterministic. */
    public static Result run(
            String sql,
            String streamName,
            String inputSchemaSpec,
            String inputPath,
            String outputSchemaSpec,
            String outputPath) {
        return run(sql, streamName, inputSchemaSpec, inputPath, outputSchemaSpec, outputPath, 1, null);
    }

    /** Plans and runs, returning counts and timings. */
    public static Result run(
            String sql,
            String streamName,
            String inputSchemaSpec,
            String inputPath,
            String outputSchemaSpec,
            String outputPath,
            int lanes) {
        return run(sql, streamName, inputSchemaSpec, inputPath, outputSchemaSpec, outputPath, lanes, null);
    }

    /**
     * Plans and runs, sending records the source cannot decode to {@code deadLetterFile}.
     *
     * <p>Without a file, one unparseable line ends the run and nothing is written -- the behaviour
     * this command has always had, and the right default: a record is not discarded merely because
     * nobody said where to put it. With one, the run finishes, the good rows are written, and every
     * rejected line is on disk with its line number and its original bytes, which is what somebody
     * needs to fix the file and re-run it.
     *
     * @param deadLetterFile where rejected records are written, or {@code null} for none
     */
    public static Result run(
            String sql,
            String streamName,
            String inputSchemaSpec,
            String inputPath,
            String outputSchemaSpec,
            String outputPath,
            int lanes,
            Path deadLetterFile) {
        return run(
                sql, streamName, inputSchemaSpec, "", inputPath, outputSchemaSpec, outputPath, lanes, deadLetterFile);
    }

    /**
     * The same run, with the stream's event-time column declared.
     *
     * @param eventTimeColumn the column carrying each row's own time, or blank for none. Blank
     *     refuses a windowed query (TIME-6) rather than planning one whose windows a watermark
     *     could never close -- the same refusal a node makes, which is the point of being able to
     *     rehearse a query here at all
     */
    public static Result run(
            String sql,
            String streamName,
            String inputSchemaSpec,
            String eventTimeColumn,
            String inputPath,
            String outputSchemaSpec,
            String outputPath,
            int lanes,
            Path deadLetterFile) {

        StreamSchema sourceSchema = SchemaOption.parse(streamName, inputSchemaSpec, eventTimeColumn);

        long planStart = System.nanoTime();
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(sourceSchema).plan(sql));
        long planMicros = (System.nanoTime() - planStart) / 1_000L;

        requireTheDeclaredOutputSchemaMatchesThePlan(plan.outputSchema(), outputSchemaSpec);

        long executeStart = System.nanoTime();
        long rowsRead = 0;
        long rowsWritten;
        long written = 0;
        long rowsRejected = 0;
        long deadLetterFailures = 0;

        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin();
                FilesystemSinkPlugin sink = new FilesystemSinkPlugin()) {
            source.configure(new Ctx(streamName, Map.of("path", inputPath, "schema", inputSchemaSpec)));
            source.open();
            // A one-shot run writes its whole answer, so the file starts empty rather than
            // accumulating every earlier run's (the sink appends by default, for restarts).
            sink.configure(new Ctx("out", Map.of("path", outputPath, "schema", outputSchemaSpec, "append", "false")));
            sink.open();

            Collector collector = new Collector(plan.outputSchema());
            LaneConfig laneConfig = LaneConfig.defaults()
                    .withInbox(4096, 4096)
                    .withBatchSize(256)
                    .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                    .withThreads("pravaha-run", true);

            // Offer the source whatever of the WHERE clause it can evaluate itself. The
            // filesystem plugin declares no pushdown today, so this resolves to nothing and costs
            // one plan walk; a source that does declare it reads less. The engine's own filter
            // stays in the plan either way, which is what makes the offer safe to make blindly.
            ReadRequest request = SourcePushdown.requestFor(plan, streamName, source.capabilities());
            try (PartitionReader reader =
                            source.createReader(source.partitions(streamName).get(0), null, request);
                    DeadLetterQueue deadLetters = openDeadLetters(deadLetterFile)) {
                QueryExecution execution =
                        QueryExecution.start(plan, lanes, laneConfig, MemoryAccess.best(), () -> collector);
                try {
                    IngestPump pump = execution.pumpInto(0, reader, BackpressurePolicy.defaults());
                    if (deadLetters != null) {
                        pump.deadLetteringTo(deadLetters, streamName);
                    }
                    // Pump until the source is exhausted. A pump returns zero both when the source
                    // has nothing right now and when it has nothing ever, and a file source is the
                    // one case where those are the same thing.
                    // pumpOnce returns 0 for two different things: the source has nothing right
                    // now, and the source has nothing ever. A file source fills the lane's inbox
                    // faster than the lane drains it, so the first zero usually means "full" -- and
                    // stopping there truncated the run silently. Five runs of one command over one
                    // 20,000-row file returned 17,664, 9,472, 7,424, 6,400 and 4,608 rows, every one
                    // reporting ok and exiting zero.
                    //
                    // So a zero is not an ending. It is a reason to let the lane drain and ask
                    // again, and only a zero that survives a drained lane means the source is spent.
                    int moved;
                    do {
                        moved = pump.pumpOnce(256);
                        rowsRead += moved;
                        execution.checkHealth();
                        if (moved == 0) {
                            // Let the lane catch up, then confirm. awaitQuiescent rethrows a lane
                            // failure, so a dead lane ends the loop with its cause rather than
                            // looking like an exhausted source.
                            execution.awaitQuiescent(java.time.Duration.ofSeconds(30));
                            moved = pump.pumpOnce(256);
                            rowsRead += moved;
                        }
                    } while (moved > 0);

                    if (!execution.awaitQuiescent(java.time.Duration.ofMinutes(5))) {
                        throw new IllegalStateException(
                                "the query did not finish within five minutes; the lane is still working or "
                                        + "stuck. Check its metrics: " + execution.metrics());
                    }
                } finally {
                    // Closing stops the lanes, and each lane finishes its own pipeline on its own
                    // thread -- which is what emits the final windows of a stateful query.
                    execution.close();
                    // TY-2. Whatever the engine produced is written here, on every exit path.
                    //
                    // It used to be written only after checkHealth() below, and a lane that died
                    // mid-stream throws from *inside the pump loop* -- so the write was never
                    // reached and out.csv was created with zero rows, contradicting what the
                    // PRV-3010 message itself promises: the offending record is diverted, not the
                    // batch. My first attempt moved the write into a finally around the checkHealth
                    // below and changed nothing at all, because the throw happens two blocks
                    // earlier than that. A probe on the collector, which never printed, is what said
                    // so.
                    //
                    // The failure still propagates and the exit code is unchanged. Rows the engine
                    // completed are simply no longer collateral.
                    written = flushCollected(sink, collector);
                }
                // After close, because close is where each lane finishes its pipeline -- and a lane
                // that dies in finish() dies after every earlier check has passed. Without this the
                // command printed "ok  3 in, 1 out" and exited zero for a query that had thrown:
                // two rows silently missing, a successful status, and the ArithmeticException that
                // caused it never reaching the person who ran it.
                // TY-2. checkHealth() throws when a lane died mid-stream, and the write below used
                // to sit after it -- so a div-by-zero on row eight produced PRV-3010, exit 1, and an
                // out.csv with *zero* rows instead of the seven that had completed. That contradicts
                // what the PRV-3010 message itself promises: the offending record is diverted, not
                // the batch.
                //
                // The failure still propagates and the exit code is unchanged. What changes is that
                // the rows the engine actually produced reach the sink first, because losing them is
                // a separate harm from the query failing, and only one of the two was intended.
                execution.checkHealth();
                rowsWritten = written;
                if (deadLetters != null) {
                    rowsRejected = deadLetters.count();
                    deadLetterFailures = deadLetters.failures();
                }
            } finally {
                collector.close();
            }
        }
        return new Result(
                rowsRead,
                rowsWritten,
                planMicros,
                (System.nanoTime() - executeStart) / 1_000L,
                rowsRejected,
                deadLetterFailures);
    }

    /**
     * Opens the dead-letter file, or returns {@code null} when none was asked for.
     *
     * <p>Failing to open it fails the run, and deliberately: an operator who typed {@code --dlq}
     * asked for the rejected records to be kept, and continuing without keeping them would discard
     * exactly the records they said they wanted.
     */
    private static DeadLetterQueue openDeadLetters(Path file) {
        if (file == null) {
            return null;
        }
        try {
            return new FileDeadLetterQueue(file);
        } catch (IOException e) {
            throw new IllegalStateException("cannot open the dead-letter file " + file.toAbsolutePath(), e);
        }
    }

    // The Batch class that used to live here is gone. It buffered decoded rows on the calling
    // thread and pushed them through the pipeline afterwards; the ingest pump now writes each row
    // straight into the lane's inbox cell, so the intermediate copy and the arena holding it are
    // both unnecessary. One fewer copy of every row, on the path the product actually runs.

    /** Holds pipeline output until the sink is written. */
    /**
     * How much a single output row may carry beyond its fixed part.
     *
     * <p>Every row is reserved this much from a 64 MiB arena that holds the whole result, so the
     * number trades the largest value a row may carry against how many rows a run may return.
     * Two KiB covers the values a person prints -- a JSON document in a column is ordinary -- and
     * still leaves room for the 20,000-row runs the examples exercise. Sixty-four KiB, tried first,
     * exhausted the arena on exactly that file.
     *
     * <p>A value beyond it is refused, naming the column and both sizes. That is a change in
     * behaviour for a value between this and whatever the old unbounded write happened to survive:
     * such a row used to come back corrupted under exit 0, and now says so.
     */
    private static final int PAYLOAD_BUDGET = 2048;

    /**
     * Writes whatever the engine produced, on every exit path including a failing one.
     *
     * <p>TY-2. Never lets its own failure replace the query's: if the query is on its way out with a
     * {@code PRV-3010}, a secondary problem writing the output must not be the message the person
     * sees instead.
     */
    private static long flushCollected(FilesystemSinkPlugin sink, Collector collector) {
        try {
            long written = sink.write(collector.rows());
            sink.flush();
            return written;
        } catch (RuntimeException secondary) {
            System.err.println("could not write the collected rows: " + secondary.getMessage());
            return 0;
        }
    }

    private static final class Collector implements RowOutput, AutoCloseable {
        private final RowLayout layout;
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 64);
        private final BinaryRowWriter writer;
        private final List<RowView> rows = new ArrayList<>();

        Collector(StreamSchema schema) {
            this.layout = RowLayout.of(schema);
            this.writer = new BinaryRowWriter(layout);
        }

        @Override
        public RowWriter begin() {
            // The budget is a real bound now: the writer is told it and refuses a value that
            // would cross it. It used to be 512 with nothing checking, so a 1 KiB string wrote
            // straight through the reservation and came back with 56 bytes of allocator bookkeeping
            // spliced into the middle, under exit 0 and the right row count.
            int payloadBudget = PAYLOAD_BUDGET;
            long handle = arena.allocate(layout.rowSize(payloadBudget));
            if (handle == ArenaHandle.NULL) {
                throw new IllegalStateException("output arena exhausted");
            }
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle), layout.rowSize(payloadBudget));
            return new CollectingWriter(
                    writer,
                    () -> rows.add(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))));
        }

        List<RowView> rows() {
            return rows;
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    /** The options this command was given are not usable together. */
    private static final com.ash.messaging.pravaha.api.ErrorCode INVALID_OPTIONS =
            new com.ash.messaging.pravaha.api.ErrorCode(1031, "CLIENT_INVALID_OPTIONS");

    /**
     * Refuses an {@code --out-schema} that is not the shape the plan produces (X-3, round 1's Q-10).
     *
     * <p>This run has <strong>two</strong> descriptions of its output: the plan's real
     * {@code outputSchema()}, which the {@code Collector} and its {@code BinaryRowWriter} are built
     * from, and the {@code --out-schema} string, which configures the sink and its decoder. Nothing
     * compared them, and the row is read back at the declared widths -- so declaring two adjacent
     * {@code INT32} columns as one {@code INT64} read eight bytes across both of them. Traced
     * exactly: row {@code 1,1} came back as {@code 4294967297}, and row {@code -2,-2} as
     * {@code -4294967298} in the first column and {@code 4294967294} in the second, the second read
     * running past the row into unrelated bytes. No exception, no warning, a full CSV of numbers.
     *
     * <p>{@code RowLayout.checkType} exists and is the <em>writer's</em> guard, not the reader's:
     * the writer is built from the true schema, so it has nothing to complain about. The comparison
     * has to happen here, where both descriptions are in scope, which is the only place they ever
     * are.
     *
     * <p>Count and type only, never names: somebody naming an output column {@code total} where the
     * plan calls it {@code EXPR$1} has said something about the CSV header and nothing about the
     * bytes.
     */
    private static void requireTheDeclaredOutputSchemaMatchesThePlan(StreamSchema planned, String declaredSpec) {
        StreamSchema declared = FilesystemSourcePlugin.parseSchema("out", declaredSpec);
        if (declared.fieldCount() != planned.fieldCount()) {
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    INVALID_OPTIONS,
                    "--out-schema declares " + declared.fieldCount() + " column(s) and this query produces "
                            + planned.fieldCount() + ". The declared schema decodes the rows the engine wrote, "
                            + "so a mismatch reads the wrong bytes rather than failing. Declare: "
                            + asSpec(planned));
        }
        for (int i = 0; i < declared.fieldCount(); i++) {
            if (declared.field(i).type().typeName() != planned.field(i).type().typeName()) {
                throw new com.ash.messaging.pravaha.api.PravahaException(
                        INVALID_OPTIONS,
                        "--out-schema declares column " + (i + 1) + " ('"
                                + declared.field(i).name() + "') as "
                                + declared.field(i).type().typeName() + " and this query produces "
                                + planned.field(i).type().typeName() + " there. The declared schema decodes the "
                                + "bytes the engine wrote, at the widths it declares, so a wrong width reads "
                                + "across the next column and prints a plausible number. Declare: "
                                + asSpec(planned));
            }
        }
    }

    /** The plan's output as an {@code --out-schema} string, so the refusal names the fix. */
    private static String asSpec(StreamSchema schema) {
        StringBuilder spec = new StringBuilder();
        for (int i = 0; i < schema.fieldCount(); i++) {
            if (i > 0) {
                spec.append(',');
            }
            spec.append(schema.field(i).name())
                    .append(':')
                    .append(schema.field(i).type().typeName());
        }
        return spec.toString();
    }
}
