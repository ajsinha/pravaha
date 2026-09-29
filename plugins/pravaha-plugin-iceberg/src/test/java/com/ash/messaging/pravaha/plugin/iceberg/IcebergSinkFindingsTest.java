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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.parquet.Parquet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ICE-1 to ICE-4: the sink on the server's Caffeine, upsert mode's bounded memory, a repeated commit
 * detected after another writer committed and expired the sink's snapshot, and the Avro Iceberg
 * writes its manifests with.
 */
class IcebergSinkFindingsTest {

    private static final String SPEND = "user_id:INT64,total:INT64";

    @TempDir
    Path dir;

    private final SinkTestRows rows = new SinkTestRows();

    @AfterEach
    void closeRows() {
        rows.close();
    }

    @Test
    void aRepeatedCommitIsDetectedAfterAnotherWriterCommittedAndExpiredTheSinksSnapshot() throws IOException {
        // ICE-3.
        IcebergSinkPlugin sink = sink("changelog", Map.of());
        StreamSchema schema = sink.schema().orElseThrow();
        sink.beginTransaction(5);
        sink.write(List.of(rows.row(schema, 1, 1L, 10L)));
        String handle = sink.prepare(5);
        sink.commit(handle);

        Table external =
                new HadoopTables(new Configuration()).load(dir.resolve("spend").toString());
        externalAppend(external, 2L, 20L);
        IcebergSinkPlugin restored = sink("changelog", Map.of());
        restored.commit(handle);
        assertThat(read(restored.icebergTable()))
                .as("interleaved: the walk passes the other writer")
                .hasSize(2);

        externalAppend(external, 3L, 30L);
        external.refresh();
        external.expireSnapshots()
                .expireOlderThan(System.currentTimeMillis() + 60_000)
                .retainLast(1)
                .commit();
        restored = sink("changelog", Map.of());
        assertThat(restored.icebergTable().snapshots())
                .as("the sink's snapshot is gone from the table's history")
                .noneMatch(s -> "spend_table".equals(s.summary().get(IcebergSinkTable.TXN_PROPERTY)));
        restored.commit(handle); // a restore that cannot know the first commit arrived
        restored.abortAfter(4);

        assertThat(totals(read(restored.icebergTable())))
                .as("label 5 applied once")
                .isEqualTo(Map.of(1L, 10L, 2L, 20L, 3L, 30L));
        assertThat(restored.icebergTable().properties())
                .containsEntry(IcebergSinkTable.COMMITTED_PREFIX + "spend_table", "5");
    }

    @Test
    void upsertModeRefusesTheKeyPastUpsertMaxKeysByName() throws IOException {
        // ICE-2.
        IcebergSinkPlugin sink = sink("upsert", Map.of("key.columns", "user_id", "upsert.max.keys", "3"));
        StreamSchema schema = sink.schema().orElseThrow();
        sink.beginTransaction(1);
        sink.write(List.of(
                rows.row(schema, 1, 1L, 10L),
                rows.row(schema, 1, 2L, 20L),
                rows.row(schema, 1, 3L, 30L),
                rows.row(schema, -1, 1L, 10L),
                rows.row(schema, 1, 1L, 11L)));
        assertThatThrownBy(() -> sink.write(List.of(rows.row(schema, 1, 4L, 40L))))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("holds 3 distinct keys changed since checkpoint 1 began, upsert.max.keys")
                .hasMessageContaining("mode: changelog")
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(IcebergErrors.SINK_BUFFER_FULL);
        sink.abort(sink.prepare(1));

        sink.beginTransaction(2);
        sink.write(List.of(rows.row(schema, 1, 4L, 40L)));
        sink.commit(sink.prepare(2));
        assertThat(totals(read(sink.icebergTable())))
                .as("the next checkpoint starts with room again")
                .isEqualTo(Map.of(4L, 40L));

        assertThatThrownBy(() -> sink("upsert", Map.of("key.columns", "user_id", "upsert.max.keys", "0")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("upsert.max.keys must be a positive whole number");
        assertThatThrownBy(() -> sink("changelog", Map.of("upsert.max.keys", "10")))
                .hasMessageContaining("changelog mode, which holds nothing in memory");
    }

    /**
     * Every Caffeine member Iceberg 1.2.1's classes call ({@code javap -c} over iceberg-core, which
     * is where Caffeine is used), resolved against the Caffeine on this classpath: a member Caffeine
     * 3 removed or changed would be a {@code NoSuchMethodError} at the first use, not at startup.
     */
    @Test
    void everyCaffeineMemberIcebergCallsResolvesOnCaffeine3() throws ReflectiveOperationException {
        String cache = "com.github.benmanes.caffeine.cache.";
        String[][] members = {
            {"Cache", "cleanUp", "()V"},
            {"Cache", "estimatedSize", "()J"},
            {"Cache", "getIfPresent", "(Ljava/lang/Object;)Ljava/lang/Object;"},
            {"Cache", "get", "(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"},
            {"Cache", "invalidateAll", "(Ljava/lang/Iterable;)V"},
            {"Cache", "invalidateAll", "()V"},
            {"Cache", "invalidate", "(Ljava/lang/Object;)V"},
            {"Cache", "put", "(Ljava/lang/Object;Ljava/lang/Object;)V"},
            {"Cache", "stats", "()Lcom/github/benmanes/caffeine/cache/stats/CacheStats;"},
            {"Caffeine", "build", "()Lcom/github/benmanes/caffeine/cache/Cache;"},
            {
                "Caffeine",
                "build",
                "(Lcom/github/benmanes/caffeine/cache/CacheLoader;)Lcom/github/benmanes/caffeine/cache/LoadingCache;"
            },
            {"Caffeine", "executor", "(Ljava/util/concurrent/Executor;)Lcom/github/benmanes/caffeine/cache/Caffeine;"},
            {
                "Caffeine",
                "expireAfterAccess",
                "(JLjava/util/concurrent/TimeUnit;)Lcom/github/benmanes/caffeine/cache/Caffeine;"
            },
            {"Caffeine", "expireAfterAccess", "(Ljava/time/Duration;)Lcom/github/benmanes/caffeine/cache/Caffeine;"},
            {"Caffeine", "maximumSize", "(J)Lcom/github/benmanes/caffeine/cache/Caffeine;"},
            {"Caffeine", "maximumWeight", "(J)Lcom/github/benmanes/caffeine/cache/Caffeine;"},
            {"Caffeine", "recordStats", "()Lcom/github/benmanes/caffeine/cache/Caffeine;"},
            {
                "Caffeine",
                "removalListener",
                "(Lcom/github/benmanes/caffeine/cache/RemovalListener;)Lcom/github/benmanes/caffeine/cache/Caffeine;"
            },
            {"Caffeine", "softValues", "()Lcom/github/benmanes/caffeine/cache/Caffeine;"},
            {
                "Caffeine",
                "ticker",
                "(Lcom/github/benmanes/caffeine/cache/Ticker;)Lcom/github/benmanes/caffeine/cache/Caffeine;"
            },
            {"Caffeine", "weakKeys", "()Lcom/github/benmanes/caffeine/cache/Caffeine;"},
            {"Caffeine", "weakValues", "()Lcom/github/benmanes/caffeine/cache/Caffeine;"},
            {
                "Caffeine",
                "weigher",
                "(Lcom/github/benmanes/caffeine/cache/Weigher;)Lcom/github/benmanes/caffeine/cache/Caffeine;"
            },
            {"LoadingCache", "get", "(Ljava/lang/Object;)Ljava/lang/Object;"},
            {"RemovalCause", "equals", "(Ljava/lang/Object;)Z"},
        };
        java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.publicLookup();
        ClassLoader loader = getClass().getClassLoader();
        for (String[] member : members) {
            Class<?> owner = Class.forName(cache + member[0], false, loader);
            java.lang.invoke.MethodType type =
                    java.lang.invoke.MethodType.fromMethodDescriptorString(member[2], loader);
            assertThat(lookup.findVirtual(owner, member[1], type))
                    .as(member[0] + "." + member[1])
                    .isNotNull();
        }
        Class<?> caffeine = Class.forName(cache + "Caffeine", false, loader);
        assertThat(lookup.findStatic(caffeine, "newBuilder", java.lang.invoke.MethodType.methodType(caffeine)))
                .isNotNull();
        Class<?> ticker = Class.forName(cache + "Ticker", false, loader);
        assertThat(lookup.findStatic(ticker, "systemTicker", java.lang.invoke.MethodType.methodType(ticker)))
                .isNotNull();
        Class<?> cause = Class.forName(cache + "RemovalCause", false, loader);
        assertThat(lookup.findStaticGetter(cause, "EXPIRED", cause)).isNotNull();
    }

    @Test
    void theSinkWritesAndReadsThroughIcebergsCaffeineCachesOnTheServersCaffeine() throws IOException {
        // ICE-1: the manifest content cache is Iceberg's Caffeine-backed one.
        assertThat(com.github.benmanes.caffeine.cache.Caffeine.class
                        .getPackage()
                        .getImplementationVersion())
                .as("the Caffeine pravaha-server ships")
                .startsWith("3.");
        IcebergSinkPlugin sink = sink("upsert", Map.of("key.columns", "user_id"));
        StreamSchema schema = sink.schema().orElseThrow();
        sink.icebergTable()
                .updateProperties()
                .set("io.manifest.cache-enabled", "true")
                .commit();
        for (long label = 1; label <= 3; label++) {
            sink.beginTransaction(label);
            sink.write(List.of(rows.row(schema, 1, label, label * 10), rows.row(schema, 1, 99L, label)));
            sink.commit(sink.prepare(label));
            assertThat(read(sink.icebergTable())).hasSize((int) label + 1);
        }
        assertThat(totals(read(sink.icebergTable()))).isEqualTo(Map.of(1L, 10L, 2L, 20L, 3L, 30L, 99L, 3L));
    }

    @Test
    void manifestsAreWrittenWithTheFixedAvroAndWithoutTheOldCommonsCompress() throws IOException {
        // ICE-4.
        assertThat(org.apache.avro.Schema.class.getPackage().getImplementationVersion())
                .isEqualTo("1.11.4");
        assertThatThrownBy(() -> Class.forName("org.apache.commons.compress.compressors.CompressorStreamFactory"))
                .as("excluded: Avro reaches it only for bzip2, which Iceberg never writes")
                .isInstanceOf(ClassNotFoundException.class);
        IcebergSinkPlugin sink = sink("changelog", Map.of());
        StreamSchema schema = sink.schema().orElseThrow();
        cycle(sink, 1, rows.row(schema, 1, 1L, 10L));
        cycle(sink, 2, rows.row(schema, 1, 2L, 20L));
        assertThat(sink.icebergTable()
                        .currentSnapshot()
                        .allManifests(sink.icebergTable().io()))
                .isNotEmpty();
        assertThat(read(sink.icebergTable())).hasSize(2);
    }

    private IcebergSinkPlugin sink(String mode, Map<String, String> extra) {
        Map<String, String> options = new HashMap<>(extra);
        options.put("path", dir.resolve("spend").toString());
        options.put("schema", SPEND);
        options.put("mode", mode);
        IcebergSinkPlugin sink = new IcebergSinkPlugin();
        sink.configure(new Ctx("spend_table", options));
        sink.open();
        return sink;
    }

    private static void cycle(IcebergSinkPlugin sink, long label, RowView... batch) {
        sink.beginTransaction(label);
        sink.write(List.of(batch));
        sink.commit(sink.prepare(label));
    }

    /** Another engine appending one row to the table. */
    private static void externalAppend(Table table, long user, long total) throws IOException {
        table.refresh();
        String file = table.location() + "/data/external-" + user + ".parquet";
        DataWriter<Record> writer = Parquet.writeData(table.io().newOutputFile(file))
                .forTable(table)
                .createWriterFunc(GenericParquetWriter::buildWriter)
                .overwrite()
                .build();
        try (writer) {
            GenericRecord record = GenericRecord.create(table.schema());
            record.setField("user_id", user);
            record.setField("total", total);
            if (table.schema().findField("_op") != null) {
                record.setField("_op", "insert");
                record.setField("_weight", 1L);
            }
            writer.write(record);
        }
        DataFile data = writer.toDataFile();
        table.newAppend().appendFile(data).commit();
    }

    private static List<Record> read(Table table) throws IOException {
        table.refresh();
        List<Record> out = new ArrayList<>();
        try (CloseableIterable<Record> records = IcebergGenerics.read(table).build()) {
            records.forEach(r -> out.add(r.copy()));
        }
        return out;
    }

    private static Map<Long, Long> totals(List<Record> records) {
        Map<Long, Long> out = new TreeMap<>();
        records.forEach(r -> out.merge((Long) r.getField("user_id"), (Long) r.getField("total"), Long::sum));
        return out;
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
