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
package com.ash.messaging.pravaha.runtime.exec;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;

/**
 * What a physical plan contains, read off its operator tree: the questions {@link QueryExecution}
 * asks of a plan before it decides how to spread it across lanes and route rows into it.
 */
final class PlanShape {

    private PlanShape() {}

    static boolean containsKeyedAggregate(PhysicalOperator operator) {
        // Both shapes. A windowed aggregate keyed only by the window boundaries is still safe on
        // one lane and unsafe on several, because two lanes both holding the same window each keep
        // their own running total for it.
        // A top-N is keyed by its partition in the same way: two lanes would each number the rows
        // of one partition they happen to see.
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator
                || operator instanceof com.ash.messaging.pravaha.runtime.plan.TopNOperator) {
            return true;
        }
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.AggregateOperator aggregate
                && !aggregate.groupKeyOrdinals().isEmpty()) {
            return true;
        }
        return operator.inputs().stream().anyMatch(PlanShape::containsKeyedAggregate);
    }

    static boolean containsJoin(PhysicalOperator operator) {
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.JoinOperator) {
            return true;
        }
        return operator.inputs().stream().anyMatch(PlanShape::containsJoin);
    }

    static com.ash.messaging.pravaha.runtime.plan.@Nullable JoinOperator findJoin(PhysicalOperator operator) {
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.JoinOperator join) {
            return join;
        }
        for (PhysicalOperator input : operator.inputs()) {
            com.ash.messaging.pravaha.runtime.plan.JoinOperator found = findJoin(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static int mapDownToScan(PhysicalOperator operator, int ordinal) {
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.ScanOperator) {
            return ordinal;
        }
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.ProjectOperator project) {
            return mapDownToScan(project.input(), project.sourceOrdinals().get(ordinal));
        }
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.FilterOperator filter) {
            return mapDownToScan(filter.input(), ordinal);
        }
        throw new PravahaException(
                RuntimeErrors.UNSUPPORTED_JOIN,
                "cannot work out which source column feeds this join key: it passes through "
                        + operator.label() + ", which changes what a column means. Run this query on one lane, "
                        + "where no partitioning is needed.");
    }

    /** Walks a plan for its scans, in the order the pipeline will register them. */
    static List<String> streamsOf(PhysicalOperator plan) {
        List<String> found = new ArrayList<>();
        collectStreams(plan, found);
        return found;
    }

    static void collectStreams(PhysicalOperator operator, List<String> into) {
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.ScanOperator scan) {
            // Once per stream. A self-join scans one stream twice and the pipeline hands each row to
            // both sides itself; listing it twice would open two readers and feed every row twice.
            if (!into.contains(scan.streamName())) {
                into.add(scan.streamName());
            }
            return;
        }
        operator.inputs().forEach(input -> collectStreams(input, into));
    }

    /**
     * The join key columns of one input, expressed in that input's own scan ordinals.
     *
     * <p>Walks down from the join, mapping ordinals through anything between it and the scan. A
     * projection renumbers columns, so taking the join's ordinals as the scan's would route rows by
     * whatever column happens to sit at that position -- correct-looking, and wrong.
     */
    static int[] joinKeyOrdinals(PhysicalOperator plan, int input) {
        com.ash.messaging.pravaha.runtime.plan.JoinOperator join = PlanShape.findJoin(plan);
        if (join == null) {
            throw new IllegalStateException("this query has no join, so there is no key to partition by; use pumpInto");
        }
        boolean left = input == 0;
        List<Integer> keys = left ? join.leftKeys() : join.rightKeys();
        PhysicalOperator side = left ? join.left() : join.right();
        int[] mapped = new int[keys.size()];
        for (int i = 0; i < mapped.length; i++) {
            mapped[i] = PlanShape.mapDownToScan(side, keys.get(i));
        }
        return mapped;
    }
}
