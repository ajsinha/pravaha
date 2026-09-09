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

/**
 * A named, ordinal-addressed column.
 *
 * <p>The ordinal is what generated code actually uses (design section 12.3). Names exist for humans and
 * for SQL; nothing on the hot path ever looks a field up by name.
 */
public record Field(String name, PravahaType type, int ordinal) {

    public Field {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("field name must not be blank");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative, got " + ordinal);
        }
    }

    /** Convenience for a field whose ordinal is assigned later by {@link StreamSchema}. */
    public static Field of(String name, PravahaType type) {
        return new Field(name, type, 0);
    }

    Field withOrdinal(int value) {
        return value == ordinal ? this : new Field(name, type, value);
    }
}
