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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The feed-file source.
 *
 * <p>The tests worth reading first are {@link #anIncompleteFileIsNotReadUntilItsMarkerArrives} and
 * {@link #capabilitiesFollowTheConfiguration}. The first is the failure this connector exists to
 * prevent -- a half-transferred file decodes perfectly, it is simply short, and no schema check
 * catches it. The second is the honesty rule: the same plugin cannot be exactly-once in every
 * configuration, and saying so is the difference between a guarantee and a slogan.
 */
class FeedFileSourcePluginTest {

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    private static final String SCHEMA = "id:INT64,name:STRING,amount:FLOAT64?";

    private static FeedFileSourcePlugin open(Path dir, Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(
                Map.of("dir", dir.toString(), "schema", SCHEMA, "stream", "orders", "completion", "immediate"));
        config.putAll(extra);
        FeedFileSourcePlugin plugin = new FeedFileSourcePlugin();
        plugin.configure(new Ctx("orders", config));
        plugin.open();
        return plugin;
    }

    private static void writeCsv(Path dir, String name, String... lines) throws IOException {
        Files.writeString(dir.resolve(name), String.join("\n", lines) + "\n");
    }

    private static List<RowView> drain(FeedFileSourcePlugin plugin, PartitionReader reader, FeedCollector collector) {
        while (reader.poll(collector, 64) > 0) {
            // each poll returns what is ready, not what will ever exist
        }
        return collector.rows();
    }

    @Test
    void readsFilesInNameOrderAcrossTheWholeDirectory(@TempDir Path dir) throws IOException {
        // Written out of order on purpose: the order files are *created* in is not the order they
        // must be *applied* in, and a feed of daily deltas applied backwards is a wrong answer with
        // no error anywhere.
        writeCsv(dir, "orders-03.csv", "5,eve,50.0");
        writeCsv(dir, "orders-01.csv", "1,ann,10.5", "2,bob,20.0");
        writeCsv(dir, "orders-02.csv", "3,cat,30.25", "4,dan,");

        FeedFileSourcePlugin plugin = open(dir, Map.of());
        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            List<RowView> rows = drain(plugin, reader, collector);

            assertThat(rows).hasSize(5);
            assertThat(rows.stream().map(r -> r.getLong(0))).containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(rows.get(0).getString(1)).isEqualTo("ann");
            assertThat(rows.get(3).isNull(2))
                    .as("an empty field is null, not zero")
                    .isTrue();
            assertThat(rows.stream().map(RowView::weight)).containsOnly(1L);
        }
    }

    @Test
    void anIncompleteFileIsNotReadUntilItsMarkerArrives(@TempDir Path dir) throws IOException {
        // A partner streaming a large file over SFTP lets a reader see its first megabyte, and a
        // truncated CSV decodes perfectly -- it is simply short. Only the writer knows when it is
        // done, which is what a completion marker is.
        writeCsv(dir, "orders-01.csv", "1,ann,10.5");
        FeedFileSourcePlugin plugin = open(dir, Map.of("completion", "marker"));

        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(reader.poll(collector, 64))
                    .as("no marker, no read: the file may still be being written")
                    .isZero();

            Files.writeString(dir.resolve("orders-01.csv.done"), "");
            assertThat(drain(plugin, reader, collector)).hasSize(1);
        }
    }

    @Test
    void capabilitiesFollowTheConfiguration(@TempDir Path dir) {
        assertThat(open(dir, Map.of("completion", "marker")).capabilities()).satisfies(c -> {
            assertThat(c.replayableOffsets()).isTrue();
            assertThat(c.guarantee()).isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
        });

        // Stability is a heuristic: a stalled transfer looks exactly like a finished one.
        assertThat(open(dir, Map.of("completion", "stable")).capabilities().guarantee())
                .isEqualTo(DeliveryGuarantee.AT_LEAST_ONCE);

        // Archiving moves the file out of the feed, so an offset naming it cannot be replayed.
        assertThat(open(
                                dir,
                                Map.of(
                                        "completion",
                                        "marker",
                                        "archive.dir",
                                        dir.resolve("done").toString()))
                        .capabilities()
                        .replayableOffsets())
                .isFalse();
    }

    @Test
    void aReaderResumesMidFileWithoutRepeatingOrLosingARecord(@TempDir Path dir) throws IOException {
        writeCsv(dir, "orders-01.csv", "1,a,1.0", "2,b,2.0", "3,c,3.0");
        writeCsv(dir, "orders-02.csv", "4,d,4.0", "5,e,5.0");
        FeedFileSourcePlugin plugin = open(dir, Map.of());

        SourceOffset checkpoint;
        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(reader.poll(collector, 2)).isEqualTo(2);
            checkpoint = reader.position();
            assertThat(collector.rows().stream().map(r -> r.getLong(0))).containsExactly(1L, 2L);
        }

        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader resumed =
                        plugin.createReader(plugin.partitions("orders").get(0), checkpoint)) {
            assertThat(drain(plugin, resumed, collector).stream().map(r -> r.getLong(0)))
                    .as("resume picks up mid-file and continues into the next one")
                    .containsExactly(3L, 4L, 5L);
        }
    }

    @Test
    void aFileArrivingWhileTheFeedIsLiveIsPickedUp(@TempDir Path dir) throws IOException {
        writeCsv(dir, "orders-01.csv", "1,a,1.0");
        FeedFileSourcePlugin plugin = open(dir, Map.of());

        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(plugin, reader, collector)).hasSize(1);

            writeCsv(dir, "orders-02.csv", "2,b,2.0");
            assertThat(drain(plugin, reader, collector).stream().map(r -> r.getLong(0)))
                    .containsExactly(1L, 2L);
        }
    }

    @Test
    void quotedFieldsWithCommasAndDoubledQuotesSurvive(@TempDir Path dir) throws IOException {
        // The three cases a split-on-comma implementation fails, all of which arrive eventually.
        writeCsv(dir, "orders-01.csv", "1,\"Smith, Ann\",10.5", "2,\"say \"\"hi\"\"\",20.0");
        FeedFileSourcePlugin plugin = open(dir, Map.of());

        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            List<RowView> rows = drain(plugin, reader, collector);
            assertThat(rows.get(0).getString(1)).isEqualTo("Smith, Ann");
            assertThat(rows.get(1).getString(1)).isEqualTo("say \"hi\"");
        }
    }

    @Test
    void aPoisonFileIsQuarantinedRatherThanStoppingTheFeed(@TempDir Path dir) throws IOException {
        Path quarantine = dir.resolve("quarantine");
        writeCsv(dir, "orders-01.csv", "1,a,1.0");
        writeCsv(dir, "orders-02.csv", "not-a-number,b,2.0");
        writeCsv(dir, "orders-03.csv", "3,c,3.0");

        FeedFileSourcePlugin plugin = open(dir, Map.of("quarantine.dir", quarantine.toString()));
        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            List<RowView> rows = drain(plugin, reader, collector);

            assertThat(rows.stream().map(r -> r.getLong(0)))
                    .as("one bad file from one partner must not stop a feed carrying nine others")
                    .containsExactly(1L, 3L);
            assertThat(quarantine.resolve("orders-02.csv")).exists();
        }
    }

    @Test
    void withoutAQuarantineDirectoryAPoisonFileStopsTheFeedAndSaysWhy(@TempDir Path dir) throws IOException {
        // Failing is the right default: dropping the file silently would be worse than stopping.
        writeCsv(dir, "orders-01.csv", "oops,a,1.0");
        FeedFileSourcePlugin plugin = open(dir, Map.of());

        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThatThrownBy(() -> reader.poll(collector, 64))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("orders-01.csv")
                    .hasMessageContaining("line 1")
                    .extracting(e -> ((PravahaException) e).errorCode())
                    .isEqualTo(FeedFileErrors.DECODE_FAILED);
        }
    }

    @Test
    void processedFilesCanBeArchived(@TempDir Path dir) throws IOException {
        Path archive = dir.resolve("archive");
        writeCsv(dir, "orders-01.csv", "1,a,1.0");
        FeedFileSourcePlugin plugin = open(dir, Map.of("archive.dir", archive.toString()));

        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            assertThat(drain(plugin, reader, collector)).hasSize(1);
            assertThat(archive.resolve("orders-01.csv")).exists();
            assertThat(dir.resolve("orders-01.csv")).doesNotExist();
        }
    }

    @Test
    void aMisconfiguredFeedSaysWhatToFix(@TempDir Path dir) {
        assertThatThrownBy(() -> open(dir, Map.of("completion", "whenever")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("marker, stable or immediate");
        assertThatThrownBy(() -> open(dir, Map.of("format", "avro")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("csv or parquet");
        assertThatThrownBy(() -> open(dir, Map.of("delimiter", "||")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("single character");
    }

    @Test
    void aDeclaredEventTimeColumnStampsEachRowWithItsOwnTime(@TempDir Path dir) throws IOException {
        // HLP-6. Every row was stamped with event time zero whatever the file held, so the watermark
        // never left 1970 and an event-time window over a feed never closed.
        Files.writeString(dir.resolve("orders-01.csv"), "1,1700000000000000000\n2,1700000005000000000\n3,\n");
        Map<String, String> config = new HashMap<>(Map.of(
                "dir", dir.toString(),
                "schema", "id:INT64,ts:TIMESTAMP?",
                "stream", "orders",
                "completion", "immediate",
                "event.time", "ts"));
        FeedFileSourcePlugin plugin = new FeedFileSourcePlugin();
        plugin.configure(new Ctx("orders", config));
        plugin.open();
        try (FeedCollector collector = new FeedCollector(plugin.schema());
                PartitionReader reader =
                        plugin.createReader(plugin.partitions("orders").get(0), SourceOffset.BEGINNING)) {
            List<RowView> rows = drain(plugin, reader, collector);

            assertThat(rows.stream().map(RowView::eventTimestampNanos))
                    .as("the column's value, and zero where the row has none")
                    .containsExactly(1_700_000_000_000_000_000L, 1_700_000_005_000_000_000L, 0L);
        }
    }

    @Test
    void anEventTimeNamingNoColumnIsRefused(@TempDir Path dir) {
        assertThatThrownBy(() -> open(dir, Map.of("event.time", "nope")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("event.time")
                .hasMessageContaining("nope");
        assertThatThrownBy(() -> open(dir, Map.of("event.time", "name")))
                .as("a text column cannot be an event time")
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("name");
    }
}
