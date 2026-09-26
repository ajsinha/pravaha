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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UninitializedMessageException;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * {@code format: protobuf}: a record's value read as one message of a descriptor set the deployment
 * supplies, through {@link DynamicMessage} -- no generated classes, and no {@code protoc} at run time.
 *
 * <p><strong>Fields are matched to columns by name</strong> (exactly, then ignoring case), and the
 * mapping is made when the binding is configured: a column the message has no field for, or a field
 * whose type cannot become its column, is {@code PRV-5108} then and there. A field no column names is
 * never read.
 *
 * <p><strong>What a column accepts:</strong> {@code BOOLEAN} from {@code bool}; the integer columns
 * from any of the ten integer types, unsigned ones widened honestly and a value out of the column's
 * range refused rather than truncated; {@code FLOAT32} from {@code float}, {@code FLOAT64} from
 * {@code float} or {@code double}; {@code STRING} from {@code string} or an {@code enum}'s symbol;
 * {@code BYTES} from {@code bytes}; {@code DECIMAL} from a {@code string} holding the number exactly
 * (protobuf has no decimal type, and a {@code double} would not be one); {@code TIMESTAMP} from
 * {@code google.protobuf.Timestamp} or an ISO-8601 {@code string}; {@code DATE} and {@code TIME} from
 * an ISO-8601 {@code string} ({@code 2026-09-19}, {@code 10:15:30.5}). A {@code repeated} field, a
 * map, a group and any other message type map to no column.
 *
 * <p><strong>proto3 defaults are not SQL NULL, and this plugin does not pretend they are.</strong> A
 * proto3 scalar without {@code optional} has no presence on the wire: a field that was never set and
 * a field set to {@code 0}, {@code ""} or {@code false} are the same bytes -- none. So such a field
 * fills its column with the type's default, and <em>never</em> with NULL, even when the column is
 * nullable. Fields that <em>do</em> carry presence -- {@code optional} in proto3, any message field
 * (including {@code google.protobuf.Timestamp}), and proto2's {@code optional} -- read as NULL when
 * they are absent. If a column must be able to be unknown, declare the field {@code optional} or wrap
 * it; the documentation says this in the same words.
 *
 * <p>With {@code schema.registry.url} set, the value carries the registry's five-byte prefix and
 * then Confluent's <em>message-index</em> array -- a zig-zag varint count and that many indexes, or
 * the single byte {@code 0} for the first message in the schema. With a {@code schema.descriptor},
 * both are read past and the message is the one {@code schema.message} names; no request is made to
 * the registry. Without one, {@link ProtobufRegistryDecoder} asks the registry for each id's
 * descriptor and makes one of these per id and message.
 */
final class ProtobufValueDecoder implements KafkaValueDecoder {

    private static final String TIMESTAMP_MESSAGE = "google.protobuf.Timestamp";
    private static final long NANOS_PER_DAY = 86_400_000_000_000L;

    private final StreamSchema schema;
    private final int eventTimeOrdinal;
    private final Descriptor message;
    private final FieldDescriptor[] fields;
    private final boolean registryFramed;

    private ProtobufValueDecoder(
            StreamSchema schema,
            int eventTimeOrdinal,
            Descriptor message,
            FieldDescriptor[] fields,
            boolean registryFramed) {
        this.schema = schema;
        this.eventTimeOrdinal = eventTimeOrdinal;
        this.message = message;
        this.fields = fields;
        this.registryFramed = registryFramed;
    }

    /** The mapping from {@code message} to {@code schema}, or {@link Unmappable} saying what stopped it. */
    static ProtobufValueDecoder map(
            StreamSchema schema, int eventTimeOrdinal, Descriptor message, boolean registryFramed) {
        FieldDescriptor[] fields = new FieldDescriptor[schema.fieldCount()];
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            String column = schema.field(ordinal).name();
            FieldDescriptor field = message.findFieldByName(column);
            if (field == null) {
                field = caseInsensitive(message, column);
            }
            if (field == null) {
                throw new Unmappable("message " + message.getFullName() + " has no field for column '" + column
                        + "'; its fields are "
                        + message.getFields().stream()
                                .map(FieldDescriptor::getName)
                                .toList());
            }
            String why = why(field, schema.field(ordinal).type());
            if (why != null) {
                throw new Unmappable("field '" + field.getName() + "' of " + message.getFullName() + " is "
                        + described(field) + " and column '" + column + "' is "
                        + schema.field(ordinal).type() + ": " + why);
            }
            fields[ordinal] = field;
        }
        return new ProtobufValueDecoder(schema, eventTimeOrdinal, message, fields, registryFramed);
    }

    private static FieldDescriptor caseInsensitive(Descriptor message, String column) {
        for (FieldDescriptor field : message.getFields()) {
            if (field.getName().equalsIgnoreCase(column)) {
                return field;
            }
        }
        return null;
    }

    private static String described(FieldDescriptor field) {
        String base = field.getType() == FieldDescriptor.Type.MESSAGE
                ? field.getMessageType().getFullName()
                : field.getType().name().toLowerCase(Locale.ROOT);
        return field.isRepeated() ? (field.isMapField() ? "a map of " + base : "repeated " + base) : base;
    }

    /** Why {@code field} cannot fill a {@code type} column, or null when it can. */
    private static String why(FieldDescriptor field, PravahaType type) {
        if (field.isRepeated()) {
            return "a repeated field or a map is many values, and a column is one";
        }
        FieldDescriptor.JavaType java = field.getJavaType();
        boolean timestampMessage = java == FieldDescriptor.JavaType.MESSAGE
                && field.getMessageType().getFullName().equals(TIMESTAMP_MESSAGE);
        boolean fits =
                switch (type.typeName()) {
                    case BOOLEAN -> java == FieldDescriptor.JavaType.BOOLEAN;
                    case INT8, INT16, INT32, INT64 ->
                        java == FieldDescriptor.JavaType.INT || java == FieldDescriptor.JavaType.LONG;
                    case FLOAT32 -> java == FieldDescriptor.JavaType.FLOAT;
                    case FLOAT64 -> java == FieldDescriptor.JavaType.FLOAT || java == FieldDescriptor.JavaType.DOUBLE;
                    case STRING -> java == FieldDescriptor.JavaType.STRING || java == FieldDescriptor.JavaType.ENUM;
                    case BYTES -> java == FieldDescriptor.JavaType.BYTE_STRING;
                    case DECIMAL, DATE, TIME -> java == FieldDescriptor.JavaType.STRING;
                    case TIMESTAMP_LTZ -> java == FieldDescriptor.JavaType.STRING || timestampMessage;
                    default -> false;
                };
        if (fits) {
            return null;
        }
        return switch (type.typeName()) {
            case DECIMAL ->
                "protobuf has no decimal type, so a DECIMAL column is read from a string holding the number "
                        + "exactly; a double would not be exact";
            case DATE, TIME -> "a DATE or TIME column is read from an ISO-8601 string";
            case TIMESTAMP_LTZ ->
                "a TIMESTAMP column is read from google.protobuf.Timestamp or an ISO-8601 string; a bare "
                        + "int64 does not say whether it counts seconds, millis or micros";
            default -> "no reading of that protobuf type makes that column";
        };
    }

    @Override
    public Row decode(byte[] value, long recordTimestampMillis) throws Undecodable {
        int from = registryFramed ? payloadStart(value) : 0;
        DynamicMessage decoded;
        try {
            CodedInputStream input = CodedInputStream.newInstance(value, from, value.length - from);
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(message);
            builder.mergeFrom(input);
            if (!input.isAtEnd()) {
                throw new Undecodable("the value has more after message " + message.getFullName());
            }
            decoded = builder.build();
        } catch (InvalidProtocolBufferException e) {
            throw new Undecodable("the value is not a " + message.getFullName() + ": " + e.getMessage());
        } catch (UninitializedMessageException e) {
            throw new Undecodable(
                    "the value leaves a required field of " + message.getFullName() + " unset: " + e.getMessage());
        } catch (java.io.IOException e) {
            throw new Undecodable("the value is not a " + message.getFullName() + ": " + e.getMessage());
        }
        Object[] values = new Object[schema.fieldCount()];
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            values[ordinal] = value(decoded, fields[ordinal], ordinal);
        }
        return KafkaValueDecoder.finish(schema, values, eventTimeOrdinal, 1L, recordTimestampMillis);
    }

    /** Past the registry's five-byte prefix and Confluent's message-index array. */
    private static int payloadStart(byte[] value) throws Undecodable {
        if (!SchemaRegistry.framed(value)) {
            throw new Undecodable("the value does not begin with the schema registry's wire format (the byte 0x00 "
                    + "and a four-byte schema id), and schema.registry.url is set. A topic whose producer writes "
                    + "bare protobuf messages is read with schema.registry.url left out");
        }
        // The indexes are zig-zag varints, the same encoding Avro uses, so the same reader reads them.
        AvroBinary indexes = new AvroBinary(value, SchemaRegistry.SCHEMA_ID_BYTES + 1);
        long count = indexes.readLong();
        if (count < 0 || count > 64) {
            throw new Undecodable("the value's Confluent message-index array claims " + count + " entries");
        }
        for (long i = 0; i < count; i++) {
            indexes.readLong();
        }
        return indexes.position();
    }

    private Object value(DynamicMessage decoded, FieldDescriptor field, int ordinal) throws Undecodable {
        // A field that carries presence and is absent is SQL NULL; one that does not (a plain proto3
        // scalar) is its type's default, which is a value and not an absence. See the class comment.
        if (field.hasPresence() && !decoded.hasField(field)) {
            return null;
        }
        Object raw = decoded.getField(field);
        PravahaType type = schema.field(ordinal).type();
        String column = schema.field(ordinal).name();
        return switch (type.typeName()) {
            case BOOLEAN -> raw;
            case INT8 -> (byte) inRange(number(raw, field, column), Byte.MIN_VALUE, Byte.MAX_VALUE, type, column);
            case INT16 -> (short) inRange(number(raw, field, column), Short.MIN_VALUE, Short.MAX_VALUE, type, column);
            case INT32 -> (int) inRange(number(raw, field, column), Integer.MIN_VALUE, Integer.MAX_VALUE, type, column);
            case INT64 -> number(raw, field, column);
            case FLOAT32 -> raw;
            case FLOAT64 -> raw instanceof Float single ? (double) single : raw;
            case STRING -> raw instanceof EnumValueDescriptor symbol ? symbol.getName() : raw;
            case BYTES -> ((ByteString) raw).toByteArray();
            case DECIMAL -> decimal((String) raw, (DecimalType) type, column);
            case DATE -> date((String) raw, column);
            case TIME -> time((String) raw, column);
            case TIMESTAMP_LTZ -> raw instanceof Message nested ? instant(nested, column) : text((String) raw, column);
            default ->
                throw new Undecodable(
                        "column '" + column + "' has type " + type + ", which this source " + "does not read");
        };
    }

    /** An integer field's value as a long, unsigned types widened rather than wrapped. */
    private static long number(Object raw, FieldDescriptor field, String column) throws Undecodable {
        boolean unsigned = field.getType() == FieldDescriptor.Type.UINT32
                || field.getType() == FieldDescriptor.Type.FIXED32
                || field.getType() == FieldDescriptor.Type.UINT64
                || field.getType() == FieldDescriptor.Type.FIXED64;
        if (raw instanceof Integer value) {
            return unsigned ? Integer.toUnsignedLong(value) : value;
        }
        long value = (Long) raw;
        if (unsigned && value < 0) {
            throw new Undecodable("column '" + column + "' holds the unsigned value " + Long.toUnsignedString(value)
                    + ", which is past the largest signed 64-bit integer");
        }
        return value;
    }

    private static long inRange(long raw, long min, long max, PravahaType type, String column) throws Undecodable {
        if (raw < min || raw > max) {
            throw new Undecodable(
                    "column '" + column + "' is " + type.typeName() + " and " + raw + " is out of its range");
        }
        return raw;
    }

    private static BigDecimal decimal(String raw, DecimalType type, String column) throws Undecodable {
        BigDecimal written;
        try {
            written = new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            throw new Undecodable("column '" + column + "' is " + type + " and '" + raw + "' is not a number");
        }
        BigDecimal scaled;
        try {
            scaled = written.setScale(type.scale(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new Undecodable("column '" + column + "' is " + type + " and " + written.toPlainString()
                    + " has more than " + type.scale() + " decimal places; it is refused rather than rounded");
        }
        if (scaled.precision() > type.precision() && scaled.signum() != 0) {
            throw new Undecodable("column '" + column + "' is " + type + " and " + written.toPlainString()
                    + " has more than " + type.precision() + " digits");
        }
        return scaled;
    }

    private static int date(String raw, String column) throws Undecodable {
        try {
            return Math.toIntExact(LocalDate.parse(raw.strip()).toEpochDay());
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new Undecodable(
                    "column '" + column + "' is DATE and '" + raw + "' is not a date such as " + "2026-09-19");
        }
    }

    private static long time(String raw, String column) throws Undecodable {
        try {
            long nanos = LocalTime.parse(raw.strip()).toNanoOfDay();
            if (nanos < 0 || nanos >= NANOS_PER_DAY) {
                throw new Undecodable("column '" + column + "' is TIME and '" + raw + "' is not within a day");
            }
            return nanos;
        } catch (DateTimeParseException e) {
            throw new Undecodable(
                    "column '" + column + "' is TIME and '" + raw + "' is not a time such as " + "10:15:30.5");
        }
    }

    private static long text(String raw, String column) throws Undecodable {
        Instant instant;
        try {
            instant = Instant.parse(raw.strip());
        } catch (DateTimeParseException notAnInstant) {
            try {
                instant = OffsetDateTime.parse(raw.strip()).toInstant();
            } catch (DateTimeParseException e) {
                throw new Undecodable("column '" + column + "' is TIMESTAMP and '" + raw + "' is not an ISO-8601 "
                        + "instant such as 2026-09-19T10:15:30Z");
            }
        }
        return epochNanos(instant.getEpochSecond(), instant.getNano(), column, raw);
    }

    /** {@code google.protobuf.Timestamp}: seconds since the epoch and nanoseconds within the second. */
    private static long instant(Message timestamp, String column) throws Undecodable {
        long seconds = 0;
        int nanos = 0;
        List<String> unexpected = new ArrayList<>();
        for (FieldDescriptor field : timestamp.getDescriptorForType().getFields()) {
            switch (field.getName()) {
                case "seconds" -> seconds = (Long) timestamp.getField(field);
                case "nanos" -> nanos = (Integer) timestamp.getField(field);
                default -> unexpected.add(field.getName());
            }
        }
        if (!unexpected.isEmpty()) {
            throw new Undecodable(
                    "column '" + column + "' reads a " + TIMESTAMP_MESSAGE + " that also has " + unexpected);
        }
        return epochNanos(seconds, nanos, column, seconds + "s+" + nanos + "ns");
    }

    private static long epochNanos(long seconds, int nanos, String column, String shown) throws Undecodable {
        try {
            return Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), nanos);
        } catch (ArithmeticException e) {
            throw new Undecodable("column '" + column + "' is TIMESTAMP and " + shown + " is out of the range of a "
                    + "nanosecond timestamp");
        }
    }
}
