/*
 * Copyright the Pravaha authors.
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
