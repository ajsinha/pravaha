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
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.plugin.filesystem.CollectingWriter;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSinkPlugin;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

/**
 * Plans a query and runs it over delimited files.
 *
 * <p>Shared by the CLI's {@code run} command and its tests, and it is what makes the vertical slice
 * demonstrable in one call rather than a page of wiring.
 */
public final class QueryRunner {

    /** What a run produced. */
    public record Result(long rowsRead, long rowsWritten, long planMicros, long executeMicros) {}

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private QueryRunner() {}

    /** Plans and runs, returning counts and timings. */
    public static Result run(
            String sql,
            String streamName,
            String inputSchemaSpec,
            String inputPath,
            String outputSchemaSpec,
            String outputPath) {

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
            try (InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, collector);
                    Batch batch = new Batch(sourceSchema);
                    PartitionReader reader =
                            source.createReader(source.partitions(streamName).get(0), null)) {

                int polled;
                while ((polled = reader.poll(batch, 256)) > 0) {
                    rowsRead += polled;
                    batch.drainTo(pipeline);
                }
                pipeline.finish();
                rowsWritten = sink.write(collector.rows());
                sink.flush();
            } finally {
                collector.close();
            }
        }
        return new Result(rowsRead, rowsWritten, planMicros, (System.nanoTime() - executeStart) / 1_000L);
    }

    /** Buffers a batch of decoded source rows before pushing them through. */
    private static final class Batch implements PartitionReader.RecordSink, AutoCloseable {
        private final RowLayout layout;
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 64);
        private final BinaryRowWriter writer;
        private final BinaryRowView view;
        private final List<Long> handles = new ArrayList<>();

        Batch(StreamSchema schema) {
            this.layout = RowLayout.of(schema);
            this.writer = new BinaryRowWriter(layout);
            this.view = new BinaryRowView(layout);
        }

        @Override
        public RowWriter beginRow() {
            long handle = arena.allocate(layout.rowSize(512));
            if (handle == ArenaHandle.NULL) {
                throw new IllegalStateException("input batch arena exhausted; reduce the batch size");
            }
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            return new CollectingWriter(writer, () -> handles.add(handle));
        }

        void drainTo(InterpretedPipeline pipeline) {
            for (long handle : handles) {
                pipeline.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            handles.clear();
            // The batch is consumed, so its space goes back in one move -- the whole reason the
            // arena exists (design 8.5).
            arena.reset();
        }

        @Override
        public void close() {
            arena.close();
        }
    }

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
