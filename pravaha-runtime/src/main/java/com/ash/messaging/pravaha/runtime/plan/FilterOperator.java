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
 * Selection.
 *
 * <p>Linear, so its incremental form is stateless: apply it to the delta and keep nothing
 * (design section 9.3). That is why a filter costs the same incrementally as it does in batch, and why
 * pushing one into a store is pure profit.
 */
public record FilterOperator(PhysicalOperator input, Predicate predicate) implements PhysicalOperator {

    public FilterOperator {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(predicate, "predicate");
    }

    @Override
    public StreamSchema outputSchema() {
        return input.outputSchema();
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of(input);
    }

    @Override
    public String label() {
        return "Filter(" + predicate.describe() + ")";
    }

    @Override
    public String identity() {
        // The predicate's record form: ordinals, operators, typed values, never an ambiguous name.
        return "Filter(" + predicate + ")";
    }
}
