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
package com.ash.messaging.pravaha.sql;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.type.SqlTypeName;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.StringType;
import com.ash.messaging.pravaha.api.data.TimestampType;
import com.ash.messaging.pravaha.api.data.Types;

/**
 * Translates between Pravaha's type system and Calcite's.
 *
 * <p>Kept in one place because a mapping that disagrees with itself in two directions produces the
 * worst kind of bug: a query validates, plans, and then reads a column as the wrong width. The
 * round trip is property-tested rather than assumed.
 *
 * <p>Two decisions worth stating. Nullability is carried through rather than defaulted, because
 * Calcite's optimiser uses it -- a {@code NOT NULL} column lets it eliminate null checks and
 * simplify predicates, and telling it everything is nullable quietly costs that. And
 * {@code TIMESTAMP} maps to {@code TIMESTAMP_WITH_LOCAL_TIME_ZONE}: Pravaha timestamps are instants
 * on the UTC timeline (design section 15.1), and Calcite's plain {@code TIMESTAMP} is a wall-clock
 * type whose comparison semantics differ in exactly the cases windowing depends on.
 */
public final class TypeMapping {

    private TypeMapping() {}

    /** Pravaha type to Calcite type. */
    public static RelDataType toCalcite(RelDataTypeFactory factory, PravahaType type) {
        RelDataType base = baseType(factory, type);
        return factory.createTypeWithNullability(base, type.nullable());
    }

    private static RelDataType baseType(RelDataTypeFactory factory, PravahaType type) {
        return switch (type.typeName()) {
            case BOOLEAN -> factory.createSqlType(SqlTypeName.BOOLEAN);
            case INT8 -> factory.createSqlType(SqlTypeName.TINYINT);
            case INT16 -> factory.createSqlType(SqlTypeName.SMALLINT);
            case INT32 -> factory.createSqlType(SqlTypeName.INTEGER);
            case INT64 -> factory.createSqlType(SqlTypeName.BIGINT);
            case FLOAT32 -> factory.createSqlType(SqlTypeName.REAL);
            case FLOAT64 -> factory.createSqlType(SqlTypeName.DOUBLE);
            case DECIMAL -> {
                DecimalType d = (DecimalType) type;
                yield factory.createSqlType(SqlTypeName.DECIMAL, d.precision(), d.scale());
            }
            case DATE -> factory.createSqlType(SqlTypeName.DATE);
            case TIME -> factory.createSqlType(SqlTypeName.TIME);
            // An instant on the UTC timeline, not a wall-clock reading. Calcite's plain TIMESTAMP
            // compares differently in exactly the cases windowing depends on.
            case TIMESTAMP_LTZ ->
                factory.createSqlType(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE, ((TimestampType) type).precision());
            case STRING -> {
                StringType s = (StringType) type;
                yield s.maxLength() == StringType.UNBOUNDED
                        ? factory.createSqlType(SqlTypeName.VARCHAR)
                        : factory.createSqlType(SqlTypeName.VARCHAR, s.maxLength());
            }
            case BYTES -> factory.createSqlType(SqlTypeName.VARBINARY);
            case ARRAY, MAP, ROW -> factory.createSqlType(SqlTypeName.ANY);
        };
    }

    /** Calcite type back to a Pravaha type. */
    public static PravahaType fromCalcite(RelDataType type) {
        PravahaType base = baseFromCalcite(type);
        return type.isNullable() ? base.withNullable(true) : base;
    }

    private static PravahaType baseFromCalcite(RelDataType type) {
        return switch (type.getSqlTypeName()) {
            case BOOLEAN -> Types.bool();
            case TINYINT -> Types.int8();
            case SMALLINT -> Types.int16();
            case INTEGER -> Types.int32();
            case BIGINT -> Types.int64();
            case REAL, FLOAT -> Types.float32();
            case DOUBLE -> Types.float64();
            case DECIMAL -> Types.decimal(type.getPrecision(), type.getScale());
            case DATE -> Types.date();
            case TIME -> Types.time();
            case TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE ->
                Types.timestamp(Math.min(type.getPrecision(), TimestampType.MAX_PRECISION));
            case VARCHAR, CHAR ->
                type.getPrecision() == RelDataType.PRECISION_NOT_SPECIFIED
                        ? Types.string()
                        : Types.string(type.getPrecision());
            case VARBINARY, BINARY -> Types.bytes();
            default ->
                throw new IllegalArgumentException("no Pravaha type for SQL type " + type.getSqlTypeName()
                        + "; the supported set is in TypeMapping");
        };
    }

    /** A whole schema as a Calcite row type. */
    public static RelDataType toRowType(RelDataTypeFactory factory, StreamSchema schema) {
        RelDataTypeFactory.Builder builder = factory.builder();
        for (int i = 0; i < schema.fieldCount(); i++) {
            builder.add(
                    schema.field(i).name(), toCalcite(factory, schema.field(i).type()));
        }
        return builder.build();
    }
}
