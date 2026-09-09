/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
