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

import java.util.List;

/**
 * Factory for {@link PravahaType} values.
 *
 * <p>Every factory produces a NOT NULL type; call {@link PravahaType#withNullable(boolean)} to relax it.
 * Defaulting to not-nullable is deliberate: nullability should be a decision someone made, not one
 * they inherited by omission.
 */
public final class Types {

    private Types() {}

    public static PravahaType bool() {
        return new PrimitiveType(TypeName.BOOLEAN, false);
    }

    public static PravahaType int8() {
        return new PrimitiveType(TypeName.INT8, false);
    }

    public static PravahaType int16() {
        return new PrimitiveType(TypeName.INT16, false);
    }

    public static PravahaType int32() {
        return new PrimitiveType(TypeName.INT32, false);
    }

    public static PravahaType int64() {
        return new PrimitiveType(TypeName.INT64, false);
    }

    public static PravahaType float32() {
        return new PrimitiveType(TypeName.FLOAT32, false);
    }

    public static PravahaType float64() {
        return new PrimitiveType(TypeName.FLOAT64, false);
    }

    public static PravahaType date() {
        return new PrimitiveType(TypeName.DATE, false);
    }

    public static PravahaType time() {
        return new PrimitiveType(TypeName.TIME, false);
    }

    public static PravahaType decimal(int precision, int scale) {
        return new DecimalType(precision, scale, false);
    }

    public static PravahaType timestamp() {
        return new TimestampType(TimestampType.MAX_PRECISION, false);
    }

    public static PravahaType timestamp(int precision) {
        return new TimestampType(precision, false);
    }

    public static PravahaType string() {
        return new StringType(StringType.UNBOUNDED, false);
    }

    public static PravahaType string(int maxLength) {
        return new StringType(maxLength, false);
    }

    public static PravahaType bytes() {
        return new BytesType(BytesType.UNBOUNDED, false);
    }

    public static PravahaType bytes(int maxLength) {
        return new BytesType(maxLength, false);
    }

    public static PravahaType array(PravahaType element) {
        return new ArrayType(element, false);
    }

    public static PravahaType map(PravahaType key, PravahaType value) {
        return new MapType(key, value, false);
    }

    public static PravahaType row(List<Field> fields) {
        return new RowType(fields, false);
    }
}
