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

import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Turns a batch query into its incremental form, by rule.
 *
 * <p>This is the mechanism the whole design rests on. Flink and ksqlDB require each operator author
 * to reason about retractions independently, which is historically where streaming SQL engines have
 * their subtlest bugs -- outer joins and {@code DISTINCT} over updating inputs especially. Here the
 * incremental form is *derived*, so correctness composes instead of being re-established per
 * operator (design section 9.1).
 *
 * <p>Wave 2 covers the linear operators. Aggregates arrive in Wave 4 and bilinear joins in Wave 5;
 * the phasing is deliberate, so the oracle exists and is proven before the hard forms are written.
 */
public final class Lift {

    private Lift() {}

    /**
     * Lifts a linear query.
     *
     * <p>A query is linear when {@code Q(a + b) == Q(a) + Q(b)}. For those the incremental form is
     * simply the query applied to the delta -- <strong>no state at all</strong>. Filter, project,
     * flat-map and {@code UNION ALL} are all linear, and together they cover a large fraction of
     * real queries, which is why this rule is worth having on its own.
     */
    public static <I, O> IncrementalQuery<I, O> linear(Query<I, O> query) {
        return (delta, state) -> query.evaluate(delta);
    }

    /** {@code WHERE}. Linear. */
    public static <T> Query<T, T> filter(Predicate<? super T> predicate) {
        return input -> input.filter(predicate);
    }

    /** {@code SELECT} projection. Linear. */
    public static <I, O> Query<I, O> project(Function<? super I, ? extends O> mapper) {
        return input -> input.map(mapper);
    }

    /** {@code UNNEST}, and the expand half of a join. Linear. */
    public static <I, O> Query<I, O> flatMap(Function<? super I, ? extends Iterable<? extends O>> mapper) {
        return input -> input.flatMap(mapper);
    }

    /** Composition. The composite of two linear queries is linear. */
    public static <A, B, C> Query<A, C> compose(Query<A, B> first, Query<B, C> second) {
        return input -> second.evaluate(first.evaluate(input));
    }

    /**
     * {@code SELECT DISTINCT}. <strong>Not</strong> linear.
     *
     * <p>Whether a row is present depends on its accumulated weight, so the incremental form must
     * consult the state: it computes presence before and after the delta and emits only the
     * difference. This is the shape every non-linear operator takes, and the reason {@code DISTINCT}
     * needs state where a filter does not.
     */
    public static <T> Query<T, T> distinct() {
        return ZSet::distinct;
    }

    /**
     * The incremental form of {@link #distinct()}.
     *
     * <p>Emits only rows whose presence actually changed. A row going from weight 3 to weight 4 is
     * still present, so nothing propagates -- which is the zero-delta pruning that makes a chain of
     * dependent views cheap (design section 9.4).
     */
    public static <T> IncrementalQuery<T, T> distinctIncremental() {
        return (delta, state) -> {
            ZSet<T> before = state.distinct();
            ZSet<T> after = state.plus(delta).distinct();
            return after.minus(before);
        };
    }

    /**
     * The general fallback: recompute before and after, and emit the difference.
     *
     * <p>Always correct for any query, and always O(state) rather than O(delta), so it defeats the
     * point. It exists as the escape hatch design section 9.8 promises -- a construct whose incremental
     * form proves impractical can use this and lose that operator's efficiency without losing the
     * query's correctness -- and as the reference the oracle compares real lifts against.
     */
    public static <I, O> IncrementalQuery<I, O> byRecomputation(Query<I, O> query) {
        return (delta, state) -> query.evaluate(state.plus(delta)).minus(query.evaluate(state));
    }

    /**
     * Combines two Z-sets pointwise on a shared key, for aggregate accumulation.
     *
     * <p>Provided now because the aggregate lift in Wave 4 needs it and its behaviour is worth
     * pinning while the algebra is small.
     */
    public static <T> ZSet<T> combine(ZSet<T> left, ZSet<T> right, BinaryOperator<Long> weightMerge) {
        ZSet.Builder<T> b = ZSet.builder();
        left.entries().forEach(b::add);
        right.entries().forEach((row, w) -> b.add(row, weightMerge.apply(0L, w)));
        return b.build();
    }
}
