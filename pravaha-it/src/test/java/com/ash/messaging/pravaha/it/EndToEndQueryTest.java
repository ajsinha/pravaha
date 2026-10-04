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
package com.ash.messaging.pravaha.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL text in, results out, through every real component.
 *
 * <p>Wave 2's vertical slice. No mocks and no shortcuts: Calcite parses and optimises, the plan
 * builder translates, the interpreted pipeline executes over arena-backed binary rows, and the
 * filesystem plugin supplies input and receives output.
 *
 * <p>A slice like this is worth more than the sum of its unit tests, because it is where the seams
 * between well-tested components turn out to disagree -- and every one of those disagreements has
 * so far been in the tests rather than the code.
 */
class EndToEndQueryTest {

    private static final String SCHEMA_SPEC = "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING";

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    @Test
    void filtersAndProjectsFromFileToFile(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, """
                1,alice,500,COMPLETED
                2,bob,50,COMPLETED
                3,carol,900,PENDING
                4,dave,150,COMPLETED
                5,erin,75,COMPLETED
                """);
        Path output = dir.resolve("out.csv");

        run(
                "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100",
                input,
                output,
                "user_id:STRING,amount:INT64");

        // alice(500) and dave(150) qualify; bob and erin are under the threshold and carol is PENDING.
        assertThat(Files.readAllLines(output)).containsExactly("alice,500", "dave,150");
    }

    @Test
    void projectionReordersColumns(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "1,alice,500,COMPLETED\n");
        Path output = dir.resolve("out.csv");

        run("SELECT status, user_id FROM txn", input, output, "status:STRING,user_id:STRING");

        assertThat(Files.readAllLines(output)).containsExactly("COMPLETED,alice");
    }

    @Test
    void aGlobalAggregateCountsAndSums(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, """
                1,alice,500,COMPLETED
                2,bob,50,COMPLETED
                3,carol,900,PENDING
                """);
        Path output = dir.resolve("out.csv");

        run("SELECT COUNT(*), SUM(amount) FROM txn WHERE status = 'COMPLETED'", input, output, "n:INT64,total:INT64");

        assertThat(Files.readAllLines(output)).containsExactly("2,550");
    }

    @Test
    void anEmptyResultProducesAnEmptyFileRatherThanFailing(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "1,alice,10,PENDING\n");
        Path output = dir.resolve("out.csv");

        run("SELECT user_id FROM txn WHERE status = 'COMPLETED'", input, output, "user_id:STRING");

        assertThat(Files.readAllLines(output)).isEmpty();
    }

    @Test
    void aKeyedGroupByIsRefusedBeforeAnythingRuns(@TempDir Path dir) throws IOException {
        // The bounded-state rule, end to end: the query never starts, and the message says what to
        // do about it. Design 9.6 -- refusing is the only intervention that reliably works.
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "1,alice,10,COMPLETED\n");
        Path output = dir.resolve("out.csv");

        assertThatThrownBy(() -> run(
                        "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id", input, output, "user_id:STRING,n:INT64"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2050")
                .hasMessageContaining("Bound it with a window");

        assertThat(Files.exists(output))
                .as("a refused query must not have produced output")
                .isFalse();
    }

    @Test
    void aTypoInAColumnNameFailsAtPlanningNotAtRuntime(@TempDir Path dir) throws IOException {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "1,alice,10,COMPLETED\n");
        assertThatThrownBy(() -> run("SELECT user_idd FROM txn", input, dir.resolve("out.csv"), "user_id:STRING"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2002");
    }

    // ------------------------------------------------------------------ the harness

    /** Plans {@code sql} and runs it from {@code input} to {@code output}. */
    @SuppressWarnings("NullAway") // nulls passed on purpose
    private static void run(String sql, Path input, Path output, String outputSchemaSpec) {
        StreamSchema sourceSchema = FilesystemSourcePlugin.parseSchema("txn", SCHEMA_SPEC);

        // 1. SQL -> Calcite -> Pravaha's plan. Everything expensive happens once, here.
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(sourceSchema).plan(sql));

        try (FilesystemSourcePlugin source = new FilesystemSourcePlugin();
                FilesystemSinkPlugin sink = new FilesystemSinkPlugin()) {
            source.configure(new Ctx("txn", Map.of("path", input.toString(), "schema", SCHEMA_SPEC)));
            source.open();
            sink.configure(new Ctx("out", Map.of("path", output.toString(), "schema", outputSchemaSpec)));
            sink.open();

            SinkCollector collector = new SinkCollector(plan.outputSchema());
            try (InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, collector);
                    ReaderCollector reader = new ReaderCollector(sourceSchema);
                    PartitionReader partition =
                            source.createReader(source.partitions("txn").get(0), null)) {

                // 2. Pull batches, push each row through the pipeline.
                while (partition.poll(reader, 128) > 0) {
                    reader.drainTo(pipeline);
                }
                // 3. End of input: stateful stages emit.
                pipeline.finish();

                // 4. Results to the sink.
                sink.write(collector.rows());
                sink.flush();
            } finally {
                collector.close();
            }
        }
    }

    /** Buffers decoded source rows, then feeds them through. */
    private static final class ReaderCollector implements PartitionReader.RecordSink, AutoCloseable {
        private final RowLayout layout;
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 32);
        private final BinaryRowWriter writer;
        private final List<Long> handles = new ArrayList<>();

        ReaderCollector(StreamSchema schema) {
            this.layout = RowLayout.of(schema);
            this.writer = new BinaryRowWriter(layout);
        }

        @Override
        public RowWriter beginRow() {
            long handle = arena.allocate(layout.rowSize(512));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            return new CollectingWriter(writer, () -> handles.add(handle));
        }

        void drainTo(InterpretedPipeline pipeline) {
            BinaryRowView view = new BinaryRowView(layout);
            for (long handle : handles) {
                pipeline.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            handles.clear();
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    /** Receives pipeline output and holds it until the sink is written. */
    private static final class SinkCollector implements RowOutput, AutoCloseable {
        private final RowLayout layout;
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 32);
        private final BinaryRowWriter writer;
        private final List<RowView> rows = new ArrayList<>();

        SinkCollector(StreamSchema schema) {
            this.layout = RowLayout.of(schema);
            this.writer = new BinaryRowWriter(layout);
        }

        @Override
        public RowWriter begin() {
            long handle = arena.allocate(layout.rowSize(512));
            if (handle == ArenaHandle.NULL) {
                throw new IllegalStateException("sink collector arena exhausted");
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
