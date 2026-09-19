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
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rel.type.RelDataTypeSystemImpl;
import org.apache.calcite.sql.type.SqlTypeName;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.TimestampType;

/**
 * Calcite's type system, widened to match Pravaha's.
 *
 * <p>Two defaults in {@link RelDataTypeSystem#DEFAULT} are narrower than this engine's own types,
 * and both were found by the round-trip property rather than by reading documentation -- which is
 * the argument for having written that property.
 *
 * <ul>
 *   <li><strong>{@code DECIMAL} precision.</strong> Calcite defaults to 19, so
 *       {@code DECIMAL(38, 0)} silently came back as {@code DECIMAL(19, 0)}. Pravaha stores decimals
 *       as a 128-bit unscaled integer and supports 38 digits (design section 8.2). Left at 19, a financial
 *       aggregate over large values would validate, plan, and then be quietly truncated -- the exact
 *       failure a decimal type exists to prevent.
 *   <li><strong>{@code TIMESTAMP} precision.</strong> Calcite defaults to 3, so {@code TIMESTAMP(9)}
 *       came back as {@code TIMESTAMP(3)}. Pravaha timestamps are nanoseconds since epoch
 *       (design section 15.1) and windowing arithmetic is done in them; rounding to milliseconds at the
 *       planner boundary would put window edges in the wrong place for sub-millisecond event times.
 * </ul>
 */
public final class PravahaTypeSystem extends RelDataTypeSystemImpl {

    public static final RelDataTypeSystem INSTANCE = new PravahaTypeSystem();

    private PravahaTypeSystem() {}

    @Override
    public int getMaxNumericPrecision() {
        // 128-bit unscaled: 38 decimal digits.
        return DecimalType.MAX_PRECISION;
    }

    @Override
    public int getMaxNumericScale() {
        return DecimalType.MAX_PRECISION;
    }

    @Override
    public int getMaxPrecision(SqlTypeName typeName) {
        return switch (typeName) {
            case TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE, TIME, TIME_WITH_LOCAL_TIME_ZONE ->
                TimestampType.MAX_PRECISION;
            case DECIMAL -> DecimalType.MAX_PRECISION;
            default -> super.getMaxPrecision(typeName);
        };
    }

    @Override
    public int getDefaultPrecision(SqlTypeName typeName) {
        return switch (typeName) {
            // An unqualified TIMESTAMP in SQL means the engine's native precision, not Calcite's
            // millisecond default; otherwise "TIMESTAMP" and "TIMESTAMP(9)" would behave differently
            // for no reason a user could see.
            case TIMESTAMP, TIMESTAMP_WITH_LOCAL_TIME_ZONE -> TimestampType.MAX_PRECISION;
            default -> super.getDefaultPrecision(typeName);
        };
    }

    /**
     * {@code SUM} of an integer narrower than {@code BIGINT} is a {@code BIGINT}.
     *
     * <p>Calcite's default keeps the argument's type, so {@code SUM(CASE WHEN tier = 'silver' THEN 1
     * ELSE 0 END)} -- an {@code INTEGER} -- planned an {@code INT32} output column. The engine's
     * accumulators add in 64 bits, and the sum of a stream of 32-bit values outgrows 32 bits long
     * before the stream ends; a column that narrow was a runtime failure waiting for its first row
     * (HLP-1). {@code SUM0} derives through here too.
     */
    @Override
    public RelDataType deriveSumType(RelDataTypeFactory typeFactory, RelDataType argumentType) {
        return switch (argumentType.getSqlTypeName()) {
            case TINYINT, SMALLINT, INTEGER ->
                typeFactory.createTypeWithNullability(
                        typeFactory.createSqlType(SqlTypeName.BIGINT), argumentType.isNullable());
            default -> super.deriveSumType(typeFactory, argumentType);
        };
    }

    @Override
    public boolean shouldConvertRaggedUnionTypesToVarying() {
        // A union of CHAR(3) and CHAR(5) becomes VARCHAR(5) rather than space-padding the shorter
        // side. Padding is a surprise in a streaming result, where the value goes straight to a sink.
        return true;
    }
}
