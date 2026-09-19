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
package com.ash.messaging.pravaha.plugin.feedfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.example.GroupWriteSupport;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same feed, in Parquet.
 *
 * <p>Parquet is where a CSV feed goes when it gets large -- typed, compressed, a fifth of the bytes
 * -- so the two formats have to be interchangeable to a query. The point of this test is that
 * nothing but {@code format} changes: same directory logic, same ordering, same offsets, same
 * schema declaration.
 *
 * <p>The written file deliberately puts its columns in a <strong>different order</strong> than the
 * declared schema. Partners reorder columns between releases and almost never mention it, and a
 * decoder reading by position rather than by name would silently swap two values of the same type.
 */
class ParquetFeedTest {

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static final MessageType PARQUET_SCHEMA = MessageTypeParser.parseMessageType(
            "message order { required binary name (UTF8); required int64 id; required double amount; }");

    private static void writeParquet(Path file, long... ids) throws IOException {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        GroupWriteSupport.setSchema(PARQUET_SCHEMA, conf);
        SimpleGroupFactory factory = new SimpleGroupFactory(PARQUET_SCHEMA);
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new org.apache.hadoop.fs.Path(file.toUri()))
                .withConf(conf)
                .withType(PARQUET_SCHEMA)
                .build()) {
            for (long id : ids) {
                writer.write(factory.newGroup()
                        .append("name", "row-" + id)
                        .append("id", id)
                        .append("amount", id * 1.5));
            }
        }
    }

    private static FeedFileSourcePlugin open(Path dir) {
        FeedFileSourcePlugin plugin = new FeedFileSourcePlugin();
        plugin.configure(new Ctx(
                "orders",
                Map.of(
                        "dir", dir.toString(),
                        "glob", "*.parquet",
                        "format", "parquet",
                        "schema", "id:INT64,name:STRING,amount:FLOAT64",
                        "stream", "orders",
                        "completion", "immediate")));
        plugin.open();
        return plugin;
    }

    @Test
    void readsParquetDropFilesInOrder(@TempDir Path dir) throws IOException {
        writeParquet(dir.resolve("orders-01.parquet"), 1L, 2L, 3L);
        writeParquet(dir.resolve("orders-02.parquet"), 4L, 5L);

        FeedFileSourcePlugin plugin = open(dir);
        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            while (reader.poll(collector, 64) > 0) {
                // drain
            }
            List<RowView> rows = collector.rows();

            assertThat(rows).hasSize(5);
            assertThat(rows.stream().map(r -> r.getLong(0))).containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(rows.get(0).getString(1))
                    .as("columns are matched by name: the file's order differs from the schema's")
                    .isEqualTo("row-1");
            assertThat(rows.get(4).getDouble(2)).isEqualTo(7.5);
        }
    }

    @Test
    void aDeclaredEventTimeColumnStampsParquetRowsToo(@TempDir Path dir) throws IOException {
        // HLP-6, in the other format: an int64 of nanoseconds read as a TIMESTAMP, named by event.time.
        writeParquet(dir.resolve("orders-01.parquet"), 7L, 9L);
        FeedFileSourcePlugin plugin = new FeedFileSourcePlugin();
        plugin.configure(new Ctx(
                "orders",
                Map.of(
                        "dir", dir.toString(),
                        "glob", "*.parquet",
                        "format", "parquet",
                        "schema", "id:TIMESTAMP,name:STRING,amount:FLOAT64",
                        "stream", "orders",
                        "completion", "immediate",
                        "event.time", "id")));
        plugin.open();
        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            while (reader.poll(collector, 64) > 0) {
                // drain
            }
            assertThat(collector.rows().stream().map(RowView::eventTimestampNanos))
                    .containsExactly(7L, 9L);
        }
    }

    @Test
    void aParquetFeedResumesMidFile(@TempDir Path dir) throws IOException {
        writeParquet(dir.resolve("orders-01.parquet"), 1L, 2L, 3L, 4L);
        FeedFileSourcePlugin plugin = open(dir);

        SourceOffset checkpoint;
        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(reader.poll(collector, 2)).isEqualTo(2);
            checkpoint = reader.position();
        }

        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader resumed =
                        plugin.createReader(plugin.partitions("orders").get(0), checkpoint)) {
            while (resumed.poll(collector, 64) > 0) {
                // drain
            }
            assertThat(collector.rows().stream().map(r -> r.getLong(0))).containsExactly(3L, 4L);
        }
    }

    @Test
    void aFeedFileTakenAwayMidReadIsReportedRatherThanBeingSilence(@TempDir Path dir) throws IOException {
        // E-1/PRV-5064. FEEDFILE_FILE_GONE was declared, documented, and thrown from nowhere. So a
        // feed file rotated or deleted while a reader still sat inside it produced **silence**: the
        // reader returned zero for ever, the query stayed RUNNING, and the rows left in that file
        // were never read and never missed. A source that had stopped and a source with nothing to
        // say looked exactly the same from outside.
        writeParquet(dir.resolve("orders-01.parquet"), 1L, 2L, 3L, 4L);
        FeedFileSourcePlugin plugin = open(dir);

        SourceOffset checkpoint;
        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(reader.poll(collector, 2)).isEqualTo(2);
            checkpoint = reader.position();
        }

        // Whatever rotates the feed takes the file away while the cursor is still two records in,
        // and a later file arrives -- which is what makes the loss demonstrable: the reader is about
        // to move past an unfinished cursor.
        Files.delete(dir.resolve("orders-01.parquet"));
        writeParquet(dir.resolve("orders-02.parquet"), 5L, 6L);

        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader resumed =
                        plugin.createReader(plugin.partitions("orders").get(0), checkpoint)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> resumed.poll(collector, 64))
                    .as("returning zero here is the defect: it is the same answer as 'nothing new yet'")
                    .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                    .hasMessageContaining("PRV-5064")
                    .hasMessageContaining("orders-01.parquet")
                    .hasMessageContaining("skip the rest of it")
                    .hasMessageContaining("orders-02.parquet");
        }
    }

    @Test
    void afinishedFeedFileMayBeRotatedAwayWithoutComplaint(@TempDir Path dir) throws IOException {
        // The property the fix must not cost, and the ordinary case: rotation exists precisely so
        // that files read to the end can be taken away. Only a file the reader is still inside is a
        // loss.
        writeParquet(dir.resolve("orders-01.parquet"), 1L, 2L);
        FeedFileSourcePlugin plugin = open(dir);

        SourceOffset checkpoint;
        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            while (reader.poll(collector, 64) > 0) {
                // drain the file completely
            }
            checkpoint = reader.position();
        }

        Files.delete(dir.resolve("orders-01.parquet"));

        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader resumed =
                        plugin.createReader(plugin.partitions("orders").get(0), checkpoint)) {
            org.assertj.core.api.Assertions.assertThatCode(() -> resumed.poll(collector, 64))
                    .as("a file read to its end and then rotated is rotation working, not a failure")
                    .doesNotThrowAnyException();
        }
    }
}
