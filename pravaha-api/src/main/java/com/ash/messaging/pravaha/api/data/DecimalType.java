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
 * Exact decimal, stored as a 128-bit unscaled integer.
 *
 * <p>Deliberately not {@code BigDecimal}: the financial aggregations this engine targets would
 * otherwise allocate on every record, which is the cost the whole design exists to avoid.
 */
public record DecimalType(int precision, int scale, boolean nullable) implements PravahaType {

    /** Maximum digits representable in 128 bits. */
    public static final int MAX_PRECISION = 38;

    public DecimalType {
        if (precision < 1 || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("precision must be in [1, " + MAX_PRECISION + "], got " + precision);
        }
        if (scale < 0 || scale > precision) {
            throw new IllegalArgumentException("scale must be in [0, precision=" + precision + "], got " + scale);
        }
    }

    @Override
    public TypeName typeName() {
        return TypeName.DECIMAL;
    }

    @Override
    public String sqlName() {
        return "DECIMAL(" + precision + ", " + scale + ")" + (nullable ? "" : " NOT NULL");
    }

    @Override
    public PravahaType withNullable(boolean value) {
        return value == nullable ? this : new DecimalType(precision, scale, value);
    }
}
