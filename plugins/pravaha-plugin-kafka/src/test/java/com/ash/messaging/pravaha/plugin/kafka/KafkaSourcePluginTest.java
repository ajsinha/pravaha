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
package com.ash.messaging.pravaha.plugin.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The source against an in-memory topic ({@link FakeTopic}): partitions, exact offsets, the lane's
 * non-blocking poll, dead letters, the refusals, and what is committed to a group and when. The broker
 * tests prove the same against a real Kafka, transactions included.
 */
class KafkaSourcePluginTest {

    private static final String SCHEMA = "user_id:STRING,amount:INT64";

    private final FakeTopic topic = new FakeTopic("txn", 3);

    @SuppressWarnings("NullAway.Init") // set by the test that uses it; @AfterEach closes what was set
    private KafkaSourcePlugin plugin;

    @AfterEach
    void tearDown() {
        if (plugin != null) {
            plugin.close();
        }
    }

    @Test
    void thePartitionsAreTheTopicsInPartitionOrder() {
        plugin = open(Map.of());

        List<SourcePartition> partitions = plugin.partitions("txn");

        assertThat(partitions).extracting(SourcePartition::index).containsExactly(0, 1, 2);
        assertThat(partitions.get(2).properties()).containsEntry("topic", "txn").containsEntry("partition", "2");
        assertThat(plugin.name()).isEqualTo("kafka");
    }

    @Test
    void aReaderDeliversItsPartitionFromTheStartAndItsPositionIsTheNextOffset() {
        topic.append(1, "u1", "{\"user_id\":\"u1\",\"amount\":300}");
        topic.append(1, "u2", "{\"user_id\":\"u2\",\"amount\":50}");
        topic.append(0, "u9", "{\"user_id\":\"u9\",\"amount\":9}");
        plugin = open(Map.of());

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(1), SourceOffset.BEGINNING)) {
            assertThat(reader.position()).isEqualTo(new SourceOffset("txn/1@0"));
            assertThat(reader.poll(rows, 64)).isEqualTo(2);

            assertThat(rows.described()).containsExactly("[u1, 300, @1]", "[u2, 50, @1]");
            assertThat(rows.rows().get(0).sequence()).as("the Kafka offset").isZero();
            assertThat(rows.rows().get(1).eventTimestampNanos())
                    .as("no event.time: the record's timestamp, 2000 ms")
                    .isEqualTo(2_000_000_000L);
            assertThat(reader.position()).isEqualTo(new SourceOffset("txn/1@2"));
        }
    }

    @Test
    void pollNeverWaitsAndMaxRecordsIsHonoured() {
        for (int i = 0; i < 5; i++) {
            topic.append(0, "k", "{\"user_id\":\"u" + i + "\",\"amount\":" + i + "}");
        }
        plugin = open(Map.of());

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            assertThat(reader.poll(rows, 2)).isEqualTo(2);
            assertThat(reader.position()).isEqualTo(new SourceOffset("txn/0@2"));
            assertThat(reader.poll(rows, 64)).isEqualTo(3);

            long start = System.nanoTime();
            assertThat(reader.poll(rows, 64)).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(50));

            topic.append(0, "k", "{\"user_id\":\"late\",\"amount\":6}");
            awaitRows(reader, rows, 6);
            assertThat(rows.described()).last().isEqualTo("[late, 6, @1]");
        }
    }

    @Test
    void resumingFromAPositionDeliversExactlyWhatFollowsIt() {
        for (int i = 0; i < 6; i++) {
            topic.append(0, "k", "{\"user_id\":\"u" + i + "\",\"amount\":" + i + "}");
        }
        plugin = open(Map.of());
        SourceOffset midpoint;
        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            reader.poll(rows, 4);
            midpoint = reader.position();
        }

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), midpoint)) {
            reader.poll(rows, 64);
            assertThat(rows.described()).containsExactly("[u4, 4, @1]", "[u5, 5, @1]");
        }
    }

    @Test
    void offsetsWithNoRecordAreSteppedOverOnlyOnceEverythingBeforeThemIsHandedOver() {
        topic.append(0, "k", "{\"user_id\":\"u1\",\"amount\":1}");
        topic.gap(0, 1); // a transaction's commit marker
        topic.append(0, "k", "{\"user_id\":\"u2\",\"amount\":2}");
        topic.gap(0, 3); // an aborted transaction's two records and its abort marker
        plugin = open(Map.of());

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            assertThat(reader.poll(rows, 1)).isEqualTo(1);
            assertThat(reader.position())
                    .as("the marker lies after what is still queued")
                    .isEqualTo(offset(0, 1));
            reader.poll(rows, 64);
            assertThat(reader.position())
                    .as("past the trailing aborted records: nothing there will ever be delivered")
                    .isEqualTo(offset(0, 6));
        }
    }

    @Test
    void aPartitionAddedToTheTopicIsListedAndItsReaderStartsAtItsFirstRecordEvenFromLatest() {
        topic.append(0, "k", "{\"user_id\":\"old\",\"amount\":1}");
        plugin = open(Map.of("start.from", "latest"));
        assertThat(plugin.partitionRefreshInterval()).as("the default refresh").isEqualTo(Duration.ofSeconds(30));
        assertThat(plugin.partitions("txn")).hasSize(3);

        int added = topic.addPartition();
        topic.append(added, "k", "{\"user_id\":\"first\",\"amount\":1}");
        topic.append(added, "k", "{\"user_id\":\"second\",\"amount\":2}");
        assertThat(plugin.partitions("txn")).extracting(SourcePartition::index).containsExactly(0, 1, 2, 3);

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader latest = plugin.createReader(partition(0), null);
                PartitionReader added3 = plugin.createReaderForNewPartition(
                        partition(added), com.ash.messaging.pravaha.api.plugin.ReadRequest.NOTHING)) {
            assertThat(latest.position())
                    .as("a partition that was there: start.from latest skips its history")
                    .isEqualTo(offset(0, 1));
            awaitRows(added3, rows, 2);
            assertThat(rows.described())
                    .as("a partition that was not there has no history to skip")
                    .containsExactly("[first, 1, @1]", "[second, 2, @1]");
        }
    }

    @Test
    void thePartitionRefreshIsConfiguredAndNeverFasterThanASecond() {
        plugin = open(Map.of("partitions.refresh", "5m"));
        assertThat(plugin.partitionRefreshInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThatThrownBy(() -> configured(Map.of("partitions.refresh", "500ms")))
                .hasMessageContaining("PRV-5100")
                .hasMessageContaining("partitions.refresh must be at least 1s");
        assertThatThrownBy(() -> configured(Map.of("partitions.refresh", "soon")))
                .hasMessageContaining("partitions.refresh must be a duration");
    }

    @Test
    void startFromLatestResolvesToAConcreteOffsetSoACheckpointBeforeAnyRecordStillMeansSomething() {
        topic.append(0, "k", "{\"user_id\":\"old\",\"amount\":1}");
        plugin = open(Map.of("start.from", "latest"));

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), SourceOffset.BEGINNING)) {
            assertThat(reader.position()).isEqualTo(offset(0, 1));
            topic.append(0, "k", "{\"user_id\":\"new\",\"amount\":2}");
            awaitRows(reader, rows, 1);
            assertThat(rows.described()).containsExactly("[new, 2, @1]");
        }
    }

    @Test
    void pausedReadersProduceNothingAndPauseTheConsumer() {
        topic.append(0, "k", "{\"user_id\":\"u1\",\"amount\":1}");
        plugin = open(Map.of());

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            reader.pause();
            assertThat(reader.poll(rows, 64)).isZero();
            awaitTrue(
                    () -> !topic.consumers
                            .get(topic.consumers.size() - 1)
                            .paused()
                            .isEmpty(),
                    "consumer paused");
            reader.resume();
            assertThat(reader.poll(rows, 64)).isEqualTo(1);
        }
    }

    @Test
    void aRejectedRecordCountsAgainstMaxRecordsAndMovesThePosition() {
        // REPL-2's contract (PartitionReader#poll): maxRecords bounds records consumed, delivered
        // or rejected, so a backfill reading history one record at a time cannot be carried past
        // the running version's position by a rejection inside the same poll.
        topic.append(0, "k", "{\"user_id\":\"u1\",\"amount\":1}");
        topic.append(0, "k", "{\"user_id\":\"u2\",\"amount\":\"lots\"}");
        topic.append(0, "k", "{\"user_id\":\"u3\",\"amount\":3}");
        plugin = open(Map.of());

        try (Collected rows = new Collected(plugin.schema(), true);
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            assertThat(reader.poll(rows, 1)).isEqualTo(1);
            assertThat(reader.position()).isEqualTo(offset(0, 1));
            assertThat(reader.poll(rows, 1))
                    .as("the record at 1 is consumed, and rejected")
                    .isZero();
            assertThat(reader.position()).as("and the position is past it").isEqualTo(offset(0, 2));
            assertThat(rows.rejections).hasSize(1);
            assertThat(reader.poll(rows, 1))
                    .as("the record at 2 is the next poll's")
                    .isEqualTo(1);
            assertThat(reader.position()).isEqualTo(offset(0, 3));
        }
    }

    @Test
    void anUndecodableRecordIsADeadLetterWhenThereIsAQueueAndStopsTheReaderWhenThereIsNot() {
        topic.append(0, "k", "{\"user_id\":\"u1\",\"amount\":1}");
        topic.append(0, "k", "{\"user_id\":\"u2\",\"amount\":\"lots\"}");
        topic.append(0, "k", "{\"user_id\":\"u3\",\"amount\":3}");
        plugin = open(Map.of());

        try (Collected rows = new Collected(plugin.schema(), true);
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            assertThat(reader.poll(rows, 64)).isEqualTo(2);
            assertThat(rows.rejections).hasSize(1);
            assertThat(rows.rejections.get(0).at())
                    .as("replayable: topic/partition@offset")
                    .isEqualTo("txn/0@1");
            assertThat(rows.rejections.get(0).raw()).contains("lots");
            assertThat(rows.rejections.get(0).reason()).contains("'amount' is INT64");
            assertThat(reader.position()).isEqualTo(offset(0, 3));
        }

        try (Collected rows = new Collected(plugin.schema(), false);
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            assertThatThrownBy(() -> reader.poll(rows, 64))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-5105")
                    .hasMessageContaining("txn/0@1");
            assertThat(rows.described()).containsExactly("[u1, 1, @1]");
            assertThat(reader.position())
                    .as("not past the record nobody set aside, so a restart meets it again")
                    .isEqualTo(offset(0, 1));
            assertThatThrownBy(() -> reader.poll(rows, 64)).hasMessageContaining("PRV-5105");
        }
    }

    @Test
    void aTombstoneIsRefusedUnlessTheBindingSaysToReadInsertionsOnly() {
        topic.append(0, "{\"user_id\":\"u1\"}", "{\"user_id\":\"u1\",\"amount\":1}");
        topic.append(0, "{\"user_id\":\"u1\"}", null);
        topic.append(0, "{\"user_id\":\"u2\"}", "{\"user_id\":\"u2\",\"amount\":2}");

        plugin = open(Map.of());
        try (Collected rows = new Collected(plugin.schema(), true);
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            reader.poll(rows, 64);
            assertThat(rows.rejections).singleElement().satisfies(rejection -> {
                assertThat(rejection.reason()).contains("tombstone");
                assertThat(rejection.raw())
                        .as("the key, since there is no value")
                        .isEqualTo("{\"user_id\":\"u1\"}");
            });
        }
        plugin.close();

        plugin = open(Map.of("tombstone", "skip"));
        try (Collected rows = new Collected(plugin.schema(), false);
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            reader.poll(rows, 64);
            assertThat(rows.described()).containsExactly("[u1, 1, @1]", "[u2, 2, @1]");
            assertThat(reader.position()).isEqualTo(offset(0, 3));
        }
    }

    @Test
    void theChangelogFormCarriesItsWeightsAndDeclaresDeletes() {
        topic.append(0, "k", "{\"op\":\"insert\",\"weight\":1,\"row\":{\"user_id\":\"u1\",\"amount\":300}}");
        topic.append(0, "k", "{\"op\":\"delete\",\"weight\":-1,\"row\":{\"user_id\":\"u1\",\"amount\":300}}");
        plugin = open(Map.of("format", "changelog"));

        assertThat(plugin.capabilities().emitsDeletes()).isTrue();
        assertThat(plugin.capabilities().emitsBeforeImage()).isTrue();
        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            reader.poll(rows, 64);
            assertThat(rows.described()).containsExactly("[u1, 300, @1]", "[u1, 300, @-1]");
        }
    }

    @Test
    void theCapabilitiesAreExactlyOnceReplayableAndOrderedWithoutDeletesForJson() {
        plugin = open(Map.of());

        assertThat(plugin.capabilities().guarantee()).isEqualTo(DeliveryGuarantee.EXACTLY_ONCE);
        assertThat(plugin.capabilities().replayableOffsets()).isTrue();
        assertThat(plugin.capabilities().orderedWithinPartition()).isTrue();
        assertThat(plugin.capabilities().emitsDeletes()).isFalse();
        assertThat(plugin.capabilities().pushdown()).isEmpty();
    }

    @Test
    void aResumePointRetentionHasDeletedIsRefusedNotSkippedForward() {
        for (int i = 0; i < 4; i++) {
            topic.append(0, "k", "{\"user_id\":\"u" + i + "\",\"amount\":" + i + "}");
        }
        topic.deleteBefore(0, 3);
        plugin = open(Map.of());

        assertThatThrownBy(() -> plugin.createReader(partition(0), offset(0, 1)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5106")
                .hasMessageContaining("retention deleted 2 records");
        assertThatThrownBy(() -> plugin.createReader(partition(0), offset(0, 9)))
                .hasMessageContaining("PRV-5106")
                .hasMessageContaining("past the end of the partition (4)");
    }

    @Test
    void retentionOvertakingARunningReaderStopsItAfterWhatItHadRead() {
        topic.append(0, "k", "{\"user_id\":\"u0\",\"amount\":0}");
        plugin = open(Map.of("buffer.records", "1"));

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            reader.pause();
            for (int i = 1; i < 5; i++) {
                topic.append(0, "k", "{\"user_id\":\"u" + i + "\",\"amount\":" + i + "}");
            }
            topic.deleteBefore(0, 4);
            reader.resume();
            assertThatThrownBy(() -> {
                        long deadline =
                                System.nanoTime() + Duration.ofSeconds(10).toNanos();
                        while (System.nanoTime() < deadline) {
                            reader.poll(rows, 64);
                            Thread.sleep(5);
                        }
                    })
                    .hasMessageContaining("PRV-5106")
                    .hasMessageContaining("no longer in the log");
            assertThat(rows.described())
                    .as("what was fetched before retention overtook it")
                    .contains("[u0, 0, @1]");
            assertThat(plugin.health().state()).isEqualTo(HealthStatus.State.UNHEALTHY);
        }
    }

    @Test
    void aTopicDeletedUnderARunningReaderStopsItWithTheTopicsOwnCode() {
        // TOPICGONE-1: the consumer only logs "unknown topic or partition" for a deleted topic, so the
        // feed stayed RUNNING and health UP. Past topic.missing.timeout the reader stops, PRV-5130.
        topic.append(0, "k", "{\"user_id\":\"u0\",\"amount\":0}");
        plugin = open(Map.of("topic.missing.timeout", "1s"));
        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            awaitRows(reader, rows, 1);
            topic.drop();
            assertThatThrownBy(() -> {
                        long deadline =
                                System.nanoTime() + Duration.ofSeconds(10).toNanos();
                        while (System.nanoTime() < deadline) {
                            reader.poll(rows, 64);
                            Thread.sleep(5);
                        }
                    })
                    .hasMessageContaining("PRV-5130")
                    .hasMessageContaining("topic.missing.timeout");
            assertThat(plugin.health().state()).isEqualTo(HealthStatus.State.UNHEALTHY);
        }
    }

    @Test
    void theTopicMissingTimeoutIsNeverShorterThanASecond() {
        assertThatThrownBy(() -> configured(Map.of("topic.missing.timeout", "500ms")))
                .hasMessageContaining("PRV-5100")
                .hasMessageContaining("topic.missing.timeout");
    }

    @Test
    void aCheckpointFromAnotherTopicOrPartitionIsRefused() {
        plugin = open(Map.of());

        assertThatThrownBy(() -> plugin.createReader(partition(0), new SourceOffset("orders/0@5")))
                .hasMessageContaining("PRV-5104")
                .hasMessageContaining("orders/0");
        assertThatThrownBy(() -> plugin.createReader(partition(0), new SourceOffset("txn/2@5")))
                .hasMessageContaining("PRV-5104");
        assertThatThrownBy(() -> plugin.createReader(partition(0), new SourceOffset("lsn=0/16B3748")))
                .hasMessageContaining("PRV-5104")
                .hasMessageContaining("topic/partition@next");
    }

    @Test
    void theGroupIsNeverReadAndIsCommittedOnlyWhatADurableCheckpointRecorded() {
        for (int i = 0; i < 3; i++) {
            topic.append(0, "k", "{\"user_id\":\"u" + i + "\",\"amount\":" + i + "}");
        }
        plugin = open(Map.of("monitoring.group", "dashboards"));
        TopicPartition p0 = new TopicPartition("txn", 0);

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            FakeTopic.FakeConsumer consumer = topic.consumers.get(topic.consumers.size() - 1);
            assertThat(consumer.config).containsEntry("group.id", "dashboards");
            reader.poll(rows, 64);
            sleep(300);
            assertThat(consumer.committed(Set.of(p0)).get(p0))
                    .as("delivered is not durable: nothing is committed at delivery")
                    .isNull();

            reader.checkpointed(offset(0, 2));
            awaitTrue(() -> consumer.committed(Set.of(p0)).get(p0) != null, "the checkpoint's offset is committed");
            assertThat(consumer.committed(Set.of(p0)).get(p0))
                    .isEqualTo(new OffsetAndMetadata(2, "pravaha checkpoint"));

            reader.checkpointed(offset(0, 1));
            sleep(300);
            assertThat(Objects.requireNonNull(consumer.committed(Set.of(p0)).get(p0))
                            .offset())
                    .as("never backwards")
                    .isEqualTo(2);
        }
    }

    @Test
    void withoutAMonitoringGroupNoGroupIsJoinedOrCommitted() {
        topic.append(0, "k", "{\"user_id\":\"u1\",\"amount\":1}");
        plugin = open(Map.of());

        try (PartitionReader reader = plugin.createReader(partition(0), null)) {
            reader.checkpointed(offset(0, 1));
            FakeTopic.FakeConsumer consumer = topic.consumers.get(topic.consumers.size() - 1);
            assertThat(consumer.config).doesNotContainKey("group.id");
            sleep(200);
            assertThat(consumer.committed(Set.of(new TopicPartition("txn", 0))).get(new TopicPartition("txn", 0)))
                    .isNull();
        }
    }

    @Test
    void healthReportsLagAndDegradesPastTheWarningAndIsUnhealthyWhenTheBrokersDoNotAnswer() {
        for (int i = 0; i < 5; i++) {
            topic.append(0, "k", "{\"user_id\":\"u" + i + "\",\"amount\":" + i + "}");
        }
        plugin = open(Map.of("lag.warn.records", "3"));
        assertThat(plugin.health().detail()).contains("none being read");

        try (Collected rows = new Collected(plugin.schema());
                PartitionReader reader = plugin.createReader(partition(0), null)) {
            HealthStatus behind = plugin.health();
            assertThat(behind.state()).isEqualTo(HealthStatus.State.DEGRADED);
            assertThat(behind.detail()).contains("p0 5").contains("lag.warn.records=3");
            reader.poll(rows, 64);
        }

        KafkaSourcePlugin fresh = open(Map.of("lag.warn.records", "3"));
        try (Collected rows = new Collected(fresh.schema());
                PartitionReader reader = fresh.createReader(partition(0), null)) {
            reader.poll(rows, 64);
            assertThat(fresh.health().state()).isEqualTo(HealthStatus.State.HEALTHY);
            assertThat(fresh.health().detail()).contains("p0 0");
        } finally {
            fresh.close();
        }

        KafkaSourcePlugin cutOff = open(Map.of());
        try {
            topic.unreachable(new org.apache.kafka.common.errors.TimeoutException("no brokers"));
            assertThat(cutOff.health().state()).isEqualTo(HealthStatus.State.UNHEALTHY);
            assertThat(cutOff.health().detail()).contains("did not answer");
        } finally {
            topic.unreachable(null);
            cutOff.close();
        }
    }

    @Test
    void aTopicThatDoesNotExistOrBrokersThatDoNotAnswerAreRefusedAtOpen() {
        topic.drop();
        KafkaSourcePlugin missing = configured(Map.of());
        assertThatThrownBy(missing::open)
                .hasMessageContaining("PRV-5101")
                .hasMessageContaining("does not exist")
                .hasMessageContaining("never creates");

        FakeTopic down = new FakeTopic("txn", 1);
        down.unreachable(new KafkaException("timed out"));
        KafkaSourcePlugin unreachable = new KafkaSourcePlugin(down);
        unreachable.configure(new Ctx("txn", options(Map.of())));
        assertThatThrownBy(unreachable::open).hasMessageContaining("PRV-5101").hasMessageContaining("timed out");
        assertThat(unreachable.health().state()).isEqualTo(HealthStatus.State.UNHEALTHY);
    }

    @Test
    void closingThePluginClosesItsReadersAndIsIdempotent() {
        topic.append(0, "k", "{\"user_id\":\"u1\",\"amount\":1}");
        plugin = open(Map.of());
        PartitionReader reader = plugin.createReader(partition(0), null);

        plugin.close();
        plugin.close();

        try (Collected rows = new Collected(plugin.schema())) {
            assertThat(reader.poll(rows, 64)).isZero();
        }
        awaitTrue(() -> topic.consumers.stream().allMatch(FakeTopic.FakeConsumer::closed), "every consumer closed");
    }

    // ---------------------------------------------------------------------------------------

    private KafkaSourcePlugin open(Map<String, String> overrides) {
        KafkaSourcePlugin opened = configured(overrides);
        opened.open();
        return opened;
    }

    private KafkaSourcePlugin configured(Map<String, String> overrides) {
        KafkaSourcePlugin configured = new KafkaSourcePlugin(topic);
        configured.configure(new Ctx("txn", options(overrides)));
        return configured;
    }

    private static Map<String, String> options(Map<String, String> overrides) {
        Map<String, String> options = new HashMap<>();
        options.put("bootstrap.servers", "localhost:9");
        options.put("topic", "txn");
        options.put("schema", SCHEMA);
        options.put("start.timeout", "2s");
        options.putAll(overrides);
        return options;
    }

    private static SourcePartition partition(int index) {
        return new SourcePartition("txn", index, Map.of());
    }

    private static SourceOffset offset(int partition, long next) {
        return new SourceOffset("txn/" + partition + "@" + next);
    }

    private static void awaitRows(PartitionReader reader, Collected rows, int count) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (rows.rows().size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("only " + rows.described() + " arrived");
            }
            reader.poll(rows, 64);
            sleep(5);
        }
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("never: " + what);
            }
            sleep(5);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
