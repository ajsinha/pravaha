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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.protobuf.Descriptors.Descriptor;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * {@code format: protobuf} with {@code schema.registry.url} and no {@code schema.descriptor}: each
 * record's message is described by the registry, by the schema id in the record's prefix and the
 * message the record's Confluent message-index array selects in that schema.
 *
 * <p>The schema is fetched serialized ({@link SchemaRegistry#protobufSchema}), references and all, so
 * no {@code .proto} source is parsed here. The mapping of that message to the declared columns is made
 * once per schema id and message, with the same rules as a supplied descriptor ({@link
 * ProtobufValueDecoder}); a message that does not map is a dead letter naming the id and the reason,
 * and the reason is remembered. When {@code schema.message} is set, the message a record selects must
 * be that one, by full or simple name -- a producer that switched message types is refused by name,
 * not read field number by field number as another message.
 */
final class ProtobufRegistryDecoder implements KafkaValueDecoder {

    /** A schema id and message's mapping: a decoder, or the reason there is none. */
    private record Mapped(
            @Nullable ProtobufValueDecoder decoder,
            @Nullable String failure) {}

    private final StreamSchema schema;
    private final int eventTimeOrdinal;
    private final String messageName;
    private final SchemaRegistry registry;
    /** Per fetch thread, by schema id and message indexes; the registry's own cache is shared. */
    private final Map<String, Mapped> byKey = new HashMap<>();

    ProtobufRegistryDecoder(StreamSchema schema, int eventTimeOrdinal, String messageName, SchemaRegistry registry) {
        this.schema = schema;
        this.eventTimeOrdinal = eventTimeOrdinal;
        this.messageName = messageName;
        this.registry = registry;
    }

    @Override
    public Row decode(byte[] value, long recordTimestampMillis) throws Undecodable {
        if (!SchemaRegistry.framed(value)) {
            throw new Undecodable("the value does not begin with the schema registry's wire format (the byte 0x00 "
                    + "and a four-byte schema id), and schema.registry.url is set. A topic whose producer writes "
                    + "bare protobuf messages is read with schema.descriptor and schema.message instead");
        }
        int id = SchemaRegistry.schemaIdIn(value);
        List<Integer> indexes = messageIndexes(value);
        Mapped mapped = byKey.get(id + ":" + indexes);
        if (mapped == null) {
            mapped = mapping(id, indexes);
            byKey.put(id + ":" + indexes, mapped);
        }
        if (mapped.failure() != null) {
            throw new Undecodable(mapped.failure());
        }
        return Objects.requireNonNull(mapped.decoder(), "mapped without a failure")
                .decode(value, recordTimestampMillis);
    }

    /** Confluent's message-index array: a count and that many indexes, or {@code 0} for {@code [0]}. */
    static List<Integer> messageIndexes(byte[] value) throws Undecodable {
        AvroBinary in = new AvroBinary(value, SchemaRegistry.SCHEMA_ID_BYTES + 1);
        long count = in.readLong();
        if (count < 0 || count > 64) {
            throw new Undecodable("the value's Confluent message-index array claims " + count + " entries");
        }
        if (count == 0) {
            return List.of(0);
        }
        List<Integer> indexes = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            long index = in.readLong();
            if (index < 0 || index > Integer.MAX_VALUE) {
                throw new Undecodable("the value's Confluent message-index array holds " + index);
            }
            indexes.add((int) index);
        }
        return List.copyOf(indexes);
    }

    private Mapped mapping(int id, List<Integer> indexes) {
        // A registry that cannot be reached, or cannot serve a serialized descriptor, is PRV-5109 from
        // here: not this record's fault, so it stops the reader rather than becoming a dead letter.
        SchemaRegistry.ProtobufSchema fetched = registry.protobufSchema(id);
        try {
            Descriptor message = ProtobufSchemas.registryMessage(
                    fetched.files(), fetched.root(), indexes, "schema id " + id + " in the registry");
            if (!messageName.isEmpty()
                    && !message.getFullName().equals(messageName)
                    && !message.getName().equals(messageName)) {
                return new Mapped(
                        null,
                        "schema id " + id + "'s message indexes " + indexes + " select "
                                + message.getFullName() + ", and schema.message is " + messageName
                                + ". The record is set "
                                + "aside rather than read as a message it is not");
            }
            return new Mapped(ProtobufValueDecoder.map(schema, eventTimeOrdinal, message, true), null);
        } catch (Unmappable e) {
            return new Mapped(
                    null,
                    "schema id " + id + " cannot be read into this stream's columns: " + e.getMessage()
                            + ". Every record written with that schema is set aside");
        }
    }
}
