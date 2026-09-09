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
 * Instant on the UTC timeline, stored as nanoseconds since epoch in a {@code long}.
 *
 * <p>{@code precision} records the declared fractional-second digits for SQL rendering and for
 * validating source data; the physical representation is always nanoseconds (design section 15.1).
 */
public record TimestampType(int precision, boolean nullable) implements PravahaType {

    /** Nanosecond precision: the physical representation, and the default. */
    public static final int MAX_PRECISION = 9;

    public TimestampType {
        if (precision < 0 || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("precision must be in [0, " + MAX_PRECISION + "], got " + precision);
        }
    }

    @Override
    public TypeName typeName() {
        return TypeName.TIMESTAMP_LTZ;
    }

    @Override
    public String sqlName() {
        return "TIMESTAMP(" + precision + ") WITH LOCAL TIME ZONE" + (nullable ? "" : " NOT NULL");
    }

    @Override
    public PravahaType withNullable(boolean value) {
        return value == nullable ? this : new TimestampType(precision, value);
    }
}
