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
package com.ash.messaging.pravaha.algebra;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A multiset with signed integer weights: the single representation for relations and for changes.
 *
 * <p>A conventional relation is a Z-set whose weights are all {@code +1}. A changelog is a Z-set
 * with mixed signs. <strong>They are the same object</strong>, which is why insert, update and delete
 * stop being three cases an operator author must reason about separately and become arithmetic
 * (design section 9.2).
 *
 * <p>An update is not a special kind of record here; it is {@code -1} of the old row and {@code +1}
 * of the new. Flink and ksqlDB model that as a retract *stream*, which every downstream operator
 * must be hand-written to interpret correctly, and which is historically where streaming SQL engines
 * have their subtlest bugs. Making it addition removes the category.
 *
 * <p><strong>Zero weight means absent.</strong> The invariant is maintained on every operation, not
 * checked afterwards: a row whose weight reaches zero is removed rather than stored as a zero. That
 * is what makes {@link #isEmpty()} meaningful and what lets the runtime prune a no-op delta before
 * it propagates -- on a chain of dependent views most changes die within a hop or two, and that
 * pruning is where the efficiency actually comes from.
 *
 * <p>Immutable. Operations return new instances. The runtime uses a mutable arena-backed form on the
 * hot path (Wave 3); this is the reference implementation the oracle checks that form against.
 *
 * @param <T> the row type. Must have value-based {@code equals} and {@code hashCode} -- row identity
 *     is what weights are keyed on, so a reference-equality type would make every row distinct.
 */
public final class ZSet<T> {

    private static final ZSet<?> EMPTY = new ZSet<>(Map.of());

    private final Map<T, Long> weights;

    private ZSet(Map<T, Long> weights) {
        this.weights = weights;
    }

    @SuppressWarnings("unchecked")
    public static <T> ZSet<T> empty() {
        return (ZSet<T>) EMPTY;
    }

    /** A Z-set of one row with the given weight. Weight zero yields the empty set. */
    public static <T> ZSet<T> of(T row, long weight) {
        Objects.requireNonNull(row, "row");
        return weight == 0 ? empty() : new ZSet<>(Map.of(row, weight));
    }

    /** A conventional relation: every row at weight {@code +1}. */
    @SafeVarargs
    public static <T> ZSet<T> ofRows(T... rows) {
        Builder<T> b = builder();
        for (T row : rows) {
            b.add(row, 1);
        }
        return b.build();
    }

    public static <T> ZSet<T> of(Map<T, Long> weights) {
        Builder<T> b = builder();
        weights.forEach(b::add);
        return b.build();
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /** The weight of a row; zero when absent. */
    public long weightOf(T row) {
        return weights.getOrDefault(row, 0L);
    }

    public boolean contains(T row) {
        return weights.containsKey(row);
    }

    /** Rows with a non-zero weight. */
    public Set<T> support() {
        return Collections.unmodifiableSet(weights.keySet());
    }

    /** Row-to-weight entries. Never contains a zero weight. */
    public Map<T, Long> entries() {
        return Collections.unmodifiableMap(weights);
    }

    /** Distinct rows present. Not the same as {@link #cardinality()}. */
    public int size() {
        return weights.size();
    }

    public boolean isEmpty() {
        return weights.isEmpty();
    }

    /** Sum of all weights. Can be negative: a pure retraction has negative cardinality. */
    public long cardinality() {
        long total = 0;
        for (long w : weights.values()) {
            total += w;
        }
        return total;
    }

    // ------------------------------------------------------------------ the group operations

    /**
     * Pointwise addition -- the group operation, and the only way changes are ever combined.
     *
     * <p>Applying a delta to a relation, merging two deltas, and unioning two relations are all this
     * one operation. Rows whose weights cancel disappear.
     */
    public ZSet<T> plus(ZSet<T> other) {
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        Builder<T> b = new Builder<>(weights);
        other.weights.forEach(b::add);
        return b.build();
    }

    /** Negation. {@code z.plus(z.negate())} is always empty -- the group inverse. */
    public ZSet<T> negate() {
        if (isEmpty()) {
            return this;
        }
        Map<T, Long> negated = new LinkedHashMap<>(weights.size() * 2);
        weights.forEach((row, w) -> negated.put(row, -w));
        return new ZSet<>(negated);
    }

    /** {@code this - other}, which is {@code this.plus(other.negate())}. */
    public ZSet<T> minus(ZSet<T> other) {
        return plus(other.negate());
    }

    /** Multiplies every weight. Zero yields the empty set, preserving the invariant. */
    public ZSet<T> scale(long factor) {
        if (factor == 0 || isEmpty()) {
            return empty();
        }
        if (factor == 1) {
            return this;
        }
        Builder<T> b = builder();
        weights.forEach((row, w) -> b.add(row, w * factor));
        return b.build();
    }

    // ------------------------------------------------------------------ linear operators

    /**
     * Selection. Linear: {@code filter(a + b) == filter(a) + filter(b)}.
     *
     * <p>Linearity is exactly why a filter needs no state to run incrementally -- applying it to the
     * delta gives the delta of the result, with nothing remembered (design section 9.3).
     */
    public ZSet<T> filter(Predicate<? super T> predicate) {
        Builder<T> b = builder();
        weights.forEach((row, w) -> {
            if (predicate.test(row)) {
                b.add(row, w);
            }
        });
        return b.build();
    }

    /**
     * Projection. Also linear.
     *
     * <p>Weights of rows that map to the same output are added, which is what makes projecting away
     * a key column behave the way SQL expects rather than losing duplicates.
     */
    public <R> ZSet<R> map(Function<? super T, ? extends R> mapper) {
        Builder<R> b = builder();
        weights.forEach((row, w) -> b.add(mapper.apply(row), w));
        return b.build();
    }

    /**
     * Flattens each row to zero or more outputs. Linear.
     *
     * <p>Covers {@code UNNEST} and the expand half of a join.
     */
    public <R> ZSet<R> flatMap(Function<? super T, ? extends Iterable<? extends R>> mapper) {
        Builder<R> b = builder();
        weights.forEach((row, w) -> mapper.apply(row).forEach(out -> b.add(out, w)));
        return b.build();
    }

    /**
     * Collapses weights to {@code +1} for present rows, dropping negatives.
     *
     * <p>This is {@code SELECT DISTINCT}, and it is the first operator here that is <em>not</em>
     * linear: whether a row is present depends on its accumulated weight, so the incremental form
     * needs the running total. That is why design section 9.3 lists it separately, and why it needs state
     * where a filter does not.
     */
    public ZSet<T> distinct() {
        Builder<T> b = builder();
        weights.forEach((row, w) -> {
            if (w > 0) {
                b.add(row, 1);
            }
        });
        return b.build();
    }

    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        return o instanceof ZSet<?> other && weights.equals(other.weights);
    }

    @Override
    public int hashCode() {
        return weights.hashCode();
    }

    @Override
    public String toString() {
        if (weights.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        weights.forEach((row, w) -> sb.append(sb.length() > 1 ? ", " : "")
                .append(row)
                .append(": ")
                .append(w > 0 ? "+" : "")
                .append(w));
        return sb.append('}').toString();
    }

    /**
     * Accumulates rows, maintaining the zero-means-absent invariant as it goes.
     *
     * <p>Consolidating on insert rather than afterwards is what lets a {@code +1}/{@code -1} pair
     * cancel inside an operator and never reach the next one.
     */
    public static final class Builder<T> {
        private final Map<T, Long> weights;

        Builder() {
            this.weights = new LinkedHashMap<>();
        }

        Builder(Map<T, Long> initial) {
            this.weights = new LinkedHashMap<>(initial);
        }

        public Builder<T> add(T row, long weight) {
            Objects.requireNonNull(row, "row");
            if (weight == 0) {
                return this;
            }
            weights.merge(row, weight, (a, b) -> {
                long sum = a + b;
                return sum == 0 ? null : sum;
            });
            return this;
        }

        public ZSet<T> build() {
            return weights.isEmpty() ? empty() : new ZSet<>(new LinkedHashMap<>(weights));
        }
    }
}
