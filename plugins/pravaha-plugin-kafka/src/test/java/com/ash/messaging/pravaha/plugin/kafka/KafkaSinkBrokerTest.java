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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sink's protocol against a real broker, one call at a time, with a {@code read_committed}
 * consumer as the judge -- the consumer the exactly-once guarantee is stated for.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class KafkaSinkBrokerTest {

    private static final StreamSchema LATEST = StreamSchema.builder("latest")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private final TestRows rows = new TestRows();
    private final List<KafkaSinkPlugin> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(KafkaSinkPlugin::close);
        rows.close();
    }

    @Test
    void upsertModeWritesKeyedRowsAndATombstoneForARetraction() {
        String topic = KafkaBroker.topic("upsert", 1, true);
        KafkaSinkPlugin sink = open(topic, "upsert-sink", Map.of());

        sink.beginTransaction(1);
        sink.write(List.of(row(1, "u1", 300L), row(1, "u2", 50L)));
        sink.write(List.of(row(-1, "u1", 300L), row(1, "u1", 375L), row(-1, "u2", 50L)));
        sink.commit(sink.prepare(1));

        assertThat(KafkaBroker.readCommitted(topic))
                .extracting(Object::toString)
                .containsExactly(
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":300}",
                        "{\"user_id\":\"u2\"}={\"user_id\":\"u2\",\"amount\":50}",
                        "{\"user_id\":\"u1\"}=null",
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":375}",
                        "{\"user_id\":\"u2\"}=null");
    }

    @Test
    void changelogModeWritesEachChangeWithItsOpAndWeight() {
        String topic = KafkaBroker.topic("changelog", 1, false);
        KafkaSinkPlugin sink = open(topic, "changelog-sink", Map.of("mode", "changelog", "key.columns", ""));

        sink.beginTransaction(1);
        sink.write(List.of(row(1, "u1", 300L), row(-1, "u1", 300L), row(2, "u2", 7L)));
        sink.commit(sink.prepare(1));

        assertThat(KafkaBroker.readCommitted(topic))
                .extracting(Object::toString)
                .containsExactly(
                        "{\"user_id\":\"u1\",\"amount\":300}={\"op\":\"insert\",\"weight\":1,\"row\":{\"user_id\":\"u1\",\"amount\":300}}",
                        "{\"user_id\":\"u1\",\"amount\":300}={\"op\":\"delete\",\"weight\":-1,\"row\":{\"user_id\":\"u1\",\"amount\":300}}",
                        "{\"user_id\":\"u2\",\"amount\":7}={\"op\":\"insert\",\"weight\":2,\"row\":{\"user_id\":\"u2\",\"amount\":7}}");
    }

    @Test
    void aReadCommittedConsumerSeesNothingUntilTheCheckpointCommits() {
        String topic = KafkaBroker.topic("invisible", 1, true);
        KafkaSinkPlugin sink = open(topic, "invisible-sink", Map.of());

        sink.beginTransaction(1);
        sink.write(List.of(row(1, "u1", 300L)));
        sink.flush();
        String handle = sink.prepare(1);
        sink.beginTransaction(2);
        sink.write(List.of(row(1, "u2", 50L)));
        sink.flush();

        assertThat(KafkaBroker.readCommitted(topic))
                .as("prepared, not committed")
                .isEmpty();
        assertThat(KafkaBroker.read(topic, "read_uncommitted", Duration.ofSeconds(2)))
                .as("staged in another topic, not written to this one uncommitted")
                .isEmpty();

        sink.commit(handle);
        assertThat(KafkaBroker.readCommitted(topic))
                .as("the checkpoint's change, and not the open transaction's")
                .extracting(Object::toString)
                .containsExactly("{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":300}");
    }

    /**
     * The crash the design exists for: the checkpoint recording the handle is durable, and the
     * process dies before its commit reaches Kafka. A new process -- a new producer, the same
     * transactional.id -- is handed the recorded handle and must write it, exactly once, from what
     * was staged; and a second commit of the same handle, as a restore after a crash that followed
     * the commit would send, must write nothing.
     */
    @Test
    void aCrashBetweenPrepareAndCommitThenARestoreWritesEachChangeExactlyOnce() {
        String topic = KafkaBroker.topic("crash", 1, true);
        Map<String, String> binding = Map.of("transactional.id", "crash-" + topic);
        KafkaSinkPlugin first = open(topic, "crash-sink", binding);
        first.beginTransaction(1);
        first.write(List.of(row(1, "u1", 300L), row(1, "u2", 50L)));
        String handle = first.prepare(1);
        first.beginTransaction(2);
        first.write(List.of(row(-1, "u1", 300L), row(1, "u1", 375L))); // the tail after the cut
        first.close(); // dies here: the checkpoint is durable, the commit was never sent

        assertThat(KafkaBroker.readCommitted(topic)).isEmpty();

        KafkaSinkPlugin second = open(topic, "crash-sink", binding);
        second.commit(handle); // the restore commits what the checkpoint recorded
        second.abortAfter(1); // and abandons what came after it
        second.commit(handle); // a repeated commit, as after a crash that followed the first
        List<KafkaBroker.Seen> afterRestore = KafkaBroker.readCommitted(topic);

        assertThat(afterRestore)
                .as("the recorded checkpoint's changes, once each; not the tail after it")
                .extracting(Object::toString)
                .containsExactly(
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":300}",
                        "{\"user_id\":\"u2\"}={\"user_id\":\"u2\",\"amount\":50}");

        // The replay writes the tail again, into the restarted process's own transaction.
        second.beginTransaction(2);
        second.write(List.of(row(-1, "u1", 300L), row(1, "u1", 375L)));
        second.commit(second.prepare(2));
        assertThat(KafkaBroker.readCommitted(topic))
                .extracting(Object::toString)
                .containsExactly(
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":300}",
                        "{\"user_id\":\"u2\"}={\"user_id\":\"u2\",\"amount\":50}",
                        "{\"user_id\":\"u1\"}=null",
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":375}");
    }

    /**
     * The other crash: the process died <em>inside</em> a commit, its target transaction open with
     * some records sent. Opening the next sink aborts that transaction (a read_committed consumer
     * never sees it), and the recorded handle is committed whole.
     */
    @SuppressWarnings(
            "FutureReturnValueIgnored") // the task reports its own outcome (a callback, or a catch-all in the task)
    @Test
    void aCrashInsideACommitIsAbortedByTheNextOpenAndTheHandleIsCommittedWhole() throws Exception {
        String topic = KafkaBroker.topic("midcommit", 1, true);
        String transactionalId = "midcommit-" + topic;
        Map<String, String> binding = Map.of("transactional.id", transactionalId);
        KafkaSinkPlugin first = open(topic, "midcommit-sink", binding);
        first.beginTransaction(1);
        first.write(List.of(row(1, "u1", 300L), row(1, "u2", 50L)));
        String handle = first.prepare(1);
        first.close();

        // What the dead process's commit had got as far as: a transaction under the sink's own
        // transactional.id, half its records sent, never committed.
        try (KafkaProducer<byte[], byte[]> dying = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaBroker.bootstrap(),
                ProducerConfig.TRANSACTIONAL_ID_CONFIG,
                transactionalId,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class))) {
            dying.initTransactions();
            dying.beginTransaction();
            dying.send(new ProducerRecord<>(topic, bytes("{\"user_id\":\"u1\"}"), bytes("half-written")));
            dying.flush();
            // No commit, no abort: the process is gone. Closing without either leaves it open.
        }

        KafkaSinkPlugin second = open(topic, "midcommit-sink", binding);
        second.commit(handle);

        assertThat(KafkaBroker.readCommitted(topic))
                .extracting(Object::toString)
                .containsExactly(
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":300}",
                        "{\"user_id\":\"u2\"}={\"user_id\":\"u2\",\"amount\":50}");
    }

    @Test
    void aSecondSinkWithTheSameTransactionalIdFencesTheFirstWhichThenFailsLoudly() {
        String topic = KafkaBroker.topic("fenced", 1, true);
        Map<String, String> binding = Map.of("transactional.id", "fenced-" + topic);
        KafkaSinkPlugin first = open(topic, "fenced-sink", binding);
        first.beginTransaction(1);
        first.write(List.of(row(1, "u1", 300L)));
        String handle = first.prepare(1);

        open(topic, "fenced-sink", binding);

        assertThatThrownBy(() -> first.commit(handle))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5102")
                .hasMessageContaining("must not share a transactional.id");
        assertThat(first.health().state() == HealthStatus.State.HEALTHY).isFalse();
    }

    @Test
    void theStagingTopicIsCreatedWithOnePartitionAndDeletePolicy() throws Exception {
        String topic = KafkaBroker.topic("staging", 1, true);
        KafkaSinkPlugin sink = open(topic, "staging-sink", Map.of("staging.topic", topic + ".stage"));
        assertThat(sink.health().state() == HealthStatus.State.HEALTHY).isTrue();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KafkaBroker.bootstrap()))) {
            TopicDescription staging = admin.describeTopics(List.of(topic + ".stage"))
                    .allTopicNames()
                    .get(30, TimeUnit.SECONDS)
                    .get(topic + ".stage");
            assertThat(Objects.requireNonNull(staging).partitions()).hasSize(1);
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic + ".stage");
            Config config = admin.describeConfigs(List.of(resource))
                    .all()
                    .get(30, TimeUnit.SECONDS)
                    .get(resource);
            assertThat(Objects.requireNonNull(config).get("cleanup.policy").value())
                    .isEqualTo("delete");
            assertThat(config.get("retention.ms").value()).isEqualTo("604800000");
        }
    }

    @Test
    void aCompactedStagingTopicAndAMissingTargetAreRefusedWhenTheSinkOpens() {
        String topic = KafkaBroker.topic("refused", 1, true);
        String compacted = KafkaBroker.topic("compacted-stage", 1, true);
        assertThatThrownBy(() -> open(topic, "refused-sink", Map.of("staging.topic", compacted)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5103")
                .hasMessageContaining("is compacted");
        assertThatThrownBy(() -> open("no-such-topic-" + System.nanoTime(), "missing-sink", Map.of()))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5101")
                .hasMessageContaining("does not exist");
    }

    @Test
    void withoutTransactionsEachFlushedBatchIsVisibleAtOnce() {
        String topic = KafkaBroker.topic("direct", 3, true);
        KafkaSinkPlugin sink = open(topic, "direct-sink", Map.of("transactional", "false"));
        assertThat(sink.capabilities().transactional()).isFalse();
        assertThat(sink.capabilities().idempotentUpsert()).isTrue();

        sink.write(List.of(row(1, "u1", 300L), row(1, "u2", 50L), row(-1, "u1", 300L)));
        sink.flush();

        List<KafkaBroker.Seen> seen = KafkaBroker.readCommitted(topic);
        assertThat(seen).hasSize(3);
        assertThat(seen.stream()
                        .filter(s -> "{\"user_id\":\"u1\"}".equals(s.key()))
                        .map(Object::toString)
                        .toList())
                .as("one key's changes stay on one partition, in order")
                .containsExactly(
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":300}", "{\"user_id\":\"u1\"}=null");
    }

    // ---------------------------------------------------------------------------------------

    private KafkaSinkPlugin open(String topic, String name, Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "bootstrap.servers",
                KafkaBroker.bootstrap(),
                "topic",
                topic,
                "schema",
                "user_id:STRING,amount:INT64",
                "key.columns",
                "user_id",
                "staging.topic",
                topic + ".staging"));
        config.putAll(extra);
        KafkaSinkPlugin sink = new KafkaSinkPlugin();
        sink.configure(new Ctx(name, config));
        sink.open();
        opened.add(sink);
        return sink;
    }

    private RowView row(long weight, String user, long amount) {
        return rows.row(LATEST, weight, user, amount);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
