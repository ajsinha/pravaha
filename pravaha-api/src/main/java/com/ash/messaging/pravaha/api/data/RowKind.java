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
 * How a row changes the relation it belongs to.
 *
 * <p>Internally Pravaha represents change as a signed Z-set weight (design section 9.2); {@code RowKind}
 * is the presentation of that weight at a sink or client boundary, derived from the weight's sign
 * and the query's declared emit mode. It exists because sinks and humans think in inserts and
 * deletes, not in integers.
 */
public enum RowKind {
    /** A new row. Weight {@code +1}. */
    INSERT("+I"),
    /** Retraction of a previously emitted row. Weight {@code -1}, paired with {@link #UPDATE_AFTER}. */
    UPDATE_BEFORE("-U"),
    /** The replacement row. Weight {@code +1}, paired with {@link #UPDATE_BEFORE}. */
    UPDATE_AFTER("+U"),
    /** Removal: a source tombstone, or a window expiring. Weight {@code -1}. */
    DELETE("-D");

    private final String shorthand;

    RowKind(String shorthand) {
        this.shorthand = shorthand;
    }

    /** The two-character form used in plans, logs and the debugger UI, e.g. {@code "+I"}. */
    public String shorthand() {
        return shorthand;
    }

    /** {@code true} for kinds that add to a relation ({@link #INSERT}, {@link #UPDATE_AFTER}). */
    public boolean isAddition() {
        return this == INSERT || this == UPDATE_AFTER;
    }

    /** The Z-set weight this kind corresponds to: {@code +1} for additions, {@code -1} for removals. */
    public long weight() {
        return isAddition() ? 1L : -1L;
    }

    /**
     * The kind that represents the given Z-set weight in append/upsert presentation.
     *
     * @throws IllegalArgumentException if {@code weight} is zero, which has no representation --
     *     a zero-weight row has been consolidated away and must never reach a sink
     */
    public static RowKind ofWeight(long weight) {
        if (weight > 0) {
            return INSERT;
        }
        if (weight < 0) {
            return DELETE;
        }
        throw new IllegalArgumentException("weight 0 has no RowKind: consolidated rows must not be emitted");
    }
}
