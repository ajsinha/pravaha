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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Delta source, against real tables written by Kernel.
 *
 * <p>The test that carries the most weight is {@link #aRemovedFileArrivesAsRetractions}: it is the
 * claim that Delta's file-level rewrite semantics already are Z-set deltas, and if that is wrong
 * the connector is wrong in a way that produces plausible answers containing rows the table says
 * were deleted.
 */
class DeltaSourcePluginTest {

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static DeltaSourcePlugin openPlugin(Path table) {
        DeltaSourcePlugin plugin = new DeltaSourcePlugin();
        plugin.configure(new Ctx("delta", Map.of("path", table.toString(), "stream", "people")));
        plugin.open();
        return plugin;
    }

    /** Drains a reader completely, which for a Delta table means "until the table stops moving". */
    private static List<RowView> drain(DeltaSourcePlugin plugin, PartitionReader reader, DeltaCollector collector) {
        while (reader.poll(collector, 128) > 0) {
            // keep going: each poll returns what was ready, not what will ever exist
        }
        return collector.rows();
    }

    @Test
    void readsATableAsASnapshotOfInsertions(@TempDir Path dir) {
        Path table = dir.resolve("people");
        DeltaTableFixture.withRows(table.toString(), 1, 3, "row");

        DeltaSourcePlugin plugin = openPlugin(table);
        StreamSchema schema = plugin.discoverSchemas().get(0);
        assertThat(schema.fieldCount()).isEqualTo(2);
        assertThat(schema.field(0).name()).isEqualTo("id");
        assertThat(schema.field(1).name()).isEqualTo("name");

        try (DeltaCollector collector = new DeltaCollector(schema);
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("people").get(0), SourceOffset.BEGINNING)) {
            List<RowView> rows = drain(plugin, reader, collector);

            assertThat(rows).hasSize(3);
            assertThat(rows.stream().map(r -> r.getLong(0))).containsExactlyInAnyOrder(1L, 2L, 3L);
            assertThat(rows.stream().map(r -> r.getString(1))).contains("row-1");
            assertThat(rows.stream().map(RowView::weight))
                    .as("a snapshot is all insertions")
                    .containsOnly(1L);
        }
    }

    @Test
    void aLaterCommitArrivesAsMoreInsertions(@TempDir Path dir) {
        Path table = dir.resolve("people");
        DeltaTableFixture fixture = DeltaTableFixture.withRows(table.toString(), 1, 2, "row");

        DeltaSourcePlugin plugin = openPlugin(table);
        StreamSchema schema = plugin.discoverSchemas().get(0);
        try (DeltaCollector collector = new DeltaCollector(schema);
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("people").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(plugin, reader, collector)).hasSize(2);

            // The table moves while the reader is live, which is the entire point of a continuous
            // source: nothing is re-read, and the new commit arrives on the next poll.
            fixture.append(List.of(3L, 4L), List.of("row-3", "row-4"));

            List<RowView> rows = drain(plugin, reader, collector);
            assertThat(rows).hasSize(4);
            assertThat(rows.stream().map(r -> r.getLong(0))).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
            assertThat(rows.stream().map(RowView::weight)).containsOnly(1L);
        }
    }

    @Test
    void aRemovedFileArrivesAsRetractions(@TempDir Path dir) {
        // The claim: Delta removes and adds whole files, so a commit's removals weighted -1 and its
        // additions weighted +1 are the Z-set delta. If this is wrong, a maintained view keeps
        // serving rows the table has deleted, forever, with nothing to indicate it.
        Path table = dir.resolve("people");
        DeltaTableFixture fixture = DeltaTableFixture.withRows(table.toString(), 1, 2, "row");
        fixture.append(List.of(3L, 4L), List.of("row-3", "row-4"));

        DeltaSourcePlugin plugin = openPlugin(table);
        StreamSchema schema = plugin.discoverSchemas().get(0);
        try (DeltaCollector collector = new DeltaCollector(schema);
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("people").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(plugin, reader, collector)).hasSize(4);

            fixture.removeFileAddedBy(0);

            List<RowView> rows = drain(plugin, reader, collector);
            assertThat(rows).hasSize(6);
            List<RowView> retractions =
                    rows.stream().filter(r -> r.weight() < 0).toList();
            assertThat(retractions)
                    .as("the removed file's rows come back as retractions")
                    .hasSize(2);
            assertThat(retractions.stream().map(r -> r.getLong(0))).containsExactlyInAnyOrder(1L, 2L);
            assertThat(retractions.stream().map(RowView::weight)).containsOnly(-1L);

            // The net Z-set: rows 1 and 2 cancel to zero, 3 and 4 remain at +1. That sum is the
            // table's actual contents, which is the property the whole approach rests on.
            assertThat(netWeight(rows, 1L)).isZero();
            assertThat(netWeight(rows, 2L)).isZero();
            assertThat(netWeight(rows, 3L)).isEqualTo(1L);
            assertThat(netWeight(rows, 4L)).isEqualTo(1L);
        }
    }

    private static long netWeight(List<RowView> rows, long id) {
        return rows.stream()
                .filter(r -> r.getLong(0) == id)
                .mapToLong(RowView::weight)
                .sum();
    }

    @Test
    void aReaderResumesExactlyWhereItStopped(@TempDir Path dir) {
        Path table = dir.resolve("people");
        DeltaTableFixture.withRows(table.toString(), 1, 6, "row");

        DeltaSourcePlugin plugin = openPlugin(table);
        StreamSchema schema = plugin.discoverSchemas().get(0);
        SourcePartition partition = plugin.partitions("people").get(0);

        SourceOffset checkpoint;
        List<Long> first;
        try (DeltaCollector collector = new DeltaCollector(schema);
                PartitionReader reader = plugin.createReader(partition, SourceOffset.BEGINNING)) {
            assertThat(reader.poll(collector, 2)).isEqualTo(2);
            checkpoint = reader.position();
            first = collector.rows().stream().map(r -> r.getLong(0)).toList();
        }

        try (DeltaCollector collector = new DeltaCollector(schema);
                PartitionReader resumed = plugin.createReader(partition, checkpoint)) {
            List<Long> rest = drain(plugin, resumed, collector).stream()
                    .map(r -> r.getLong(0))
                    .toList();
            assertThat(rest).as("no row is repeated across the resume").doesNotContainAnyElementsOf(first);
            assertThat(rest).hasSize(4);
        }
    }

    @Test
    void anOffsetThisPluginDidNotWriteIsRefused(@TempDir Path dir) {
        Path table = dir.resolve("people");
        DeltaTableFixture.withRows(table.toString(), 1, 1, "row");
        DeltaSourcePlugin plugin = openPlugin(table);

        assertThatThrownBy(
                        () -> plugin.createReader(plugin.partitions("people").get(0), new SourceOffset("kafka:0:17")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("not a Delta offset")
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(DeltaErrors.MALFORMED_OFFSET);
    }

    @Test
    void aDirectoryThatIsNotADeltaTableSaysSo(@TempDir Path dir) {
        DeltaSourcePlugin plugin = new DeltaSourcePlugin();
        plugin.configure(new Ctx("delta", Map.of("path", dir.toString())));

        assertThatThrownBy(plugin::open)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("_delta_log")
                .extracting(e -> ((PravahaException) e).errorCode())
                .isEqualTo(DeltaErrors.TABLE_UNREADABLE);
    }

    @Test
    void capabilitiesSayWhatThisReaderCanAndCannotDo(@TempDir Path dir) {
        Path table = dir.resolve("people");
        DeltaTableFixture.withRows(table.toString(), 1, 1, "row");
        DeltaSourcePlugin plugin = openPlugin(table);

        var capabilities = plugin.capabilities();
        assertThat(capabilities.replayableOffsets()).isTrue();
        assertThat(capabilities.emitsDeletes()).isTrue();
        assertThat(capabilities.emitsBeforeImage())
                .as("a retraction is not a paired before-image, and claiming one would mislead an operator")
                .isFalse();
        assertThat(capabilities.pushdown())
                .as("Kernel takes a predicate, but declaring pushdown before implementing it would make "
                        + "the planner believe filtering had happened")
                .isEmpty();
    }
}
