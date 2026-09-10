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
package com.ash.messaging.pravaha.plugin.delta;

import java.util.List;

import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.types.BinaryType;
import io.delta.kernel.types.BooleanType;
import io.delta.kernel.types.ByteType;
import io.delta.kernel.types.DataType;
import io.delta.kernel.types.DateType;
import io.delta.kernel.types.DecimalType;
import io.delta.kernel.types.DoubleType;
import io.delta.kernel.types.FloatType;
import io.delta.kernel.types.IntegerType;
import io.delta.kernel.types.LongType;
import io.delta.kernel.types.ShortType;
import io.delta.kernel.types.StringType;
import io.delta.kernel.types.StructField;
import io.delta.kernel.types.StructType;
import io.delta.kernel.types.TimestampNTZType;
import io.delta.kernel.types.TimestampType;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

/**
 * Delta's type system, mapped onto Pravaha's.
 *
 * <p>The mapping refuses what it cannot represent rather than approximating it. A Delta
 * {@code ARRAY}, {@code MAP} or {@code STRUCT} column is named in the error with the column it
 * belongs to, because the alternative -- quietly dropping the column, or stringifying it -- produces
 * a query that runs, returns answers, and is wrong about that column forever.
 *
 * <p>Two mappings are worth stating explicitly because they are lossy in a direction that matters:
 *
 * <ul>
 *   <li>Delta {@code TIMESTAMP} is microsecond-precision UTC; Pravaha's is nanoseconds, so the
 *       conversion multiplies and never rounds. Reading is exact; a value written back would be
 *       truncated, which is a sink concern and is why the sink is not in this version.
 *   <li>Delta {@code DATE} is days since epoch, which is Pravaha's {@code DATE} exactly.
 * </ul>
 */
final class DeltaTypes {

    private DeltaTypes() {}

    /** Converts a Delta table schema into a Pravaha stream schema. */
    static StreamSchema toStreamSchema(String streamName, StructType deltaSchema) {
        StreamSchema.Builder builder = StreamSchema.builder(streamName);
        for (StructField field : deltaSchema.fields()) {
            PravahaType type = toPravahaType(streamName, field);
            builder.field(field.getName(), field.isNullable() ? type.withNullable(true) : type);
        }
        return builder.build();
    }

    private static PravahaType toPravahaType(String streamName, StructField field) {
        DataType type = field.getDataType();
        if (type instanceof BooleanType) {
            return Types.bool();
        } else if (type instanceof ByteType) {
            return Types.int8();
        } else if (type instanceof ShortType) {
            return Types.int16();
        } else if (type instanceof IntegerType) {
            return Types.int32();
        } else if (type instanceof LongType) {
            return Types.int64();
        } else if (type instanceof FloatType) {
            return Types.float32();
        } else if (type instanceof DoubleType) {
            return Types.float64();
        } else if (type instanceof StringType) {
            return Types.string();
        } else if (type instanceof BinaryType) {
            return Types.bytes();
        } else if (type instanceof DateType) {
            return Types.date();
        } else if (type instanceof TimestampType || type instanceof TimestampNTZType) {
            return Types.timestamp();
        } else if (type instanceof DecimalType decimal) {
            return Types.decimal(decimal.getPrecision(), decimal.getScale());
        }
        throw new PravahaException(
                DeltaErrors.UNSUPPORTED_TYPE,
                "column '" + field.getName() + "' of stream '" + streamName + "' has Delta type "
                        + type + ", which this plugin does not map. Nested and collection types need a "
                        + "flattening or encoding decision that belongs to the query, not to the reader. "
                        + "Project the column away, or flatten it in the table.");
    }

    /**
     * Copies one value out of a Delta column vector into a Pravaha row.
     *
     * <p>Null first, and unconditionally: every other branch here would read a garbage value from a
     * null slot, and Delta's vectors do not promise anything about the contents of one.
     */
    static void copyValue(ColumnVector vector, int rowInBatch, RowWriter writer, int ordinal, DataType type) {
        if (vector.isNullAt(rowInBatch)) {
            writer.setNull(ordinal);
            return;
        }
        if (type instanceof BooleanType) {
            writer.setBoolean(ordinal, vector.getBoolean(rowInBatch));
        } else if (type instanceof ByteType) {
            writer.setByte(ordinal, vector.getByte(rowInBatch));
        } else if (type instanceof ShortType) {
            writer.setShort(ordinal, vector.getShort(rowInBatch));
        } else if (type instanceof IntegerType || type instanceof DateType) {
            writer.setInt(ordinal, vector.getInt(rowInBatch));
        } else if (type instanceof LongType) {
            writer.setLong(ordinal, vector.getLong(rowInBatch));
        } else if (type instanceof TimestampType || type instanceof TimestampNTZType) {
            // Delta stores microseconds since epoch; Pravaha's timestamps are nanoseconds (ADR-012).
            writer.setLong(ordinal, Math.multiplyExact(vector.getLong(rowInBatch), 1_000L));
        } else if (type instanceof FloatType) {
            writer.setFloat(ordinal, vector.getFloat(rowInBatch));
        } else if (type instanceof DoubleType) {
            writer.setDouble(ordinal, vector.getDouble(rowInBatch));
        } else if (type instanceof StringType) {
            writer.setString(ordinal, vector.getString(rowInBatch));
        } else if (type instanceof BinaryType) {
            writer.setBytes(ordinal, vector.getBinary(rowInBatch));
        } else if (type instanceof DecimalType) {
            java.math.BigInteger unscaled = vector.getDecimal(rowInBatch).unscaledValue();
            writer.setDecimal(ordinal, unscaled.shiftRight(64).longValue(), unscaled.longValue());
        } else {
            throw new PravahaException(
                    DeltaErrors.UNSUPPORTED_TYPE, "no reader for Delta type " + type + " at ordinal " + ordinal);
        }
    }

    /** The Delta types of a schema's columns, in order, so the copy loop does no lookups. */
    static List<DataType> columnTypes(StructType schema) {
        return schema.fields().stream().map(StructField::getDataType).toList();
    }
}
