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
package com.ash.messaging.pravaha.bindings.ingest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A source that gains partitions while a query reads it: the new partition is read from its first
 * row, its offset enters the checkpoint with the partition it belongs to, and a restore matches every
 * offset back to its own partition -- including across two streams, where the order the pumps were
 * created in no longer says which is which.
 */
class PartitionGrowthTest {

    private static StreamSchema schema(String name) {
        return StreamSchema.builder(name)
                .field("id", Types.int64())
                .field("part", Types.int64())
                .build();
    }

    @Test
    void aPartitionAddedWhileTheQueryRunsIsReadFromItsFirstRow() throws Exception {
        String topic = "grow-" + System.nanoTime();
        GrowingPartitionsPlugin.create(topic, 1);
        GrowingPartitionsPlugin.append(topic, 0, 1);
        GrowingPartitionsPlugin.append(topic, 0, 2);
        PluginSourceFeeds feeds =
                new PluginSourceFeeds().bind(new SourceBinding("s", "growing-partitions", Map.of("topic", topic)));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, schema("s")).feedingFrom(feeds)) {
            RegisteredQuery query =
                    registry.register("grown", "SELECT id, part FROM s", List.of(0), Principal.ANONYMOUS);
            awaitView(views, "SELECT id FROM grown", 2);

            int added = GrowingPartitionsPlugin.grow(topic);
            GrowingPartitionsPlugin.append(topic, added, 3);
            GrowingPartitionsPlugin.append(topic, added, 4);
            GrowingPartitionsPlugin.append(topic, added, 5);
            awaitView(views, "SELECT id FROM grown", 5);

            assertThat(new ViewQuery(views)
                            .execute("SELECT id FROM grown WHERE part = 1")
                            .rows())
                    .as("every row of the new partition, from its first")
                    .hasSize(3);
            assertThat(query.feed().describe()).contains("s gained 1 partition while running [1]");
            assertThat(query.feedStatus().sources())
                    .as("the new partition is listed with the others")
                    .hasSize(2);
            assertThat(GrowingPartitionsPlugin.OPENED_AS_NEW).containsEntry(topic, 1);
        }
    }

    @Test
    void aNewPartitionIsReadFromItsFirstRowEvenWhenTheBindingStartsAtTheLatest() throws Exception {
        String topic = "latest-" + System.nanoTime();
        GrowingPartitionsPlugin.create(topic, 1);
        GrowingPartitionsPlugin.append(topic, 0, 1);
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("s", "growing-partitions", Map.of("topic", topic, "start.from", "latest")));

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, schema("s")).feedingFrom(feeds)) {
            registry.register("latest", "SELECT id, part FROM s", List.of(0), Principal.ANONYMOUS);
            GrowingPartitionsPlugin.append(topic, 0, 2);
            awaitView(views, "SELECT id FROM latest", 1);

            int added = GrowingPartitionsPlugin.grow(topic);
            GrowingPartitionsPlugin.append(topic, added, 10);
            GrowingPartitionsPlugin.append(topic, added, 11);
            awaitView(views, "SELECT id FROM latest", 3);
            Thread.sleep(200);

            assertThat(new ViewQuery(views).execute("SELECT id FROM latest").rows())
                    .extracting(row -> ((Number) row[0]).longValue())
                    .as("history before registration skipped; the new partition read whole")
                    .containsExactlyInAnyOrder(2L, 10L, 11L);
        }
    }

    @Test
    void aRestoreMatchesEveryOffsetToItsOwnPartitionAcrossTwoStreams(@TempDir Path dir) throws Exception {
        String left = "left-" + System.nanoTime();
        String right = "right-" + System.nanoTime();
        GrowingPartitionsPlugin.create(left, 1);
        GrowingPartitionsPlugin.create(right, 1);
        for (long id = 1; id <= 3; id++) {
            GrowingPartitionsPlugin.append(left, 0, id);
            GrowingPartitionsPlugin.append(right, 0, id);
        }
        java.util.function.Supplier<PluginSourceFeeds> feeds = () -> new PluginSourceFeeds()
                .bind(new SourceBinding("a", "growing-partitions", Map.of("topic", left)))
                .bind(new SourceBinding("b", "growing-partitions", Map.of("topic", right)));
        Path checkpoints = dir.resolve("checkpoints");
        com.ash.messaging.pravaha.common.config.Configuration rarely =
                com.ash.messaging.pravaha.common.config.Configuration.builder()
                        .set("pravaha.checkpoint.interval", "1h")
                        .build();
        String sql = "SELECT a.id AS id, a.part AS part FROM a JOIN b ON a.id = b.id";

        ViewCatalog first = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(first, schema("a"), schema("b"))
                .feedingFrom(feeds.get())
                .checkpointingTo(checkpoints, rarely)) {
            RegisteredQuery query = registry.register("joined", sql, List.of(0), Principal.ANONYMOUS);
            awaitView(first, "SELECT id FROM joined", 3);

            int added = GrowingPartitionsPlugin.grow(left);
            for (long id = 4; id <= 5; id++) {
                GrowingPartitionsPlugin.append(left, added, id);
                GrowingPartitionsPlugin.append(right, 0, id);
            }
            awaitView(first, "SELECT id FROM joined", 5);

            Checkpoint checkpoint = checkpointerOf(query).checkpointNow();
            assertThat(checkpoint.offsets())
                    .as("the new partition's pump came last, after b's, and says whose offset it holds")
                    .containsEntry("partition-0", left + "/0@3")
                    .containsEntry("partition-1", right + "/0@5")
                    .containsEntry("partition-2", left + "/1@2")
                    .containsEntry("source-of-partition-0", "0/a")
                    .containsEntry("source-of-partition-1", "0/b")
                    .containsEntry("source-of-partition-2", "1/a");
        }

        // While nothing is reading: more rows, and a partition the checkpoint never saw.
        GrowingPartitionsPlugin.append(left, 1, 6);
        GrowingPartitionsPlugin.append(right, 0, 6);
        int later = GrowingPartitionsPlugin.grow(left);
        GrowingPartitionsPlugin.append(left, later, 7);
        GrowingPartitionsPlugin.append(right, 0, 7);

        ViewCatalog second = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(second, schema("a"), schema("b"))
                .feedingFrom(feeds.get())
                .checkpointingTo(checkpoints, rarely)) {
            RegisteredQuery restarted = registry.register("joined", sql, List.of(0), Principal.ANONYMOUS);
            awaitView(second, "SELECT id FROM joined", 7);
            Thread.sleep(300);

            List<Long> ids = new ArrayList<>();
            for (Object[] row :
                    new ViewQuery(second).execute("SELECT id FROM joined").rows()) {
                ids.add(((Number) row[0]).longValue());
            }
            assertThat(ids)
                    .as("restored at five, each partition resumed at its own offset, and the partition "
                            + "added while the node was down read from its first row: nothing twice")
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L);
            assertThat(restarted.feed().describe()).doesNotContain("stopped");
        }
    }

    @Test
    void aRefreshThatFailsIsRetriedAndSaidSo() {
        List<PartitionReader> opened = new ArrayList<>();
        int[] asks = {0};
        com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin flaky = new GrowingPartitionsPlugin() {};
        PartitionGrowth growth = new PartitionGrowth(
                "s",
                new FlakyPartitions(asks),
                ReadRequest.NOTHING,
                List.of(0),
                Duration.ofMillis(1),
                (index, reader, resources) -> {
                    opened.add(reader);
                    return null;
                },
                0);

        assertThat(growth.poll(-1)).as("not yet time").isEmpty();
        assertThat(growth.poll(2_000_000)).isEmpty();
        assertThat(growth.describe()).contains("refreshing the partitions of s failed, retrying: brokers away");
        assertThat(growth.poll(4_000_000)).hasSize(1);
        assertThat(growth.describe())
                .isEqualTo("s gained 1 partition while running [1]")
                .doesNotContain("failed");
        assertThat(opened).hasSize(1);
        assertThat(asks[0]).isEqualTo(2);
        assertThat(flaky.partitionRefreshInterval()).isPositive();
    }

    @Test
    void aSourceWhosePartitionsNeverChangeIsNotWatched() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PartitionGrowth(
                        "s", new FlakyPartitions(new int[1]), ReadRequest.NOTHING, List.of(), Duration.ZERO, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCheckpointWithoutPartitionNamesIsReadInOrderAndAMissingOffsetOnARestoreIsANewPartition() {
        ResumePositions old = ResumePositions.of(Map.of("partition-0", "x", "partition-1", "y"));
        assertThat(old.tokenFor("a", 0)).isEqualTo("x");
        assertThat(old.tokenFor("b", 0)).isEqualTo("y");
        String none = old.tokenFor("a", 1);
        assertThat(none).isNull();
        assertThat(old.isNew(none)).as("a restore with nothing recorded for it").isTrue();

        ResumePositions fresh = ResumePositions.of(Map.of());
        assertThat(fresh.isNew(fresh.tokenFor("a", 0)))
                .as("a fresh registration opens nothing as new")
                .isFalse();
        assertThat(ResumePositions.of(null).tokenFor("a", 0)).isNull();

        ResumePositions named = ResumePositions.of(Map.of(
                "partition-0", "a0",
                "partition-1", "b0",
                "partition-2", "a1",
                "source-of-partition-0", "0/a",
                "source-of-partition-1", "0/b",
                "source-of-partition-2", "1/a"));
        assertThat(named.tokenFor("a", 0)).isEqualTo("a0");
        assertThat(named.tokenFor("a", 1)).as("by name, not by order").isEqualTo("a1");
        assertThat(named.tokenFor("b", 0)).isEqualTo("b0");
    }

    /** Fails its first answer, then has gained a partition. */
    private static final class FlakyPartitions extends GrowingPartitionsPlugin {

        private final int[] asks;

        FlakyPartitions(int[] asks) {
            this.asks = asks;
        }

        @Override
        public List<SourcePartition> partitions(String streamName) {
            asks[0]++;
            if (asks[0] == 1) {
                throw new IllegalStateException("brokers away");
            }
            return List.of(new SourcePartition(streamName, 0, Map.of()), new SourcePartition(streamName, 1, Map.of()));
        }

        @Override
        public PartitionReader createReaderForNewPartition(SourcePartition partition, ReadRequest request) {
            return createReader(partition, SourceOffset.BEGINNING);
        }

        @Override
        public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
            return new PartitionReader() {
                @Override
                public int poll(RecordSink sink, int maxRecords) {
                    return 0;
                }

                @Override
                public SourceOffset position() {
                    return SourceOffset.BEGINNING;
                }

                @Override
                public void pause() {}

                @Override
                public void resume() {}

                @Override
                public void close() {}
            };
        }
    }

    private static void awaitView(ViewCatalog views, String sql, int expected) throws InterruptedException {
        ViewQuery reader = new ViewQuery(views);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int size = -1;
        while (System.nanoTime() < deadline) {
            size = reader.execute(sql).size();
            if (size >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(size).as("rows in the view after twenty seconds").isGreaterThanOrEqualTo(expected);
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) {
        try {
            java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
            field.setAccessible(true);
            return (PeriodicCheckpointer) field.get(query);
        } catch (ReflectiveOperationException e) {
            throw new LinkageError(e.getMessage(), e);
        }
    }
}
