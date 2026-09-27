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
 * A projection whose columns are computed rather than copied.
 *
 * <p>Separate from {@link ProjectOperator} rather than folded into it, and the separation earns its
 * keep in two places. The generated code for a pure column copy is a load and a store with constant
 * offsets; an expression tree in the same operator would put a branch in that path for every column
 * of every row, on the hot path, to ask a question the plan already knew the answer to. And a plan
 * that says {@code Project} where a copy happens and {@code Compute} where arithmetic happens is
 * readable in {@code EXPLAIN} without reading the expressions.
 *
 * <p>Still linear, in the DBSP sense: a computed column is a function of one row, so the operator
 * applies unchanged to a delta and needs no state (design section 9.3).
 *
 * @param expressions one per output column, in order
 */
public record ComputeOperator(PhysicalOperator input, StreamSchema outputSchema, List<Expression> expressions)
        implements PhysicalOperator {

    public ComputeOperator {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(outputSchema, "outputSchema");
        expressions = List.copyOf(Objects.requireNonNull(expressions, "expressions"));
        if (expressions.size() != outputSchema.fieldCount()) {
            throw new IllegalArgumentException("compute has " + expressions.size()
                    + " expressions but its output schema has " + outputSchema.fieldCount() + " fields");
        }
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of(input);
    }

    @Override
    public String label() {
        return "Compute["
                + expressions.stream().map(Expression::describe).collect(java.util.stream.Collectors.joining(", "))
                + "]";
    }

    @Override
    public String identity() {
        return "Compute(" + expressions + " as "
                + outputSchema.fields().stream()
                        .map(com.ash.messaging.pravaha.api.data.Field::name)
                        .toList() + ")";
    }
}
