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
 * join needs a bound, and {@code maxRowsPerSide} is where the bound is enforced until windowed and
 * time-versioned joins land.
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
        long maxRowsPerSide)
        implements PhysicalOperator {

    public JoinOperator {
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
        return "Join[" + condition + "]";
    }

    /** How many columns come from the left, and so where the right's begin in the output. */
    public int leftWidth() {
        return left.outputSchema().fields().size();
    }
}
