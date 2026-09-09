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
 * Reads a stream.
 *
 * <p>{@code pushedFilters} and {@code projectedFields} record what the source agreed to absorb.
 * Anything the plugin declined stays above this operator as a residual filter -- never silently
 * dropped, which is the classic pushdown correctness bug (design FR-3).
 *
 * @param streamName the stream to read
 * @param outputSchema what the scan emits after any pushed projection
 * @param pushedFilters predicates the source accepted, rendered for EXPLAIN
 * @param projectedFields ordinals into the source's own schema, or empty when reading everything
 */
public record ScanOperator(
        String streamName, StreamSchema outputSchema, List<String> pushedFilters, List<Integer> projectedFields)
        implements PhysicalOperator {

    public ScanOperator {
        Objects.requireNonNull(streamName, "streamName");
        Objects.requireNonNull(outputSchema, "outputSchema");
        pushedFilters = pushedFilters == null ? List.of() : List.copyOf(pushedFilters);
        projectedFields = projectedFields == null ? List.of() : List.copyOf(projectedFields);
    }

    public static ScanOperator of(String streamName, StreamSchema schema) {
        return new ScanOperator(streamName, schema, List.of(), List.of());
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of();
    }

    @Override
    public String label() {
        return "Scan(" + streamName + ")";
    }
}
