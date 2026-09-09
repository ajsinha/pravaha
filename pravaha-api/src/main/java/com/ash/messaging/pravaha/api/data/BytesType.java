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
