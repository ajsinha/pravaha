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
            // BYTES, ARRAY, MAP and ROW have no comparison Pravaha can defend yet -- nested
            // equality has ordering and null questions of its own -- and a wrong answer here means a
            // retraction failing to cancel its insert, which grows state forever. Refused loudly.
            default ->
                throw new UnsupportedOperationException(
                        "cannot compare '" + schema.field(ordinal).name() + "' of type "
                                + schema.field(ordinal).type().typeName() + " for row equality yet");
        };
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
