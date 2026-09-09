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
