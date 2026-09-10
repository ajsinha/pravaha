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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
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
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

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
 * thread scheduling and make the documented output true only most of the time. {@code --lanes} is
 * there for anyone who wants the parallelism and can accept unordered output.
 */
public final class QueryRunner {

    /** What a run produced. */
    public record Result(long rowsRead, long rowsWritten, long planMicros, long executeMicros) {}

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
        return run(sql, streamName, inputSchemaSpec, inputPath, outputSchemaSpec, outputPath, 1);
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

        StreamSchema sourceSchema = FilesystemSourcePlugin.parseSchema(streamName, inputSchemaSpec);

        long planStart = System.nanoTime();
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(sourceSchema).plan(sql));
        long planMicros = (System.nanoTime() - planStart) / 1_000L;

        long executeStart = System.nanoTime();
        long rowsRead = 0;
        long rowsWritten;

        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin();
                FilesystemSinkPlugin sink = new FilesystemSinkPlugin()) {
            source.configure(new Ctx(streamName, Map.of("path", inputPath, "schema", inputSchemaSpec)));
            source.open();
            sink.configure(new Ctx("out", Map.of("path", outputPath, "schema", outputSchemaSpec)));
            sink.open();

            Collector collector = new Collector(plan.outputSchema());
            LaneConfig laneConfig = LaneConfig.defaults()
                    .withInbox(4096, 4096)
                    .withBatchSize(256)
                    .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                    .withThreads("pravaha-run", true);

            try (PartitionReader reader =
                    source.createReader(source.partitions(streamName).get(0), null)) {
                QueryExecution execution =
                        QueryExecution.start(plan, lanes, laneConfig, MemoryAccess.best(), () -> collector);
                try {
                    IngestPump pump = execution.pumpInto(0, reader, BackpressurePolicy.defaults());
                    // Pump until the source is exhausted. A pump returns zero both when the source
                    // has nothing right now and when it has nothing ever, and a file source is the
                    // one case where those are the same thing.
                    int moved;
                    do {
                        moved = pump.pumpOnce(256);
                        rowsRead += moved;
                        execution.checkHealth();
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
                }
                rowsWritten = sink.write(collector.rows());
                sink.flush();
            } finally {
                collector.close();
            }
        }
        return new Result(rowsRead, rowsWritten, planMicros, (System.nanoTime() - executeStart) / 1_000L);
    }

    // The Batch class that used to live here is gone. It buffered decoded rows on the calling
    // thread and pushed them through the pipeline afterwards; the ingest pump now writes each row
    // straight into the lane's inbox cell, so the intermediate copy and the arena holding it are
    // both unnecessary. One fewer copy of every row, on the path the product actually runs.

    /** Holds pipeline output until the sink is written. */
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
            long handle = arena.allocate(layout.rowSize(512));
            if (handle == ArenaHandle.NULL) {
                throw new IllegalStateException("output arena exhausted");
            }
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
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
}
