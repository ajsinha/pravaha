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
import java.util.stream.Collectors;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A maintained top-N per partition: {@code ROW_NUMBER() OVER (PARTITION BY p ORDER BY o) <= N}.
 *
 * <p>The output is the input's columns followed by the row's number within its partition, 1 to
 * {@code limit}. As a Z-set, the output holds exactly the pairs (row, number) for the first {@code
 * limit} rows of every partition in the order the keys give. A row that enters the top N is emitted
 * at {@code +1} with its number; every row whose number it changes is retracted at its old number and
 * emitted at its new one; and the row pushed out past N is retracted. A retraction of an input row
 * does the reverse, promoting the row below it. So the maintained answer is, at every moment, the
 * answer a fresh computation over the net input would give.
 *
 * <p>That needs a deterministic order, and SQL's is not: {@code ROW_NUMBER} over tied keys may number
 * the ties either way. The runtime breaks ties on the remaining columns, in schema order, so two runs
 * over the same rows agree and a retraction finds the pair it has to withdraw. Rows equal in every
 * column are indistinguishable and numbered in either order to the same effect.
 *
 * <p><strong>State.</strong> Every row of a partition is held, not only the first N, because a
 * retraction of a top row promotes the next one and that row must still be there. {@code maxRows}
 * caps the rows held across all partitions, and exceeding it is refused with a sentence rather than
 * left to exhaust memory, exactly as an unwindowed join's ceiling is.
 *
 * @param partitionOrdinals the input columns that partition the numbering; empty for one partition
 * @param sortKeys the order within a partition, most significant first
 * @param limit the largest number emitted
 */
public record TopNOperator(
        PhysicalOperator input,
        StreamSchema outputSchema,
        List<Integer> partitionOrdinals,
        List<SortKey> sortKeys,
        long limit,
        long maxRows)
        implements PhysicalOperator {

    /** One ORDER BY key: a column, its direction, and where its nulls sort. */
    public record SortKey(int ordinal, boolean descending, boolean nullsFirst) {}

    public TopNOperator {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(outputSchema, "outputSchema");
        partitionOrdinals = List.copyOf(partitionOrdinals);
        sortKeys = List.copyOf(sortKeys);
        if (limit < 1) {
            throw new IllegalArgumentException("a top-N keeps at least one row per partition, and was given " + limit);
        }
        if (outputSchema.fieldCount() != input.outputSchema().fieldCount() + 1) {
            throw new IllegalArgumentException("a top-N emits its input's columns and the row number, "
                    + (input.outputSchema().fieldCount() + 1) + " columns, and its schema has "
                    + outputSchema.fieldCount());
        }
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of(input);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    public String label() {
        StreamSchema in = input.outputSchema();
        return "TopN[" + limit + " per ("
                + partitionOrdinals.stream().map(i -> in.field(i).name()).collect(Collectors.joining(", "))
                + ") by "
                + sortKeys.stream()
                        .map(k -> in.field(k.ordinal()).name()
                                + (k.descending() ? " DESC" : " ASC")
                                + (k.nullsFirst() ? " NULLS FIRST" : " NULLS LAST"))
                        .collect(Collectors.joining(", "))
                + "]";
    }

    @Override
    public String identity() {
        return "TopN(" + limit + ", partition=" + partitionOrdinals + ", order=" + sortKeys + ")";
    }
}
