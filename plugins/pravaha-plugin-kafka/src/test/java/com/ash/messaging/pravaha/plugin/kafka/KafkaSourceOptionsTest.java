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
import java.util.Map;
import java.util.Objects;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A {@code kafka} source binding's options: the defaults, what each client is given, and the refusals. */
class KafkaSourceOptionsTest {

    @Test
    void theDefaultsReadCommittedJsonFromTheEarliestOffsetWithNoGroup() {
        KafkaSourceOptions options = options(Map.of());

        assertThat(options.format).isEqualTo(KafkaSourceOptions.Format.JSON);
        assertThat(options.readCommitted).isTrue();
        assertThat(options.startAtLatest).isFalse();
        assertThat(options.skipTombstones).isFalse();
        assertThat(options.monitoringGroup).isEmpty();
        assertThat(options.bufferRecords).isEqualTo(10_000);
        assertThat(options.startTimeout).isEqualTo(Duration.ofSeconds(30));
        assertThat(options.eventTimeOrdinal).isEqualTo(-1);
        assertThat(options.schema.name()).as("named for the stream it feeds").isEqualTo("txn");

        Map<String, Object> reader = options.readerConsumer(new TopicPartition("txn", 2));
        assertThat(reader)
                .containsEntry("isolation.level", "read_committed")
                .containsEntry("enable.auto.commit", false)
                .containsEntry("auto.offset.reset", "none")
                .containsEntry("allow.auto.create.topics", false)
                .containsEntry("security.protocol", "PLAINTEXT")
                .containsEntry("client.id", "pravaha-txn-txn-2")
                .doesNotContainKey("group.id");
        assertThat(options.metadataConsumer()).doesNotContainKey("group.id");
    }

    @Test
    void aMonitoringGroupIsGivenToTheReadersAndNothingElse() {
        KafkaSourceOptions options = options(Map.of("monitoring.group", "lag-dashboards"));

        assertThat(options.readerConsumer(new TopicPartition("txn", 0))).containsEntry("group.id", "lag-dashboards");
        assertThat(options.metadataConsumer()).doesNotContainKey("group.id");
    }

    @Test
    void theOptionsThatChangeWhatIsReadAreParsed() {
        KafkaSourceOptions options = options(Map.of(
                "format", "CHANGELOG",
                "tombstone", "skip",
                "start.from", "latest",
                "isolation.level", "read_uncommitted",
                "buffer.records", "64",
                "start.timeout", "500ms",
                "lag.warn.records", "7",
                "schema", "user_id:STRING,amount:INT64,at:TIMESTAMP",
                "event.time", "AT"));

        assertThat(options.format).isEqualTo(KafkaSourceOptions.Format.CHANGELOG);
        assertThat(options.skipTombstones).isTrue();
        assertThat(options.startAtLatest).isTrue();
        assertThat(options.readerConsumer(new TopicPartition("txn", 0)))
                .containsEntry("isolation.level", "read_uncommitted");
        assertThat(options.bufferRecords).isEqualTo(64);
        assertThat(options.startTimeout).isEqualTo(Duration.ofMillis(500));
        assertThat(options.lagWarnRecords).isEqualTo(7);
        assertThat(options.eventTimeOrdinal).isEqualTo(2);
        assertThat(options.schema.eventTimeOrdinal()).hasValue(2);
    }

    @Test
    void settingsThatCannotBeHonouredAreRefusedAtConfigure() {
        // The formats themselves, and every combination of format and schema option, are in
        // KafkaSourceFormatOptionsTest; here only that a name that is no format at all is refused.
        assertRefused(Map.of("format", "csv"), "is not json, changelog, avro or protobuf");
        assertRefused(Map.of("tombstone", "retract"), "tombstone must be reject or skip");
        assertRefused(Map.of("start.from", "committed"), "start.from must be earliest or latest");
        assertRefused(Map.of("isolation.level", "dirty"), "isolation.level must be");
        assertRefused(Map.of("buffer.records", "0"), "buffer.records must be a whole number");
        assertRefused(Map.of("lag.warn.records", "many"), "lag.warn.records must be");
        assertRefused(Map.of("start.timeout", "soon"), "start.timeout must be a duration");
        assertRefused(Map.of("topic", "not a topic"), "is not a valid Kafka topic name");
        assertRefused(Map.of("event.time", "missing"), "not a column of the declared schema");
        assertRefused(Map.of("event.time", "amount"), "only a TIMESTAMP column");
        assertRefused(Map.of("schema", "amount:MONEY"), "unknown type");
    }

    @Test
    void passThroughIsConsumerPropertiesOnlyAndNeverOnesThatMoveThePosition() {
        KafkaSourceOptions options = options(Map.of("kafka.fetch.min.bytes", "1024", "kafka.max.poll.records", "200"));
        assertThat(options.readerConsumer(new TopicPartition("txn", 0)))
                .containsEntry("fetch.min.bytes", "1024")
                .containsEntry("max.poll.records", "200");

        assertRefused(Map.of("kafka.group.id", "g"), "the checkpoint is the position");
        assertRefused(Map.of("kafka.enable.auto.commit", "true"), "only after a durable checkpoint");
        assertRefused(Map.of("kafka.auto.offset.reset", "earliest"), "seeks to exact offsets");
        assertRefused(Map.of("kafka.isolation.level", "read_uncommitted"), "on the binding itself");
        assertRefused(Map.of("kafka.allow.auto.create.topics", "true"), "never creates the topic");
        assertRefused(Map.of("kafka.ssl.truststore.location", "/x"), "shared tls.* options");
        assertRefused(Map.of("kafka.linger.ms", "5"), "not a Kafka consumer property");
    }

    @Test
    void securityIsTheSinksMappingInTheSourcesWords() {
        assertThat(options(Map.of("user", "svc", "password", "pw", "sasl.mechanism", "SCRAM-SHA-512"))
                        .readerConsumer(new TopicPartition("txn", 0)))
                .containsEntry("security.protocol", "SASL_PLAINTEXT")
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512");
        assertRefused(Map.of("user", "svc", "password", "pw"), "source 'txn' uses SASL PLAIN without TLS");
        assertRefused(Map.of("tls.ca-certificate", "/x.pem"), "the SDK's spelling");
    }

    @Test
    void anOffsetRoundTripsAndOnlyForItsOwnPartition() {
        TopicPartition partition = new TopicPartition("orders.v2-eu", 3);
        SourceOffset token = KafkaSourceOffset.at(partition, 42).toSourceOffset();

        assertThat(token.token()).isEqualTo("orders.v2-eu/3@42");
        assertThat(Objects.requireNonNull(KafkaSourceOffset.parse(token, partition))
                        .next())
                .isEqualTo(42);
        assertThat(KafkaSourceOffset.parse(SourceOffset.BEGINNING, partition)).isNull();
        assertThat(KafkaSourceOffset.parse(null, partition)).isNull();
        for (String bad : new String[] {"42", "orders.v2-eu/x@1", "orders.v2-eu/3@-1", "/3@1", "orders.v2-eu@3"}) {
            assertThatThrownBy(() -> KafkaSourceOffset.parse(new SourceOffset(bad), partition))
                    .as(bad)
                    .hasMessageContaining("PRV-5104");
        }
        assertThatThrownBy(() -> KafkaSourceOffset.parse(new SourceOffset("orders.v2-eu/4@1"), partition))
                .hasMessageContaining("PRV-5104")
                .hasMessageContaining("An offset means nothing in another partition");
    }

    private static void assertRefused(Map<String, String> overrides, String message) {
        assertThatThrownBy(() -> options(overrides))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("PRV-5100")
                .hasMessageContaining(message);
    }

    private static KafkaSourceOptions options(Map<String, String> overrides) {
        Map<String, String> config = new HashMap<>();
        config.put("bootstrap.servers", "kafka-1:9092");
        config.put("topic", "txn");
        config.put("schema", "user_id:STRING,amount:INT64");
        config.putAll(overrides);
        return new KafkaSourceOptions(new Ctx("txn", config));
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
