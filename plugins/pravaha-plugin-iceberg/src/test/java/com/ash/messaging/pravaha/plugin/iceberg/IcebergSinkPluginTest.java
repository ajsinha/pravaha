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
package com.ash.messaging.pravaha.plugin.iceberg;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code iceberg-sink} against real Iceberg tables on the local filesystem, read back by Iceberg. */
class IcebergSinkPluginTest {

    private static final String SPEND = "user_id:INT64,total:INT64";

    @TempDir
    Path dir;

    private final SinkTestRows rows = new SinkTestRows();

    @AfterEach
    void closeRows() {
        rows.close();
    }

    @Test
    void changelogCommitsNothingUntilCommitThenEveryChangeWithItsWeight() throws IOException {
        IcebergSinkPlugin sink = sink("changelog", Map.of());
        StreamSchema schema = sink.schema().orElseThrow();

        sink.beginTransaction(1);
        sink.write(List.of(rows.row(schema, 1, 7L, 100L), rows.row(schema, -1, 7L, 90L)));
        String handle = sink.prepare(1);
        assertThat(read(sink.icebergTable())).as("prepared is not visible").isEmpty();

        sink.commit(handle);
        List<Record> table = read(sink.icebergTable());
        assertThat(table).hasSize(2);
        assertThat(table.get(0).getField("_op")).isEqualTo("insert");
        assertThat(table.stream().map(r -> r.getField("_weight")).toList()).containsExactlyInAnyOrder(1L, -1L);
        assertThat(sink.capabilities().emitModes()).contains(EmitMode.APPEND);
        assertThat(sink.rowsApplied()).isEqualTo(2);
    }

    @Test
    void upsertReplacesAndRemovesByKeyThroughEqualityDeletes() throws IOException {
        IcebergSinkPlugin sink = sink("upsert", Map.of("key.columns", "user_id"));
        StreamSchema schema = sink.schema().orElseThrow();

        cycle(sink, 1, rows.row(schema, 1, 1L, 10L), rows.row(schema, 1, 2L, 20L));
        cycle(
                sink,
                2,
                rows.row(schema, -1, 1L, 10L),
                rows.row(schema, 1, 1L, 11L),
                rows.row(schema, -1, 2L, 20L),
                rows.row(schema, 1, 3L, 30L));

        assertThat(byKey(read(sink.icebergTable()))).isEqualTo(Map.of(1L, 11L, 3L, 30L));
        assertThat(sink.icebergTable().snapshots()).hasSize(2);
    }

    @Test
    void aCrashBetweenPrepareAndCommitCommitsOnceAndAbortAfterDropsTheRest() throws IOException {
        IcebergSinkPlugin first = sink("upsert", Map.of("key.columns", "user_id"));
        StreamSchema schema = first.schema().orElseThrow();
        first.beginTransaction(1);
        first.write(List.of(rows.row(schema, 1, 1L, 10L)));
        String durable = first.prepare(1);
        first.beginTransaction(2);
        first.write(List.of(rows.row(schema, 1, 2L, 20L)));
        first.prepare(2); // prepared, and the checkpoint recording it never became durable
        // The process dies here: nothing committed, nothing closed.

        IcebergSinkPlugin restored = sink("upsert", Map.of("key.columns", "user_id"));
        restored.abortAfter(1);
        restored.commit(durable);
        restored.commit(durable); // a restore cannot know whether the first commit arrived

        assertThat(byKey(read(restored.icebergTable()))).isEqualTo(Map.of(1L, 10L));
        assertThat(restored.icebergTable().snapshots()).hasSize(1);
        assertThat(IcebergSinkTable.labels(staging())).containsExactly(1L);
    }

    @Test
    void aCommitRepeatedAfterItLandedIsSkippedByTheTablesOwnRecord() throws IOException {
        IcebergSinkPlugin sink = sink("changelog", Map.of());
        StreamSchema schema = sink.schema().orElseThrow();
        sink.beginTransaction(5);
        sink.write(List.of(rows.row(schema, 1, 1L, 10L)));
        String handle = sink.prepare(5);
        sink.commit(handle);

        IcebergSinkPlugin restored = sink("changelog", Map.of());
        restored.commit(handle);
        restored.abortAfter(4); // must not touch label 5, which the table has

        assertThat(read(restored.icebergTable())).hasSize(1);
        assertThat(restored.icebergTable().currentSnapshot().summary())
                .containsEntry(IcebergSinkTable.TXN_PROPERTY, "spend_table")
                .containsEntry(IcebergSinkTable.LABEL_PROPERTY, "5");
        assertThat(read(restored.icebergTable())).hasSize(1);
    }

    @Test
    void abortDiscardsAndATransactionIdMismatchIsRefused() throws IOException {
        IcebergSinkPlugin sink = sink("changelog", Map.of());
        StreamSchema schema = sink.schema().orElseThrow();
        sink.beginTransaction(1);
        sink.write(List.of(rows.row(schema, 1, 1L, 10L)));
        String handle = sink.prepare(1);
        sink.abort(handle);
        sink.commit(handle);
        assertThat(read(sink.icebergTable())).isEmpty();

        assertThatThrownBy(() -> sink.commit("iceberg-sink:v1:1:someone_else"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5142");
        assertThatThrownBy(() -> sink.commit("garbage")).hasMessageContaining("not a handle");
    }

    @Test
    void withoutTransactionsEachWriteIsItsOwnSnapshot() throws IOException {
        IcebergSinkPlugin sink = sink("upsert", Map.of("key.columns", "user_id", "transactional", "false"));
        StreamSchema schema = sink.schema().orElseThrow();
        sink.write(List.of(rows.row(schema, 1, 1L, 10L)));
        sink.write(List.of(rows.row(schema, 1, 1L, 12L)));
        assertThat(sink.prepare(1)).isEmpty();
        assertThat(byKey(read(sink.icebergTable()))).isEqualTo(Map.of(1L, 12L));
        assertThat(sink.icebergTable().snapshots()).hasSize(2);
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void everyTypeRoundTripsThroughIcebergsReader() throws IOException {
        Map<String, String> options = new HashMap<>();
        options.put("path", dir.resolve("typed").toString());
        options.put("mode", "changelog");
        options.put(
                "schema",
                "b:BOOLEAN,i8:INT8,i16:INT16,i32:INT32,f:FLOAT32,d:FLOAT64,s:STRING,bin:BYTES,day:DATE,"
                        + "ts:TIMESTAMP,amount:DECIMAL(10,2),note:STRING?");
        IcebergSinkPlugin sink = new IcebergSinkPlugin();
        sink.configure(new Ctx("typed", options));
        sink.open();
        StreamSchema schema = sink.schema().orElseThrow();
        long nanos = 1_700_000_000_123_456_000L;
        sink.beginTransaction(1);
        sink.write(List.of(rows.row(
                schema,
                1,
                true,
                (byte) 3,
                (short) 4,
                5,
                1.5f,
                2.5d,
                "x",
                new byte[] {1, 2},
                19_000,
                nanos,
                new BigDecimal("12.34"),
                null)));
        sink.commit(sink.prepare(1));

        Record r = read(sink.icebergTable()).get(0);
        assertThat(r.getField("b")).isEqualTo(true);
        assertThat(r.getField("i8")).isEqualTo(3);
        assertThat(r.getField("i16")).isEqualTo(4);
        assertThat(r.getField("f")).isEqualTo(1.5f);
        assertThat(r.getField("s")).isEqualTo("x");
        assertThat(r.getField("bin")).isEqualTo(ByteBuffer.wrap(new byte[] {1, 2}));
        assertThat(r.getField("day")).isEqualTo(LocalDate.ofEpochDay(19_000));
        assertThat(((OffsetDateTime) r.getField("ts")).toInstant().toEpochMilli())
                .isEqualTo(1_700_000_000_123L);
        assertThat(((OffsetDateTime) r.getField("ts")).getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(r.getField("amount")).isEqualTo(new BigDecimal("12.34"));
        assertThat(r.getField("note")).isNull();
    }

    @Test
    void aTimestampThatIsNotWholeMicrosecondsIsRefusedNotRounded() {
        Map<String, String> options = new HashMap<>();
        options.put("path", dir.resolve("ts").toString());
        options.put("mode", "changelog");
        options.put("schema", "ts:TIMESTAMP");
        IcebergSinkPlugin sink = new IcebergSinkPlugin();
        sink.configure(new Ctx("ts", options));
        sink.open();
        RowView row = rows.row(sink.schema().orElseThrow(), 1, 1_000_000_001L);
        assertThatThrownBy(() -> sink.write(List.of(row))).hasMessageContaining("PRV-5142");
    }

    @Test
    void configurationRefusesByName() {
        assertThatThrownBy(() -> new IcebergSinkPlugin().configure(new Ctx("s", Map.of("schema", SPEND))))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5140")
                .hasMessageContaining("needs 'path'");
        assertThatThrownBy(() -> configure(Map.of("path", "s3://bucket/t", "schema", SPEND)))
                .hasMessageContaining("local");
        assertThatThrownBy(() -> configure(Map.of("path", dir.toString()))).hasMessageContaining("needs 'schema'");
        assertThatThrownBy(() -> configure(Map.of("path", dir.toString(), "schema", SPEND, "mode", "sideways")))
                .hasMessageContaining("PRV-5140");
        assertThatThrownBy(() -> configure(Map.of("path", dir.toString(), "schema", SPEND)))
                .hasMessageContaining("needs key.columns");
        assertThatThrownBy(() -> configure(
                        Map.of("path", dir.toString(), "schema", SPEND, "mode", "changelog", "key.columns", "user_id")))
                .hasMessageContaining("changelog mode");
        assertThatThrownBy(() -> configure(Map.of("path", dir.toString(), "schema", SPEND, "key.columns", "nope")))
                .hasMessageContaining("not in the declared schema");
        assertThatThrownBy(() -> configure(Map.of(
                        "path", dir.toString(), "schema", "user_id:INT64?,total:INT64", "key.columns", "user_id")))
                .hasMessageContaining("nullable");
        assertThatThrownBy(() -> configure(Map.of("path", dir.toString(), "schema", "a:WIDGET", "mode", "changelog")))
                .hasMessageContaining("unknown type");
        assertThatThrownBy(() -> configure(Map.of("path", dir.toString(), "schema", "_op:STRING", "mode", "changelog")))
                .hasMessageContaining("_op");
        assertThatThrownBy(() -> configure(
                        Map.of("path", dir.toString(), "schema", SPEND, "mode", "changelog", "transaction.id", "a/b")))
                .hasMessageContaining("transaction.id");
    }

    @Test
    void aTableWhoseSchemaIsNotTheBindingsIsRefusedAtConfiguration() {
        String path = dir.resolve("other").toString();
        new HadoopTables(new Configuration())
                .create(
                        new Schema(Types.NestedField.required(1, "user_id", Types.StringType.get())),
                        PartitionSpec.unpartitioned(),
                        Map.of(),
                        path);
        assertThatThrownBy(() -> configure(Map.of("path", path, "schema", SPEND, "mode", "changelog")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5141");

        String v1 = dir.resolve("v1").toString();
        new HadoopTables(new Configuration())
                .create(
                        new Schema(
                                Types.NestedField.required(1, "user_id", Types.LongType.get()),
                                Types.NestedField.required(2, "total", Types.LongType.get())),
                        PartitionSpec.unpartitioned(),
                        Map.of("format-version", "1"),
                        v1);
        assertThatThrownBy(() -> configure(Map.of("path", v1, "schema", SPEND, "key.columns", "user_id")))
                .hasMessageContaining("format version 1");

        IcebergSinkPlugin absent = new IcebergSinkPlugin();
        absent.configure(new Ctx(
                "a",
                Map.of(
                        "path",
                        dir.resolve("none").toString(),
                        "schema",
                        SPEND,
                        "mode",
                        "changelog",
                        "create",
                        "false")));
        assertThatThrownBy(absent::open).hasMessageContaining("PRV-5141").hasMessageContaining("create is false");
        assertThatThrownBy(() -> absent.write(List.of())).hasMessageContaining("not open");
    }

    private void configure(Map<String, String> options) {
        new IcebergSinkPlugin().configure(new Ctx("s", options));
    }

    private IcebergSinkPlugin sink(String mode, Map<String, String> extra) {
        Map<String, String> options = new HashMap<>(extra);
        options.put("path", dir.resolve("spend").toString());
        options.put("schema", SPEND);
        options.put("mode", mode);
        IcebergSinkPlugin sink = new IcebergSinkPlugin();
        sink.configure(new Ctx("spend_table", options));
        sink.open();
        assertThat(sink.health().isUsable()).isTrue();
        return sink;
    }

    private Path staging() {
        return dir.resolve("spend").resolve("data").resolve("_pravaha").resolve("spend_table");
    }

    private static void cycle(IcebergSinkPlugin sink, long label, RowView... batch) {
        sink.beginTransaction(label);
        sink.write(List.of(batch));
        sink.commit(sink.prepare(label));
    }

    private static List<Record> read(Table table) throws IOException {
        table.refresh();
        List<Record> out = new ArrayList<>();
        try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
            records.forEach(r -> out.add(r.copy()));
        }
        return out;
    }

    private static Map<Long, Long> byKey(List<Record> records) {
        Map<Long, Long> out = new TreeMap<>();
        records.forEach(r -> out.put((Long) r.getField("user_id"), (Long) r.getField("total")));
        assertThat(out).as("one row per key").hasSize(records.size());
        return out;
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
