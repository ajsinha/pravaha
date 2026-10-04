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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.jspecify.annotations.Nullable;

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

    private final @Nullable VectorSchemaRoot root;
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
        this.detached = null;
        this.detachedWeight = 1L;
    }

    /**
     * A row copied out of its batch, valid for as long as it is held.
     *
     * <p>For a subscription's snapshot, which may span several Arrow batches and is handed over as
     * one: the batches before the last are gone by the time it is delivered, so their rows cannot
     * be cursors over them.
     */
    private Row(List<String> columns, @Nullable Object[] values, long weight) {
        this.root = null;
        this.columns = columns;
        this.weightOrdinal = -1;
        this.detached = values;
        this.detachedWeight = weight;
    }

    private final @Nullable Object @Nullable [] detached;
    private final long detachedWeight;

    Row at(int rowIndex) {
        this.index = rowIndex;
        return this;
    }

    /** A copy of this row, values and weight, that outlives its batch. */
    Row detach() {
        return new Row(columns, toArray(), weight());
    }

    /** Read only once {@link #isNull} has said the value is there. */
    private Object raw(int ordinal) {
        Object value = detached != null
                ? detached[ordinal]
                : batch().getVector(ordinal).getObject(index);
        if (value == null) {
            throw new IllegalStateException("column '" + columns.get(ordinal) + "' was read as present and is null");
        }
        return value;
    }

    /** The batch this row is a cursor over. Only a detached row has none, and it never asks. */
    private VectorSchemaRoot batch() {
        VectorSchemaRoot current = root;
        if (current == null) {
            throw new IllegalStateException("a detached row has no batch to read");
        }
        return current;
    }

    /** The column names, in order. */
    public List<String> columns() {
        return columns;
    }

    public boolean isNull(int ordinal) {
        return detached != null
                ? detached[ordinal] == null
                : batch().getVector(ordinal).isNull(index);
    }

    public boolean isNull(String column) {
        return isNull(ordinalOf(column));
    }

    /** A column as text. Null stays null rather than becoming "null" or "". */
    public @Nullable String getString(int ordinal) {
        if (isNull(ordinal)) {
            return null;
        }
        Object value = raw(ordinal);
        if (value instanceof org.apache.arrow.vector.util.Text text) {
            return text.toString();
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        // Plain, never exponent: BigDecimal.toString writes a DECIMAL(18, 8) zero as "0E-8".
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    /** {@link #getString(int)} by name; null when the column is. */
    public @Nullable String getString(String column) {
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
        return ((Number) raw(ordinal)).longValue();
    }

    public long getLong(String column) {
        return getLong(ordinalOf(column));
    }

    public double getDouble(int ordinal) {
        if (isNull(ordinal)) {
            throw new PravahaClientException(
                    ClientErrors.READ_FAILED, "column '" + columns.get(ordinal) + "' is null", false);
        }
        return ((Number) raw(ordinal)).doubleValue();
    }

    public double getDouble(String column) {
        return getDouble(ordinalOf(column));
    }

    /**
     * A {@code DECIMAL} column exactly, at the column's scale, or null (FLIGHTDECIMAL-1).
     *
     * <p>The server sends a decimal as Arrow's Decimal128 with the column's precision and scale, so
     * this is the value the engine holds, digit for digit: {@code 2.50} in a {@code DECIMAL(18, 2)}
     * arrives as {@code 2.50}, not {@code 2.5}. {@link #get(int)} returns the same object. Reading
     * it with {@link #getDouble(int)} is allowed and is the rounding the caller chose.
     *
     * @throws PravahaClientException if the column is not a decimal or a whole number
     */
    public @Nullable BigDecimal getBigDecimal(int ordinal) {
        if (isNull(ordinal)) {
            return null;
        }
        Object value = raw(ordinal);
        return switch (value) {
            case BigDecimal decimal -> decimal;
            case Long whole -> BigDecimal.valueOf(whole);
            case Integer whole -> BigDecimal.valueOf(whole);
            case Short whole -> BigDecimal.valueOf(whole);
            case Byte whole -> BigDecimal.valueOf(whole);
            default ->
                throw new PravahaClientException(
                        ClientErrors.READ_FAILED,
                        "column '" + columns.get(ordinal) + "' is "
                                + value.getClass().getSimpleName()
                                + ", not an exact number; read it with get() or getDouble()",
                        false);
        };
    }

    /** {@link #getBigDecimal(int)} by name; null when the column is. */
    public @Nullable BigDecimal getBigDecimal(String column) {
        return getBigDecimal(ordinalOf(column));
    }

    /** A column as whatever it is, or null. */
    public @Nullable Object get(int ordinal) {
        if (isNull(ordinal)) {
            return null;
        }
        Object value = raw(ordinal);
        return value instanceof org.apache.arrow.vector.util.Text text ? text.toString() : value;
    }

    /** {@link #get(int)} by name; null when the column is. */
    public @Nullable Object get(String column) {
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
     * <p>The weights are the view's changelog. For a keyed view that upserts -- a second row under a
     * key replacing the first -- the replaced row gets no {@code -1} (KEYEDWT-1), so summed they
     * count that key twice. Subscribe to a query registered over the view to receive its answer's
     * changes instead; those sum to exactly what a reader sees.
     *
     * <p>An ordinary query answer has no weights: every row in it is a row that is present, so this
     * reports {@code 1} there rather than failing. Only a subscription carries real ones.
     */
    public long weight() {
        if (detached != null) {
            return detachedWeight;
        }
        if (weightOrdinal < 0) {
            return 1L;
        }
        return ((Number) batch().getVector(weightOrdinal).getObject(index)).longValue();
    }

    /** Whether this withdraws a row rather than adding one. */
    public boolean isRetraction() {
        return weight() < 0;
    }

    /** A copy that outlives the iteration. */
    public @Nullable Object[] toArray() {
        @Nullable Object[] values = new Object[columns.size()];
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
