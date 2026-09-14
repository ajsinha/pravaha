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

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;

/**
 * Value equality between two rows of the same schema.
 *
 * <p>Field by field rather than byte by byte, and the distinction matters wherever a row is treated
 * as a Z-set element. Two arrivals of the same values carry different weights, event times and
 * sequence numbers in their headers; byte equality would call them different elements, and a
 * retraction would then never cancel the insert it was meant to cancel.
 */
final class RowValues {

    private RowValues() {}

    /** Whether one field holds the same value in both rows. Neither may be null at this ordinal. */
    static boolean equal(RowView left, RowView right, int ordinal, StreamSchema schema) {
        return switch (schema.field(ordinal).type().typeName()) {
            case BOOLEAN -> left.getBoolean(ordinal) == right.getBoolean(ordinal);
            case INT8 -> left.getByte(ordinal) == right.getByte(ordinal);
            case INT16 -> left.getShort(ordinal) == right.getShort(ordinal);
            case INT32, DATE -> left.getInt(ordinal) == right.getInt(ordinal);
            case INT64, TIME, TIMESTAMP_LTZ -> left.getLong(ordinal) == right.getLong(ordinal);
            case FLOAT32 -> Float.compare(left.getFloat(ordinal), right.getFloat(ordinal)) == 0;
            case FLOAT64 -> Double.compare(left.getDouble(ordinal), right.getDouble(ordinal)) == 0;
            case DECIMAL ->
                left.getDecimalHigh(ordinal) == right.getDecimalHigh(ordinal)
                        && left.getDecimalLow(ordinal) == right.getDecimalLow(ordinal);
            case STRING -> left.getString(ordinal).equals(right.getString(ordinal));
            // BYTES is byte equality, which has no ambiguity to defend. It was refused along with
            // the nested types, and that cost more than it protected: row identity compares *every*
            // column, not the key ones, so a join crashed the instant two rows on one side shared a
            // key -- with nothing to do with what the join was keyed on. A stream carrying one
            // binary column anywhere could not be joined at all, and it failed with an uncoded
            // UnsupportedOperationException that took the query to FAILED mid-flight.
            case BYTES -> sameBytes(left, right, ordinal);
            // ARRAY, MAP and ROW still have no comparison this engine can defend -- nested equality
            // has ordering and null questions of its own -- and a wrong answer here means a
            // retraction failing to cancel its insert, which grows state for ever. Refused loudly,
            // and now with a code: this reaches a user as a failed query, so it needs to say what
            // to do rather than only what happened.
            default ->
                throw new com.ash.messaging.pravaha.api.PravahaException(
                        RuntimeErrors.UNSUPPORTED_AGGREGATE,
                        "column '" + schema.field(ordinal).name() + "' is "
                                + schema.field(ordinal).type().typeName()
                                + ", and comparing two of them for row identity is not built. Row identity "
                                + "compares every column, so this is reached by any query that joins or "
                                + "retracts over a row carrying one -- not only by one keyed on it. Project "
                                + "the column away before the join.");
        };
    }

    /** Byte-for-byte equality of a variable-width binary field. */
    private static boolean sameBytes(RowView left, RowView right, int ordinal) {
        com.ash.messaging.pravaha.api.data.MutableSlice leftSlice =
                new com.ash.messaging.pravaha.api.data.MutableSlice();
        com.ash.messaging.pravaha.api.data.MutableSlice rightSlice =
                new com.ash.messaging.pravaha.api.data.MutableSlice();
        left.getBytes(ordinal, leftSlice);
        right.getBytes(ordinal, rightSlice);
        if (leftSlice.length() != rightSlice.length()) {
            return false;
        }
        if (leftSlice.length() == 0) {
            return true;
        }
        if (!(left instanceof com.ash.messaging.pravaha.common.row.BinaryRowView leftRow)
                || !(right instanceof com.ash.messaging.pravaha.common.row.BinaryRowView rightRow)) {
            // Without the regions there is nothing to compare, and guessing equal would let a
            // retraction cancel a row it does not match.
            throw new com.ash.messaging.pravaha.api.PravahaException(
                    RuntimeErrors.UNSUPPORTED_AGGREGATE,
                    "comparing binary columns needs rows backed by a memory region");
        }
        byte[] leftBytes = new byte[leftSlice.length()];
        byte[] rightBytes = new byte[rightSlice.length()];
        leftRow.region().getBytes(leftSlice.offset(), leftBytes, 0, leftBytes.length);
        rightRow.region().getBytes(rightSlice.offset(), rightBytes, 0, rightBytes.length);
        return java.util.Arrays.equals(leftBytes, rightBytes);
    }

    /** Whether every field holds the same value, nulls included. */
    static boolean sameFields(RowView left, RowView right, StreamSchema schema) {
        for (int ordinal = 0; ordinal < schema.fields().size(); ordinal++) {
            if (left.isNull(ordinal) != right.isNull(ordinal)) {
                return false;
            }
            if (!left.isNull(ordinal) && !equal(left, right, ordinal, schema)) {
                return false;
            }
        }
        return true;
    }
}
