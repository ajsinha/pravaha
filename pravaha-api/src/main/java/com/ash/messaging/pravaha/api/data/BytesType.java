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

/** Opaque binary. {@code maxLength} is {@link #UNBOUNDED} when the source declares no limit. */
public record BytesType(int maxLength, boolean nullable) implements PravahaType {

    /** Sentinel for a binary value with no declared maximum length. */
    public static final int UNBOUNDED = -1;

    public BytesType {
        if (maxLength != UNBOUNDED && maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be positive or UNBOUNDED, got " + maxLength);
        }
    }

    @Override
    public TypeName typeName() {
        return TypeName.BYTES;
    }

    @Override
    public String sqlName() {
        String base = maxLength == UNBOUNDED ? "VARBINARY" : "VARBINARY(" + maxLength + ")";
        return base + (nullable ? "" : " NOT NULL");
    }

    @Override
    public PravahaType withNullable(boolean value) {
        return value == nullable ? this : new BytesType(maxLength, value);
    }
}
