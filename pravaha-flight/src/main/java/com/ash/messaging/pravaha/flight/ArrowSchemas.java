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
package com.ash.messaging.pravaha.flight;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeStampNanoVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

/**
 * Pravaha's types on the wire.
 *
 * <p>Arrow is the format the receiving end already wants -- pandas, Polars, DuckDB, a JDBC driver
 * and a Go client all read it without a translation layer -- so this is the only place the engine's
 * type system meets anybody else's, and the mapping is written once here rather than per client.
 *
 * <p>Two mappings are worth reading twice. <strong>Timestamps go out as nanoseconds</strong>, which
 * is what the engine holds (ADR-012); downgrading them to microseconds at the boundary would lose
 * precision the engine was careful to keep. And <strong>DECIMAL is refused</strong> rather than
 * mapped to a float: Arrow has a real decimal type, but the engine's two-word form is not yet
 * convertible without a rounding decision, and making that decision silently at the wire is how a
 * ledger acquires a rounding error.
 */
final class ArrowSchemas {

    private ArrowSchemas() {}

    /** The Arrow schema a client will see for these rows. */
    static Schema toArrow(StreamSchema schema) {
        List<Field> fields = new ArrayList<>(schema.fields().size());
        for (com.ash.messaging.pravaha.api.data.Field field : schema.fields()) {
            fields.add(new Field(field.name(), FieldType.nullable(arrowTypeOf(field)), null));
        }
        return new Schema(fields);
    }

    private static ArrowType arrowTypeOf(com.ash.messaging.pravaha.api.data.Field field) {
        return switch (field.type().typeName()) {
            case BOOLEAN -> ArrowType.Bool.INSTANCE;
            case INT8 -> new ArrowType.Int(8, true);
            case INT16 -> new ArrowType.Int(16, true);
            case INT32 -> new ArrowType.Int(32, true);
            case INT64 -> new ArrowType.Int(64, true);
            case FLOAT32 -> new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
            case FLOAT64 -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            case STRING -> ArrowType.Utf8.INSTANCE;
            case BYTES -> ArrowType.Binary.INSTANCE;
            case DATE -> new ArrowType.Date(DateUnit.DAY);
            // Nanoseconds, because that is what the engine holds. Truncating here would throw away
            // precision the whole engine is built to preserve.
            case TIME, TIMESTAMP_LTZ -> new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC");
            default ->
                throw new PravahaException(
                        FlightErrors.UNSUPPORTED_TYPE,
                        "column '" + field.name() + "' is " + field.type().typeName()
                                + ", which Pravaha does not put on the wire yet. DECIMAL in particular is "
                                + "refused rather than sent as a float, because the rounding decision belongs "
                                + "to whoever owns the ledger and not to a serialiser.");
        };
    }

    /** Writes one row of plain values into the vectors at {@code index}. */
    static void write(VectorSchemaRoot root, int index, Object[] values, StreamSchema schema) {
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            FieldVector vector = root.getVector(ordinal);
            Object value = values[ordinal];
            if (value == null) {
                vector.setNull(index);
                continue;
            }
            TypeName type = schema.field(ordinal).type().typeName();
            switch (type) {
                case BOOLEAN -> ((BitVector) vector).setSafe(index, (Boolean) value ? 1 : 0);
                case INT8 -> ((TinyIntVector) vector).setSafe(index, ((Number) value).byteValue());
                case INT16 -> ((SmallIntVector) vector).setSafe(index, ((Number) value).shortValue());
                case INT32 -> ((IntVector) vector).setSafe(index, ((Number) value).intValue());
                case DATE -> ((DateDayVector) vector).setSafe(index, ((Number) value).intValue());
                case INT64 -> ((BigIntVector) vector).setSafe(index, ((Number) value).longValue());
                case TIME, TIMESTAMP_LTZ -> ((TimeStampNanoVector) vector).setSafe(index, ((Number) value).longValue());
                case FLOAT32 -> ((Float4Vector) vector).setSafe(index, ((Number) value).floatValue());
                case FLOAT64 -> ((Float8Vector) vector).setSafe(index, ((Number) value).doubleValue());
                case BYTES -> ((VarBinaryVector) vector).setSafe(index, (byte[]) value);
                default ->
                    ((VarCharVector) vector)
                            .setSafe(index, String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
