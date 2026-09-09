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
 * A storage-agnostic column type.
 *
 * <p>Sealed so that the planner, the layout computation and the code generator can switch over the full set
 * exhaustively and the compiler will flag any case they forget. Plugins map these onto their native
 * store types (design section 8.2); nothing in the engine knows about Aerospike bins or Cassandra columns.
 *
 * <p>Implementations are immutable value types. Use {@link Types} to construct them.
 */
public sealed interface PravahaType
        permits PrimitiveType, DecimalType, TimestampType, StringType, BytesType, ArrayType, MapType, RowType {

    /** The physical type tag. */
    TypeName typeName();

    /** Whether a value of this type may be null. */
    boolean nullable();

    /** Encoded width in bytes, or {@link TypeName#VARIABLE}. */
    default int fixedWidth() {
        return typeName().fixedWidth();
    }

    /** Convenience for {@code fixedWidth() != TypeName.VARIABLE}. */
    default boolean isFixedWidth() {
        return typeName().isFixedWidth();
    }

    /** The SQL rendering of this type, e.g. {@code DECIMAL(18, 4) NOT NULL}. */
    String sqlName();

    /** This type with the given nullability. Returns {@code this} when already correct. */
    PravahaType withNullable(boolean nullable);
}
