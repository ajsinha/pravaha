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
package com.ash.messaging.pravaha.api.data;

/**
 * The closed set of physical type tags Pravaha understands.
 *
 * <p>{@link #fixedWidth()} drives the binary row layout (design section 8.3): fixed-width types live
 * inline in the fixed region, variable-width types are referenced by an (offset, length) pair.
 */
public enum TypeName {
    BOOLEAN(1),
    INT8(1),
    INT16(2),
    INT32(4),
    INT64(8),
    FLOAT32(4),
    FLOAT64(8),
    /** 128-bit unscaled value; avoids allocating a {@code BigDecimal} per record. */
    DECIMAL(16),
    /** Days since epoch. */
    DATE(4),
    /** Nanoseconds since midnight. */
    TIME(8),
    /** Nanoseconds since epoch, UTC. Range to year 2262, which is adequate and half the cost of 96 bits. */
    TIMESTAMP_LTZ(8),
    STRING(-1),
    BYTES(-1),
    ARRAY(-1),
    MAP(-1),
    ROW(-1);

    /**
     * Sentinel width for types whose encoded size depends on the value.
     *
     * <p>Spelled literally in the constant list above: an enum constant may not forward-reference a
     * static field, so the two must be kept in step by hand. {@code TypeNameTest} asserts they are.
     */
    public static final int VARIABLE = -1;

    private final int fixedWidth;

    TypeName(int fixedWidth) {
        this.fixedWidth = fixedWidth;
    }

    /** Encoded width in bytes, or {@link #VARIABLE} for variable-width types. */
    public int fixedWidth() {
        return fixedWidth;
    }

    /** {@code true} if values of this type occupy a constant number of bytes. */
    public boolean isFixedWidth() {
        return fixedWidth != VARIABLE;
    }
}
