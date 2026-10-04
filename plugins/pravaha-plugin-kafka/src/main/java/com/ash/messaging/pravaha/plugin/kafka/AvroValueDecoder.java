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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * {@code format: avro}: a record's value read as Avro's binary encoding, with the writer schema
 * either configured once ({@code schema.file}) or named by the record itself (the schema registry's
 * five-byte prefix, and {@code schema.registry.url}).
 *
 * <p><strong>With {@code schema.file}</strong> the value is the bare binary encoding of one record,
 * with no framing of any kind -- which is what Avro's binary encoding is, and what a producer using
 * {@code BinaryMessageEncoder} or writing the datum itself puts on the topic. The mapping to the
 * declared schema is made and refused when the binding is configured ({@code PRV-5108}), so a typo
 * is a registration that fails rather than a stream of dead letters.
 *
 * <p><strong>With {@code schema.registry.url}</strong> each value begins with the wire format's
 * {@code 0x00} and a four-byte big-endian schema id; the schema of that id is fetched once ({@link
 * SchemaRegistry}) and mapped then, and the record's bytes follow the prefix. A record <em>without</em>
 * the prefix is a dead letter saying so. Because a schema arrives with the record, a schema that does
 * not map to the declared columns cannot be refused at open: that record is a dead letter naming the
 * schema id and the mismatch, and the mismatch is remembered so the next thousand records of the same
 * id cost nothing.
 *
 * <p><strong>With {@code schema.reader.file}</strong> every writer schema -- the file's, or each id's
 * from the registry -- is resolved against that reader schema by the specification's rules ({@link
 * AvroResolver}): fields added with a default, fields removed, promotions, renames through aliases,
 * enum symbols added. The columns are matched against the reader schema, so a producer's schema can
 * evolve under a binding that does not change. A writer schema that does not resolve is refused by
 * name: at configure for {@code schema.file}, and as a dead letter naming the schema id for the
 * registry's -- never decoded as whatever the bytes happen to spell.
 *
 * <p>A value that begins with {@code 0x00} when <em>no</em> registry is configured is the mistake
 * this format makes most: the refusal says so by name, and says to set {@code schema.registry.url}.
 */
final class AvroValueDecoder implements KafkaValueDecoder {

    /** A schema id's mapping: a reader, or the reason there is none. */
    private record Mapped(
            @Nullable AvroRowReader reader, @Nullable String failure) {}

    private final String instanceName;
    private final StreamSchema schema;
    private final int eventTimeOrdinal;
    private final @Nullable AvroRowReader configured;
    /** {@code schema.reader.file}'s schema, or null to read each registry schema as itself. */
    private final AvroSchema.@Nullable Node readerSchema;

    private final @Nullable SchemaRegistry registry;
    /** Per fetch thread; the registry's own cache is what is shared. */
    private final Map<Integer, Mapped> byId = new HashMap<>();

    AvroValueDecoder(
            String instanceName,
            StreamSchema schema,
            int eventTimeOrdinal,
            @Nullable AvroRowReader configured,
            AvroSchema.@Nullable Node readerSchema,
            @Nullable SchemaRegistry registry) {
        this.instanceName = instanceName;
        this.schema = schema;
        this.eventTimeOrdinal = eventTimeOrdinal;
        this.configured = configured;
        this.readerSchema = readerSchema;
        this.registry = registry;
    }

    @Override
    public Row decode(byte[] value, long recordTimestampMillis) throws Undecodable {
        if (registry != null) {
            if (!SchemaRegistry.framed(value)) {
                throw new Undecodable("the value does not begin with the schema registry's wire format (the byte "
                        + "0x00 and a four-byte schema id), and schema.registry.url is set. A topic whose producer "
                        + "writes bare Avro is read with schema.file instead");
            }
            int id = SchemaRegistry.schemaIdIn(value);
            Mapped mapped = mappingFor(id);
            if (mapped.failure() != null) {
                throw new Undecodable(mapped.failure());
            }
            return Objects.requireNonNull(mapped.reader(), "mapped without a failure")
                    .read(value, SchemaRegistry.SCHEMA_ID_BYTES + 1, recordTimestampMillis);
        }
        try {
            return Objects.requireNonNull(configured, "without a registry, schema.file")
                    .read(value, 0, recordTimestampMillis);
        } catch (Undecodable e) {
            if (SchemaRegistry.framed(value)) {
                throw new Undecodable(e.getMessage() + ". The value begins with 0x00 and the four-byte schema id "
                        + SchemaRegistry.schemaIdIn(value) + ", which is the schema registry's wire format: this "
                        + "topic's producer registers its schemas, so set schema.registry.url instead of "
                        + "schema.file");
            }
            throw e;
        }
    }

    /** The mapping for a schema id, fetched and made once -- including a mapping that failed. */
    private Mapped mappingFor(int id) {
        Mapped known = byId.get(id);
        if (known != null) {
            return known;
        }
        // A registry that cannot be reached is PravahaException PRV-5109 from here: not this
        // record's fault, so it stops the reader rather than becoming a dead letter.
        String text = Objects.requireNonNull(registry, "only a registry's ids are looked up")
                .schemaText(id);
        Mapped mapped;
        try {
            AvroSchema.Node writer = AvroSchema.parse(text);
            mapped = new Mapped(
                    AvroRowReader.map(schema, writer, readerSchema != null ? readerSchema : writer, eventTimeOrdinal),
                    null);
        } catch (AvroSchema.Invalid e) {
            mapped = new Mapped(
                    null,
                    "source '" + instanceName + "': schema id " + id + " in the registry is not a valid Avro "
                            + "schema: " + e.getMessage());
        } catch (Unmappable e) {
            mapped = new Mapped(
                    null,
                    "schema id " + id + " cannot be read into this stream's columns: " + e.getMessage()
                            + ". Every record written with that schema is set aside");
        }
        byId.put(id, mapped);
        return mapped;
    }
}
