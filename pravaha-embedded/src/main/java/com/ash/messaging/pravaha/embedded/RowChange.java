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
package com.ash.messaging.pravaha.embedded;

import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.serving.ViewChange;

/**
 * One committed change to a view, readable by column name.
 *
 * <p>A {@link ViewChange} is the engine's own form -- values by position and a weight -- and this is
 * that change with the view's schema beside it, so an application never has to count columns. The
 * weight is the part not to lose: {@code +1} adds a row, {@code -1} withdraws one, and an update
 * arrives as a withdrawal of the old row followed by the new one, in that order, in one commit.
 * {@link #isRetraction()} is how a listener tells them apart.
 */
public final class RowChange {

    private final StreamSchema schema;
    private final ViewChange change;
    private @Nullable Map<String, Object> values;

    public RowChange(StreamSchema schema, ViewChange change) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.change = Objects.requireNonNull(change, "change");
    }

    /** The row, column name to value, in the view's column order. */
    public Map<String, Object> values() {
        Map<String, Object> current = values;
        if (current == null) {
            current = RowMapping.toMap(schema, change.values());
            values = current;
        }
        return current;
    }

    /** One column's value, or null when it is null. */
    public Object get(String column) {
        if (!schema.hasField(column)) {
            throw new IllegalArgumentException(
                    "'" + schema.name() + "' has no column '" + column + "'; its columns are " + values().keySet());
        }
        return change.values()[schema.indexOf(column)];
    }

    /** The row as a record, matched by column name; see {@link RowMapping}. */
    public <R> R as(Class<R> type) {
        return RowMapping.toRecord(type, schema, change.values());
    }

    /** {@code +1} for a row added, {@code -1} for one withdrawn. */
    public long weight() {
        return change.weight();
    }

    /** Whether this withdraws a row the view held -- a delete, or the first half of an update. */
    public boolean isRetraction() {
        return change.isRetraction();
    }

    /** The view's schema. */
    public StreamSchema schema() {
        return schema;
    }

    /** The engine's own form of this change. */
    public ViewChange change() {
        return change;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RowChange that && change.equals(that.change);
    }

    @Override
    public int hashCode() {
        return change.hashCode();
    }

    @Override
    public String toString() {
        return (isRetraction() ? "-" : "+") + values();
    }
}
