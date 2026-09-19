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
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;

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

    /** The column as a {@code long}, sign-extended from whatever width it is stored at. */
    static long read(RowView row, int ordinal, TypeName type) {
        if (type == null) {
            return row.getLong(ordinal);
        }
        return switch (type) {
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            default -> row.getLong(ordinal);
        };
    }

    /**
     * Writes an aggregate's answer at the width of its output column. A narrower column only ever
     * holds a {@code MIN}, {@code MAX} or {@code AVG} of values that were that width, so it fits.
     */
    static void write(RowWriter writer, int ordinal, long value, TypeName type) {
        switch (type) {
            case INT8 -> writer.setByte(ordinal, (byte) value);
            case INT16 -> writer.setShort(ordinal, (short) value);
            case INT32, DATE -> writer.setInt(ordinal, (int) value);
            default -> writer.setLong(ordinal, value);
        }
    }
}
