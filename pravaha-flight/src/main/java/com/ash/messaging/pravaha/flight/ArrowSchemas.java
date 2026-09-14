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
import java.util.Map;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
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

    /**
     * The name of the column a subscription carries its weight in.
     *
     * <p>Underscore-prefixed and namespaced, because it shares an Arrow schema with whatever columns
     * the view selected and a collision would be silent: a view with its own {@code weight} column
     * would have it overwritten by the engine's.
     */
    static final String WEIGHT_COLUMN = "_pravaha_weight";

    /** Metadata marking the weight column, so a client finds it by contract rather than by name. */
    static final String WEIGHT_METADATA_KEY = "pravaha.weight";

    /**
     * The Arrow schema a subscriber sees: the view's columns, then the weight.
     *
     * <p>The weight is the difference between a row arriving and a row being withdrawn, and without
     * it on the wire the two are byte-identical. A subscriber maintaining its own total against a
     * view that corrects a window would drift from the view silently and permanently -- the
     * retraction that should have cancelled the old value would land as a second copy of it.
     *
     * <p>Last, and marked in field metadata rather than recognised by name. Last so every existing
     * ordinal keeps its meaning; marked so a client that wants the weight does not have to trust a
     * name that a view could also have chosen.
     */
    static Schema subscriptionSchema(StreamSchema schema) {
        List<Field> fields = new ArrayList<>(toArrow(schema).getFields());
        fields.add(new Field(
                WEIGHT_COLUMN,
                new FieldType(false, new ArrowType.Int(64, true), null, Map.of(WEIGHT_METADATA_KEY, "true")),
                null));
        return new Schema(fields);
    }

    /**
     * The Arrow schema for a prepared statement's {@code ?} placeholders (ADR-032).
     *
     * <p>Named {@code param_1}, {@code param_2} and so on, counting from one, because that is how a
     * placeholder is referred to everywhere a person will read about it -- an error message, a JDBC
     * call, documentation. Counting from zero here and from one everywhere else is a small thing
     * that costs somebody an afternoon.
     *
     * <p>Every field is nullable. A caller is allowed to bind NULL to any placeholder, and the
     * schema is a statement about what may be sent, not about what the query will match.
     */
    static Schema parameterSchema(com.ash.messaging.pravaha.sql.plan.ParameterMetadata parameters) {
        List<Field> fields = new ArrayList<>(parameters.count());
        for (int i = 0; i < parameters.count(); i++) {
            fields.add(new Field("param_" + (i + 1), FieldType.nullable(arrowTypeOf(parameters.typeOf(i))), null));
        }
        return new Schema(fields);
    }

    private static ArrowType arrowTypeOf(com.ash.messaging.pravaha.api.data.Field field) {
        try {
            return arrowTypeOf(field.type().typeName());
        } catch (PravahaException e) {
            // Named. The refusal is correct and was unusable without it: a client saw which *type*
            // could not be sent and had to work out which column carried it, on a schema it may not
            // have written.
            throw new PravahaException(
                    FlightErrors.UNSUPPORTED_TYPE, "column '" + field.name() + "': " + e.getMessage(), e);
        }
    }

    private static ArrowType arrowTypeOf(com.ash.messaging.pravaha.api.data.TypeName typeName) {
        return switch (typeName) {
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
            // Distinct Arrow types. Both mapped to a zoned timestamp, so a TIME column arrived at a
            // client indistinguishable from an instant and a time of day could not be recovered.
            case TIME -> new ArrowType.Time(TimeUnit.NANOSECOND, 64);
            case TIMESTAMP_LTZ -> new ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC");
            default ->
                throw new PravahaException(
                        FlightErrors.UNSUPPORTED_TYPE,
                        typeName + " is not something Pravaha puts on the wire yet. DECIMAL in "
                                + "particular is refused rather than sent as a float, because the rounding "
                                + "decision belongs to whoever owns the ledger and not to a serialiser.");
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
                // TZ, matching the field declared above as Timestamp(NANOSECOND, "UTC"). A zoned
                // Arrow timestamp materialises as TimeStampNanoTZVector, and casting it to the
                // unzoned vector threw ClassCastException at serialisation time. It had never
                // fired because no query had ever put a timestamp on the wire: windows emitted
                // nothing, so window_start and window_end never reached a client. One bug was
                // keeping the other one hidden.
                case TIME, TIMESTAMP_LTZ ->
                    ((org.apache.arrow.vector.TimeStampNanoTZVector) vector)
                            .setSafe(index, ((Number) value).longValue());
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
