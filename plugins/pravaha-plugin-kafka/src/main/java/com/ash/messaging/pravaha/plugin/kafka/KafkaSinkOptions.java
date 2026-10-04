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

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.net.ssl.SSLContext;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginTls;

/**
 * A {@code kafka-sink} binding's options, checked, and turned into the three clients' configurations.
 *
 * <p>Everything is refused at {@code configure}, before the sink opens: a sink that fails its first
 * batch is detached (PRV-8009), which is later and louder than a registration refused for a typo.
 */
final class KafkaSinkOptions {

    /** The staging topic's retention when this sink creates it: a week. */
    static final long DEFAULT_STAGING_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * {@code kafka.*} properties the sink sets itself, or that would quietly weaken what it promises,
     * each with what to do instead.
     */
    private static final Map<String, String> REFUSED = refused();

    private static Map<String, String> refused() {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("bootstrap.servers", "set bootstrap.servers on the binding itself");
        refused.put("transactional.id", "set transactional.id on the binding itself");
        refused.put("key.serializer", "the sink writes the key itself, in the binding's key.format");
        refused.put("value.serializer", "the sink writes the value itself, in the binding's format");
        refused.put("key.deserializer", "the sink reads its staging topic itself");
        refused.put("value.deserializer", "the sink reads its staging topic itself");
        refused.put("group.id", "the staging reader's group is commit.group");
        refused.put("isolation.level", "the staging reader must read committed data");
        refused.put("enable.auto.commit", "the sink commits its marker offset inside each transaction");
        refused.put("auto.offset.reset", "the staging reader seeks to exact offsets");
        refused.put("security.protocol", "it follows from the tls.* options and user/password");
        refused.put("sasl.mechanism", "set sasl.mechanism on the binding itself");
        refused.put("sasl.jaas.config", "set user and password on the binding itself");
        return Map.copyOf(refused);
    }

    final String instanceName;
    final String bootstrapServers;
    final String topic;
    final StreamSchema schema;
    final List<String> keyNames;
    final int[] keyOrdinals;
    final boolean changelog;
    /** {@code json}, {@code avro} or {@code protobuf}. */
    final String format;
    /** The Avro or protobuf value writer, or null for JSON. */
    final KafkaRecords.@Nullable ValueEncoder valueEncoder;
    /** {@code json}, {@code string}, {@code avro} or {@code protobuf}. */
    final String keyFormat;
    /** The key writer over the key columns, or null for a JSON key. */
    final KafkaRecords.@Nullable ValueEncoder keyEncoder;

    final boolean transactional;
    final String transactionalId;
    final String stagingTopic;
    final long stagingRetentionMs;
    final String commitGroup;

    /** Security and pass-through properties, before each client takes the ones it knows. */
    private final Map<String, Object> shared;

    KafkaSinkOptions(PluginContext context) {
        this.instanceName = context.instanceName();
        this.bootstrapServers = context.require("bootstrap.servers").strip();
        this.topic = requireTopicName(context.require("topic").strip(), "topic");
        this.schema = KafkaSchema.parse(topic, context.require("schema"));

        String mode = context.get("mode", "upsert").strip().toLowerCase(Locale.ROOT);
        this.changelog = switch (mode) {
            case "upsert" -> false;
            case "changelog" -> true;
            default -> throw refusal("mode '" + mode + "' is not upsert or changelog");
        };
        this.format = context.get("format", "json").strip().toLowerCase(Locale.ROOT);
        this.keyFormat = context.get("key.format", "json").strip().toLowerCase(Locale.ROOT);
        int[] precisions = KafkaSchema.precisions(context.require("schema"));

        List<String> names = new ArrayList<>();
        for (String part : context.get("key.columns", "").split(",", -1)) {
            if (!part.isBlank()) {
                names.add(part.strip());
            }
        }
        if (!changelog && names.isEmpty()) {
            throw refusal("needs key.columns in upsert mode: the record key is what a compacted topic keeps one "
                    + "value per, and what a retraction's tombstone deletes. It must be the query's --keys. "
                    + "For a query with no key, use mode: changelog.");
        }
        this.keyNames = List.copyOf(names);
        this.keyOrdinals = new int[keyNames.size()];
        for (int k = 0; k < keyNames.size(); k++) {
            keyOrdinals[k] = keyOrdinal(keyNames.get(k));
        }
        SchemaRegistry registry = registry(context);
        try {
            KafkaSinkEncoders encoders = new KafkaSinkEncoders(instanceName, registry, this::refusal);
            this.valueEncoder = valueEncoder(context, encoders, precisions);
            this.keyEncoder = keyEncoder(context, encoders, precisions);
        } finally {
            if (registry != null) {
                registry.close();
            }
        }

        this.transactional = parseBoolean(context, "transactional", "true");
        this.transactionalId = context.get("transactional.id", instanceName).strip();
        if (transactionalId.isEmpty() || transactionalId.length() > 200) {
            throw refusal("transactional.id must be 1 to 200 characters, got '" + transactionalId + "'");
        }
        this.stagingTopic = requireTopicName(
                context.get("staging.topic", "pravaha-staging." + sanitised(transactionalId))
                        .strip(),
                "staging.topic");
        if (transactional && stagingTopic.equals(topic)) {
            throw refusal("staging.topic is the target topic. The staging topic holds changes that are not yet "
                    + "committed, and readers of the target must never see them.");
        }
        this.stagingRetentionMs =
                parseLong(context, "staging.retention.ms", Long.toString(DEFAULT_STAGING_RETENTION_MS));
        this.commitGroup =
                context.get("commit.group", "pravaha-sink." + transactionalId).strip();
        if (commitGroup.isEmpty()) {
            throw refusal("commit.group must not be empty");
        }

        Map<String, Object> security = new KafkaSecurity(this::refusal).properties(context);

        Map<String, Object> merged = new LinkedHashMap<>(passThrough(context));
        merged.putAll(security);
        this.shared = merged;
    }

    /**
     * The value writer {@code format} names, with its schema options checked here: a schema option for
     * another format, a changelog mode with nowhere to put its weight, or a column the schema cannot
     * hold exactly is refused at configure rather than at the first batch.
     */
    private KafkaRecords.@Nullable ValueEncoder valueEncoder(
            PluginContext context, KafkaSinkEncoders encoders, int[] precisions) {
        String schemaFile = context.get("schema.file", "").strip();
        String schemaId = context.get("schema.id", "").strip();
        String descriptor = context.get("schema.descriptor", "").strip();
        String message = context.get("schema.message", "").strip();
        switch (format) {
            case "json" -> {}
            case "avro", "protobuf" -> {
                if (changelog) {
                    throw refusal("mode: changelog is JSON only: its weight and op are part of the value, and an "
                            + format + " value has only the schema's fields to put them in. Use mode: upsert (a "
                            + "retraction is a tombstone), or format: json for the changelog.");
                }
            }
            default ->
                throw refusal("format '" + format + "' is not json, avro or protobuf. avro writes Avro's binary "
                        + "encoding of schema.file; protobuf writes one message of schema.descriptor.");
        }
        refuseUnless(format.equals("avro") || schemaFile.isEmpty(), "schema.file", "avro");
        refuseUnless(
                format.equals("avro") || format.equals("protobuf") || schemaId.isEmpty(),
                "schema.id",
                "avro or format: protobuf");
        refuseUnless(format.equals("protobuf") || descriptor.isEmpty(), "schema.descriptor", "protobuf");
        refuseUnless(format.equals("protobuf") || message.isEmpty(), "schema.message", "protobuf");
        if (format.equals("json")) {
            return null;
        }
        return encoders.build(
                new KafkaSinkEncoders.Spec("value", "schema.", format, schemaFile, schemaId, descriptor, message),
                schema,
                precisions);
    }

    /**
     * The key writer {@code key.format} names, over the key columns in {@code key.columns} order --
     * or every column, in a changelog with no key columns -- with its options checked here (KSF-1).
     */
    private KafkaRecords.@Nullable ValueEncoder keyEncoder(
            PluginContext context, KafkaSinkEncoders encoders, int[] precisions) {
        String file = context.get("key.schema.file", "").strip();
        String id = context.get("key.schema.id", "").strip();
        String message = context.get("key.schema.message", "").strip();
        String descriptor = context.get("key.schema.descriptor", "").strip();
        switch (keyFormat) {
            case "json", "string", "avro", "protobuf" -> {}
            default ->
                throw refusal("key.format '" + keyFormat + "' is not json, string, avro or protobuf. json writes the "
                        + "key columns as a JSON object; string one key column as its text; avro and protobuf "
                        + "the key columns as key.schema.file's record or key.schema.message");
        }
        refuseUnless(keyFormat.equals("avro") || file.isEmpty(), "key.schema.file", "avro", "key.format");
        refuseUnless(
                keyFormat.equals("avro") || keyFormat.equals("protobuf") || id.isEmpty(),
                "key.schema.id",
                "avro or key.format: protobuf",
                "key.format");
        refuseUnless(keyFormat.equals("protobuf") || message.isEmpty(), "key.schema.message", "protobuf", "key.format");
        refuseUnless(
                keyFormat.equals("protobuf") || descriptor.isEmpty(),
                "key.schema.descriptor",
                "protobuf",
                "key.format");
        if (keyFormat.equals("json")) {
            return null;
        }
        if (keyFormat.equals("protobuf") && descriptor.isEmpty() && id.isEmpty()) {
            // The key message is usually declared in the same .proto as the value's.
            descriptor = context.get("schema.descriptor", "").strip();
        }
        int[] ordinals = keyOrdinals.length > 0 ? keyOrdinals : allOrdinals();
        StreamSchema.Builder keys = StreamSchema.builder(topic + "-key");
        int[] keyPrecisions = new int[ordinals.length];
        for (int k = 0; k < ordinals.length; k++) {
            keys.field(
                    schema.field(ordinals[k]).name(), schema.field(ordinals[k]).type());
            keyPrecisions[k] = precisions[ordinals[k]];
        }
        return encoders.build(
                new KafkaSinkEncoders.Spec("key", "key.schema.", keyFormat, file, id, descriptor, message),
                keys.build(),
                keyPrecisions);
    }

    private int[] allOrdinals() {
        int[] all = new int[schema.fieldCount()];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        return all;
    }

    /**
     * The registry {@code schema.registry.url} names, to check the binding's schema ids against at
     * configuration (KSF-3), or null when there is none. The caller closes it.
     */
    private @Nullable SchemaRegistry registry(PluginContext context) {
        String url = context.get("schema.registry.url", "").strip();
        String user = context.get("schema.registry.user", "").strip();
        String password = context.get("schema.registry.password", "");
        String token = context.get("schema.registry.token", "").strip();
        if (url.isEmpty()) {
            if (!user.isEmpty() || !password.strip().isEmpty() || !token.isEmpty()) {
                throw refusal("sets schema.registry.user, schema.registry.password or schema.registry.token "
                        + "without schema.registry.url, so there is no registry to send them to");
            }
            return null;
        }
        if (context.get("schema.id", "").isBlank()
                && context.get("key.schema.id", "").isBlank()) {
            throw refusal("sets schema.registry.url and neither schema.id nor key.schema.id: the sink registers "
                    + "nothing, and asks the registry only to check the ids it writes. Set the id the schema was "
                    + "registered under, or remove schema.registry.url");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw refusal("schema.registry.url '" + url + "' is not an http:// or https:// URL");
        }
        try {
            URI.create(url);
        } catch (IllegalArgumentException e) {
            throw refusal("schema.registry.url '" + url + "' is not a URL: " + e.getMessage());
        }
        SSLContext tls = null;
        if (url.startsWith("https://")) {
            if (!PluginTls.verifyHostname(context)) {
                throw refusal("sets tls.verify-hostname: false with an https schema.registry.url. The JDK's HTTP "
                        + "client verifies the certificate's name and cannot be told not to for one client; use "
                        + "http:// for the registry, or a certificate whose name matches");
            }
            tls = PluginTls.from(context, KafkaErrors.BAD_CONFIGURATION).orElse(null);
        }
        return new SchemaRegistry(
                instanceName,
                "sink",
                "Its schema ids are checked against the registry before the sink writes a record, so the sink is "
                        + "not registered until the registry answers.",
                url,
                tls,
                SchemaRegistry.authorizationHeader(user, password, token),
                registryTimeout(context));
    }

    private Duration registryTimeout(PluginContext context) {
        String raw = context.get("schema.registry.timeout", "10s").strip().toLowerCase(Locale.ROOT);
        try {
            Duration parsed = raw.endsWith("ms")
                    ? Duration.ofMillis(
                            Long.parseLong(raw.substring(0, raw.length() - 2).strip()))
                    : raw.endsWith("s")
                            ? Duration.ofSeconds(Long.parseLong(
                                    raw.substring(0, raw.length() - 1).strip()))
                            : Duration.ofMinutes(Long.parseLong(
                                    raw.substring(0, raw.length() - 1).strip()));
            if (parsed.isNegative() || parsed.isZero() || !(raw.endsWith("s") || raw.endsWith("m"))) {
                throw new NumberFormatException(raw);
            }
            return parsed;
        } catch (RuntimeException e) {
            throw refusal("schema.registry.timeout must be a duration such as 500ms, 10s or 1m, got '" + raw + "'");
        }
    }

    private void refuseUnless(boolean fine, String option, String format) {
        refuseUnless(fine, option, format, "format");
    }

    private void refuseUnless(boolean fine, String option, String format, String formatOption) {
        if (!fine) {
            throw refusal(option + " is for " + formatOption + ": " + format + ", and this sink writes "
                    + (formatOption.equals("format") ? this.format : keyFormat));
        }
    }

    private int keyOrdinal(String key) {
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            if (schema.field(ordinal).name().equalsIgnoreCase(key)) {
                TypeName type = schema.field(ordinal).type().typeName();
                if (type == TypeName.FLOAT32 || type == TypeName.FLOAT64) {
                    throw refusal("key column '" + key + "' is " + type + ". A floating-point key makes two values "
                            + "that print alike two records, and a tombstone that misses by a rounding error "
                            + "deletes nothing.");
                }
                if (schema.field(ordinal).type().nullable()) {
                    throw refusal(
                            "key column '" + key + "' is declared nullable; a record cannot be keyed by " + "nothing");
                }
                return ordinal;
            }
        }
        throw refusal("key column '" + key + "' is not in the declared schema, which has "
                + schema.fields().stream().map(f -> f.name()).toList());
    }

    /** The target producer, or the only producer when not transactional. */
    Map<String, Object> targetProducer() {
        Map<String, Object> config = only(ProducerConfig.configNames());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "pravaha-" + instanceName);
        if (transactional) {
            config.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, transactionalId);
        }
        return producerBasics(config);
    }

    /** The producer that stages rows between checkpoints: idempotent, not transactional. */
    Map<String, Object> stagingProducer() {
        Map<String, Object> config = only(ProducerConfig.configNames());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "pravaha-" + instanceName + "-staging");
        return producerBasics(config);
    }

    private Map<String, Object> producerBasics(Map<String, Object> config) {
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return config;
    }

    /** Reads staged changes back and the commit marker's offset. Never subscribes, never commits. */
    Map<String, Object> stagingConsumer() {
        Map<String, Object> config = only(ConsumerConfig.configNames());
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "pravaha-" + instanceName + "-staging-reader");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, commitGroup);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // read_committed also makes committed() ask the broker for a *stable* offset, one no open
        // transaction is still writing, which is the only kind the idempotence check may trust.
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return config;
    }

    Map<String, Object> admin() {
        Map<String, Object> config = only(AdminClientConfig.configNames());
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(AdminClientConfig.CLIENT_ID_CONFIG, "pravaha-" + instanceName + "-admin");
        return config;
    }

    private Map<String, Object> only(Set<String> known) {
        Map<String, Object> config = new LinkedHashMap<>();
        shared.forEach((key, value) -> {
            if (known.contains(key)) {
                config.put(key, value);
            }
        });
        return config;
    }

    /**
     * {@code kafka.<property>} options, with the prefix removed. Each client takes the ones it
     * knows; a property no client knows is refused, since a misspelled one would otherwise be
     * dropped with a log line nobody reads.
     */
    private Map<String, Object> passThrough(PluginContext context) {
        Set<String> anyClient = new TreeSet<>(ProducerConfig.configNames());
        anyClient.addAll(ConsumerConfig.configNames());
        anyClient.addAll(AdminClientConfig.configNames());
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, String> option : new TreeSet<>(context.config().keySet())
                .stream()
                        .filter(key -> key.startsWith("kafka."))
                        .map(key -> Map.entry(key, context.config().get(key)))
                        .toList()) {
            String property = option.getKey().substring("kafka.".length());
            String value = option.getValue() == null ? "" : option.getValue().strip();
            String instead = REFUSED.get(property);
            if (instead != null) {
                throw refusal("'" + option.getKey() + "' is not passed through: " + instead + ".");
            }
            if (property.startsWith("ssl.")) {
                throw refusal("'" + option.getKey() + "' is not passed through: TLS is configured with the shared "
                        + "tls.* options (docs/guides/CONNECTOR_TLS.md), so it reads the same for every connector.");
            }
            if (!anyClient.contains(property)) {
                throw refusal("'" + option.getKey() + "' is not a Kafka client property, so it would be dropped "
                        + "without effect. Check its spelling against the Kafka producer configuration.");
            }
            switch (property) {
                case "enable.idempotence" -> {
                    if (!Boolean.parseBoolean(value)) {
                        throw refusal("'kafka.enable.idempotence: " + value + "' is refused. Without idempotence a "
                                + "retried send can be written twice or out of order, and a transactional "
                                + "producer requires it; this sink turns it on and keeps it on.");
                    }
                }
                case "acks" -> {
                    if (!value.equals("all") && !value.equals("-1")) {
                        throw refusal("'kafka.acks: " + value + "' is refused. Anything less than all lets a "
                                + "leader failover lose a change the sink was told was written.");
                    }
                }
                case "compression.type" -> requireCodec(value);
                default -> {}
            }
            properties.put(property, value);
        }
        return properties;
    }

    /** lz4 is refused by name, and snappy or zstd when the native library does not load here (ADR-053). */
    private void requireCodec(String codec) {
        String why = KafkaCodecs.whySinkCannotWrite(codec);
        if (why != null) {
            throw refusal(why);
        }
    }

    /** A topic name Kafka accepts: letters, digits, '.', '_' and '-', at most 249 characters. */
    private String requireTopicName(String name, String option) {
        if (!name.matches("[A-Za-z0-9._-]{1,249}") || name.equals(".") || name.equals("..")) {
            throw refusal(option + " '" + name + "' is not a valid Kafka topic name (letters, digits, '.', '_' "
                    + "and '-', at most 249 characters)");
        }
        return name;
    }

    private static String sanitised(String id) {
        String cleaned = id.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    private boolean parseBoolean(PluginContext context, String option, String fallback) {
        String value = context.get(option, fallback).strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw refusal(option + " must be true or false, got '" + value + "'");
        };
    }

    private long parseLong(PluginContext context, String option, String fallback) {
        String value = context.get(option, fallback).strip();
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0 && parsed != -1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw refusal(
                    option + " must be a positive number of milliseconds, or -1 for no limit; got '" + value + "'");
        }
    }

    private ConfigurationException refusal(String message) {
        return new ConfigurationException(KafkaErrors.BAD_CONFIGURATION, "sink '" + instanceName + "' " + message);
    }
}
