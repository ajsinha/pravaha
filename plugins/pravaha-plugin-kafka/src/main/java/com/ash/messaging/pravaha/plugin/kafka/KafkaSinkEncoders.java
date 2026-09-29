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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.function.Function;

import com.google.protobuf.Descriptors.Descriptor;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * {@code kafka-sink}'s Avro and Protobuf writers for a record's value or key, built from a binding's
 * schema options and, when {@code schema.registry.url} is set, checked against the registry.
 *
 * <p><strong>A schema id is checked, never trusted</strong> (KSF-3). With a registry, {@code
 * schema.id} (or {@code key.schema.id}) is fetched at configuration: an Avro id must name the same
 * schema as {@code schema.file} when both are given, and is the writer schema when only the id is;
 * either way the stream's columns must map to it exactly ({@code PRV-5108} names the id and the
 * reason). A Protobuf id must hold {@code schema.message}, which must be the message {@code
 * schema.descriptor} describes when both are given, and its place in the registered file becomes the
 * Confluent framing's message indexes (KSF-2). Without a registry, an Avro id is still written as
 * given, unchecked; a Protobuf id is refused, since only the registry says which indexes name the
 * message.
 *
 * <p><strong>Keys</strong> (KSF-1) are written the same way from the key columns alone, in {@code
 * key.columns} order, with {@code key.schema.*}; or with {@code key.format: string}, one key column
 * as its text, UTF-8.
 */
final class KafkaSinkEncoders {

    /**
     * What one side of a record is written with.
     *
     * @param side "value" or "key", as a refusal says it
     * @param prefix the options' prefix: {@code schema.} or {@code key.schema.}
     * @param format {@code avro}, {@code protobuf} or {@code string}
     */
    record Spec(String side, String prefix, String format, String file, String id, String descriptor, String message) {}

    private final String instanceName;
    private final SchemaRegistry registry;
    private final Function<String, ConfigurationException> refusal;

    KafkaSinkEncoders(String instanceName, SchemaRegistry registry, Function<String, ConfigurationException> refusal) {
        this.instanceName = instanceName;
        this.registry = registry;
        this.refusal = refusal;
    }

    /** The writer {@code spec} names for {@code columns}; {@code precisions} as {@link KafkaSchema#precisions}. */
    KafkaRecords.ValueEncoder build(Spec spec, StreamSchema columns, int[] precisions) {
        try {
            return switch (spec.format()) {
                case "avro" -> avro(spec, columns, precisions);
                case "protobuf" -> protobuf(spec, columns);
                case "string" -> string(columns);
                default -> throw new IllegalArgumentException(spec.format());
            };
        } catch (AvroSchema.Invalid e) {
            throw unmappable(spec, "is not an Avro schema: " + e.getMessage());
        } catch (KafkaValueDecoder.Unmappable e) {
            throw unmappable(spec, "cannot be written from the stream's columns: " + e.getMessage());
        }
    }

    private KafkaRecords.ValueEncoder avro(Spec spec, StreamSchema columns, int[] precisions) {
        int id = schemaId(spec);
        String text = spec.file().isEmpty() ? null : new String(read(spec.file(), spec.prefix() + "file"), UTF8);
        if (id >= 0 && registry != null) {
            String registered = registry.schemaText(id);
            if (text != null && !sameJson(text, registered)) {
                throw unmappable(
                        spec,
                        "is not the schema the registry holds under " + spec.prefix() + "id " + id + ": "
                                + shorten(registered) + ". Every consumer would decode these records with the "
                                + "registry's schema; point " + spec.prefix() + "id at the id " + spec.prefix()
                                + "file was registered under, or leave " + spec.prefix() + "file out to write "
                                + "with the registry's");
            }
            text = registered;
        }
        if (text == null) {
            throw refusal.apply("format: avro needs " + spec.prefix() + "file, the writer schema as Avro JSON, or "
                    + spec.prefix() + "id with schema.registry.url, for the " + spec.side());
        }
        return AvroRowWriter.map(columns, AvroSchema.parse(text), id, precisions);
    }

    private KafkaRecords.ValueEncoder protobuf(Spec spec, StreamSchema columns) {
        int id = schemaId(spec);
        if (spec.message().isEmpty()) {
            throw refusal.apply("format: protobuf needs " + spec.prefix() + "message, the message the " + spec.side()
                    + " is written as");
        }
        Descriptor fromFile = spec.descriptor().isEmpty()
                ? null
                : ProtobufSchemas.message(read(spec.descriptor(), spec.prefix() + "descriptor"), spec.message());
        if (id < 0) {
            if (fromFile == null) {
                throw refusal.apply("format: protobuf needs " + spec.prefix() + "descriptor (a FileDescriptorSet, "
                        + "written with protoc --include_imports --descriptor_set_out=x.desc), or " + spec.prefix()
                        + "id with schema.registry.url, for the " + spec.side());
            }
            return ProtobufRowWriter.map(columns, fromFile);
        }
        if (registry == null) {
            throw refusal.apply(spec.prefix() + "id with format: protobuf needs schema.registry.url: the Confluent "
                    + "framing names the message by its place in the registered schema, which only the registry "
                    + "can say");
        }
        SchemaRegistry.ProtobufSchema fetched = registry.protobufSchema(id);
        ProtobufSchemas.Located located = ProtobufSchemas.locate(
                fetched.files(), fetched.root(), spec.message(), "schema id " + id + " in the registry");
        if (fromFile != null && !fromFile.toProto().equals(located.message().toProto())) {
            throw unmappable(
                    spec,
                    "describes " + fromFile.getFullName() + " differently from schema id " + id + " in the "
                            + "registry. Every consumer would decode these records with the registry's; point "
                            + spec.prefix() + "id at the id this descriptor was registered under, or leave "
                            + spec.prefix() + "descriptor out to write with the registry's");
        }
        return ProtobufRowWriter.map(columns, located.message()).framed(id, located.indexes());
    }

    /** One key column as its text: what a consumer reading keys as plain strings expects. */
    private KafkaRecords.ValueEncoder string(StreamSchema columns) {
        if (columns.fieldCount() != 1) {
            throw refusal.apply("key.format: string writes one key column as text, and the key has "
                    + columns.fieldCount() + " columns "
                    + columns.fields().stream().map(f -> f.name()).toList()
                    + ". Use key.format: json, avro or protobuf for a key of several columns");
        }
        TypeName type = columns.field(0).type().typeName();
        if (type == TypeName.BYTES) {
            throw refusal.apply("key.format: string cannot write the BYTES key column '"
                    + columns.field(0).name() + "' as text; use key.format: json (base64) or avro");
        }
        return values -> text(type, values[0]).getBytes(StandardCharsets.UTF_8);
    }

    private static String text(TypeName type, Object value) {
        return switch (type) {
            case DECIMAL -> ((BigDecimal) value).toPlainString();
            case DATE -> LocalDate.ofEpochDay((Integer) value).toString();
            case TIME -> LocalTime.ofNanoOfDay((Long) value).toString();
            case TIMESTAMP_LTZ -> {
                long nanos = (Long) value;
                yield Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L))
                        .toString();
            }
            default -> value.toString();
        };
    }

    private int schemaId(Spec spec) {
        if (spec.id().isEmpty()) {
            return -1;
        }
        try {
            int id = Integer.parseInt(spec.id());
            if (id < 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException e) {
            throw refusal.apply(spec.prefix() + "id must be a schema registry id, a non-negative 32-bit integer; got '"
                    + spec.id() + "'");
        }
    }

    /** The same JSON document, ignoring whitespace and the order of an object's members. */
    private static boolean sameJson(String a, String b) {
        try {
            return AvroSchema.readJson(a).equals(AvroSchema.readJson(b));
        } catch (AvroSchema.Invalid e) {
            return false;
        }
    }

    private byte[] read(String path, String option) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException | InvalidPathException e) {
            throw refusal.apply("cannot read " + option + " '" + path + "': " + e.getMessage());
        }
    }

    private ConfigurationException unmappable(Spec spec, String why) {
        String what = !spec.file().isEmpty()
                ? spec.prefix() + "file '" + spec.file() + "'"
                : !spec.descriptor().isEmpty()
                        ? spec.prefix() + "descriptor '" + spec.descriptor() + "'"
                        : spec.prefix() + "id " + spec.id();
        return new ConfigurationException(
                KafkaErrors.SCHEMA_UNMAPPABLE,
                "sink '" + instanceName + "': the " + spec.side() + "'s " + what + " " + why);
    }

    private static String shorten(String text) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 160 ? "'" + oneLine + "'" : "'" + oneLine.substring(0, 160) + "...'";
    }

    private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
}
