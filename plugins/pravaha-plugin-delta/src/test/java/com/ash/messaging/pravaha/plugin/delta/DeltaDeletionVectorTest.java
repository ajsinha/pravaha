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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.testkit.tck.ArenaRowCollector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tables with deletion vectors: a row deleted without its file being rewritten.
 *
 * <p>The tables are real. Kernel writes the data and the protocol with the {@code deletionVectors}
 * feature, and {@link DeltaTableFixture#deleteRows} writes each deletion vector in the protocol's
 * own format and commits it the way a {@code DELETE} does. Kernel's reader loads the vectors and
 * checks their size and checksum, so a vector that was not really one would fail here.
 */
class DeltaDeletionVectorTest {

    @TempDir
    Path root;

    @Test
    void aSnapshotLeavesOutTheRowsADeletionVectorDeletes() {
        DeltaTableFixture fixture = table(1, 2, 3, 4);
        fixture.deleteRows(0, 1, 3);
        assertThat(logEntry(fixture, 0))
                .as("Kernel created the table with the deletionVectors reader and writer feature")
                .contains("\"readerFeatures\":[\"deletionVectors\"]")
                .contains("\"delta.enableDeletionVectors\":\"true\"");
        assertThat(logEntry(fixture, 1)).contains("\"deletionVector\":{\"storageType\":\"u\"");

        DeltaSourcePlugin plugin = openPlugin(fixture.path());
        try (ArenaRowCollector collector = new ArenaRowCollector(schemaOf(plugin));
                PartitionReader reader = plugin.createReader(partition(plugin), SourceOffset.BEGINNING)) {
            List<RowView> rows = drain(reader, collector);

            assertThat(rows.stream().map(r -> r.getLong(0)))
                    .as("rows 2 and 4 are deleted by the vector and never emitted")
                    .containsExactlyInAnyOrder(1L, 3L);
            assertThat(rows.stream().map(RowView::weight)).containsOnly(1L);
        }
    }

    @Test
    void aRowNewlyDeletedByADeletionVectorArrivesAsARetraction() {
        DeltaTableFixture fixture = table(1, 2, 3, 4);

        DeltaSourcePlugin plugin = openPlugin(fixture.path());
        try (ArenaRowCollector collector = new ArenaRowCollector(schemaOf(plugin));
                PartitionReader reader = plugin.createReader(partition(plugin), SourceOffset.BEGINNING)) {
            assertThat(drain(reader, collector)).hasSize(4);

            fixture.deleteRows(0, 1);
            List<RowView> afterFirst = List.copyOf(drain(reader, collector));
            assertThat(net(afterFirst.subList(4, afterFirst.size())))
                    .as("the commit's change: row 2 retracted, and nothing else")
                    .isEqualTo(Map.of(2L, -1L));

            // The vector grows: the file's entry now names one that deletes rows 2 and 3.
            fixture.deleteRows(0, 2);
            List<RowView> afterSecond = drain(reader, collector);
            assertThat(net(afterSecond.subList(afterFirst.size(), afterSecond.size())))
                    .as("only the row the new vector adds is retracted; row 2 is not retracted twice")
                    .isEqualTo(Map.of(3L, -1L));

            assertThat(net(afterSecond))
                    .as("the whole stream sums to what the table holds")
                    .isEqualTo(Map.of(1L, 1L, 4L, 1L));
        }
    }

    @Test
    void aResumeInsideAFileWithADeletionVectorRepeatsNothingAndMissesNothing() {
        DeltaTableFixture fixture = table(1, 2, 3, 4, 5, 6);
        fixture.deleteRows(0, 0, 3);

        DeltaSourcePlugin plugin = openPlugin(fixture.path());
        SourceOffset checkpoint;
        List<Long> first;
        try (ArenaRowCollector collector = new ArenaRowCollector(schemaOf(plugin));
                PartitionReader reader = plugin.createReader(partition(plugin), SourceOffset.BEGINNING)) {
            assertThat(reader.poll(collector, 2)).isEqualTo(2);
            checkpoint = reader.position();
            first = collector.rows().stream().map(r -> r.getLong(0)).toList();
        }
        try (ArenaRowCollector collector = new ArenaRowCollector(schemaOf(plugin));
                PartitionReader resumed = plugin.createReader(partition(plugin), checkpoint)) {
            List<Long> rest =
                    drain(resumed, collector).stream().map(r -> r.getLong(0)).toList();

            assertThat(first).containsExactly(2L, 3L);
            assertThat(rest).containsExactly(5L, 6L);
        }
    }

    @Test
    void theSinkRefusesToRewriteATableWhoseFilesCarryDeletionVectors() {
        DeltaTableFixture fixture = table(1, 2);
        fixture.deleteRows(0, 0);
        DeltaSinkPlugin sink = new DeltaSinkPlugin();
        sink.configure(new Ctx(
                "people_table",
                Map.of("path", fixture.path(), "schema", "id:INT64,name:STRING?", "key.columns", "id")));
        sink.open();
        SinkTestRows rows = new SinkTestRows();
        try {
            StreamSchema people = sink.schema().orElseThrow();
            sink.beginTransaction(1);
            sink.write(List.of(rows.row(people, -1, 2L, "row-2")));
            String handle = sink.prepare(1);

            assertThatThrownBy(() -> sink.commit(handle))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-5055")
                    .hasMessageContaining("carry deletion vectors");
            assertThat(DeltaTableReader.read(fixture.path()))
                    .as("the row the vector deletes stays deleted")
                    .containsExactly("2|row-2");
        } finally {
            sink.close();
            rows.close();
        }
    }

    // -----------------------------------------------------------------------------------------

    private DeltaTableFixture table(long... ids) {
        List<Long> idList = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();
        for (long id : ids) {
            idList.add(id);
            names.add("row-" + id);
        }
        DeltaTableFixture fixture = new DeltaTableFixture(root.resolve("people").toString()).withDeletionVectors();
        fixture.append(idList, names);
        return fixture;
    }

    private static String logEntry(DeltaTableFixture fixture, long version) {
        try {
            return java.nio.file.Files.readString(
                    Path.of(fixture.path(), "_delta_log", String.format("%020d.json", version)));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DeltaSourcePlugin openPlugin(String path) {
        DeltaSourcePlugin plugin = new DeltaSourcePlugin();
        plugin.configure(new Ctx("delta", Map.of("path", path, "stream", "people")));
        plugin.open();
        return plugin;
    }

    private static StreamSchema schemaOf(DeltaSourcePlugin plugin) {
        return plugin.discoverSchemas().get(0);
    }

    private static SourcePartition partition(DeltaSourcePlugin plugin) {
        return plugin.partitions("people").get(0);
    }

    private static List<RowView> drain(PartitionReader reader, ArenaRowCollector collector) {
        while (reader.poll(collector, 128) > 0) {
            // each poll returns what was ready
        }
        return collector.rows();
    }

    /** The net weight of each id, leaving out the ids that net to zero. */
    private static Map<Long, Long> net(List<RowView> rows) {
        Map<Long, Long> net = new HashMap<>();
        for (RowView row : rows) {
            net.merge(row.getLong(0), row.weight(), Long::sum);
        }
        net.values().removeIf(w -> w == 0L);
        return net;
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
