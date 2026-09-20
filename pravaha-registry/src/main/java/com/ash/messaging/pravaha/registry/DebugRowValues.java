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
package com.ash.messaging.pravaha.registry;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Writes a replayed value back into a binary row (ADR-048).
 *
 * <p>A debug session reads its input as values -- it has to, because those values are what the
 * exported fixture states -- and then has to hand the engine a binary row. This is the one place
 * that turns one into the other, so the row a fork is fed and the row a fixture replays are encoded
 * by the same code and cannot disagree.
 */
final class DebugRowValues {

    private DebugRowValues() {}

    static void write(RowWriter writer, StreamSchema schema, int ordinal, Object value) {
        if (value == null) {
            writer.setNull(ordinal);
            return;
        }
        switch (schema.field(ordinal).type().typeName()) {
            case BOOLEAN -> writer.setBoolean(ordinal, (Boolean) value);
            case INT8 -> writer.setByte(ordinal, ((Number) value).byteValue());
            case INT16 -> writer.setShort(ordinal, ((Number) value).shortValue());
            case INT32, DATE -> writer.setInt(ordinal, ((Number) value).intValue());
            case INT64, TIME, TIMESTAMP_LTZ -> writer.setLong(ordinal, ((Number) value).longValue());
            case FLOAT32 -> writer.setFloat(ordinal, ((Number) value).floatValue());
            case FLOAT64 -> writer.setDouble(ordinal, ((Number) value).doubleValue());
            case STRING -> writer.setString(ordinal, String.valueOf(value));
            case BYTES -> writer.setBytes(ordinal, (byte[]) value);
            default ->
                throw new PravahaException(
                        DebugErrors.BAD_STEP,
                        "column '" + schema.field(ordinal).name() + "' is "
                                + schema.field(ordinal).type().typeName()
                                + ", which a debug session cannot replay: it reads its input as values so that "
                                + "it can be exported as a fixture, and this type has no value form here. Debug "
                                + "a query that projects the column away.");
        }
    }
}
