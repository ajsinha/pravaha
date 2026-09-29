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
package com.ash.messaging.pravaha.sql.plan;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * DECSUM-1: which aggregates of a {@code DECIMAL} the engine answers, and the one it refuses.
 *
 * <p>The accumulators hold a decimal's unscaled value, so {@code SUM}, {@code MIN} and {@code MAX}
 * are exact -- provided the answer's column has the argument's scale, because the unscaled value is
 * written into it as it stands. Calcite gives all three the argument's scale ({@code
 * PravahaTypeSystem.deriveSumType} widens only the precision); the check makes that an invariant
 * rather than an assumption.
 *
 * <p>{@code AVG} is a quotient, and a quotient of decimals is exact at a fixed scale only by luck --
 * the reason {@code /} over decimals is refused {@code PRV-2021}. SQL types {@code AVG} of a {@code
 * DECIMAL(10, 2)} as a {@code DECIMAL(10, 2)}, so answering would round most groups silently.
 * Refused with the same code, naming the column and the exact alternative.
 */
final class DecimalAggregates {

    private DecimalAggregates() {}

    /**
     * Refuses an aggregate of a {@code DECIMAL} that would not be exact; returns for every other.
     *
     * @param kind the aggregate
     * @param answer the type SQL gives the aggregate's answer
     * @param argument the argument's ordinal in {@code input}, or negative for none
     * @param input the aggregate's input schema
     */
    static void refuseInexact(
            AggregateOperator.AggregateCall.Kind kind, RelDataType answer, int argument, StreamSchema input) {
        if (argument < 0 || argument >= input.fieldCount()) {
            return;
        }
        if (input.field(argument).type().typeName() != TypeName.DECIMAL) {
            return;
        }
        String column = input.field(argument).name();
        switch (kind) {
            case COUNT, COUNT_DISTINCT -> {
                // Never reads the value's arithmetic.
            }
            case AVG ->
                throw new PravahaException(
                        SqlErrors.UNSUPPORTED_EXPRESSION,
                        "AVG(" + column + ") is over a DECIMAL column, and an average of decimals is a "
                                + "quotient: SQL gives it the column's own scale, so the answer would be "
                                + "rounded for most groups without saying so. Ask for the exact parts -- "
                                + "SUM(" + column + ") and COUNT(" + column + ") -- and divide where the "
                                + "rounding is yours to choose.");
            default -> {
                int scale = ((DecimalType) input.field(argument).type()).scale();
                if (answer.getSqlTypeName() != SqlTypeName.DECIMAL || answer.getScale() != scale) {
                    throw new PravahaException(
                            SqlErrors.UNSUPPORTED_EXPRESSION,
                            kind + "(" + column + ") would answer a " + answer + " for a DECIMAL argument at "
                                    + "scale " + scale + "; an aggregate of a decimal is answered exactly only "
                                    + "at the argument's own scale.");
                }
            }
        }
    }
}
