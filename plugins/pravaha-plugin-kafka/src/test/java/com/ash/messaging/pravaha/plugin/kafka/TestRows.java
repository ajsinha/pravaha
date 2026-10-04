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
package com.ash.messaging.pravaha.plugin.kafka;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.DecimalType;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.Decimals;
import com.ash.messaging.pravaha.common.row.RowLayout;

/** Builds the engine's binary rows for a sink test, the way a delivery hands them to a sink. */
final class TestRows implements AutoCloseable {

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);

    /** A row of {@code values} in {@code schema}'s layout, with {@code weight}; a null is a null. */
    RowView row(StreamSchema schema, long weight, @Nullable Object... values) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            Object value = values[ordinal];
            if (value == null) {
                writer.setNull(ordinal);
                continue;
            }
            switch (value) {
                case Boolean v -> writer.setBoolean(ordinal, v);
                case Byte v -> writer.setByte(ordinal, v);
                case Short v -> writer.setShort(ordinal, v);
                case Integer v -> writer.setInt(ordinal, v);
                case Long v -> writer.setLong(ordinal, v);
                case Float v -> writer.setFloat(ordinal, v);
                case Double v -> writer.setDouble(ordinal, v);
                case String v -> writer.setString(ordinal, v);
                case byte[] v -> writer.setBytes(ordinal, v);
                case BigDecimal v -> {
                    int scale = ((DecimalType) schema.field(ordinal).type()).scale();
                    writer.setDecimal(ordinal, Decimals.high(v, scale), Decimals.low(v, scale));
                }
                default -> throw new IllegalArgumentException(value.toString());
            }
        }
        writer.weight(weight).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    @Override
    public void close() {
        arena.close();
    }
}
