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
package com.ash.messaging.pravaha.plugin.delta;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.delta.kernel.Operation;
import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterable;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.plugin.delta.DeltaSinkRows.Change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Delta sink writing partitioned tables: rows filed under their partition's directory, each
 * file's partition values recorded in the log, and the sink's guarantees unchanged -- one commit per
 * checkpoint, a repeated commit a no-op, a concurrent writer refused.
 *
 * <p>The table is read back through Kernel ({@link DeltaTableReader}), which rebuilds a partition
 * column from the log's {@code partitionValues}, so a value recorded wrongly reads back wrongly.
 */
class DeltaSinkPartitionTest {

    private static final StreamSchema SPEND = StreamSchema.builder("spend")
            .field("region", Types.string())
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private static final String SPEND_SCHEMA = "region:STRING,user_id:STRING,total:INT64";

    @TempDir
    Path root;

    private final SinkTestRows rows = new SinkTestRows();
    private final java.util.List<DeltaSinkPlugin> opened = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(DeltaSinkPlugin::close);
        rows.close();
    }

    // ---- upsert ------------------------------------------------------------------------------

    @Test
    void upsertFilesEachRowUnderItsPartitionAndRecordsTheValuesInTheLog() throws Exception {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path);

        cycle(
                sink,
                1,
                rows.row(SPEND, 1, "eu", "u1", 300L),
                rows.row(SPEND, 1, "us", "u2", 50L),
                rows.row(SPEND, 1, "eu", "u3", 7L));

        assertThat(DeltaTableReader.read(path)).containsExactly("eu|u1|300", "eu|u3|7", "us|u2|50");
        assertThat(DeltaTableReader.version(path))
                .as("two partitions, one checkpoint, one Delta commit after the table's creation")
                .isEqualTo(1L);
        assertThat(partitionColumns(path)).containsExactly("region");
        assertThat(parquetIn(path, "region=eu")).hasSize(1);
        assertThat(parquetIn(path, "region=us")).hasSize(1);
        String commit = logEntry(path, 1);
        assertThat(addedPartitionValues(commit))
                .as("each file's partition values, recorded in its add action")
                .containsExactlyInAnyOrder("{\"region\":\"eu\"}", "{\"region\":\"us\"}");
        assertThat(count(commit, "\"txn\""))
                .as("one txn action for the one checkpoint")
                .isEqualTo(1);
    }

    @Test
    void aRetractionAndAKeyMovingPartitionRewriteOnlyTheFilesHoldingThoseKeys() throws Exception {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path);
        cycle(
                sink,
                1,
                rows.row(SPEND, 1, "eu", "u1", 300L),
                rows.row(SPEND, 1, "us", "u2", 50L),
                rows.row(SPEND, 1, "eu", "u3", 7L));

        // u1 is removed, and u2 moves from us to eu. The eu file is rewritten without u1 and back into
        // eu; the us file is removed with nothing left to rewrite; u2 is added under eu.
        cycle(
                sink,
                2,
                rows.row(SPEND, -1, "eu", "u1", 300L),
                rows.row(SPEND, -1, "us", "u2", 50L),
                rows.row(SPEND, 1, "eu", "u2", 60L));

        assertThat(DeltaTableReader.read(path)).containsExactly("eu|u2|60", "eu|u3|7");
        assertThat(DeltaTableReader.version(path)).isEqualTo(2L);
        assertThat(sink.filesRewritten()).isEqualTo(2L);
        String commit = logEntry(path, 2);
        assertThat(addedPartitionValues(commit))
                .as("the survivor u3 goes back into eu, and the new u2 is filed there too")
                .containsOnly("{\"region\":\"eu\"}");
        assertThat(count(commit, "\"remove\"")).isEqualTo(2);
    }

    @Test
    void aCommitCarryingAnAlreadyRecordedLabelIsANoOp() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path);
        List<Change> changes = List.of(
                new Change(new Object[] {"eu", "u1", 300L}, 1L), new Change(new Object[] {"us", "u2", 50L}, 1L));

        assertThat(sink.commits().apply(changes, "spend_table", 7L)).hasValue(1L);
        assertThat(sink.commits().apply(changes, "spend_table", 7L))
                .as("the table's txn action already holds label 7")
                .isEmpty();

        assertThat(DeltaTableReader.read(path)).containsExactly("eu|u1|300", "us|u2|50");
        assertThat(DeltaTableReader.version(path)).isEqualTo(1L);
    }

    @Test
    void aWriterInsideTheCommitsWindowIsRefusedWithNothingWritten() {
        String path = table("spend");
        DeltaSinkPlugin sink = upsertSink(path);
        cycle(sink, 1, rows.row(SPEND, 1, "eu", "u1", 300L));

        sink.beginTransaction(2);
        sink.write(List.of(rows.row(SPEND, 1, "us", "u2", 50L), rows.row(SPEND, -1, "eu", "u1", 300L)));
        String handle = sink.prepare(2);
        sink.commits().openConflictWindow(() -> commitSomethingElse(path));

        assertThatThrownBy(() -> sink.commit(handle))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5059");
        assertThat(DeltaTableReader.read(path)).containsExactly("eu|u1|300");
    }

    // ---- changelog ---------------------------------------------------------------------------

    @Test
    void changelogModeAppendsIntoDatePartitions() throws Exception {
        StreamSchema daily = StreamSchema.builder("daily")
                .field("day", Types.date())
                .field("user_id", Types.string())
                .field("total", Types.int64())
                .build();
        String path = table("daily");
        DeltaSinkPlugin sink = open(
                path,
                Map.of(
                        "schema", "day:DATE,user_id:STRING,total:INT64",
                        "mode", "changelog",
                        "partition.columns", "day"));

        cycle(sink, 1, rows.row(daily, 1, 19_000, "u1", 300L), rows.row(daily, 1, 19_001, "u2", 50L));
        cycle(sink, 2, rows.row(daily, -1, 19_000, "u1", 300L));

        assertThat(DeltaTableReader.read(path))
                .containsExactly("19000|u1|300|delete|-1", "19000|u1|300|insert|1", "19001|u2|50|insert|1");
        assertThat(parquetIn(path, "day=" + LocalDate.ofEpochDay(19_000))).hasSize(2);
        assertThat(parquetIn(path, "day=" + LocalDate.ofEpochDay(19_001))).hasSize(1);
        assertThat(addedPartitionValues(logEntry(path, 1)))
                .containsExactlyInAnyOrder(
                        "{\"day\":\"" + LocalDate.ofEpochDay(19_000) + "\"}",
                        "{\"day\":\"" + LocalDate.ofEpochDay(19_001) + "\"}");
    }

    // ---- what is refused ---------------------------------------------------------------------

    @Test
    void anUnpartitionedBindingOverAPartitionedTableIsRefused() {
        String path = table("spend");
        Engine engine = DefaultEngine.create(new Configuration());
        Table.forPath(engine, path)
                .createTransactionBuilder(engine, "test", Operation.CREATE_TABLE)
                .withSchema(
                        engine,
                        new StructType().add("user_id", StringType.STRING).add("total", LongType.LONG))
                .withPartitionColumns(engine, List.of("user_id"))
                .build(engine)
                .commit(engine, CloseableIterable.emptyIterable());

        assertThatThrownBy(() -> open(path, Map.of("schema", "user_id:STRING,total:INT64", "key.columns", "user_id")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5057")
                .hasMessageContaining("is partitioned by [user_id]")
                .hasMessageContaining("declares no partition.columns");
    }

    @Test
    void aPartitionedBindingOverATablePartitionedOtherwiseIsRefused() {
        String path = table("spend");
        upsertSink(path).close();

        assertThatThrownBy(() -> open(
                        path, Map.of("schema", SPEND_SCHEMA, "key.columns", "user_id", "partition.columns", "user_id")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5057")
                .hasMessageContaining("is partitioned by [region]")
                .hasMessageContaining("declares partition.columns [user_id]");
    }

    @Test
    void aPartitionColumnThatIsNotInTheQuerysOutputIsRefusedAtConfiguration() {
        assertThatThrownBy(() -> configure(Map.of("key.columns", "user_id", "partition.columns", "country")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5056")
                .hasMessageContaining("partition column 'country', which is not one of the query's output columns");
        assertThatThrownBy(() -> configure(Map.of("mode", "changelog", "partition.columns", "_op")))
                .as("changelog mode's own columns are not the query's")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("partition column '_op'");
    }

    @Test
    void aPartitionColumnOfATypeThatCannotBeFiledExactlyIsRefusedAtConfiguration() {
        assertThatThrownBy(() -> new DeltaSinkPlugin()
                        .configure(new Ctx(
                                "spend_table",
                                Map.of(
                                        "path", "/tmp/nowhere",
                                        "schema", "tag:BYTES,user_id:STRING",
                                        "key.columns", "user_id",
                                        "partition.columns", "tag"))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5056")
                .hasMessageContaining("cannot partition by 'tag', which is BYTES");
    }

    @Test
    void partitioningByEveryColumnOrTwiceByOneIsRefused() {
        assertThatThrownBy(
                        () -> configure(Map.of("key.columns", "user_id", "partition.columns", "region,user_id,total")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("partitions by every column");
        assertThatThrownBy(() -> configure(Map.of("key.columns", "user_id", "partition.columns", "region,REGION")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("twice");
    }

    // -----------------------------------------------------------------------------------------

    private String table(String name) {
        return root.resolve(name).toString();
    }

    private DeltaSinkPlugin upsertSink(String path) {
        return open(path, Map.of("schema", SPEND_SCHEMA, "key.columns", "user_id", "partition.columns", "region"));
    }

    private DeltaSinkPlugin open(String path, Map<String, String> extra) {
        Map<String, String> options = new HashMap<>(Map.of("path", path));
        options.putAll(extra);
        DeltaSinkPlugin sink = new DeltaSinkPlugin();
        sink.configure(new Ctx("spend_table", options));
        sink.open();
        opened.add(sink);
        return sink;
    }

    private static void configure(Map<String, String> extra) {
        Map<String, String> options = new HashMap<>(Map.of("path", "/tmp/nowhere", "schema", SPEND_SCHEMA));
        options.putAll(extra);
        new DeltaSinkPlugin().configure(new Ctx("spend_table", options));
    }

    private static void cycle(DeltaSinkPlugin sink, long label, RowView... batch) {
        sink.beginTransaction(label);
        sink.write(List.of(batch));
        sink.flush();
        sink.commit(sink.prepare(label));
    }

    private static void commitSomethingElse(String path) {
        Engine engine = DefaultEngine.create(new Configuration());
        Table.forPath(engine, path)
                .createTransactionBuilder(engine, "somebody-else", Operation.WRITE)
                .build(engine)
                .commit(engine, CloseableIterable.emptyIterable());
    }

    private static List<String> partitionColumns(String path) {
        Engine engine = DefaultEngine.create(new Configuration());
        return Table.forPath(engine, path).getLatestSnapshot(engine).getPartitionColumnNames();
    }

    private static List<Path> parquetIn(String path, String directory) throws Exception {
        Path dir = Path.of(path, directory);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".parquet"))
                    .toList();
        }
    }

    private static String logEntry(String path, long version) throws Exception {
        return Files.readString(Path.of(path, "_delta_log", String.format("%020d.json", version)));
    }

    /** The {@code partitionValues} of every {@code add} action in a commit, as the JSON records them. */
    private static List<String> addedPartitionValues(String commit) {
        List<String> values = new java.util.ArrayList<>();
        for (String line : commit.lines().toList()) {
            if (!line.startsWith("{\"add\"")) {
                continue;
            }
            Matcher matcher =
                    Pattern.compile("\"partitionValues\":(\\{[^}]*\\})").matcher(line);
            values.add(matcher.find() ? matcher.group(1) : "<none>");
        }
        return values;
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
