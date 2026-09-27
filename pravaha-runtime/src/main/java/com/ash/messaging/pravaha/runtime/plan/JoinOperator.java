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
package com.ash.messaging.pravaha.runtime.plan;

import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * An equi-join between two streams, evaluated incrementally.
 *
 * <p>The bilinear rule of design section 9.3: {@code Δ(A⋈B) = ΔA⋈I(B) + I(A)⋈ΔB + ΔA⋈ΔB}. Both
 * sides are streams, both keep state, and an update on either side is just a retraction and an
 * insert -- there is no update path in the operator because the algebra does not need one.
 *
 * <p><strong>The state is the whole design problem.</strong> Each side holds every row that could
 * still match, so an unbounded join over two unbounded streams grows until the node dies. Pravaha
 * refuses that at planning time rather than accepting it and failing at three in the morning: a
 * <p><strong>A join is bounded in time, and that bound is part of what it means.</strong> Two
 * streams joined on a key would, without one, have to hold every unmatched row for as long as the
 * process lives: a partner could arrive at any moment, so nothing is ever safe to forget. With a
 * match window of {@code T}, the join means "rows that match and whose event times are within
 * {@code T} of each other", and a row older than the watermark minus {@code T} can no longer be part
 * of any match it promises -- so releasing it is not losing data, it is the definition being
 * honoured.
 *
 * <p>That distinction is why eviction here is safe and a size-based eviction would not be. Dropping
 * the oldest rows to stay under a ceiling would silently lose matches the query <em>did</em> ask
 * for; {@code maxRowsPerSide} therefore fails loudly instead, and exists only as a backstop for a
 * key space that is wrong rather than merely large.
 *
 * <p>Output columns are the left's followed by the right's, which is what SQL says and what makes
 * the ordinal arithmetic downstream trivial: a right-side column {@code i} is output column
 * {@code leftWidth + i}.
 *
 * @param leftKeys ordinals in the left input, positionally paired with {@code rightKeys}
 * @param rightKeys ordinals in the right input
 * @param maxRowsPerSide the ceiling on rows held per side. Reached means the query is unbounded in
 *     practice, whatever it looked like on paper, and it is refused with the count rather than
 *     allowed to consume the heap.
 */
public record JoinOperator(
        PhysicalOperator left,
        PhysicalOperator right,
        List<Integer> leftKeys,
        List<Integer> rightKeys,
        StreamSchema outputSchema,
        long maxRowsPerSide,
        long matchWithinNanos,
        long matchLowerNanos,
        long matchUpperNanos,
        boolean leftOuter)
        implements PhysicalOperator {

    /**
     * The match window a join gets when the query does not state one.
     *
     * <p>An hour of event time. Chosen to be generous enough that ordinary correlated streams match,
     * and short enough that state is released while the process is still young. The number is
     * arguable; having one is not, because the alternative is a join that holds every unmatched row
     * for as long as the process lives.
     */
    public static final long DEFAULT_MATCH_WITHIN_NANOS = 3_600L * 1_000_000_000L;

    /** A join with the default match window, which is what SQL without a temporal predicate gets. */
    public JoinOperator(
            PhysicalOperator left,
            PhysicalOperator right,
            List<Integer> leftKeys,
            List<Integer> rightKeys,
            StreamSchema outputSchema,
            long maxRowsPerSide) {
        this(left, right, leftKeys, rightKeys, outputSchema, maxRowsPerSide, DEFAULT_MATCH_WITHIN_NANOS);
    }

    /** A join with a symmetric window and no stated direction: the default shape. */
    public JoinOperator(
            PhysicalOperator left,
            PhysicalOperator right,
            List<Integer> leftKeys,
            List<Integer> rightKeys,
            StreamSchema outputSchema,
            long maxRowsPerSide,
            long matchWithinNanos) {
        this(
                left,
                right,
                leftKeys,
                rightKeys,
                outputSchema,
                maxRowsPerSide,
                matchWithinNanos,
                -matchWithinNanos,
                matchWithinNanos,
                false);
    }

    /**
     * Builds a join from a temporal predicate the query stated.
     *
     * <p>The bounds are on {@code left.time - right.time}, in the order the predicate was written.
     * {@code l.t BETWEEN r.t - INTERVAL '5' MINUTE AND r.t} is {@code [-5 minutes, 0]}: a left row
     * may be up to five minutes older than its match and never newer.
     *
     * <p>Direction is kept rather than collapsed to a width because it is part of the answer. "The
     * payment came after the order" and "the two were within five minutes" are different questions,
     * and a join that treats them alike answers the wrong one silently.
     */
    public static JoinOperator withinRange(
            PhysicalOperator left,
            PhysicalOperator right,
            List<Integer> leftKeys,
            List<Integer> rightKeys,
            StreamSchema outputSchema,
            long maxRowsPerSide,
            long lowerNanos,
            long upperNanos) {
        if (upperNanos < lowerNanos) {
            throw new IllegalArgumentException("a join's time bounds are inverted: lower " + lowerNanos
                    + "ns is above upper " + upperNanos + "ns, so no pair of rows can satisfy them and the "
                    + "join can only ever return nothing");
        }
        // State has to be kept for the longer of the two directions, whichever way the window leans.
        long span = Math.max(Math.abs(lowerNanos), Math.abs(upperNanos));
        return new JoinOperator(
                left,
                right,
                leftKeys,
                rightKeys,
                outputSchema,
                maxRowsPerSide,
                span == 0 ? 1 : span,
                lowerNanos,
                upperNanos,
                false);
    }

    /**
     * The same join, emitting a null-padded row for every left row that never found a match.
     *
     * <p>Only available with a stated time bound, and that is the whole reason outer joins were
     * refused before there was one. An outer join has to decide when to give up on a left row, and
     * "never" is the only honest answer without a window: the row is held for the life of the
     * process in case a match arrives. With a window, the moment is exact -- when the watermark
     * passes the point where a match could still arrive, the row has definitively not matched, and
     * the null-padded row is emitted then and never retracted.
     */
    public JoinOperator asLeftOuter() {
        return new JoinOperator(
                left,
                right,
                leftKeys,
                rightKeys,
                outputSchema,
                maxRowsPerSide,
                matchWithinNanos,
                matchLowerNanos,
                matchUpperNanos,
                true);
    }

    /** True if a pair whose event times differ by {@code deltaNanos} is inside the stated window. */
    public boolean matchesInTime(long leftNanos, long rightNanos) {
        long delta = leftNanos - rightNanos;
        return delta >= matchLowerNanos && delta <= matchUpperNanos;
    }

    public JoinOperator {
        if (matchWithinNanos <= 0) {
            throw new IllegalArgumentException("a join's match window must be positive, got " + matchWithinNanos
                    + ". A join with no time bound holds every unmatched row forever");
        }
        leftKeys = List.copyOf(leftKeys);
        rightKeys = List.copyOf(rightKeys);
        if (leftKeys.isEmpty()) {
            // A join with no equality is a cross product: every row of one side against every row of
            // the other, forever, on two unbounded streams. There is no bound that makes it
            // survivable, so it is not a ceiling question -- it is refused outright.
            throw new IllegalArgumentException(
                    "a stream-to-stream join needs at least one equality condition; a cross product between "
                            + "two streams has no bounded execution");
        }
        if (leftKeys.size() != rightKeys.size()) {
            throw new IllegalArgumentException("join keys must pair up: " + leftKeys.size() + " on the left, "
                    + rightKeys.size() + " on the right");
        }
        if (maxRowsPerSide < 1) {
            throw new IllegalArgumentException("the per-side row ceiling must be at least 1, got " + maxRowsPerSide);
        }
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of(left, right);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    public String label() {
        StringBuilder condition = new StringBuilder();
        for (int i = 0; i < leftKeys.size(); i++) {
            if (i > 0) {
                condition.append(" AND ");
            }
            condition
                    .append(left.outputSchema().field(leftKeys.get(i)).name())
                    .append(" = ")
                    .append(right.outputSchema().field(rightKeys.get(i)).name());
        }
        return (leftOuter ? "LeftJoin[" : "Join[") + condition + ", " + window() + "]";
    }

    @Override
    public String identity() {
        return (leftOuter ? "LeftJoin" : "Join") + "(keys=" + leftKeys + "=" + rightKeys
                + ", left-right in [" + matchLowerNanos + "," + matchUpperNanos + "]ns"
                + ", within=" + matchWithinNanos + "ns)";
    }

    /** The time bound as a reader wants it: what the left row's time minus the right row's may be. */
    private String window() {
        return "left - right in [" + duration(matchLowerNanos) + ", " + duration(matchUpperNanos) + "]";
    }

    private static String duration(long nanos) {
        if (nanos % 1_000_000_000L == 0) {
            return (nanos / 1_000_000_000L) + "s";
        }
        if (nanos % 1_000_000L == 0) {
            return (nanos / 1_000_000L) + "ms";
        }
        return nanos + "ns";
    }

    /** How many columns come from the left, and so where the right's begin in the output. */
    public int leftWidth() {
        return left.outputSchema().fields().size();
    }
}
