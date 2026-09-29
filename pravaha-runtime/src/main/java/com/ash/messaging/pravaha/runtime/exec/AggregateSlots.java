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
package com.ash.messaging.pravaha.runtime.exec;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;

/**
 * How an aggregate reads its argument into, and writes its result out of, a {@code long}.
 *
 * <p>HLP-1. The accumulators are 64-bit and every one of them read its argument with {@code
 * getLong} and wrote its answer with {@code setLong}, whatever the column. A slot is as wide as its
 * type, so {@code getLong} over an {@code INT32} read the value's four bytes and four of whatever
 * came next, and {@code setLong} into an {@code INT32} output column -- which is what the planner
 * gave {@code MIN}, {@code MAX} and {@code AVG} of an {@code INT}, and {@code SUM} of one before
 * {@code PravahaTypeSystem.deriveSumType} -- was refused by the writer and killed the lane:
 * "field 0 ('silver') is INT32, not INT64".
 */
final class AggregateSlots {

    private AggregateSlots() {}

    /** The type of each column in {@code schema}, or null at a negative ordinal (no argument). */
    static TypeName[] typesOf(StreamSchema schema, java.util.List<Integer> ordinals) {
        TypeName[] types = new TypeName[ordinals.size()];
        for (int i = 0; i < types.length; i++) {
            int ordinal = ordinals.get(i);
            types[i] = ordinal < 0 ? null : schema.field(ordinal).type().typeName();
        }
        return types;
    }

    /** An aggregate call as a refusal names it: {@code SUM(amount)}, {@code COUNT(*)}. */
    static String describe(
            com.ash.messaging.pravaha.runtime.plan.AggregateOperator.AggregateCall call, StreamSchema input) {
        int ordinal = call.argumentOrdinal();
        return call.kind() + "(" + (ordinal < 0 ? "*" : input.field(ordinal).name()) + ")";
    }

    /**
     * The column as a {@code long}, sign-extended from whatever width it is stored at.
     *
     * <p>A {@code DECIMAL} is read as its unscaled value (DECSUM-1): {@code 222.74} at scale 2 is
     * {@code 22274}. {@code SUM}, {@code MIN} and {@code MAX} of unscaled values at one scale are the
     * unscaled {@code SUM}, {@code MIN} and {@code MAX}, so the 64-bit accumulators answer them
     * exactly, and {@link #write} puts the scale back by writing into a column of that scale -- the
     * planner guarantees the output column's scale is the argument's. It used to read the slot with
     * {@code getLong}, which is the high half of the 128-bit value, and the write then failed "is
     * DECIMAL, not INT64" with no code. A value whose unscaled form does not fit 64 bits is refused by
     * name rather than cut to its low half.
     *
     * @param input the schema {@code ordinal} indexes, to name the column in a refusal
     */
    static long read(RowView row, int ordinal, TypeName type, StreamSchema input) {
        if (type == null) {
            return row.getLong(ordinal);
        }
        return switch (type) {
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            case DECIMAL -> unscaled(row, ordinal, input);
            default -> row.getLong(ordinal);
        };
    }

    private static long unscaled(RowView row, int ordinal, StreamSchema input) {
        long high = row.getDecimalHigh(ordinal);
        long low = row.getDecimalLow(ordinal);
        if (high != (low >> 63)) {
            throw new PravahaException(
                    RuntimeErrors.UNSUPPORTED_AGGREGATE,
                    "an aggregate over DECIMAL column '" + input.field(ordinal).name() + "' met a value of "
                            + "more than 18 digits (unscaled); aggregates accumulate a decimal's unscaled value "
                            + "in 64 bits, and this one has no 64-bit form. Refused rather than answered with "
                            + "part of the number. Narrow the column, or aggregate it in the continuous query "
                            + "over values that fit.");
        }
        return low;
    }

    /**
     * Writes an aggregate's answer at the width of its output column. A narrower column only ever
     * holds a {@code MIN}, {@code MAX} or {@code AVG} of values that were that width, so it fits. A
     * {@code DECIMAL} column receives the unscaled value, sign-extended to 128 bits.
     */
    static void write(RowWriter writer, int ordinal, long value, TypeName type) {
        switch (type) {
            case INT8 -> writer.setByte(ordinal, (byte) value);
            case INT16 -> writer.setShort(ordinal, (short) value);
            case INT32, DATE -> writer.setInt(ordinal, (int) value);
            case DECIMAL -> writer.setDecimal(ordinal, value >> 63, value);
            default -> writer.setLong(ordinal, value);
        }
    }

    /**
     * An aggregate's value as text for an inspection (ADR-048): a {@code DECIMAL} at its scale,
     * {@code 222.74} rather than the unscaled {@code 22274} the accumulator holds.
     */
    static String text(long value, StreamSchema output, int column) {
        if (output.field(column).type() instanceof com.ash.messaging.pravaha.api.data.DecimalType decimal) {
            return java.math.BigDecimal.valueOf(value, decimal.scale()).toPlainString();
        }
        return Long.toString(value);
    }
}
