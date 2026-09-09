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

import java.util.EnumSet;
import java.util.Set;

/** A fixed-width scalar with no parameters: booleans, integers, floats, {@code DATE}, {@code TIME}. */
public record PrimitiveType(TypeName typeName, boolean nullable) implements PravahaType {

    private static final Set<TypeName> SUPPORTED = EnumSet.of(
            TypeName.BOOLEAN,
            TypeName.INT8,
            TypeName.INT16,
            TypeName.INT32,
            TypeName.INT64,
            TypeName.FLOAT32,
            TypeName.FLOAT64,
            TypeName.DATE,
            TypeName.TIME);

    public PrimitiveType {
        if (!SUPPORTED.contains(typeName)) {
            throw new IllegalArgumentException(
                    typeName + " is not a primitive type; use the parameterised type for it");
        }
    }

    @Override
    public String sqlName() {
        return typeName().name() + (nullable ? "" : " NOT NULL");
    }

    @Override
    public PravahaType withNullable(boolean value) {
        return value == nullable ? this : new PrimitiveType(typeName, value);
    }
}
