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
package com.ash.messaging.pravaha.sql.plan;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * The values bound to the {@code ?} placeholders in a prepared query (ADR-032).
 *
 * <p>Binding happens when the <em>physical</em> plan is built, not when the SQL is parsed. That
 * split is the whole point: parsing, validating and planning a statement is the expensive part and
 * happens once, while binding walks an already-planned tree and happens per call. It also means a
 * bound value can never be parsed as SQL, because by the time it exists there is no parser left to
 * reach -- which is a stronger guarantee than escaping, and a different one from "we escaped it
 * carefully".
 *
 * <p>There is deliberately no way to build an unbound physical plan. A placeholder that survived
 * into something executable would be a plan that fails at the first row, on a machine that is
 * already serving traffic; instead the failure is here, before anything runs.
 */
public final class BoundParameters {

    private static final BoundParameters NONE = new BoundParameters(new Object[0]);

    private final Object[] values;

    private BoundParameters(Object[] values) {
        this.values = values;
    }

    /** No parameters, for a statement that has none. */
    public static BoundParameters none() {
        return NONE;
    }

    /** Binds positionally, in the order the {@code ?} placeholders appear. */
    public static BoundParameters of(Object... values) {
        return new BoundParameters(values == null ? new Object[0] : values.clone());
    }

    public static BoundParameters of(List<Object> values) {
        return new BoundParameters(values == null ? new Object[0] : values.toArray());
    }

    public int size() {
        return values.length;
    }

    public boolean isEmpty() {
        return values.length == 0;
    }

    /**
     * The value at {@code index}, or a refusal naming what was missing.
     *
     * <p>An out-of-range placeholder is a refusal rather than a null, because a null here would be
     * indistinguishable from a caller who deliberately bound NULL -- and those two mean very
     * different things to the query.
     */
    public Object at(int index) {
        if (index < 0 || index >= values.length) {
            throw new PravahaException(
                    SqlErrors.PARAMETER_NOT_BOUND,
                    "this statement uses ?" + (index + 1) + " but only " + values.length
                            + " value" + (values.length == 1 ? " was" : "s were") + " bound. "
                            + "Placeholders are positional and every one must be given a value, "
                            + "including the ones bound to NULL");
        }
        return values[index];
    }

    /** Refuses a binding of the wrong arity before any of it is applied. */
    public void requireArity(int expected) {
        if (values.length != expected) {
            throw new PravahaException(
                    SqlErrors.PARAMETER_ARITY,
                    "this statement has " + expected + " placeholder" + (expected == 1 ? "" : "s")
                            + " and " + values.length + " value" + (values.length == 1 ? " was" : "s were")
                            + " bound");
        }
    }

    /**
     * Checks a bound value against the type the planner inferred for its placeholder.
     *
     * <p>Checked here rather than trusted, even though the transport carries types of its own: a
     * value that arrives as the wrong type is a caller's mistake, and finding it at bind time gives
     * them the placeholder number. Finding it at row time gives them a class cast inside an
     * operator.
     */
    public static void checkAssignable(int index, Object value, TypeName expected) {
        if (value == null) {
            return;
        }
        boolean ok =
                switch (expected) {
                    case INT8, INT16, INT32, INT64, DATE, TIME, TIMESTAMP_LTZ ->
                        value instanceof Byte
                                || value instanceof Short
                                || value instanceof Integer
                                || value instanceof Long;
                    case FLOAT32, FLOAT64 ->
                        value instanceof Float
                                || value instanceof Double
                                || value instanceof Integer
                                || value instanceof Long;
                    case STRING -> value instanceof CharSequence;
                    case BOOLEAN -> value instanceof Boolean;
                    case BYTES -> value instanceof byte[];
                    // DECPARAM-1: exact values only. A double is refused rather than read as the
                    // decimal nearest to it, which is a rounding nobody asked for.
                    case DECIMAL -> exactDecimal(value) != null;
                    default -> false;
                };
        if (!ok) {
            throw new PravahaException(
                    SqlErrors.PARAMETER_TYPE,
                    "?" + (index + 1) + " is used where the query needs " + expected + ", but a "
                            + value.getClass().getSimpleName() + " was bound");
        }
    }

    /**
     * {@code value} as the exact decimal it is -- a {@link BigDecimal}, or any integer -- or null when
     * it is not one (a double, text, anything else).
     */
    public static @Nullable BigDecimal exactDecimal(Object value) {
        return switch (value) {
            case BigDecimal exact -> exact;
            case BigInteger whole -> new BigDecimal(whole);
            case Long whole -> BigDecimal.valueOf(whole);
            case Integer whole -> BigDecimal.valueOf(whole);
            case Short whole -> BigDecimal.valueOf(whole);
            case Byte whole -> BigDecimal.valueOf(whole);
            case null, default -> null;
        };
    }

    @Override
    public String toString() {
        // The values are the caller's data and may be somebody's identifier; the count is what is
        // useful in a log line and the values are what should not be in one.
        return "BoundParameters[" + values.length + " bound]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BoundParameters p && Arrays.equals(values, p.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
