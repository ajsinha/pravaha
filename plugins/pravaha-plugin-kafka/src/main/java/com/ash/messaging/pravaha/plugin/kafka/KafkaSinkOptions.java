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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

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
        refused.put("key.serializer", "the sink writes the key itself, as JSON");
        refused.put("value.serializer", "the sink writes the value itself, as JSON");
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

    /** Codecs kafka-clients loads from a library this plugin does not ship; see the pom. */
    private static final Map<String, String> CODEC_CLASSES = Map.of(
            "lz4", "net.jpountz.lz4.LZ4Factory",
            "snappy", "org.xerial.snappy.Snappy",
            "zstd", "com.github.luben.zstd.Zstd");

    final String instanceName;
    final String bootstrapServers;
    final String topic;
    final StreamSchema schema;
    final List<String> keyNames;
    final int[] keyOrdinals;
    final boolean changelog;
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

        String format = context.get("format", "json").strip().toLowerCase(Locale.ROOT);
        if (!format.equals("json")) {
            throw refusal("format '" + format + "' is not built; json is the one format this sink writes");
        }
        String mode = context.get("mode", "upsert").strip().toLowerCase(Locale.ROOT);
        this.changelog = switch (mode) {
            case "upsert" -> false;
            case "changelog" -> true;
            default -> throw refusal("mode '" + mode + "' is not upsert or changelog");
        };

        List<String> names = new ArrayList<>();
        for (String part : context.get("key.columns", "").split(",")) {
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
                        + "tls.* options (docs/CONNECTOR_TLS.md), so it reads the same for every connector.");
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

    private void requireCodec(String codec) {
        String library = CODEC_CLASSES.get(codec.toLowerCase(Locale.ROOT));
        if (library == null) {
            return;
        }
        try {
            Class.forName(library, false, KafkaSinkOptions.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw refusal("'kafka.compression.type: " + codec + "' needs " + library + ", which this plugin does "
                    + "not ship: the codec libraries carry per-platform native code. none and gzip work as "
                    + "shipped; to use " + codec + ", put its library on this plugin's classpath.");
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
