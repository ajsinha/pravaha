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

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import javax.net.ssl.SSLContext;

import com.google.protobuf.Descriptors.Descriptor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginTls;

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
        CHANGELOG,
        /** The value is Avro's binary encoding, against {@code schema.file} or the registry's schema. */
        AVRO,
        /** The value is one protobuf message of {@code schema.descriptor}, read with {@code DynamicMessage}. */
        PROTOBUF
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
        refused.put("key.deserializer", "the source reads the value itself, in the format the binding declares");
        refused.put("value.deserializer", "the source reads the value itself, in the format the binding declares");
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

    /** {@code format: avro} with {@code schema.file}: the mapping, made and refused here. */
    private final AvroRowReader avroReader;

    /**
     * {@code format: avro} with {@code schema.reader.file}: the reader schema every writer schema is
     * resolved against, or null to read each writer schema as itself.
     */
    private final AvroSchema.Node avroReaderSchema;

    /**
     * {@code format: protobuf}: the message {@code schema.message} names in {@code schema.descriptor},
     * or null when the registry describes each record's message instead.
     */
    private final Descriptor protobufMessage;

    /** {@code schema.message}, or empty; with the registry's descriptors, what a record must select. */
    private final String protobufMessageName;

    /** {@code schema.registry.url}, or empty; with what it takes to dial it, checked at configure. */
    private final String registryUrl;

    private final SSLContext registryTls;
    private final String registryAuthorization;
    private final Duration registryTimeout;

    /**
     * The registry client, made when the first reader asks and shared by every reader of this
     * binding so that its cache is one cache. Guarded by {@code this}: it is made on whichever
     * thread opens a reader and closed when the source closes, and a source that is opened again
     * after that makes a new one rather than using a client somebody shut.
     */
    private SchemaRegistry registry;

    KafkaSourceOptions(PluginContext context) {
        this.instanceName = context.instanceName();
        this.bootstrapServers = context.require("bootstrap.servers").strip();
        this.topic = requireTopicName(context.require("topic").strip());

        String formatName = context.get("format", "json").strip().toLowerCase(Locale.ROOT);
        this.format = switch (formatName) {
            case "json" -> Format.JSON;
            case "changelog" -> Format.CHANGELOG;
            case "avro" -> Format.AVRO;
            case "protobuf" -> Format.PROTOBUF;
            default ->
                throw refusal("format '" + formatName + "' is not json, changelog, avro or protobuf. json reads a "
                        + "value that is a JSON object of the row, as kafka-sink's upsert mode writes it; "
                        + "changelog reads kafka-sink's changelog mode, weights and all; avro reads Avro's binary "
                        + "encoding against schema.file or the schema registry; protobuf reads one message of "
                        + "schema.descriptor.");
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

        // Last, because the mapping needs the declared schema and the event-time column above it.
        this.registryUrl = requireFormatOptions(context);
        this.registryTls = registryUrl.isEmpty() ? null : registryTls(context);
        this.registryAuthorization = SchemaRegistry.authorizationHeader(
                context.get("schema.registry.user", "").strip(),
                context.get("schema.registry.password", ""),
                context.get("schema.registry.token", "").strip());
        this.registryTimeout = duration(context, "schema.registry.timeout", Duration.ofSeconds(10));
        this.avroReaderSchema = format == Format.AVRO
                ? readerSchema(context.get("schema.reader.file", "").strip())
                : null;
        this.avroReader = format == Format.AVRO && registryUrl.isEmpty()
                ? avroReader(context.get("schema.file", "").strip())
                : null;
        this.protobufMessageName = context.get("schema.message", "").strip();
        this.protobufMessage = format == Format.PROTOBUF
                        && !context.get("schema.descriptor", "").strip().isEmpty()
                ? protobufMessage(
                        context.get("schema.descriptor", "").strip(),
                        context.get("schema.message", "").strip())
                : null;
    }

    /**
     * Refuses, by name, every combination of {@code format} and the schema options that cannot work;
     * returns {@code schema.registry.url}, or empty when there is none.
     *
     * <p>At {@code configure}, so a binding that names a descriptor for a JSON topic, or an Avro
     * format with nowhere to get the schema, is a registration that fails rather than a reader that
     * dead-letters every record.
     */
    private String requireFormatOptions(PluginContext context) {
        String schemaFile = context.get("schema.file", "").strip();
        String readerFile = context.get("schema.reader.file", "").strip();
        String descriptor = context.get("schema.descriptor", "").strip();
        String messageName = context.get("schema.message", "").strip();
        String registryUrl = context.get("schema.registry.url", "").strip();
        boolean registryCredentials =
                !context.get("schema.registry.user", "").strip().isEmpty()
                        || !context.get("schema.registry.password", "").strip().isEmpty()
                        || !context.get("schema.registry.token", "").strip().isEmpty();
        switch (format) {
            case JSON, CHANGELOG -> {
                refuseUnless(schemaFile.isEmpty(), "schema.file", "avro");
                refuseUnless(readerFile.isEmpty(), "schema.reader.file", "avro");
                refuseUnless(descriptor.isEmpty(), "schema.descriptor", "protobuf");
                refuseUnless(messageName.isEmpty(), "schema.message", "protobuf");
                refuseUnless(registryUrl.isEmpty(), "schema.registry.url", "avro");
            }
            case AVRO -> {
                refuseUnless(descriptor.isEmpty(), "schema.descriptor", "protobuf");
                refuseUnless(messageName.isEmpty(), "schema.message", "protobuf");
                if (schemaFile.isEmpty() == registryUrl.isEmpty()) {
                    throw refusal("format: avro needs exactly one of schema.file (the writer schema as Avro JSON, "
                            + "for a topic whose values are bare Avro) and schema.registry.url (for a topic whose "
                            + "values carry a schema id), and "
                            + (schemaFile.isEmpty() ? "has neither" : "has both"));
                }
            }
            case PROTOBUF -> {
                refuseUnless(schemaFile.isEmpty(), "schema.file", "avro");
                refuseUnless(readerFile.isEmpty(), "schema.reader.file", "avro");
                // With a registry and no descriptor, each record's schema id names its descriptor.
                if (!(descriptor.isEmpty() && !registryUrl.isEmpty())
                        && (descriptor.isEmpty() || messageName.isEmpty())) {
                    throw refusal("format: protobuf needs schema.descriptor (a FileDescriptorSet, written with "
                            + "protoc --include_imports --descriptor_set_out=x.desc) and schema.message (the "
                            + "message in it a record holds), or schema.registry.url alone (the registry "
                            + "describes each record), and "
                            + (descriptor.isEmpty() ? "has no schema.descriptor" : "has no schema.message"));
                }
            }
        }
        if (registryCredentials && registryUrl.isEmpty()) {
            throw refusal("sets schema.registry.user, schema.registry.password or schema.registry.token without "
                    + "schema.registry.url, so there is no registry to send them to");
        }
        return registryUrl;
    }

    private void refuseUnless(boolean absent, String option, String itsFormat) {
        if (!absent) {
            throw refusal("sets " + option + " with format: " + format.name().toLowerCase(Locale.ROOT)
                    + ", which does not read it; " + option + " belongs to format: " + itsFormat);
        }
    }

    /**
     * Checks {@code schema.registry.url} is dialable and returns the TLS the JDK's client will use
     * for it -- the same {@code tls.*} as the brokers', so one trust decision covers both.
     */
    private SSLContext registryTls(PluginContext context) {
        if (!registryUrl.startsWith("http://") && !registryUrl.startsWith("https://")) {
            throw refusal("schema.registry.url '" + registryUrl + "' is not an http:// or https:// URL");
        }
        try {
            URI.create(registryUrl);
        } catch (IllegalArgumentException e) {
            throw refusal("schema.registry.url '" + registryUrl + "' is not a URL: " + e.getMessage());
        }
        if (!registryUrl.startsWith("https://")) {
            return null;
        }
        if (!PluginTls.verifyHostname(context)) {
            throw refusal("sets tls.verify-hostname: false with an https schema.registry.url. The JDK's HTTP "
                    + "client verifies the certificate's name and cannot be told not to for one client, so "
                    + "the registry would be dialled with a check the brokers are not: use http:// for the "
                    + "registry, or a certificate whose name matches");
        }
        return PluginTls.from(context, KafkaErrors.BAD_CONFIGURATION).orElse(null);
    }

    /** The one registry client of this binding, made when a reader first needs it. */
    private synchronized SchemaRegistry registry() {
        if (registryUrl.isEmpty()) {
            return null;
        }
        if (registry == null) {
            registry =
                    new SchemaRegistry(instanceName, registryUrl, registryTls, registryAuthorization, registryTimeout);
        }
        return registry;
    }

    /**
     * {@code schema.reader.file}'s schema, checked against the stream's columns here -- read as itself,
     * which is what every writer schema is resolved into -- so a reader schema with no field for a
     * column is a registration that fails, not a reader that dead-letters every record.
     */
    private AvroSchema.Node readerSchema(String path) {
        if (path.isEmpty()) {
            return null;
        }
        String text;
        try {
            text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException e) {
            throw refusal("cannot read schema.reader.file '" + path + "': " + e.getMessage());
        }
        try {
            AvroSchema.Node reader = AvroSchema.parse(text);
            AvroRowReader.map(schema, reader, reader, eventTimeOrdinal);
            return reader;
        } catch (AvroSchema.Invalid e) {
            throw new ConfigurationException(
                    KafkaErrors.SCHEMA_UNMAPPABLE,
                    "source '" + instanceName + "': schema.reader.file '" + path + "' is not an Avro schema: "
                            + e.getMessage());
        } catch (KafkaValueDecoder.Unmappable e) {
            throw new ConfigurationException(
                    KafkaErrors.SCHEMA_UNMAPPABLE,
                    "source '" + instanceName + "': schema.reader.file '" + path + "' cannot be read into this "
                            + "stream's columns: " + e.getMessage());
        }
    }

    private AvroRowReader avroReader(String path) {
        String text;
        try {
            text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException e) {
            throw refusal("cannot read schema.file '" + path + "': " + e.getMessage());
        }
        try {
            AvroSchema.Node writer = AvroSchema.parse(text);
            return AvroRowReader.map(
                    schema, writer, avroReaderSchema != null ? avroReaderSchema : writer, eventTimeOrdinal);
        } catch (AvroSchema.Invalid e) {
            throw new ConfigurationException(
                    KafkaErrors.SCHEMA_UNMAPPABLE,
                    "source '" + instanceName + "': schema.file '" + path + "' is not an Avro schema: "
                            + e.getMessage());
        } catch (KafkaValueDecoder.Unmappable e) {
            throw new ConfigurationException(
                    KafkaErrors.SCHEMA_UNMAPPABLE,
                    "source '" + instanceName + "': schema.file '" + path + "' cannot be read into this stream's "
                            + "columns: " + e.getMessage());
        }
    }

    private Descriptor protobufMessage(String path, String messageName) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(path));
        } catch (IOException | InvalidPathException e) {
            throw refusal("cannot read schema.descriptor '" + path + "': " + e.getMessage());
        }
        try {
            Descriptor message = ProtobufSchemas.message(bytes, messageName);
            // Made and thrown away: what matters is that it refuses here rather than per record.
            ProtobufValueDecoder.map(schema, eventTimeOrdinal, message, !registryUrl.isEmpty());
            return message;
        } catch (KafkaValueDecoder.Unmappable e) {
            throw new ConfigurationException(
                    KafkaErrors.SCHEMA_UNMAPPABLE,
                    "source '" + instanceName + "': schema.descriptor '" + path + "' cannot be read into this "
                            + "stream's columns: " + e.getMessage());
        }
    }

    /** A decoder for one reader: the formats hold no state between records, bar the registry's cache. */
    KafkaValueDecoder newDecoder() {
        return switch (format) {
            case JSON -> new KafkaRecordDecoder(schema, false, eventTimeOrdinal);
            case CHANGELOG -> new KafkaRecordDecoder(schema, true, eventTimeOrdinal);
            case AVRO ->
                new AvroValueDecoder(instanceName, schema, eventTimeOrdinal, avroReader, avroReaderSchema, registry());
            case PROTOBUF ->
                protobufMessage != null
                        ? ProtobufValueDecoder.map(schema, eventTimeOrdinal, protobufMessage, !registryUrl.isEmpty())
                        : new ProtobufRegistryDecoder(schema, eventTimeOrdinal, protobufMessageName, registry());
        };
    }

    /** Closes the registry's HTTP client, when one was made. Called when the source closes. */
    synchronized void close() {
        if (registry != null) {
            registry.close();
            registry = null;
        }
    }

    /** What a test counts to prove an id is fetched once; -1 when no registry is configured. */
    synchronized int registryRequests() {
        return registry == null ? (registryUrl.isEmpty() ? -1 : 0) : registry.requestCount();
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
