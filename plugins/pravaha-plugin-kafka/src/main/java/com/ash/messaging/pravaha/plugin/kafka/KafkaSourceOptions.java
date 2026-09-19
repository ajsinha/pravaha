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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.PluginContext;

/**
 * A {@code kafka} source binding's options, checked, and turned into its consumers' configuration.
 *
 * <p>Refused at {@code configure}, like the sink's: a registration refused for a typo is earlier and
 * clearer than a reader that fails on its first record.
 */
final class KafkaSourceOptions {

    /** How a record's value becomes a row. */
    enum Format {
        /** The value is a JSON object of the row's columns, by name: what {@code kafka-sink} upsert mode writes. */
        JSON,
        /** The value is {@code kafka-sink}'s changelog envelope, {@code {"op":..,"weight":n,"row":{..}}}. */
        CHANGELOG
    }

    /**
     * {@code kafka.*} consumer properties the source sets itself, or that would change what its
     * offsets mean, each with what to do instead.
     */
    private static final Map<String, String> REFUSED = refused();

    private static Map<String, String> refused() {
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("bootstrap.servers", "set bootstrap.servers on the binding itself");
        refused.put("group.id", "the checkpoint is the position, not a group; set monitoring.group to report it");
        refused.put("isolation.level", "set isolation.level on the binding itself");
        refused.put(
                "enable.auto.commit",
                "offsets are committed only after a durable checkpoint, and only for monitoring.group");
        refused.put(
                "auto.offset.reset", "the source seeks to exact offsets; start.from chooses where a new one starts");
        refused.put("key.deserializer", "the source reads the value itself, as JSON");
        refused.put("value.deserializer", "the source reads the value itself, as JSON");
        refused.put("allow.auto.create.topics", "the source never creates the topic it reads");
        refused.put("security.protocol", "it follows from the tls.* options and user/password");
        refused.put("sasl.mechanism", "set sasl.mechanism on the binding itself");
        refused.put("sasl.jaas.config", "set user and password on the binding itself");
        return Map.copyOf(refused);
    }

    final String instanceName;
    final String bootstrapServers;
    final String topic;
    final StreamSchema schema;
    final Format format;
    final boolean skipTombstones;
    /** The column carrying event time, or -1 for the record's own timestamp. */
    final int eventTimeOrdinal;

    final boolean startAtLatest;
    final boolean readCommitted;
    /** Empty when nothing is committed to any group. */
    final String monitoringGroup;

    final int bufferRecords;
    final Duration startTimeout;
    final long lagWarnRecords;

    /** Security and pass-through properties. */
    private final Map<String, Object> shared;

    KafkaSourceOptions(PluginContext context) {
        this.instanceName = context.instanceName();
        this.bootstrapServers = context.require("bootstrap.servers").strip();
        this.topic = requireTopicName(context.require("topic").strip());

        String formatName = context.get("format", "json").strip().toLowerCase(Locale.ROOT);
        this.format = switch (formatName) {
            case "json" -> Format.JSON;
            case "changelog" -> Format.CHANGELOG;
            default ->
                throw refusal("format '" + formatName + "' is not json or changelog. json reads a value that is "
                        + "a JSON object of the row, as kafka-sink's upsert mode writes it; changelog reads "
                        + "kafka-sink's changelog mode, weights and all.");
        };
        String tombstone = context.get("tombstone", "reject").strip().toLowerCase(Locale.ROOT);
        this.skipTombstones = switch (tombstone) {
            case "reject" -> false;
            case "skip" -> true;
            default -> throw refusal("tombstone must be reject or skip, got '" + tombstone + "'");
        };

        StreamSchema parsed = KafkaSchema.parse(instanceName, context.require("schema"));
        String eventTime = context.get("event.time", "").strip();
        if (eventTime.isEmpty()) {
            this.schema = parsed;
            this.eventTimeOrdinal = -1;
        } else {
            int ordinal = ordinalOf(parsed, eventTime);
            if (ordinal < 0) {
                throw refusal("event.time names '" + eventTime + "', which is not a column of the declared schema "
                        + parsed.fields().stream().map(f -> f.name()).toList()
                        + ". Leave it out to use each record's Kafka timestamp.");
            }
            if (parsed.field(ordinal).type().typeName() != TypeName.TIMESTAMP_LTZ) {
                throw refusal("event.time names '" + eventTime + "', which is declared "
                        + parsed.field(ordinal).type().typeName() + "; only a TIMESTAMP column can be a row's event "
                        + "time");
            }
            StreamSchema.Builder builder = StreamSchema.builder(instanceName);
            parsed.fields().forEach(field -> builder.field(field.name(), field.type()));
            this.schema = builder.eventTime(parsed.field(ordinal).name()).build();
            this.eventTimeOrdinal = ordinal;
        }

        String start = context.get("start.from", "earliest").strip().toLowerCase(Locale.ROOT);
        this.startAtLatest = switch (start) {
            case "earliest" -> false;
            case "latest" -> true;
            default ->
                throw refusal("start.from must be earliest or latest, got '" + start + "'. It applies only when "
                        + "there is no checkpoint; a restore always resumes from the checkpoint's offsets.");
        };
        String isolation =
                context.get("isolation.level", "read_committed").strip().toLowerCase(Locale.ROOT);
        this.readCommitted = switch (isolation) {
            case "read_committed" -> true;
            case "read_uncommitted" -> false;
            default ->
                throw refusal("isolation.level must be read_committed or read_uncommitted, got '" + isolation + "'");
        };
        this.monitoringGroup = context.get("monitoring.group", "").strip();

        this.bufferRecords = (int) positive(context, "buffer.records", 10_000L, Integer.MAX_VALUE);
        this.startTimeout = duration(context, "start.timeout", Duration.ofSeconds(30));
        this.lagWarnRecords = positive(context, "lag.warn.records", 100_000L, Long.MAX_VALUE);

        Map<String, Object> merged = new LinkedHashMap<>(passThrough(context));
        merged.putAll(new KafkaSecurity(this::refusal).properties(context));
        this.shared = merged;
    }

    /** The consumer that reads one partition, fetching on the reader's own thread. */
    Map<String, Object> readerConsumer(TopicPartition partition) {
        Map<String, Object> config = basics();
        config.put(
                ConsumerConfig.CLIENT_ID_CONFIG,
                "pravaha-" + instanceName + "-" + partition.topic() + "-" + partition.partition());
        if (!monitoringGroup.isEmpty()) {
            // Only so checkpointed offsets can be committed where lag monitors look. The consumer
            // never subscribes, so the group never assigns it anything and never rebalances it.
            config.put(ConsumerConfig.GROUP_ID_CONFIG, monitoringGroup);
        }
        return config;
    }

    /** Partitions, beginning and end offsets, and health: never fetches a record. */
    Map<String, Object> metadataConsumer() {
        Map<String, Object> config = basics();
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "pravaha-" + instanceName + "-metadata");
        return config;
    }

    private Map<String, Object> basics() {
        Map<String, Object> config = new LinkedHashMap<>(shared);
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // A position the log no longer has is refused (PRV-5106), never quietly reset.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, readCommitted ? "read_committed" : "read_uncommitted");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return config;
    }

    /** {@code kafka.<property>} options, prefix removed; only consumer properties, and not the refused ones. */
    private Map<String, Object> passThrough(PluginContext context) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String key : new TreeSet<>(context.config().keySet())) {
            if (!key.startsWith("kafka.")) {
                continue;
            }
            String property = key.substring("kafka.".length());
            String value = context.config().get(key) == null
                    ? ""
                    : context.config().get(key).strip();
            String instead = REFUSED.get(property);
            if (instead != null) {
                throw refusal("'" + key + "' is not passed through: " + instead + ".");
            }
            if (property.startsWith("ssl.")) {
                throw refusal("'" + key + "' is not passed through: TLS is configured with the shared tls.* "
                        + "options (docs/CONNECTOR_TLS.md), so it reads the same for every connector.");
            }
            if (!ConsumerConfig.configNames().contains(property)) {
                throw refusal("'" + key + "' is not a Kafka consumer property, so it would be dropped without "
                        + "effect. Check its spelling against the Kafka consumer configuration.");
            }
            properties.put(property, value);
        }
        return properties;
    }

    private static int ordinalOf(StreamSchema schema, String name) {
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            if (schema.field(ordinal).name().equals(name)) {
                return ordinal;
            }
        }
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            if (schema.field(ordinal).name().equalsIgnoreCase(name)) {
                return ordinal;
            }
        }
        return -1;
    }

    private long positive(PluginContext context, String option, long fallback, long max) {
        String value = context.get(option, Long.toString(fallback)).strip();
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1 || parsed > max) {
                throw new NumberFormatException(value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw refusal(option + " must be a whole number from 1 to " + max + ", got '" + value + "'");
        }
    }

    /** {@code 500ms}, {@code 10s}, {@code 5m}, {@code 1h}, or an ISO-8601 duration; more than zero. */
    private Duration duration(PluginContext context, String option, Duration fallback) {
        String raw = context.get(option, "").strip().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            Duration parsed;
            if (raw.startsWith("p")) {
                parsed = Duration.parse(raw.toUpperCase(Locale.ROOT));
            } else if (raw.endsWith("ms")) {
                parsed = Duration.ofMillis(
                        Long.parseLong(raw.substring(0, raw.length() - 2).strip()));
            } else {
                long amount = Long.parseLong(raw.substring(0, raw.length() - 1).strip());
                parsed = switch (raw.charAt(raw.length() - 1)) {
                    case 's' -> Duration.ofSeconds(amount);
                    case 'm' -> Duration.ofMinutes(amount);
                    case 'h' -> Duration.ofHours(amount);
                    default -> throw new NumberFormatException(raw);
                };
            }
            if (parsed.isNegative() || parsed.isZero()) {
                throw new NumberFormatException(raw);
            }
            return parsed;
        } catch (RuntimeException e) {
            throw refusal(option + " must be a duration such as 500ms, 10s or 5m, got '" + raw + "'");
        }
    }

    /** A topic name Kafka accepts: letters, digits, '.', '_' and '-', at most 249 characters. */
    private String requireTopicName(String name) {
        if (!name.matches("[A-Za-z0-9._-]{1,249}") || name.equals(".") || name.equals("..")) {
            throw refusal("topic '" + name + "' is not a valid Kafka topic name (letters, digits, '.', '_' and '-', "
                    + "at most 249 characters)");
        }
        return name;
    }

    ConfigurationException refusal(String message) {
        return new ConfigurationException(KafkaErrors.BAD_CONFIGURATION, "source '" + instanceName + "' " + message);
    }
}
