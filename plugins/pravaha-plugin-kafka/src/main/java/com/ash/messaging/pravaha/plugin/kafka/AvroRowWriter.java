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
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Unmappable;

/**
 * {@code kafka-sink} with {@code format: avro}: a row as Avro's binary encoding of the record
 * {@code schema.file} declares, optionally behind the Confluent wire format's prefix (the byte 0 and
 * the four-byte {@code schema.id}). The id is written as given; no schema is registered.
 *
 * <p><strong>The mapping is made once, by name, and refuses what it cannot write exactly</strong>
 * ({@link Unmappable}, which the options turn into {@code PRV-5108} at configuration):
 *
 * <ul>
 *   <li>every column must be a top-level field of the record, matched exactly, then ignoring case;
 *   <li>a field no column names must be able to be null (a union with {@code null}), and is written
 *       null -- Avro is positional, so every field is written;
 *   <li>a nullable column needs a union with {@code null}: there is no other way to write NULL;
 *   <li>the field's type (or, in a union, the first non-null branch that does) must hold every value
 *       of the column: {@code BOOLEAN} to {@code boolean}; {@code INT8}..{@code INT32} to {@code int}
 *       or {@code long}, {@code INT64} to {@code long}; {@code FLOAT32} to {@code float}, {@code
 *       FLOAT64} to {@code double}; {@code DECIMAL(p,s)} to {@code bytes} or {@code
 *       fixed} with {@code logicalType: decimal} of scale at least {@code s} and at least {@code p-s}
 *       integer digits; {@code STRING} to {@code string}; {@code BYTES} to {@code bytes}; {@code DATE}
 *       to {@code int}/{@code date}; {@code TIME} to {@code int}/{@code time-millis} or {@code
 *       long}/{@code time-micros}; {@code TIMESTAMP} to {@code long}/{@code timestamp-millis} or
 *       {@code timestamp-micros}.
 * </ul>
 *
 * <p>The engine's times are nanoseconds, and Avro's are millis or micros. A {@code TIME} or {@code
 * TIMESTAMP} value with more precision than its field is refused when it is written ({@code
 * PRV-5102}), naming the column and the value, rather than truncated.
 */
final class AvroRowWriter implements KafkaRecords.ValueEncoder {

    /** How one field of the writer record is filled: from column {@code ordinal}, or null when -1. */
    private record Slot(int ordinal, AvroSchema.Node node, int valueBranch, int nullBranch) {}

    private final StreamSchema schema;
    private final Slot[] slots;
    private final int schemaId;

    private AvroRowWriter(StreamSchema schema, Slot[] slots, int schemaId) {
        this.schema = schema;
        this.slots = slots;
        this.schemaId = schemaId;
    }

    /**
     * The mapping from {@code schema}'s columns to {@code writer}'s fields; {@code schemaId} is the
     * Confluent prefix's id, or -1 for bare Avro.
     */
    static AvroRowWriter map(StreamSchema schema, AvroSchema.Node writer, int schemaId) {
        if (writer.kind != AvroSchema.Kind.RECORD) {
            throw new Unmappable("the Avro schema is " + writer + ", not a record, so it has no fields for the "
                    + "stream's columns");
        }
        List<AvroSchema.Field> fields = writer.fields();
        int[] columnOf = new int[fields.size()];
        java.util.Arrays.fill(columnOf, -1);
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            String column = schema.field(ordinal).name();
            int index = fieldFor(fields, column);
            if (index < 0) {
                throw new Unmappable("the Avro record " + writer.name + " has no field for column '" + column
                        + "'; its fields are "
                        + fields.stream().map(AvroSchema.Field::name).toList());
            }
            columnOf[index] = ordinal;
        }
        Slot[] slots = new Slot[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            AvroSchema.Field field = fields.get(i);
            int nullBranch = nullBranch(field.type());
            int ordinal = columnOf[i];
            if (ordinal < 0) {
                if (nullBranch < 0) {
                    throw new Unmappable("Avro field '" + field.name() + "' is " + field.type() + ", no column names "
                            + "it, and it cannot be null, so there is nothing to write in it");
                }
                slots[i] = new Slot(-1, null, -1, nullBranch);
                continue;
            }
            slots[i] = slot(
                    field,
                    ordinal,
                    nullBranch,
                    schema.field(ordinal).type(),
                    schema.field(ordinal).name());
        }
        return new AvroRowWriter(schema, slots, schemaId);
    }

    private static Slot slot(AvroSchema.Field field, int ordinal, int nullBranch, PravahaType type, String column) {
        String where = "Avro field '" + field.name() + "' is " + field.type() + " and column '" + column + "' is "
                + type.sqlName() + ": ";
        if (type.nullable() && nullBranch < 0) {
            throw new Unmappable(where + "the column is nullable and the field has no null branch to write NULL "
                    + "in; make it a union with \"null\", or declare the column NOT NULL");
        }
        AvroSchema.Node node = field.type();
        if (node.kind != AvroSchema.Kind.UNION) {
            String why = why(node, type);
            if (why != null) {
                throw new Unmappable(where + why);
            }
            return new Slot(ordinal, node, -1, -1);
        }
        List<String> reasons = new ArrayList<>();
        for (int b = 0; b < node.branches.size(); b++) {
            AvroSchema.Node branch = node.branches.get(b);
            if (branch.kind == AvroSchema.Kind.NULL) {
                continue;
            }
            String why = why(branch, type);
            if (why == null) {
                return new Slot(ordinal, branch, b, nullBranch);
            }
            reasons.add(branch + ": " + why);
        }
        throw new Unmappable(where + "no branch of the union holds the column exactly " + reasons);
    }

    private static int fieldFor(List<AvroSchema.Field> fields, String column) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(column)) {
                return i;
            }
        }
        int found = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equalsIgnoreCase(column)) {
                if (found >= 0) {
                    throw new Unmappable("the Avro fields '" + fields.get(found).name() + "' and '"
                            + fields.get(i).name() + "' both match column '" + column + "' ignoring case");
                }
                found = i;
            }
        }
        return found;
    }

    private static int nullBranch(AvroSchema.Node node) {
        if (node.kind == AvroSchema.Kind.UNION) {
            for (int b = 0; b < node.branches.size(); b++) {
                if (node.branches.get(b).kind == AvroSchema.Kind.NULL) {
                    return b;
                }
            }
        }
        return -1;
    }

    /** Why {@code node} cannot hold every value of a {@code type} column exactly, or null when it can. */
    static String why(AvroSchema.Node node, PravahaType type) {
        AvroSchema.Kind kind = node.kind;
        boolean plain = node.logical.isEmpty();
        boolean fits =
                switch (type.typeName()) {
                    case BOOLEAN -> kind == AvroSchema.Kind.BOOLEAN && plain;
                    case INT8, INT16, INT32 -> (kind == AvroSchema.Kind.INT || kind == AvroSchema.Kind.LONG) && plain;
                    case INT64 -> kind == AvroSchema.Kind.LONG && plain;
                    case FLOAT32 -> kind == AvroSchema.Kind.FLOAT;
                    case FLOAT64 -> kind == AvroSchema.Kind.DOUBLE;
                    case DECIMAL -> decimalFits(node, (DecimalType) type);
                    case STRING -> kind == AvroSchema.Kind.STRING && plain;
                    case BYTES -> kind == AvroSchema.Kind.BYTES && plain;
                    case DATE -> kind == AvroSchema.Kind.INT && node.logical.equals("date");
                    case TIME ->
                        (kind == AvroSchema.Kind.INT && node.logical.equals("time-millis"))
                                || (kind == AvroSchema.Kind.LONG && node.logical.equals("time-micros"));
                    case TIMESTAMP_LTZ ->
                        kind == AvroSchema.Kind.LONG
                                && (node.logical.equals("timestamp-millis") || node.logical.equals("timestamp-micros"));
                    default -> false;
                };
        if (fits) {
            return null;
        }
        return switch (type.typeName()) {
            case FLOAT32 -> "a FLOAT32 column is written to a float, which is what the source reads it from";
            case INT64 -> "an INT64 column is written to a plain long; an int would not hold every value";
            case FLOAT64 -> "a FLOAT64 column is written to a double; a float would round it";
            case DECIMAL ->
                "a DECIMAL(p,s) column is written to bytes or fixed with logicalType decimal, a scale of at "
                        + "least s and at least p-s digits before the point (a fixed must be big enough for its "
                        + "precision)";
            case DATE -> "a DATE column is written to an int with logicalType date";
            case TIME -> "a TIME column is written to an int/time-millis or a long/time-micros";
            case TIMESTAMP_LTZ ->
                "a TIMESTAMP column is written to a long with logicalType timestamp-millis or timestamp-micros";
            default -> "that Avro type does not hold every value of that column exactly";
        };
    }

    private static boolean decimalFits(AvroSchema.Node node, DecimalType type) {
        if (!node.logical.equals("decimal")
                || (node.kind != AvroSchema.Kind.BYTES && node.kind != AvroSchema.Kind.FIXED)) {
            return false;
        }
        if (node.scale < type.scale() || node.precision - node.scale < type.precision() - type.scale()) {
            return false;
        }
        // A fixed of n bytes holds a two's-complement integer of up to 8n-1 bits.
        return node.kind != AvroSchema.Kind.FIXED
                || node.size > 0
                        && BigInteger.ONE
                                        .shiftLeft(8 * node.size - 1)
                                        .subtract(BigInteger.ONE)
                                        .toString()
                                        .length()
                                > node.precision;
    }

    @Override
    public byte[] encode(Object[] values) {
        AvroBinaryWriter out = new AvroBinaryWriter();
        if (schemaId >= 0) {
            out.write(0);
            out.write(schemaId >>> 24);
            out.write(schemaId >>> 16);
            out.write(schemaId >>> 8);
            out.write(schemaId);
        }
        for (Slot slot : slots) {
            Object value = slot.ordinal() < 0 ? null : values[slot.ordinal()];
            if (value == null) {
                // The mapping made sure a null has a branch: an unnamed field, or a nullable column.
                out.writeLong(slot.nullBranch());
                continue;
            }
            if (slot.valueBranch() >= 0) {
                out.writeLong(slot.valueBranch());
            }
            write(out, slot.node(), value, slot.ordinal());
        }
        return out.toByteArray();
    }

    private void write(AvroBinaryWriter out, AvroSchema.Node node, Object value, int ordinal) {
        switch (schema.field(ordinal).type().typeName()) {
            case BOOLEAN -> out.writeBoolean((Boolean) value);
            case INT8, INT16, INT32, INT64, DATE -> out.writeLong(((Number) value).longValue());
            case FLOAT32 -> out.writeFloat((Float) value);
            case FLOAT64 -> out.writeDouble((Double) value);
            case DECIMAL -> decimal(out, node, (BigDecimal) value, ordinal);
            case STRING -> out.writeString((String) value);
            case BYTES -> out.writeBytes((byte[]) value);
            case TIME ->
                out.writeLong(
                        exactly((Long) value, node.logical.equals("time-micros") ? 1_000L : 1_000_000L, node, ordinal));
            case TIMESTAMP_LTZ ->
                out.writeLong(exactly(
                        (Long) value, node.logical.equals("timestamp-micros") ? 1_000L : 1_000_000L, node, ordinal));
            default -> throw refused(ordinal, value, "its type is not one this sink writes as Avro");
        }
    }

    /** Nanoseconds in the field's unit, refused rather than truncated when they do not divide. */
    private long exactly(long nanos, long unit, AvroSchema.Node node, int ordinal) {
        if (nanos % unit != 0) {
            throw refused(
                    ordinal,
                    nanos + " ns",
                    "the field is " + node + ", which cannot hold its " + "sub-"
                            + (unit == 1_000L ? "microsecond" : "millisecond") + " part");
        }
        return nanos / unit;
    }

    private void decimal(AvroBinaryWriter out, AvroSchema.Node node, BigDecimal value, int ordinal) {
        BigDecimal scaled;
        try {
            scaled = value.setScale(node.scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw refused(ordinal, value.toPlainString(), "it has more than the field's " + node.scale + " places");
        }
        if (scaled.precision() > node.precision && scaled.signum() != 0) {
            throw refused(ordinal, value.toPlainString(), "it has more than the field's " + node.precision + " digits");
        }
        byte[] unscaled = scaled.unscaledValue().toByteArray();
        if (node.kind == AvroSchema.Kind.BYTES) {
            out.writeBytes(unscaled);
            return;
        }
        // The mapping made the fixed big enough for the precision, so this only sign-extends.
        byte[] fixed = new byte[node.size];
        byte pad = (byte) (scaled.signum() < 0 ? 0xFF : 0);
        int offset = node.size - unscaled.length;
        for (int i = 0; i < offset; i++) {
            fixed[i] = pad;
        }
        System.arraycopy(unscaled, 0, fixed, offset, unscaled.length);
        out.writeFixed(fixed);
    }

    private PravahaException refused(int ordinal, Object value, String why) {
        return new PravahaException(
                KafkaErrors.WRITE_FAILED,
                "column '" + schema.field(ordinal).name() + "' holds " + value + ", which is not written as Avro: "
                        + why + "; it is refused rather than rounded");
    }
}
