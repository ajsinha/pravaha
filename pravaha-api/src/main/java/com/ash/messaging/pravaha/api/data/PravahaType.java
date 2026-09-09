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
