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
package com.ash.messaging.pravaha.sdk.flight;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.arrow.vector.VectorSchemaRoot;

import com.ash.messaging.pravaha.sdk.ClientErrors;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

/**
 * One row of an answer.
 *
 * <p>A cursor over the current Arrow batch rather than a copy of it: iterating a million rows should
 * not allocate a million objects. That means a row is <strong>only valid until the iteration
 * advances</strong> -- the same rule the engine follows internally, and for the same reason. A caller
 * that needs to keep one should read the values out, which is what {@link #toArray()} is for.
 */
public final class Row {

    private final VectorSchemaRoot root;
    private final List<String> columns;
    private final int weightOrdinal;
    private int index;

    Row(VectorSchemaRoot root, List<String> columns) {
        this(root, columns, -1);
    }

    Row(VectorSchemaRoot root, List<String> columns, int weightOrdinal) {
        this.root = root;
        this.columns = columns;
        this.weightOrdinal = weightOrdinal;
    }

    Row at(int rowIndex) {
        this.index = rowIndex;
        return this;
    }

    /** The column names, in order. */
    public List<String> columns() {
        return columns;
    }

    public boolean isNull(int ordinal) {
        return root.getVector(ordinal).isNull(index);
    }

    public boolean isNull(String column) {
        return isNull(ordinalOf(column));
    }

    /** A column as text. Null stays null rather than becoming "null" or "". */
    public String getString(int ordinal) {
        if (isNull(ordinal)) {
            return null;
        }
        Object value = root.getVector(ordinal).getObject(index);
        if (value instanceof org.apache.arrow.vector.util.Text text) {
            return text.toString();
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    public String getString(String column) {
        return getString(ordinalOf(column));
    }

    /**
     * A column as a {@code long}.
     *
     * @throws PravahaClientException if the column is null -- a caller reading a primitive has to
     *     say what null means, and returning zero would decide for them
     */
    public long getLong(int ordinal) {
        if (isNull(ordinal)) {
            throw new PravahaClientException(
                    ClientErrors.READ_FAILED,
                    "column '" + columns.get(ordinal)
                            + "' is null; check isNull first, or read it with get() as an object",
                    false);
        }
        return ((Number) root.getVector(ordinal).getObject(index)).longValue();
    }

    public long getLong(String column) {
        return getLong(ordinalOf(column));
    }

    public double getDouble(int ordinal) {
        if (isNull(ordinal)) {
            throw new PravahaClientException(
                    ClientErrors.READ_FAILED, "column '" + columns.get(ordinal) + "' is null", false);
        }
        return ((Number) root.getVector(ordinal).getObject(index)).doubleValue();
    }

    public double getDouble(String column) {
        return getDouble(ordinalOf(column));
    }

    /** A column as whatever it is, or null. */
    public Object get(int ordinal) {
        if (isNull(ordinal)) {
            return null;
        }
        Object value = root.getVector(ordinal).getObject(index);
        return value instanceof org.apache.arrow.vector.util.Text text ? text.toString() : value;
    }

    public Object get(String column) {
        return get(ordinalOf(column));
    }

    /**
     * How this row changes the view: {@code +1} a row appearing, {@code -1} a row being withdrawn,
     * larger magnitudes several of either.
     *
     * <p>A correction arrives as a retraction of the old row followed by an insert of the new one,
     * so a consumer keeping its own running total must add the weight rather than count rows -- the
     * retraction is what cancels the value it is correcting. A consumer that only wants the current
     * state can overwrite by key and skip negatives.
     *
     * <p>An ordinary query answer has no weights: every row in it is a row that is present, so this
     * reports {@code 1} there rather than failing. Only a subscription carries real ones.
     */
    public long weight() {
        if (weightOrdinal < 0) {
            return 1L;
        }
        return ((Number) root.getVector(weightOrdinal).getObject(index)).longValue();
    }

    /** Whether this withdraws a row rather than adding one. */
    public boolean isRetraction() {
        return weight() < 0;
    }

    /** A copy that outlives the iteration. */
    public Object[] toArray() {
        Object[] values = new Object[columns.size()];
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            values[ordinal] = get(ordinal);
        }
        return values;
    }

    private int ordinalOf(String column) {
        int ordinal = columns.indexOf(column);
        if (ordinal < 0) {
            throw new PravahaClientException(
                    ClientErrors.READ_FAILED, "no column '" + column + "' in this result; it has " + columns, false);
        }
        return ordinal;
    }

    @Override
    public String toString() {
        return java.util.Arrays.toString(toArray());
    }
}
