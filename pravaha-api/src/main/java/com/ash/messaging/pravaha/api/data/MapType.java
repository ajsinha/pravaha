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

/** Key-value collection. Keys are never null, which is why {@code keyType} is forced not-nullable. */
public record MapType(PravahaType keyType, PravahaType valueType, boolean nullable) implements PravahaType {

    public MapType {
        Objects.requireNonNull(keyType, "keyType");
        Objects.requireNonNull(valueType, "valueType");
        if (keyType.nullable()) {
            keyType = keyType.withNullable(false);
        }
    }

    @Override
    public TypeName typeName() {
        return TypeName.MAP;
    }

    @Override
    public String sqlName() {
        return "MAP<" + keyType.sqlName() + ", " + valueType.sqlName() + ">" + (nullable ? "" : " NOT NULL");
    }

    @Override
    public PravahaType withNullable(boolean value) {
        return value == nullable ? this : new MapType(keyType, valueType, value);
    }
}
