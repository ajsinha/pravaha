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

import java.util.LinkedHashMap;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.MutableSlice;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Renders a row into strings, for anything that shows state to a person (ADR-047).
 *
 * <p>Immediately, while the flyweight still points at the bytes. A row read out of operator state
 * is a window onto arena memory that is reused as soon as the walk moves on, so anything that keeps
 * a row rather than its text keeps a value that changes underneath it.
 *
 * <p>Nothing here throws on a type it does not know: an inspection that fails because one column is
 * a MAP is an inspection that cannot be used on the query that needed it most.
 */
final class RowText {

    private RowText() {}

    /** Every column of {@code row}, by name, in schema order. */
    static Map<String, String> of(RowView row, StreamSchema schema) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            values.put(schema.field(ordinal).name(), at(row, schema, ordinal));
        }
        return values;
    }

    /** The values at {@code ordinals}, joined with '|': a row's key, as a page is keyed by it. */
    static String key(RowView row, int[] ordinals, StreamSchema schema) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < ordinals.length; i++) {
            if (i > 0) {
                text.append('|');
            }
            text.append(at(row, schema, ordinals[i]));
        }
        return text.toString();
    }

    static String at(RowView row, StreamSchema schema, int ordinal) {
        if (row.isNull(ordinal)) {
            return "null";
        }
        try {
            return switch (schema.field(ordinal).type().typeName()) {
                case BOOLEAN -> Boolean.toString(row.getBoolean(ordinal));
                case INT8 -> Byte.toString(row.getByte(ordinal));
                case INT16 -> Short.toString(row.getShort(ordinal));
                case INT32, DATE -> Integer.toString(row.getInt(ordinal));
                case INT64, TIME, TIMESTAMP_LTZ -> Long.toString(row.getLong(ordinal));
                case FLOAT32 -> Float.toString(row.getFloat(ordinal));
                case FLOAT64 -> Double.toString(row.getDouble(ordinal));
                case DECIMAL -> row.getDecimalHigh(ordinal) + ":" + row.getDecimalLow(ordinal);
                case STRING -> row.getString(ordinal);
                case BYTES -> bytes(row, ordinal);
                default -> "<" + schema.field(ordinal).type().typeName() + ">";
            };
        } catch (RuntimeException e) {
            // A column this engine can carry but not render is shown as unreadable rather than
            // ending the page: the other columns are usually the ones somebody is looking at.
            return "<unreadable>";
        }
    }

    private static String bytes(RowView row, int ordinal) {
        MutableSlice slice = new MutableSlice();
        row.getBytes(ordinal, slice);
        return slice.length() + " bytes";
    }
}
