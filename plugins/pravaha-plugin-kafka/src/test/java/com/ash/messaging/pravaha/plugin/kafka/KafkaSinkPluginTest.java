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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sink against Kafka's own {@link MockProducer} and {@link MockConsumer}: the record format, and
 * which producer each call touches. The container tests prove the same protocol against a broker.
 */
class KafkaSinkPluginTest {

    private static final StreamSchema LATEST = StreamSchema.builder("latest")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private final TestRows rows = new TestRows();

    @AfterEach
    void tearDown() {
        rows.close();
    }

    @Test
    void everyTypeIsWrittenAsJsonByColumnName() {
        StreamSchema all = StreamSchema.builder("all")
                .field("id", Types.int32())
                .field("flag", Types.bool())
                .field("tiny", Types.int8())
                .field("small", Types.int16())
                .field("big", Types.int64())
                .field("ratio", Types.float32())
                .field("score", Types.float64())
                .field("price", Types.decimal(12, 3))
                .field("note", Types.string().withNullable(true))
                .field("blob", Types.bytes())
                .field("day", Types.date())
                .field("at", Types.time())
                .field("ts", Types.timestamp())
                .build();
        Mocks mocks = new Mocks();
        KafkaSinkPlugin sink = open(
                mocks,
                Map.of(
                        "schema",
                        "id:INT32,flag:BOOLEAN,tiny:INT8,small:INT16,big:INT64,ratio:FLOAT32,score:FLOAT64,"
                                + "price:DECIMAL(12,3),note:STRING?,blob:BYTES,day:DATE,at:TIME,ts:TIMESTAMP",
                        "key.columns",
                        "id",
                        "transactional",
                        "false"));

        sink.write(List.of(rows.row(
                all,
                1,
                7,
                true,
                (byte) -3,
                (short) 300,
                9_007_199_254_740_993L,
                1.5f,
                Double.NaN,
                new BigDecimal("12345.678"),
                "say \"hi\"\n\t\u0001",
                new byte[] {1, 2, 3},
                20_000,
                37_815_500_000_000L,
                1_758_276_000_123_456_789L)));
        sink.flush();

        assertThat(sent(mocks.target))
                .containsExactly("{\"id\":7}={\"id\":7,\"flag\":true,\"tiny\":-3,\"small\":300,"
                        + "\"big\":9007199254740993,\"ratio\":1.5,\"score\":\"NaN\",\"price\":12345.678,"
                        + "\"note\":\"say \\\"hi\\\"\\n\\t\\u0001\",\"blob\":\"AQID\",\"day\":\"2024-10-04\","
                        + "\"at\":\"10:30:15.500\",\"ts\":\"2025-09-19T10:00:00.123456789Z\"}");
    }

    @Test
    void aNullColumnIsJsonNull() {
        Mocks mocks = new Mocks();
        KafkaSinkPlugin sink = open(mocks, Map.of("schema", "user_id:STRING,amount:INT64?", "transactional", "false"));
        StreamSchema nullable = StreamSchema.builder("n")
                .field("user_id", Types.string())
                .field("amount", Types.int64().withNullable(true))
                .build();
        sink.write(List.of(rows.row(nullable, 1, "u1", null)));
        assertThat(sent(mocks.target)).containsExactly("{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":null}");
    }

    @Test
    void withoutTransactionsARetractionIsATombstoneSentStraightToTheTarget() {
        Mocks mocks = new Mocks();
        KafkaSinkPlugin sink = open(mocks, Map.of("transactional", "false"));
        sink.beginTransaction(1); // ignored: not transactional
        sink.write(List.of(row(1, "u1", 300L), row(-1, "u1", 300L)));
        sink.flush();
        assertThat(sink.prepare(1)).isEmpty();
        sink.commit("");

        assertThat(mocks.target.transactionInitialized()).isFalse();
        assertThat(sent(mocks.target))
                .containsExactly(
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":300}", "{\"user_id\":\"u1\"}=null");
        assertThat(mocks.target.history())
                .allSatisfy(record -> assertThat(record.topic()).isEqualTo("spend"));
    }

    @Test
    void aSendThatFailsIsReportedAtTheFlush() {
        Mocks mocks = new Mocks(false);
        KafkaSinkPlugin sink = open(mocks, Map.of("transactional", "false"));
        sink.write(List.of(row(1, "u1", 300L)));
        mocks.target.errorNext(new org.apache.kafka.common.errors.TimeoutException("broker gone"));
        assertThatThrownBy(sink::flush)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5102")
                .hasMessageContaining("broker gone");
    }

    /**
     * Transactional: writes go to the staging topic only, headed with the run and the label; the
     * handle names their offsets; the commit writes them to the target and the marker offset in one
     * transaction; and a commit whose range the marker has passed writes nothing.
     */
    @Test
    void writesAreStagedAndOnlyTheCommitReachesTheTarget() {
        Mocks mocks = new Mocks();
        KafkaSinkPlugin sink = open(mocks, Map.of());
        assertThat(mocks.target.transactionInitialized()).isTrue();

        sink.beginTransaction(4);
        sink.write(List.of(row(1, "u1", 300L), row(1, "u2", 50L)));
        String handle = sink.prepare(4);

        assertThat(mocks.target.history())
                .as("nothing reaches the target at prepare")
                .isEmpty();
        assertThat(mocks.staging.history()).hasSize(2).allSatisfy(record -> {
            assertThat(record.topic()).isEqualTo("spend.staging");
            assertThat(record.partition()).isZero();
            assertThat(new String(record.headers().lastHeader("pravaha.label").value(), StandardCharsets.UTF_8))
                    .isEqualTo("4");
        });
        assertThat(handle).startsWith("kafka-sink:v1:4:").endsWith(":0:2:spend_topic");

        mocks.deliverStaged();
        sink.commit(handle);
        assertThat(sent(mocks.target))
                .containsExactly(
                        "{\"user_id\":\"u1\"}={\"user_id\":\"u1\",\"amount\":300}",
                        "{\"user_id\":\"u2\"}={\"user_id\":\"u2\",\"amount\":50}");
        assertThat(mocks.target.transactionCommitted()).isTrue();
        assertThat(mocks.target.consumerGroupOffsetsHistory())
                .singleElement()
                .satisfies(offsets -> assertThat(offsets.get("pravaha-sink.spend_topic"))
                        .containsEntry(new TopicPartition("spend.staging", 0), new OffsetAndMetadata(2, "label=4")));
        assertThat(sink.rowsCommitted()).isEqualTo(2);

        // The marker as the broker would now report it: a second commit is skipped.
        mocks.reader.commitSync(Map.of(new TopicPartition("spend.staging", 0), new OffsetAndMetadata(2)));
        sink.commit(handle);
        assertThat(mocks.target.history()).hasSize(2);
        assertThat(mocks.target.commitCount()).isEqualTo(1);
    }

    @Test
    void anEmptyTransactionCommitsNothing() {
        Mocks mocks = new Mocks();
        KafkaSinkPlugin sink = open(mocks, Map.of());
        sink.beginTransaction(1);
        String handle = sink.prepare(1);
        assertThat(handle).contains(":-1:-1:");
        sink.commit(handle);
        assertThat(mocks.target.commitCount()).isZero();
    }

    @Test
    void recordsAnotherProcessInterleavedIntoTheRangeAreNotCommitted() {
        Mocks mocks = new Mocks();
        KafkaSinkPlugin sink = open(mocks, Map.of());
        sink.beginTransaction(1);
        sink.write(List.of(row(1, "u1", 300L)));
        // A zombie with the same staging topic writes between this sink's records.
        mocks.staging.send(new ProducerRecord<>("spend.staging", 0, bytes("{\"user_id\":\"zz\"}"), bytes("zombie")));
        sink.write(List.of(row(1, "u2", 50L)));
        String handle = sink.prepare(1);
        assertThat(handle).endsWith(":0:3:spend_topic");

        mocks.deliverStaged();
        sink.commit(handle);
        assertThat(sent(mocks.target)).hasSize(2).noneMatch(text -> text.contains("zombie"));
    }

    @Test
    void aFencedProducerFailsTheCommitAndMarksTheSinkUnhealthy() {
        Mocks mocks = new Mocks();
        KafkaSinkPlugin sink = open(mocks, Map.of());
        sink.beginTransaction(1);
        sink.write(List.of(row(1, "u1", 300L)));
        String handle = sink.prepare(1);
        mocks.deliverStaged();
        mocks.target.fenceProducer();

        assertThatThrownBy(() -> sink.commit(handle))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5102")
                .hasMessageContaining("must not share a transactional.id");
        assertThat(sink.health().state()).isEqualTo(HealthStatus.State.UNHEALTHY);
    }

    @Test
    void aHandleFromAnotherSinkOrNotFromThisPluginIsRefused() {
        KafkaSinkPlugin sink = open(new Mocks(), Map.of());
        assertThatThrownBy(() -> sink.commit("kafka-sink:v1:1:run:0:2:someone_else"))
                .hasMessageContaining("belongs to transactional.id 'someone_else'");
        assertThatThrownBy(() -> sink.commit("jdbc-sink:v1:1:spend_topic")).hasMessageContaining("is not a handle");
    }

    @Test
    void aStagingTopicCreatedAgainUnderAnOldMarkerIsRefusedAtOpen() {
        Mocks mocks = new Mocks();
        mocks.markerAtOpen = 10;
        KafkaSinkPlugin sink = new KafkaSinkPlugin(mocks);
        sink.configure(new Ctx("spend_topic", config(Map.of())));
        assertThatThrownBy(sink::open)
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5103")
                .hasMessageContaining("deleted and created again");
    }

    @Test
    void aWriteOutsideATransactionOrBeforeOpenIsRefused() {
        KafkaSinkPlugin notOpen = new KafkaSinkPlugin(new Mocks());
        notOpen.configure(new Ctx("spend_topic", config(Map.of())));
        assertThatThrownBy(() -> notOpen.write(List.of(row(1, "u1", 1L)))).hasMessageContaining("is not open");
        assertThat(notOpen.health().state()).isEqualTo(HealthStatus.State.UNHEALTHY);

        KafkaSinkPlugin sink = open(new Mocks(), Map.of());
        assertThatThrownBy(() -> sink.write(List.of(row(1, "u1", 1L)))).hasMessageContaining("no transaction begun");
        assertThatThrownBy(() -> sink.prepare(1)).hasMessageContaining("no transaction begun");
        sink.close();
        sink.close();
    }

    // ---------------------------------------------------------------------------------------

    private RowView row(long weight, String user, long amount) {
        return rows.row(LATEST, weight, user, amount);
    }

    private static Map<String, String> config(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of(
                "bootstrap.servers", "localhost:9092",
                "topic", "spend",
                "schema", "user_id:STRING,amount:INT64",
                "key.columns", "user_id",
                "staging.topic", "spend.staging"));
        config.putAll(extra);
        return config;
    }

    private static KafkaSinkPlugin open(Mocks mocks, Map<String, String> extra) {
        KafkaSinkPlugin sink = new KafkaSinkPlugin(mocks);
        sink.configure(new Ctx("spend_topic", config(extra)));
        sink.open();
        return sink;
    }

    private static List<String> sent(MockProducer<byte[], byte[]> producer) {
        List<String> sent = new ArrayList<>();
        for (ProducerRecord<byte[], byte[]> record : producer.history()) {
            sent.add(text(record.key()) + "=" + text(record.value()));
        }
        return sent;
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    /** Kafka's mocks behind the sink's seam: the target producer first, then staging, then the reader. */
    private static final class Mocks implements KafkaClients {
        private final boolean autoComplete;
        private final TopicPartition staged = new TopicPartition("spend.staging", 0);
        MockProducer<byte[], byte[]> target;
        MockProducer<byte[], byte[]> staging;
        MockConsumer<byte[], byte[]> reader;
        long markerAtOpen = -1;
        private int delivered;

        Mocks() {
            this(true);
        }

        Mocks(boolean autoComplete) {
            this.autoComplete = autoComplete;
        }

        @Override
        public MockProducer<byte[], byte[]> producer(Map<String, Object> config) {
            MockProducer<byte[], byte[]> producer =
                    new MockProducer<>(autoComplete, new ByteArraySerializer(), new ByteArraySerializer());
            if (target == null) {
                target = producer;
            } else {
                staging = producer;
            }
            return producer;
        }

        @Override
        public MockConsumer<byte[], byte[]> consumer(Map<String, Object> config) {
            // MockConsumer forgets committed offsets on assign(), which a broker does not; the
            // marker is put back after it, where the broker would still have it.
            reader = new MockConsumer<>(OffsetResetStrategy.NONE) {
                @Override
                public synchronized void assign(java.util.Collection<TopicPartition> partitions) {
                    super.assign(partitions);
                    if (markerAtOpen >= 0) {
                        commitSync(Map.of(staged, new OffsetAndMetadata(markerAtOpen)));
                    }
                }
            };
            reader.updateBeginningOffsets(Map.of(staged, 0L));
            reader.updateEndOffsets(Map.of(staged, 0L));
            return reader;
        }

        @Override
        public void prepareTopics(KafkaSinkOptions options) {}

        /** What a broker would do: the staged records become readable at their offsets. */
        void deliverStaged() {
            List<ProducerRecord<byte[], byte[]>> history = staging.history();
            for (; delivered < history.size(); delivered++) {
                ProducerRecord<byte[], byte[]> sent = history.get(delivered);
                ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                        sent.topic(),
                        0,
                        delivered,
                        0L,
                        org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                        0,
                        0,
                        sent.key(),
                        sent.value(),
                        sent.headers(),
                        java.util.Optional.empty());
                reader.addRecord(record);
            }
            reader.updateEndOffsets(Map.of(staged, (long) history.size()));
        }
    }
}
