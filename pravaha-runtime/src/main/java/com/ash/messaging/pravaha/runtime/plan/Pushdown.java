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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;

/**
 * Works out what of a plan a source could do on the engine's behalf.
 *
 * <p>The single biggest systemic lever in the design (§5.1): a predicate the store evaluates is
 * bytes never sent, rows never decoded and memory never touched. Everything else the engine does to
 * a row it does after paying for the row.
 *
 * <p><strong>The engine keeps its filter either way.</strong> That is the rule that makes this
 * safe. Pushdown is an optimisation over how much arrives, never over what is correct, so a plugin
 * that honours half a request, or misreads one, or silently ignores all of it, changes the number of
 * bytes read and nothing else. The alternative -- trusting a source's predicate evaluation to be
 * exactly SQL's, including its nulls, its collations and its numeric promotions -- would need every
 * plugin verified against the engine's semantics, which is not a thing a plugin API can promise.
 *
 * <p>What is extracted is a conjunction of column-against-literal comparisons. A disjunction is not:
 * {@code a = 1 OR b = 2} cannot be weakened into separate ANDed filters without dropping rows, and
 * pushing one half alone would drop the rows that satisfy only the other. Rather than reason about
 * that per store, an OR is simply not pushed.
 */
public final class Pushdown {

    private Pushdown() {}

    /**
     * The request to send with a reader for {@code stream}.
     *
     * @param capabilities what the source declared. A source that has not declared
     *     {@link PushdownKind#FILTER} is sent nothing, because a request it has not opted into is a
     *     request nobody has thought about
     */
    public static ReadRequest requestFor(PhysicalOperator plan, String stream, SourceCapabilities capabilities) {
        Set<PushdownKind> accepted =
                capabilities == null ? EnumSet.noneOf(PushdownKind.class) : capabilities.pushdown();
        if (!accepted.contains(PushdownKind.FILTER)) {
            return ReadRequest.NOTHING;
        }
        List<ReadRequest.Filter> filters = new ArrayList<>();
        collect(plan, stream, filters);
        return filters.isEmpty() ? ReadRequest.NOTHING : new ReadRequest(filters);
    }

    /**
     * Walks down to {@code stream}'s scan, gathering the filters directly above it.
     *
     * <p>Directly above, and nothing further up. A filter sitting over a join or an aggregate refers
     * to columns that do not exist in either source, and one over a projection refers to renumbered
     * ones; both would push a predicate that means something else. The conservative reading costs a
     * missed optimisation, and the other reading costs correct answers.
     */
    private static void collect(PhysicalOperator operator, String stream, List<ReadRequest.Filter> into) {
        if (operator instanceof FilterOperator filter && readsOnly(filter.input(), stream)) {
            flatten(filter.predicate(), into);
            collect(filter.input(), stream, into);
            return;
        }
        operator.inputs().forEach(input -> collect(input, stream, into));
    }

    /** Whether an operator's whole subtree reads exactly the named stream and nothing else. */
    private static boolean readsOnly(PhysicalOperator operator, String stream) {
        if (operator instanceof ScanOperator scan) {
            return scan.streamName().equals(stream);
        }
        return !operator.inputs().isEmpty() && operator.inputs().stream().allMatch(i -> readsOnly(i, stream));
    }

    /**
     * Turns a predicate into the conjuncts a store can evaluate, dropping the rest.
     *
     * <p>Dropping is safe in exactly one direction. A filter that is not pushed means more rows
     * arrive and the engine removes them; a filter pushed that should not be means rows do not
     * arrive at all, and nothing downstream can tell. So every case that is not obviously the first
     * kind falls through to nothing.
     */
    private static void flatten(Predicate predicate, List<ReadRequest.Filter> into) {
        switch (predicate) {
            case Predicate.And and -> and.parts().forEach(part -> flatten(part, into));
            case Predicate.CompareLong c -> into.add(filter(c.columnName(), c.op(), c.value()));
            case Predicate.CompareInt c -> into.add(filter(c.columnName(), c.op(), c.value()));
            case Predicate.CompareDouble c -> into.add(filter(c.columnName(), c.op(), c.value()));
            case Predicate.CompareString c -> into.add(filter(c.columnName(), c.op(), c.value()));
            case Predicate.CompareBoolean c ->
                into.add(new ReadRequest.Filter(c.columnName(), ReadRequest.Comparison.EQ, c.value()));
            case Predicate.IsNull isNull ->
                into.add(new ReadRequest.Filter(
                        isNull.columnName(),
                        isNull.wantNull() ? ReadRequest.Comparison.IS_NULL : ReadRequest.Comparison.IS_NOT_NULL,
                        null));
            // An OR cannot be split into ANDed parts, a NOT is only ever produced over something
            // already pushed as its own negation, and a comparison between two expressions has no
            // column-and-literal shape to send. All three simply stay in the engine.
            default -> {}
        }
    }

    private static ReadRequest.Filter filter(String column, Predicate.Op op, Object value) {
        return new ReadRequest.Filter(column, comparisonOf(op), value);
    }

    private static ReadRequest.Comparison comparisonOf(Predicate.Op op) {
        return switch (op) {
            case EQ -> ReadRequest.Comparison.EQ;
            case NE -> ReadRequest.Comparison.NE;
            case LT -> ReadRequest.Comparison.LT;
            case LE -> ReadRequest.Comparison.LE;
            case GT -> ReadRequest.Comparison.GT;
            case GE -> ReadRequest.Comparison.GE;
        };
    }
}
