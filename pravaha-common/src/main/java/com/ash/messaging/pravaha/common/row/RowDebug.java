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
package com.ash.messaging.pravaha.common.row;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.PravahaType;

/**
 * Renders field values as text for humans.
 *
 * <p>Strictly off the hot path: allocates freely, and is used only by {@code toString}, the
 * dead-letter queue and the debugger UI. Kept apart from {@link BinaryRowView} so that nothing in
 * the read path can accidentally reach it.
 */
final class RowDebug {

    private RowDebug() {}

    static String render(BinaryRowView row, int ordinal) {
        PravahaType type = row.schema().field(ordinal).type();
        return switch (type.typeName()) {
            case BOOLEAN -> String.valueOf(row.getBoolean(ordinal));
            case INT8 -> String.valueOf(row.getByte(ordinal));
            case INT16 -> String.valueOf(row.getShort(ordinal));
            case INT32, DATE -> String.valueOf(row.getInt(ordinal));
            case INT64, TIME -> String.valueOf(row.getLong(ordinal));
            case FLOAT32 -> String.valueOf(row.getFloat(ordinal));
            case FLOAT64 -> String.valueOf(row.getDouble(ordinal));
            case DECIMAL -> renderDecimal(row, ordinal, (DecimalType) type);
            case TIMESTAMP_LTZ -> renderTimestamp(row.getLong(ordinal));
            case STRING -> '\'' + row.getString(ordinal) + '\'';
            case BYTES, ARRAY, MAP, ROW -> renderOpaque(row, ordinal);
        };
    }

    private static String renderDecimal(BinaryRowView row, int ordinal, DecimalType type) {
        BigInteger unscaled = Decimals.toBigInteger(row.getDecimalHigh(ordinal), row.getDecimalLow(ordinal));
        return new BigDecimal(unscaled, type.scale()).toPlainString();
    }

    private static String renderTimestamp(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L))
                .toString();
    }

    private static String renderOpaque(BinaryRowView row, int ordinal) {
        var slice = row.getBytes(ordinal, new com.ash.messaging.pravaha.api.data.MutableSlice());
        return "<" + slice.length() + "B>";
    }
}
