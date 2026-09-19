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
package com.ash.messaging.pravaha.bindings.ingest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ash.messaging.pravaha.api.plugin.ReadRequest;

/**
 * What one reader shared by several queries asks its source for: every row and every column any
 * of them would have asked for on its own.
 *
 * <p>ADR-039 item 6. A shared reader used to push nothing down the moment a second query with a
 * different WHERE clause joined, so the price of sharing a reader was the filter -- on exactly the
 * deployments (many queries over one set) that sharing exists for. What they want together is the
 * <em>disjunction</em> of their filters, which {@link ReadRequest#alternatives()} can now carry.
 *
 * <p>The shape built here: the filters every query has in common stay plain conjuncts, and what
 * remains of each query's conjunction becomes one alternative. {@code status = 'DONE' AND amount >
 * 5} and {@code status = 'DONE'} is therefore just {@code status = 'DONE'}, because the second
 * query's remainder is empty and an empty alternative makes the OR true. Every query keeps its own
 * filter in the engine regardless, so this only ever needs to be wide enough, never exact.
 *
 * <p>Never a partial aggregate: a partial is one query's rows pre-combined for one aggregate, and a
 * fan-out to several queries has to carry rows.
 */
final class SharedReadRequest {

    private SharedReadRequest() {}

    /** The narrowest request that still returns everything each of {@code wanted} would have. */
    static ReadRequest union(List<ReadRequest> wanted) {
        if (wanted.isEmpty()) {
            return ReadRequest.NOTHING;
        }
        List<String> columns = unionOfColumns(wanted);

        List<List<ReadRequest.Filter>> conjunctions = new ArrayList<>(wanted.size());
        for (ReadRequest request : wanted) {
            if (request.filters().isEmpty() || !request.alternatives().isEmpty()) {
                // One query wants every row -- or carries an OR of its own, which this does not try
                // to distribute. Either way the union reads everything; its columns may still narrow.
                return new ReadRequest(List.of(), columns, List.of());
            }
            conjunctions.add(request.filters());
        }

        Set<ReadRequest.Filter> common = new LinkedHashSet<>(conjunctions.get(0));
        for (List<ReadRequest.Filter> conjunction : conjunctions) {
            common.retainAll(conjunction);
        }
        Set<List<ReadRequest.Filter>> remainders = new LinkedHashSet<>();
        for (List<ReadRequest.Filter> conjunction : conjunctions) {
            List<ReadRequest.Filter> remainder = new ArrayList<>(conjunction);
            remainder.removeAll(common);
            if (remainder.isEmpty()) {
                // This query wants every row the common filters allow, so the OR is true.
                return new ReadRequest(List.copyOf(common), columns, List.of());
            }
            remainders.add(List.copyOf(remainder));
        }
        if (remainders.size() == 1) {
            // Every query asked for the same thing.
            List<ReadRequest.Filter> all = new ArrayList<>(common);
            all.addAll(remainders.iterator().next());
            return new ReadRequest(all, columns, List.of());
        }
        return new ReadRequest(List.copyOf(common), columns, List.of(), List.copyOf(remainders));
    }

    /** Empty -- every column -- as soon as any one query needs every column. */
    private static List<String> unionOfColumns(List<ReadRequest> wanted) {
        Set<String> columns = new LinkedHashSet<>();
        for (ReadRequest request : wanted) {
            if (request.columns().isEmpty()) {
                return List.of();
            }
            columns.addAll(request.columns());
        }
        return List.copyOf(columns);
    }
}
