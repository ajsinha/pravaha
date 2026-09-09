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

import java.util.Objects;

/** Ordered, homogeneous collection. */
public record ArrayType(PravahaType elementType, boolean nullable) implements PravahaType {

    public ArrayType {
        Objects.requireNonNull(elementType, "elementType");
    }

    @Override
    public TypeName typeName() {
        return TypeName.ARRAY;
    }

    @Override
    public String sqlName() {
        return "ARRAY<" + elementType.sqlName() + ">" + (nullable ? "" : " NOT NULL");
    }

    @Override
    public PravahaType withNullable(boolean value) {
        return value == nullable ? this : new ArrayType(elementType, value);
    }
}
