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
 * Column selection and reordering.
 *
 * <p>Also linear. Note that projecting away a distinguishing column merges rows, and their Z-set
 * weights add -- which is what makes {@code SELECT} behave the way SQL expects rather than losing
 * duplicates (design section 9.2).
 *
 * @param sourceOrdinals for each output column, the input ordinal it comes from
 */
public record ProjectOperator(PhysicalOperator input, StreamSchema outputSchema, List<Integer> sourceOrdinals)
        implements PhysicalOperator {

    public ProjectOperator {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(outputSchema, "outputSchema");
        sourceOrdinals = List.copyOf(Objects.requireNonNull(sourceOrdinals, "sourceOrdinals"));
        if (sourceOrdinals.size() != outputSchema.fieldCount()) {
            throw new IllegalArgumentException("projection has " + sourceOrdinals.size()
                    + " source ordinals but its output schema has " + outputSchema.fieldCount() + " fields");
        }
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of(input);
    }

    @Override
    public String label() {
        // A renamed column says where it came from -- `v=amount` -- so a plan with an alias is read
        // for what it computes; an unrenamed one reads as it always has.
        StreamSchema in = input.outputSchema();
        List<String> columns = new java.util.ArrayList<>();
        for (int i = 0; i < sourceOrdinals.size(); i++) {
            String name = outputSchema.field(i).name();
            String source = in.field(sourceOrdinals.get(i)).name();
            columns.add(name.equals(source) ? name : name + "=" + source);
        }
        return "Project" + columns;
    }

    @Override
    public String identity() {
        return "Project(" + sourceOrdinals + " as "
                + outputSchema.fields().stream()
                        .map(com.ash.messaging.pravaha.api.data.Field::name)
                        .toList() + ")";
    }
}
