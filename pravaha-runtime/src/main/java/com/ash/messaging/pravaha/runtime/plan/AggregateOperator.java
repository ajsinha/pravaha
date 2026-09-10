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
import java.util.Objects;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Grouped aggregation.
 *
 * <p>Stateful, and therefore subject to design section 9.6's rule: an aggregate whose key space cannot be
 * bounded is rejected at planning rather than accepted and left to exhaust memory later. Unbounded
 * integration is how incremental engines die in production, and refusing the query is the only
 * intervention that reliably works.
 *
 * <p>Wave 2 records the shape; the incremental lift for aggregates lands in Wave 4, after the
 * property oracle has been proven on the linear operators.
 *
 * @param groupKeyOrdinals input ordinals forming the grouping key; empty means a global aggregate
 * @param aggregates the functions to compute
 */
public record AggregateOperator(
        PhysicalOperator input,
        StreamSchema outputSchema,
        List<Integer> groupKeyOrdinals,
        List<AggregateCall> aggregates)
        implements PhysicalOperator {

    /** One aggregate function. */
    public record AggregateCall(Kind kind, int argumentOrdinal, String outputName) {
        public enum Kind {
            COUNT,
            /**
             * {@code COUNT(DISTINCT x)}.
             *
             * <p>Kept separate from {@link #COUNT} because it is a different kind of thing: COUNT
             * needs one number per group, and this needs one entry per distinct value, so its state
             * grows with cardinality rather than staying constant. Treating it as a variant of COUNT
             * is how an aggregate that looks cheap turns out to hold a million entries per group.
             */
            COUNT_DISTINCT,
            SUM,
            MIN,
            MAX,
            AVG
        }

        /** Whether this function's incremental form is a simple accumulation. */
        public boolean isLinear() {
            // COUNT and SUM accumulate weighted deltas directly. MIN and MAX need an ordered
            // structure per group so a retraction can restore the previous extreme, and AVG is
            // maintained as a SUM and COUNT pair rather than as a value.
            return kind == Kind.COUNT || kind == Kind.SUM;
        }
    }

    public AggregateOperator {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(outputSchema, "outputSchema");
        groupKeyOrdinals = List.copyOf(Objects.requireNonNull(groupKeyOrdinals, "groupKeyOrdinals"));
        aggregates = List.copyOf(Objects.requireNonNull(aggregates, "aggregates"));
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of(input);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    /** Whether every aggregate here accumulates simply, which decides how cheap the lift can be. */
    public boolean isFullyLinear() {
        return aggregates.stream().allMatch(AggregateCall::isLinear);
    }

    @Override
    public String label() {
        return "Aggregate(group=" + groupKeyOrdinals + ", "
                + aggregates.stream()
                        .map(a -> a.kind() + "(" + a.outputName() + ")")
                        .toList() + ")";
    }
}
