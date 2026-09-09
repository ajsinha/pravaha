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
