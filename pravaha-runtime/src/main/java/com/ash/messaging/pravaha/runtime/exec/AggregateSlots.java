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
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;

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
     * Whether call {@code kind}'s answer is SQL NULL (ALLNULLAGG-1): {@code SUM} and {@code AVG} with
     * no non-null value accumulated, {@code MIN} and {@code MAX} with none seen. {@code COUNT} and
     * {@code COUNT(DISTINCT)} are never NULL -- a count of nothing is 0. These answers were written
     * as 0, which a reader cannot tell from a real total of zero.
     *
     * @param nonNull how many non-null values {@code SUM}/{@code AVG} have accumulated, net of
     *     retractions
     * @param seen whether {@code MIN}/{@code MAX} have met a non-null value
     */
    static boolean isNull(AggregateOperator.AggregateCall.Kind kind, long nonNull, boolean seen) {
        return switch (kind) {
            case SUM, AVG -> nonNull == 0;
            case MIN, MAX -> !seen;
            case COUNT, COUNT_DISTINCT -> false;
        };
    }

    /** {@link #write}, or a NULL when {@code isNull}. */
    static void write(RowWriter writer, int ordinal, long value, boolean isNull, TypeName type) {
        if (isNull) {
            writer.setNull(ordinal);
        } else {
            write(writer, ordinal, value, type);
        }
    }

    /**
     * One published answer of an unwindowed aggregate: its values, and which are NULL. Compared by
     * content, since what decides whether a group re-publishes is whether its answer changed --
     * and 0 becoming NULL is a change.
     */
    @SuppressWarnings("ArrayRecordComponent") // equals and hashCode compare the array's contents
    record Answer(long[] values, boolean[] nulls) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Answer answer
                    && java.util.Arrays.equals(values, answer.values)
                    && java.util.Arrays.equals(nulls, answer.nulls);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(values) + java.util.Arrays.hashCode(nulls);
        }

        @Override
        public String toString() {
            return java.util.Arrays.toString(values) + " nulls " + java.util.Arrays.toString(nulls);
        }

        boolean anyNull() {
            for (boolean isNull : nulls) {
                if (isNull) {
                    return true;
                }
            }
            return false;
        }

        /** The values only, as a checkpoint written before NULL answers has them. */
        void writeValues(java.io.DataOutput out) throws java.io.IOException {
            for (long value : values) {
                out.writeLong(value);
            }
        }

        /** The null flags, which a checkpoint carries after the values when any is set. */
        void writeNulls(java.io.DataOutput out) throws java.io.IOException {
            for (boolean isNull : nulls) {
                out.writeBoolean(isNull);
            }
        }

        /** Reads {@code n} values and, when {@code withNulls}, their flags after them. */
        static Answer read(java.io.DataInput in, int n, boolean withNulls) throws java.io.IOException {
            long[] values = new long[n];
            for (int i = 0; i < n; i++) {
                values[i] = in.readLong();
            }
            boolean[] nulls = new boolean[n];
            if (withNulls) {
                for (int i = 0; i < n; i++) {
                    nulls[i] = in.readBoolean();
                }
            }
            return new Answer(values, nulls);
        }
    }

    /**
     * {@code AVG} of integers into a {@code DECIMAL} answer column (AVGINT-1): the exact quotient at the
     * column's scale, rounded half away from zero -- PostgreSQL's {@code numeric} division -- or NULL
     * for no rows, as SQL's {@code AVG} of nothing is.
     */
    static void writeAverage(RowWriter writer, int ordinal, long sum, long count, StreamSchema output) {
        if (count == 0) {
            writer.setNull(ordinal);
            return;
        }
        int scale = ((com.ash.messaging.pravaha.api.data.DecimalType)
                        output.field(ordinal).type())
                .scale();
        java.math.BigDecimal average = java.math.BigDecimal.valueOf(sum)
                .divide(java.math.BigDecimal.valueOf(count), scale, java.math.RoundingMode.HALF_UP);
        writer.setDecimal(
                ordinal,
                com.ash.messaging.pravaha.common.row.Decimals.high(average, scale),
                com.ash.messaging.pravaha.common.row.Decimals.low(average, scale));
    }

    /** Whether call {@code kind}'s answer is an exact average written by {@link #writeAverage}. */
    static boolean exactAverage(AggregateOperator.AggregateCall.Kind kind, TypeName answer) {
        return kind == AggregateOperator.AggregateCall.Kind.AVG && answer == TypeName.DECIMAL;
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
