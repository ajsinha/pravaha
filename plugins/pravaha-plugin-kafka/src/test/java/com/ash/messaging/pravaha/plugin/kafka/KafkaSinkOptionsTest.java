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

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a {@code kafka-sink} binding may say, what it may not, and what each becomes for Kafka. */
class KafkaSinkOptionsTest {

    @TempDir
    Path directory;

    private static Map<String, String> base() {
        return new HashMap<>(Map.of(
                "bootstrap.servers", "localhost:9092",
                "topic", "spend",
                "schema", "user_id:STRING,amount:INT64",
                "key.columns", "user_id"));
    }

    private static KafkaSinkOptions options(Map<String, String> config) {
        return new KafkaSinkOptions(new Ctx("spend_topic", config));
    }

    private static KafkaSinkOptions with(String key, String value) {
        Map<String, String> config = base();
        config.put(key, value);
        return options(config);
    }

    @Test
    void theDefaultsAreTransactionalUpsertNamedAfterTheBinding() {
        KafkaSinkOptions options = options(base());
        assertThat(options.transactional).isTrue();
        assertThat(options.changelog).isFalse();
        assertThat(options.transactionalId).isEqualTo("spend_topic");
        assertThat(options.stagingTopic).isEqualTo("pravaha-staging.spend_topic");
        assertThat(options.commitGroup).isEqualTo("pravaha-sink.spend_topic");
        assertThat(options.stagingRetentionMs).isEqualTo(7L * 24 * 3600 * 1000);

        assertThat(options.targetProducer())
                .containsEntry("transactional.id", "spend_topic")
                .containsEntry("enable.idempotence", true)
                .containsEntry("acks", "all")
                .containsEntry("security.protocol", "PLAINTEXT");
        assertThat(options.stagingProducer())
                .doesNotContainKey("transactional.id")
                .containsEntry("enable.idempotence", true);
        assertThat(options.stagingConsumer())
                .containsEntry("group.id", "pravaha-sink.spend_topic")
                .containsEntry("isolation.level", "read_committed")
                .containsEntry("enable.auto.commit", false);
    }

    @Test
    void aNameThatIsNotATopicNameIsSanitisedForTheDefaultStagingTopic() {
        KafkaSinkOptions options = options(new HashMap<>(base()) {
            {
                put("transactional.id", "spend by user/eu");
            }
        });
        assertThat(options.stagingTopic).isEqualTo("pravaha-staging.spend_by_user_eu");
        assertThat(options.commitGroup).isEqualTo("pravaha-sink.spend by user/eu");
    }

    @Test
    void theModesDeclareWhatTheyAccept() {
        KafkaSinkPlugin upsert = plugin(base());
        assertThat(upsert.capabilities().emitModes()).containsExactlyInAnyOrder(EmitMode.UPSERT, EmitMode.RETRACT);
        assertThat(upsert.capabilities().transactional()).isTrue();
        assertThat(upsert.keyColumns()).containsExactly("user_id");

        Map<String, String> changelog = base();
        changelog.put("mode", "changelog");
        changelog.remove("key.columns");
        changelog.put("transactional", "false");
        KafkaSinkPlugin log = plugin(changelog);
        assertThat(log.capabilities().emitModes()).containsExactlyInAnyOrder(EmitMode.APPEND, EmitMode.RETRACT);
        assertThat(log.capabilities().transactional()).isFalse();
        assertThat(log.capabilities().idempotentUpsert()).isFalse();
        assertThat(log.keyColumns()).isEmpty();
        assertThat(log.schema())
                .hasValueSatisfying(schema -> assertThat(schema.fieldCount()).isEqualTo(2));
    }

    @Test
    void theKeyIsRequiredInUpsertModeAndMustBeAWholeNonNullableColumn() {
        Map<String, String> noKey = base();
        noKey.remove("key.columns");
        assertThatThrownBy(() -> options(noKey))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("needs key.columns in upsert mode");
        assertRefused(Map.of("key.columns", "nope"), "not in the declared schema");
        assertRefused(Map.of("schema", "user_id:FLOAT64,amount:INT64"), "floating-point key");
        assertRefused(Map.of("schema", "user_id:STRING?,amount:INT64"), "declared nullable");
        assertRefused(Map.of("schema", "user_id:NOPE"), "unknown type 'NOPE'");
        assertRefused(Map.of("schema", "user_id"), "is not 'name:TYPE'");
    }

    @Test
    void settingsOutsideTheirRangeAreRefused() {
        assertRefused(Map.of("mode", "append"), "is not upsert or changelog");
        assertRefused(Map.of("format", "xml"), "is not json, avro or protobuf");
        assertRefused(Map.of("format", "avro"), "format: avro needs schema.file");
        assertRefused(Map.of("transactional", "yes"), "must be true or false");
        assertRefused(Map.of("topic", "no spaces"), "not a valid Kafka topic name");
        assertRefused(Map.of("staging.topic", "spend"), "staging.topic is the target topic");
        assertRefused(Map.of("staging.retention.ms", "0"), "positive number of milliseconds");
        assertRefused(Map.of("transactional.id", ""), "1 to 200 characters");
        assertThat(with("staging.retention.ms", "-1").stagingRetentionMs).isEqualTo(-1);
    }

    @Test
    void passThroughPropertiesGoToTheClientsThatKnowThem() {
        Map<String, String> config = base();
        config.put("kafka.linger.ms", "20");
        config.put("kafka.fetch.max.bytes", "1048576");
        config.put("kafka.request.timeout.ms", "15000");
        config.put("kafka.acks", "-1");
        config.put("kafka.enable.idempotence", "true");
        config.put("kafka.compression.type", "gzip");
        KafkaSinkOptions options = options(config);

        assertThat(options.targetProducer())
                .containsEntry("linger.ms", "20")
                .containsEntry("request.timeout.ms", "15000")
                .containsEntry("compression.type", "gzip")
                .doesNotContainKey("fetch.max.bytes");
        assertThat(options.stagingConsumer())
                .containsEntry("fetch.max.bytes", "1048576")
                .containsEntry("request.timeout.ms", "15000")
                .doesNotContainKey("linger.ms");
        assertThat(options.admin()).containsEntry("request.timeout.ms", "15000").doesNotContainKey("linger.ms");
        assertThat(options.targetProducer())
                .as("the sink's own settings win over nothing, because nothing is allowed to set them")
                .containsEntry("acks", "all");
    }

    @Test
    void passThroughThatWouldWeakenTheGuaranteeOrThatTheSinkOwnsIsRefused() {
        assertRefused(Map.of("kafka.enable.idempotence", "false"), "kafka.enable.idempotence: false' is refused");
        assertRefused(Map.of("kafka.acks", "1"), "kafka.acks: 1' is refused");
        assertRefused(Map.of("kafka.transactional.id", "x"), "set transactional.id on the binding itself");
        assertRefused(Map.of("kafka.bootstrap.servers", "x:1"), "set bootstrap.servers on the binding itself");
        assertRefused(Map.of("kafka.isolation.level", "read_uncommitted"), "must read committed data");
        assertRefused(Map.of("kafka.ssl.truststore.location", "/x"), "shared tls.* options");
        assertRefused(Map.of("kafka.security.protocol", "SSL"), "follows from the tls.* options");
        assertRefused(Map.of("kafka.sasl.jaas.config", "x"), "set user and password");
        assertRefused(Map.of("kafka.linger.msec", "5"), "is not a Kafka client property");
    }

    /**
     * ADR-053: snappy and zstd are the two native codecs the build allows, loaded once at
     * configuration; lz4 would need a third, lz4-java, so it is refused by name when configured --
     * not at the first batch, with a NoClassDefFoundError from the producer's I/O thread.
     */
    @Test
    void snappyAndZstdAreWrittenAndLz4IsRefusedNamingTheAdr() {
        for (String codec : new String[] {"snappy", "zstd", "ZSTD", "gzip", "none"}) {
            assertThat(with("kafka.compression.type", codec).targetProducer()).containsEntry("compression.type", codec);
        }
        assertRefused(Map.of("kafka.compression.type", "lz4"), "ADR-053");
        assertRefused(Map.of("kafka.compression.type", "LZ4"), "lz4-java");
    }

    @Test
    void userAndPasswordBecomeSaslAndPlainNeedsTls() {
        Map<String, String> scram =
                Map.of("user", "pravaha", "password", "s3\"cret", "sasl.mechanism", "scram-sha-512");
        Map<String, String> config = base();
        config.putAll(scram);
        KafkaSinkOptions options = options(config);
        assertThat(options.targetProducer())
                .containsEntry("security.protocol", "SASL_PLAINTEXT")
                .containsEntry("sasl.mechanism", "SCRAM-SHA-512")
                .containsEntry(
                        "sasl.jaas.config",
                        "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"pravaha\" "
                                + "password=\"s3\\\"cret\";");
        assertThat(options.stagingConsumer()).containsEntry("sasl.mechanism", "SCRAM-SHA-512");
        assertThat(options.admin()).containsEntry("security.protocol", "SASL_PLAINTEXT");

        assertRefused(Map.of("user", "pravaha", "password", "secret"), "SASL PLAIN without TLS");
        assertRefused(Map.of("user", "pravaha"), "user but not password");
        assertRefused(Map.of("sasl.mechanism", "PLAIN"), "without user and password");
        assertRefused(Map.of("user", "u", "password", "p", "sasl.mechanism", "GSSAPI"), "is not PLAIN, SCRAM");
    }

    @Test
    void theSharedTlsOptionsAreRefusedWhenUnknownOrMisspelled() {
        assertRefused(Map.of("tls.cipher", "x"), "is not a TLS option a connector understands");
        assertRefused(Map.of("tls.ca-certificate", "/x"), "is the SDK's spelling");
        assertRefused(Map.of("tls.ca", directory.resolve("missing.pem").toString()), "cannot read");
    }

    /**
     * PEM files become Kafka's PEM stores, and Kafka's own loader -- run by the producer's
     * constructor, which builds its TLS engine before it dials anything -- accepts them.
     */
    @Test
    void pemMaterialBecomesPemStoresThatKafkaLoads() throws Exception {
        TestCertificate certificate = TestCertificate.in(directory);
        Map<String, String> config = base();
        config.put("tls.ca", certificate.certificatePem().toString());
        config.put("tls.certificate", certificate.certificatePem().toString());
        config.put("tls.key", certificate.keyPem().toString());
        config.put("user", "pravaha");
        config.put("password", "secret");
        KafkaSinkOptions options = options(config);

        Map<String, Object> producer = options.targetProducer();
        assertThat(producer)
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "PLAIN")
                .containsEntry("ssl.truststore.type", "PEM")
                .containsEntry(
                        "ssl.truststore.location", certificate.certificatePem().toString())
                .containsEntry("ssl.keystore.type", "PEM")
                .containsEntry("ssl.endpoint.identification.algorithm", "https");
        assertThat((String) producer.get("ssl.keystore.key")).startsWith("-----BEGIN PRIVATE KEY-----");
        assertThat((String) producer.get("ssl.keystore.certificate.chain")).startsWith("-----BEGIN CERTIFICATE-----");
        loads(options.stagingProducer());
    }

    @Test
    void keystoresArePassedByLocationAndHostnameCheckingCanBeTurnedOff() throws Exception {
        TestCertificate certificate = TestCertificate.in(directory);
        Map<String, String> config = base();
        config.put("tls.truststore", certificate.keystore().toString());
        config.put("tls.truststore.password", certificate.password());
        config.put("tls.keystore", certificate.keystore().toString());
        config.put("tls.keystore.password", certificate.password());
        config.put("tls.verify-hostname", "false");
        KafkaSinkOptions options = options(config);

        assertThat(options.targetProducer())
                .containsEntry("security.protocol", "SSL")
                .containsEntry("ssl.truststore.location", certificate.keystore().toString())
                .containsEntry("ssl.truststore.type", "PKCS12")
                .containsEntry("ssl.keystore.location", certificate.keystore().toString())
                .containsEntry("ssl.key.password", certificate.password())
                .containsEntry("ssl.endpoint.identification.algorithm", "");
        loads(options.targetProducer());
    }

    @Test
    void tlsEnabledFalseWinsOverMaterialLyingAround() throws Exception {
        TestCertificate certificate = TestCertificate.in(directory);
        Map<String, String> config = base();
        config.put("tls.enabled", "false");
        config.put("tls.ca", certificate.certificatePem().toString());
        assertThat(options(config).targetProducer())
                .containsEntry("security.protocol", "PLAINTEXT")
                .doesNotContainKey("ssl.truststore.location");
    }

    // ---------------------------------------------------------------------------------------

    /** Kafka's producer loads its TLS material in its constructor; nothing is dialled before close. */
    private static void loads(Map<String, Object> producerConfig) {
        Map<String, Object> config = new HashMap<>(producerConfig);
        config.remove("transactional.id");
        config.put("bootstrap.servers", "localhost:1");
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(config)) {
            producer.close(Duration.ZERO);
        }
    }

    private static KafkaSinkPlugin plugin(Map<String, String> config) {
        KafkaSinkPlugin plugin = new KafkaSinkPlugin();
        plugin.configure(new Ctx("spend_topic", config));
        return plugin;
    }

    private static void assertRefused(Map<String, String> overrides, String message) {
        Map<String, String> config = base();
        config.putAll(overrides);
        assertThatThrownBy(() -> options(config))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(message);
    }

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}
}
