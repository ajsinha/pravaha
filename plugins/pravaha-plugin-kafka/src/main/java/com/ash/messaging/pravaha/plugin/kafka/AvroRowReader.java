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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Row;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Undecodable;
import com.ash.messaging.pravaha.plugin.kafka.KafkaValueDecoder.Unmappable;

/**
 * One Avro writer schema, mapped to the binding's declared schema by field name, and the rows that
 * mapping reads.
 *
 * <p><strong>The mapping is made once and refuses by name.</strong> Every column of the declared
 * schema must be a top-level field of the writer's record -- matched exactly, then ignoring case --
 * whose type this reader can turn into that column. A column no field carries, or a field whose type
 * cannot become its column, is {@link Unmappable}: {@code PRV-5108} when the schema came from {@code
 * schema.file} (at configuration, before a record moves), and a dead letter when it came from the
 * registry with the record (nothing else can be done then -- the next record may carry a schema id
 * that maps). A field no column names is <em>skipped</em>, whole: Avro is positional, so a reader
 * that cannot skip a field it does not want cannot read the fields after it.
 *
 * <p><strong>What a column accepts</strong>, and nothing else:
 *
 * <table><caption>Avro to Pravaha</caption>
 * <tr><td>{@code BOOLEAN}</td><td>{@code boolean}</td></tr>
 * <tr><td>{@code INT8}..{@code INT64}</td><td>{@code int}, {@code long} -- refused, not truncated, out of the
 * column's range</td></tr>
 * <tr><td>{@code FLOAT32}</td><td>{@code float}</td></tr>
 * <tr><td>{@code FLOAT64}</td><td>{@code float}, {@code double}</td></tr>
 * <tr><td>{@code DECIMAL(p,s)}</td><td>{@code bytes} or {@code fixed} with {@code logicalType: decimal} --
 * refused, not rounded, when it has more places than the column's scale</td></tr>
 * <tr><td>{@code STRING}</td><td>{@code string}, or an {@code enum}'s symbol</td></tr>
 * <tr><td>{@code BYTES}</td><td>{@code bytes}, {@code fixed}</td></tr>
 * <tr><td>{@code DATE}</td><td>{@code int} with {@code logicalType: date}</td></tr>
 * <tr><td>{@code TIME}</td><td>{@code int}/{@code time-millis}, {@code long}/{@code time-micros}</td></tr>
 * <tr><td>{@code TIMESTAMP}</td><td>{@code long} with {@code timestamp-millis} or {@code timestamp-micros}</td></tr>
 * </table>
 *
 * <p>A plain {@code int} is not read as a {@code DATE}, nor a plain {@code long} as a {@code
 * TIMESTAMP}: the schema either says what the number means or it does not, and guessing is how a
 * column silently becomes 1970. {@code local-timestamp-millis} and {@code -micros} have no zone, so
 * they are refused for a {@code TIMESTAMP} column rather than assumed to be UTC.
 *
 * <p>A field may be a <strong>union</strong>: every branch that is not {@code null} must map to the
 * column on its own, and a {@code null} branch reads as SQL NULL -- refused per record, like every
 * other format, when the column is {@code NOT NULL}. Records, arrays and maps map to no column type;
 * name one and the mapping is refused, leave it unnamed and it is skipped.
 */
final class AvroRowReader {

    private static final long NANOS_PER_DAY = 86_400_000_000_000L;

    private final StreamSchema schema;
    private final AvroSchema.Node record;
    private final int eventTimeOrdinal;
    /** Per writer field, the column it fills, or -1 to skip it. */
    private final int[] targets;

    private AvroRowReader(StreamSchema schema, AvroSchema.Node record, int eventTimeOrdinal, int[] targets) {
        this.schema = schema;
        this.record = record;
        this.eventTimeOrdinal = eventTimeOrdinal;
        this.targets = targets;
    }

    /**
     * The mapping from {@code writer} to {@code schema}, or {@link Unmappable} saying which column or
     * field stopped it.
     */
    static AvroRowReader map(StreamSchema schema, AvroSchema.Node writer, int eventTimeOrdinal) {
        if (writer.kind != AvroSchema.Kind.RECORD) {
            throw new Unmappable("the Avro schema is " + writer + ", not a record, so it has no fields to match the "
                    + "stream's columns to");
        }
        Map<String, Integer> exact = new HashMap<>();
        Map<String, Integer> folded = new HashMap<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            exact.put(schema.field(ordinal).name(), ordinal);
            folded.putIfAbsent(schema.field(ordinal).name().toLowerCase(Locale.ROOT), ordinal);
        }
        List<AvroSchema.Field> fields = writer.fields();
        int[] targets = new int[fields.size()];
        int[] filledBy = new int[schema.fieldCount()];
        Arrays.fill(filledBy, -1);
        for (int i = 0; i < fields.size(); i++) {
            AvroSchema.Field field = fields.get(i);
            Integer ordinal = exact.get(field.name());
            if (ordinal == null) {
                ordinal = folded.get(field.name().toLowerCase(Locale.ROOT));
            }
            if (ordinal == null) {
                targets[i] = -1;
                continue;
            }
            if (filledBy[ordinal] >= 0) {
                throw new Unmappable("the Avro fields '"
                        + fields.get(filledBy[ordinal]).name() + "' and '" + field.name() + "' both match column '"
                        + schema.field(ordinal).name() + "'");
            }
            String why = why(field.type(), schema.field(ordinal).type());
            if (why != null) {
                throw new Unmappable("Avro field '" + field.name() + "' is " + field.type() + " and column '"
                        + schema.field(ordinal).name() + "' is "
                        + schema.field(ordinal).type() + ": " + why);
            }
            targets[i] = ordinal;
            filledBy[ordinal] = i;
        }
        List<String> missing = new ArrayList<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            if (filledBy[ordinal] < 0) {
                missing.add(schema.field(ordinal).name());
            }
        }
        if (!missing.isEmpty()) {
            throw new Unmappable("the Avro schema has no field for column" + (missing.size() > 1 ? "s " : " ")
                    + missing + "; its fields are "
                    + fields.stream().map(AvroSchema.Field::name).toList());
        }
        return new AvroRowReader(schema, writer, eventTimeOrdinal, targets);
    }

    /** Why {@code node} cannot fill a {@code type} column, or null when it can. */
    private static String why(AvroSchema.Node node, PravahaType type) {
        if (node.kind == AvroSchema.Kind.UNION) {
            boolean anyValue = false;
            for (AvroSchema.Node branch : node.branches) {
                if (branch.kind == AvroSchema.Kind.NULL) {
                    continue;
                }
                anyValue = true;
                String why = why(branch, type);
                if (why != null) {
                    return "its branch " + branch + " does not fit (" + why + ")";
                }
            }
            return anyValue ? null : "the union holds only null";
        }
        if (node.kind == AvroSchema.Kind.NULL) {
            return "an Avro null field can only ever be SQL NULL";
        }
        boolean fits =
                switch (type.typeName()) {
                    case BOOLEAN -> node.kind == AvroSchema.Kind.BOOLEAN;
                    case INT8, INT16, INT32, INT64 ->
                        (node.kind == AvroSchema.Kind.INT || node.kind == AvroSchema.Kind.LONG)
                                && node.logical.isEmpty();
                    case FLOAT32 -> node.kind == AvroSchema.Kind.FLOAT;
                    case FLOAT64 -> node.kind == AvroSchema.Kind.FLOAT || node.kind == AvroSchema.Kind.DOUBLE;
                    case DECIMAL ->
                        (node.kind == AvroSchema.Kind.BYTES || node.kind == AvroSchema.Kind.FIXED)
                                && node.logical.equals("decimal");
                    case STRING -> node.kind == AvroSchema.Kind.STRING || node.kind == AvroSchema.Kind.ENUM;
                    case BYTES -> node.kind == AvroSchema.Kind.BYTES || node.kind == AvroSchema.Kind.FIXED;
                    case DATE -> node.kind == AvroSchema.Kind.INT && node.logical.equals("date");
                    case TIME ->
                        (node.kind == AvroSchema.Kind.INT && node.logical.equals("time-millis"))
                                || (node.kind == AvroSchema.Kind.LONG && node.logical.equals("time-micros"));
                    case TIMESTAMP_LTZ ->
                        node.kind == AvroSchema.Kind.LONG
                                && (node.logical.equals("timestamp-millis") || node.logical.equals("timestamp-micros"));
                    default -> false;
                };
        if (fits) {
            return null;
        }
        return switch (type.typeName()) {
            case DATE -> "a DATE column needs an int with logicalType date";
            case TIME -> "a TIME column needs an int/time-millis or a long/time-micros";
            case TIMESTAMP_LTZ ->
                "a TIMESTAMP column needs a long with logicalType timestamp-millis or timestamp-micros; "
                        + "local-timestamp-* has no zone and is not read as UTC";
            case DECIMAL -> "a DECIMAL column needs bytes or fixed with logicalType decimal";
            default -> "no reading of that Avro type makes that column";
        };
    }

    /** The columns of the record {@code value} holds from {@code from} on. */
    Row read(byte[] value, int from, long recordTimestampMillis) throws Undecodable {
        Object[] values = new Object[schema.fieldCount()];
        AvroBinary in = new AvroBinary(value, from);
        List<AvroSchema.Field> fields = record.fields();
        for (int i = 0; i < fields.size(); i++) {
            AvroSchema.Node node = fields.get(i).type();
            if (targets[i] < 0) {
                in.skip(node);
            } else {
                values[targets[i]] = value(in, node, targets[i]);
            }
        }
        if (!in.atEnd()) {
            throw new Undecodable("the record leaves " + in.remaining() + " byte(s) of the value unread, so the "
                    + "value was not written with this schema");
        }
        return KafkaValueDecoder.finish(schema, values, eventTimeOrdinal, 1L, recordTimestampMillis);
    }

    private Object value(AvroBinary in, AvroSchema.Node node, int ordinal) throws Undecodable {
        if (node.kind == AvroSchema.Kind.UNION) {
            return value(in, in.branch(node), ordinal);
        }
        PravahaType type = schema.field(ordinal).type();
        String column = schema.field(ordinal).name();
        return switch (node.kind) {
            case NULL -> null;
            case BOOLEAN -> in.readBoolean();
            case INT -> integer(in.readInt(), type, column, node);
            case LONG -> integer(in.readLong(), type, column, node);
            case FLOAT -> {
                float single = in.readFloat();
                yield type.typeName() == TypeName.FLOAT32 ? (Object) single : (Object) (double) single;
            }
            case DOUBLE -> in.readDouble();
            case STRING -> in.readString();
            case ENUM -> symbol(in.readInt(), node);
            case BYTES -> bytesOrDecimal(in.readBytes(), type, column, node);
            case FIXED -> bytesOrDecimal(in.readFixed(node.size, "a fixed"), type, column, node);
            default -> throw new Undecodable("column '" + column + "' is read from " + node + ", which it cannot be");
        };
    }

    /** An int or long, as whatever the column is: a number, a date, a time or a timestamp. */
    private static Object integer(long raw, PravahaType type, String column, AvroSchema.Node node) throws Undecodable {
        return switch (type.typeName()) {
            case INT8 -> (byte) inRange(raw, Byte.MIN_VALUE, Byte.MAX_VALUE, type, column);
            case INT16 -> (short) inRange(raw, Short.MIN_VALUE, Short.MAX_VALUE, type, column);
            case INT32 -> (int) inRange(raw, Integer.MIN_VALUE, Integer.MAX_VALUE, type, column);
            case INT64 -> raw;
            case DATE -> (int) inRange(raw, Integer.MIN_VALUE, Integer.MAX_VALUE, type, column);
            case TIME -> dayTime(node.logical.equals("time-micros") ? raw * 1_000L : raw * 1_000_000L, column);
            case TIMESTAMP_LTZ -> timestamp(raw, node.logical, column);
            default -> throw new Undecodable("column '" + column + "' is " + type + " and the value is a number");
        };
    }

    private static long inRange(long raw, long min, long max, PravahaType type, String column) throws Undecodable {
        if (raw < min || raw > max) {
            throw new Undecodable(
                    "column '" + column + "' is " + type.typeName() + " and " + raw + " is out of " + "its range");
        }
        return raw;
    }

    private static long dayTime(long nanos, String column) throws Undecodable {
        if (nanos < 0 || nanos >= NANOS_PER_DAY) {
            throw new Undecodable("column '" + column + "' is TIME and " + nanos + " nanoseconds is not within a day");
        }
        return nanos;
    }

    private static long timestamp(long raw, String logical, String column) throws Undecodable {
        try {
            return Math.multiplyExact(raw, logical.equals("timestamp-micros") ? 1_000L : 1_000_000L);
        } catch (ArithmeticException e) {
            throw new Undecodable("column '" + column + "' is TIMESTAMP and " + raw + " " + logical + " is out of "
                    + "the range of a nanosecond timestamp");
        }
    }

    private static String symbol(int index, AvroSchema.Node node) throws Undecodable {
        if (index < 0 || index >= node.symbols.size()) {
            throw new Undecodable("an enum names symbol " + index + " of " + node.symbols.size() + " in " + node.name);
        }
        return node.symbols.get(index);
    }

    private static Object bytesOrDecimal(byte[] raw, PravahaType type, String column, AvroSchema.Node node)
            throws Undecodable {
        if (type.typeName() != TypeName.DECIMAL) {
            return raw;
        }
        if (raw.length == 0) {
            throw new Undecodable("column '" + column + "' is " + type + " and its decimal has no bytes");
        }
        // Avro writes a decimal's unscaled value as a two's-complement big-endian integer, and the
        // scale in the schema; BigInteger(byte[]) reads exactly that.
        BigDecimal written = new BigDecimal(new BigInteger(raw), node.scale);
        DecimalType decimal = (DecimalType) type;
        BigDecimal scaled;
        try {
            scaled = written.setScale(decimal.scale(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new Undecodable("column '" + column + "' is " + type + " and " + written.toPlainString()
                    + " has more than " + decimal.scale() + " decimal places; it is refused rather than rounded");
        }
        if (scaled.precision() > decimal.precision() && scaled.signum() != 0) {
            throw new Undecodable("column '" + column + "' is " + type + " and " + written.toPlainString()
                    + " has more than " + decimal.precision() + " digits");
        }
        return scaled;
    }
}
