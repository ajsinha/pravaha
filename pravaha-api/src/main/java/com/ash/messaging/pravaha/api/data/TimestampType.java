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
