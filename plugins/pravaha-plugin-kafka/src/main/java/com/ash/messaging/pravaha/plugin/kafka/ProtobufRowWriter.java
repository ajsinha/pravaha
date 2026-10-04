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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DynamicMessage;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Unmappable;

/**
 * {@code kafka-sink} with {@code format: protobuf}: a row as one message of {@code schema.descriptor}
 * named by {@code schema.message}, built with {@link DynamicMessage}; written bare, or -- with a
 * {@code schema.id} checked against the registry -- behind the Confluent framing: the byte 0, the
 * four-byte id, then the message's index path in the registered file as zig-zag varints, a lone
 * {@code 0} for the file's first message (KSF-2, {@link #framed}).
 *
 * <p><strong>The mapping is made once, by name, and refuses what it cannot write exactly</strong>
 * ({@link Unmappable}, {@code PRV-5108} at configuration): every column must be a field of the
 * message (exactly, then ignoring case), not {@code repeated}; a nullable column needs a field with
 * presence ({@code optional} in proto3, or a message), since a proto3 scalar cannot say NULL; two
 * columns may not share a {@code oneof}; a proto2 {@code required} field no column names is refused.
 * Types: {@code BOOLEAN} to {@code bool}; {@code INT8}..{@code INT32} to a signed 32- or 64-bit
 * integer, {@code INT64} to a signed 64-bit one (unsigned types cannot hold a negative); {@code
 * FLOAT32} to {@code float}, {@code FLOAT64} to {@code double}; {@code STRING} to
 * {@code string}; {@code BYTES} to {@code bytes}; {@code DECIMAL} to a {@code string} of its exact
 * digits; {@code DATE} and {@code TIME} to an ISO-8601 {@code string}; {@code TIMESTAMP} to {@code
 * google.protobuf.Timestamp} or an ISO-8601 {@code string}. Fields no column names are left unset.
 */
final class ProtobufRowWriter implements KafkaRecords.ValueEncoder {

    private static final String TIMESTAMP_MESSAGE = "google.protobuf.Timestamp";

    private static final Set<FieldDescriptor.Type> SIGNED_32 =
            EnumSet.of(FieldDescriptor.Type.INT32, FieldDescriptor.Type.SINT32, FieldDescriptor.Type.SFIXED32);
    private static final Set<FieldDescriptor.Type> SIGNED_64 =
            EnumSet.of(FieldDescriptor.Type.INT64, FieldDescriptor.Type.SINT64, FieldDescriptor.Type.SFIXED64);

    private final StreamSchema schema;
    private final Descriptor message;
    private final FieldDescriptor[] fields;
    /** The Confluent framing written before each message, or empty for a bare one. */
    private final byte[] prefix;

    private ProtobufRowWriter(StreamSchema schema, Descriptor message, FieldDescriptor[] fields, byte[] prefix) {
        this.schema = schema;
        this.message = message;
        this.fields = fields;
        this.prefix = prefix;
    }

    /**
     * This writer with the Confluent framing in front of every message: magic byte, {@code schemaId},
     * and {@code indexes}, the message's path in the registered schema's file.
     */
    ProtobufRowWriter framed(int schemaId, java.util.List<Integer> indexes) {
        AvroBinaryWriter out = new AvroBinaryWriter();
        out.write(SchemaRegistry.MAGIC);
        out.write(schemaId >>> 24);
        out.write(schemaId >>> 16);
        out.write(schemaId >>> 8);
        out.write(schemaId);
        if (indexes.equals(java.util.List.of(0))) {
            out.writeLong(0);
        } else {
            out.writeLong(indexes.size());
            for (int index : indexes) {
                out.writeLong(index);
            }
        }
        return new ProtobufRowWriter(schema, message, fields, out.toByteArray());
    }

    /** The mapping from {@code schema}'s columns to {@code message}'s fields, or {@link Unmappable}. */
    static ProtobufRowWriter map(StreamSchema schema, Descriptor message) {
        FieldDescriptor[] fields = new FieldDescriptor[schema.fieldCount()];
        Map<OneofDescriptor, String> oneofs = new HashMap<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            String column = schema.field(ordinal).name();
            PravahaType type = schema.field(ordinal).type();
            FieldDescriptor field = fieldFor(message, column);
            if (field == null) {
                throw new Unmappable("message " + message.getFullName() + " has no field for column '" + column
                        + "'; its fields are "
                        + message.getFields().stream()
                                .map(FieldDescriptor::getName)
                                .toList());
            }
            String where = "field '" + field.getName() + "' of " + message.getFullName() + " and column '" + column
                    + "' (" + type.sqlName() + "): ";
            String why = why(field, type);
            if (why != null) {
                throw new Unmappable(where + why);
            }
            if (type.nullable() && !field.hasPresence()) {
                throw new Unmappable(where + "the column is nullable and the field has no presence, so NULL would "
                        + "be read back as the type's default; declare the field optional, or the column NOT NULL");
            }
            OneofDescriptor oneof = field.getRealContainingOneof();
            if (oneof != null) {
                String other = oneofs.putIfAbsent(oneof, column);
                if (other != null) {
                    throw new Unmappable("columns '" + other + "' and '" + column + "' are both in oneof "
                            + oneof.getName() + " of " + message.getFullName() + ", which holds one of them");
                }
            }
            fields[ordinal] = field;
        }
        for (FieldDescriptor field : message.getFields()) {
            if (field.isRequired() && !java.util.Arrays.asList(fields).contains(field)) {
                throw new Unmappable("required field '" + field.getName() + "' of " + message.getFullName()
                        + " has no column to fill it");
            }
        }
        return new ProtobufRowWriter(schema, message, fields, new byte[0]);
    }

    private static @Nullable FieldDescriptor fieldFor(Descriptor message, String column) {
        FieldDescriptor exact = message.findFieldByName(column);
        if (exact != null) {
            return exact;
        }
        for (FieldDescriptor field : message.getFields()) {
            if (field.getName().equalsIgnoreCase(column)) {
                return field;
            }
        }
        return null;
    }

    /** Why {@code field} cannot hold every value of a {@code type} column exactly, or null when it can. */
    private static @Nullable String why(FieldDescriptor field, PravahaType type) {
        if (field.isRepeated()) {
            return "a repeated field or a map is many values, and a column is one";
        }
        FieldDescriptor.Type wire = field.getType();
        boolean fits =
                switch (type.typeName()) {
                    case BOOLEAN -> wire == FieldDescriptor.Type.BOOL;
                    case INT8, INT16, INT32 -> SIGNED_32.contains(wire) || SIGNED_64.contains(wire);
                    case INT64 -> SIGNED_64.contains(wire);
                    case FLOAT32 -> wire == FieldDescriptor.Type.FLOAT;
                    case FLOAT64 -> wire == FieldDescriptor.Type.DOUBLE;
                    case STRING, DECIMAL, DATE, TIME -> wire == FieldDescriptor.Type.STRING;
                    case BYTES -> wire == FieldDescriptor.Type.BYTES;
                    case TIMESTAMP_LTZ ->
                        wire == FieldDescriptor.Type.STRING
                                || (wire == FieldDescriptor.Type.MESSAGE
                                        && field.getMessageType().getFullName().equals(TIMESTAMP_MESSAGE));
                    default -> false;
                };
        if (fits) {
            return null;
        }
        return switch (type.typeName()) {
            case INT8, INT16, INT32, INT64 ->
                "the field is " + wire + "; an integer column is written to a signed integer wide enough for it "
                        + "(an unsigned one cannot hold a negative)";
            case FLOAT64 -> "a FLOAT64 column is written to a double; a float would round it";
            case DECIMAL -> "a DECIMAL column is written to a string of its exact digits; protobuf has no decimal";
            case DATE, TIME -> "a DATE or TIME column is written to an ISO-8601 string";
            case TIMESTAMP_LTZ -> "a TIMESTAMP column is written to google.protobuf.Timestamp or an ISO-8601 string";
            default -> "the field is " + wire + ", which does not hold every value of that column exactly";
        };
    }

    @Override
    public byte[] encode(Object[] values) {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(message);
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            if (values[ordinal] != null) {
                builder.setField(fields[ordinal], value(fields[ordinal], ordinal, values[ordinal]));
            }
        }
        byte[] body = builder.build().toByteArray();
        if (prefix.length == 0) {
            return body;
        }
        byte[] framed = java.util.Arrays.copyOf(prefix, prefix.length + body.length);
        System.arraycopy(body, 0, framed, prefix.length, body.length);
        return framed;
    }

    private Object value(FieldDescriptor field, int ordinal, Object value) {
        return switch (schema.field(ordinal).type().typeName()) {
            case INT8, INT16, INT32, INT64 ->
                SIGNED_32.contains(field.getType())
                        ? (Object) ((Number) value).intValue()
                        : (Object) ((Number) value).longValue();
            case DECIMAL -> ((BigDecimal) value).toPlainString();
            case BYTES -> ByteString.copyFrom((byte[]) value);
            case DATE -> LocalDate.ofEpochDay((Integer) value).toString();
            case TIME -> LocalTime.ofNanoOfDay((Long) value).toString();
            case TIMESTAMP_LTZ -> timestamp(field, (Long) value);
            default -> value; // BOOLEAN, FLOAT32, FLOAT64 and STRING are already what the field holds.
        };
    }

    private static Object timestamp(FieldDescriptor field, long nanos) {
        long seconds = Math.floorDiv(nanos, 1_000_000_000L);
        int within = (int) Math.floorMod(nanos, 1_000_000_000L);
        if (field.getType() == FieldDescriptor.Type.STRING) {
            return Instant.ofEpochSecond(seconds, within).toString();
        }
        Descriptor timestamp = field.getMessageType();
        return DynamicMessage.newBuilder(timestamp)
                .setField(timestamp.findFieldByName("seconds"), seconds)
                .setField(timestamp.findFieldByName("nanos"), within)
                .build();
    }
}
