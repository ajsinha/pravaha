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
package com.ash.messaging.pravaha.sql.plan;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.ComputeOperator;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.Pushdown;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

/**
 * What the engine would like a source to do for it, beyond the filters {@link Pushdown} already
 * computes: which columns it can stop decoding, and which aggregates it may pre-combine.
 *
 * <p>Kept separate from {@link Pushdown} rather than added to it because {@code Pushdown} lives in
 * {@code pravaha-runtime}, which does not depend on Calcite and should not grow a reason to -- this
 * class exists in {@code pravaha-sql} for no deeper reason than that being where this agent's work
 * was scoped to. It composes {@code Pushdown}'s filters with what it computes itself rather than
 * duplicating the filter walk.
 *
 * <p><strong>The same rule filter pushdown follows governs both of the new kinds:</strong> the
 * conservative reading costs a missed optimisation and the other one costs a wrong answer, so every
 * method here returns "push nothing" the moment it is not certain, rather than guess. Neither
 * dropping a used column nor over-claiming an aggregate can be detected downstream -- the engine has
 * no way to tell a column it never asked to see from one a source silently withheld, and the same is
 * true of a group a source pre-combined wrongly. See {@code docs/CONNECTORS.md} section 6.
 */
public final class SourcePushdown {

    private SourcePushdown() {}

    /**
     * Everything this stream's source might be asked to do: the filters {@link Pushdown} already
     * finds, plus the columns and partial aggregate this class adds, each gated by whether {@code
     * capabilities} claims the matching {@link PushdownKind}.
     */
    public static ReadRequest requestFor(PhysicalOperator plan, String stream, SourceCapabilities capabilities) {
        ReadRequest filterRequest = Pushdown.requestFor(plan, stream, capabilities);
        Set<PushdownKind> accepted =
                capabilities == null ? EnumSet.noneOf(PushdownKind.class) : capabilities.pushdown();

        List<String> columns = accepted.contains(PushdownKind.PROJECT)
                ? projectedColumns(plan, stream).orElse(List.of())
                : List.of();
        List<ReadRequest.PartialAggregate> aggregates = accepted.contains(PushdownKind.PARTIAL_AGGREGATE)
                ? partialAggregateFor(plan, stream).map(List::of).orElse(List.<ReadRequest.PartialAggregate>of())
                : List.of();

        if (columns.isEmpty() && aggregates.isEmpty()) {
            return filterRequest;
        }
        return new ReadRequest(filterRequest.filters(), columns, aggregates);
    }

    // ---- projection ----

    /**
     * Which of {@code stream}'s own columns are still needed, or {@link Optional#empty()} when the
     * path from the plan's root down to this stream's scan leaves the shapes this method
     * understands well enough to answer safely.
     *
     * <p>Only {@link ProjectOperator} and {@link FilterOperator} are walked. A {@link
     * ComputeOperator}, an aggregate, a join, a window -- anything that derives a column from more
     * than a copy of another, or that this stream shares a plan node with another stream through --
     * stops the walk and asks for every column instead of risking a wrong guess.
     */
    static Optional<List<String>> projectedColumns(PhysicalOperator plan, String stream) {
        return narrow(plan, allOrdinals(plan.outputSchema().fieldCount()), stream);
    }

    private static Set<Integer> allOrdinals(int count) {
        Set<Integer> all = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            all.add(i);
        }
        return all;
    }

    /**
     * Translates {@code neededAtOutput} -- ordinals into {@code operator}'s own output schema --
     * into the column names of {@code stream}'s scan, recursing down through the operators this
     * class trusts and giving up the moment it reaches one it does not.
     */
    private static Optional<List<String>> narrow(
            PhysicalOperator operator, Set<Integer> neededAtOutput, String stream) {
        if (operator instanceof ScanOperator scan) {
            if (!scan.streamName().equals(stream)) {
                return Optional.empty();
            }
            StreamSchema schema = scan.outputSchema();
            List<String> names = new ArrayList<>();
            for (Integer ordinal : neededAtOutput) {
                if (ordinal < 0 || ordinal >= schema.fieldCount()) {
                    return Optional.empty();
                }
                names.add(schema.field(ordinal).name());
            }
            // Every column of this scan is needed: naming all of them asks a source to do work
            // ("select exactly these columns") that reading everything already satisfies, so this
            // asks for nothing instead -- the same convention ReadRequest.NOTHING uses for filters.
            if (names.size() >= schema.fieldCount()) {
                return Optional.of(List.of());
            }
            return Optional.of(List.copyOf(names));
        }
        if (operator instanceof FilterOperator filter) {
            Set<Integer> referenced = referencedOrdinals(filter.predicate());
            if (referenced == null) {
                return Optional.empty();
            }
            Set<Integer> combined = new LinkedHashSet<>(neededAtOutput);
            combined.addAll(referenced);
            return narrow(filter.input(), combined, stream);
        }
        if (operator instanceof ProjectOperator project) {
            Set<Integer> translated = new LinkedHashSet<>();
            for (Integer ordinal : neededAtOutput) {
                if (ordinal < 0 || ordinal >= project.sourceOrdinals().size()) {
                    return Optional.empty();
                }
                translated.add(project.sourceOrdinals().get(ordinal));
            }
            return narrow(project.input(), translated, stream);
        }
        // ComputeOperator, AggregateOperator, WindowAssignOperator, WindowedAggregateOperator,
        // JoinOperator, LookupJoinOperator, SinkOperator: none of these is "needed at my output"
        // translated to "needed at my input" by a simple ordinal lookup, so none is walked.
        return Optional.empty();
    }

    /**
     * The ordinals {@code predicate} reads, or {@code null} the moment a part of it is not one of
     * the simple column comparisons this method can attribute to a single ordinal -- {@link
     * Predicate.CompareExpressions} in particular, whose operands are arbitrary expressions this
     * method does not walk.
     */
    private static Set<Integer> referencedOrdinals(Predicate predicate) {
        return switch (predicate) {
            case Predicate.True ignored -> Set.of();
            case Predicate.False ignored -> Set.of();
            case Predicate.CompareLong p -> Set.of(p.ordinal());
            case Predicate.CompareInt p -> Set.of(p.ordinal());
            case Predicate.CompareDouble p -> Set.of(p.ordinal());
            case Predicate.CompareString p -> Set.of(p.ordinal());
            case Predicate.CompareBoolean p -> Set.of(p.ordinal());
            case Predicate.IsNull p -> Set.of(p.ordinal());
            case Predicate.Like p -> Set.of(p.ordinal());
            case Predicate.And p -> unionOrNull(p.parts());
            case Predicate.Or p -> unionOrNull(p.parts());
            case Predicate.Not p -> referencedOrdinals(p.inner());
            case Predicate.CompareExpressions ignored -> null;
        };
    }

    private static Set<Integer> unionOrNull(List<Predicate> parts) {
        Set<Integer> union = new LinkedHashSet<>();
        for (Predicate part : parts) {
            Set<Integer> referenced = referencedOrdinals(part);
            if (referenced == null) {
                return null;
            }
            union.addAll(referenced);
        }
        return union;
    }

    // ---- partial aggregate ----

    /**
     * A partial aggregate {@code stream}'s source may compute on the engine's behalf, or {@link
     * Optional#empty()} when this is not the narrow, provably-safe shape this method recognises:
     * {@code plan} itself an {@link AggregateOperator} whose every call is {@link
     * AggregateOperator.AggregateCall#isLinear()} -- {@code COUNT} and {@code SUM} only, see {@link
     * ReadRequest.PartialAggregate} for why -- sitting over a chain of {@link FilterOperator}s and
     * exactly one {@link ScanOperator} of {@code stream}.
     *
     * <p>Deliberately does not look through a {@link ProjectOperator}, a {@code HAVING} filter
     * above the aggregate, or any other composition: the aggregate's own correctness trap (design
     * note in {@link ReadRequest.PartialAggregate}) is reason enough to keep this the one shape
     * proven here, rather than extend it before there is a second one to generalise from.
     */
    static Optional<ReadRequest.PartialAggregate> partialAggregateFor(PhysicalOperator plan, String stream) {
        if (!(plan instanceof AggregateOperator aggregate) || !aggregate.isFullyLinear()) {
            return Optional.empty();
        }
        PhysicalOperator input = aggregate.input();

        List<String> groupByColumns = new ArrayList<>();
        for (Integer ordinal : aggregate.groupKeyOrdinals()) {
            Optional<String> name = resolveToSourceColumn(input, ordinal, stream);
            if (name.isEmpty()) {
                return Optional.empty();
            }
            groupByColumns.add(name.get());
        }

        List<ReadRequest.PartialAggregate.AggregateCall> calls = new ArrayList<>();
        for (AggregateOperator.AggregateCall call : aggregate.aggregates()) {
            Optional<ReadRequest.PartialAggregate.AggregateCall> resolved = resolveCall(call, input, stream);
            if (resolved.isEmpty()) {
                return Optional.empty();
            }
            calls.add(resolved.get());
        }

        return Optional.of(new ReadRequest.PartialAggregate(groupByColumns, calls));
    }

    private static Optional<ReadRequest.PartialAggregate.AggregateCall> resolveCall(
            AggregateOperator.AggregateCall call, PhysicalOperator input, String stream) {
        ReadRequest.PartialAggregate.Kind kind =
                switch (call.kind()) {
                    case COUNT -> ReadRequest.PartialAggregate.Kind.COUNT;
                    case SUM -> ReadRequest.PartialAggregate.Kind.SUM;
                    // MIN, MAX, AVG, COUNT_DISTINCT: aggregate.isFullyLinear() already refused these
                    // before this method is reached. Handled here only so the switch is exhaustive.
                    case MIN, MAX, AVG, COUNT_DISTINCT -> null;
                };
        if (kind == null) {
            return Optional.empty();
        }
        if (call.argumentOrdinal() < 0) {
            // COUNT(*): no column to resolve.
            return Optional.of(new ReadRequest.PartialAggregate.AggregateCall(kind, null, call.outputName()));
        }
        return resolveToSourceColumn(input, call.argumentOrdinal(), stream)
                .map(column -> new ReadRequest.PartialAggregate.AggregateCall(kind, column, call.outputName()));
    }

    /**
     * Resolves {@code ordinal}, in {@code operator}'s own output schema, back to the name of the
     * matching column at {@code stream}'s scan -- walking down through a {@link FilterOperator}
     * (whose output schema is its input's, unchanged) and a {@link ProjectOperator} (whose ordinals
     * are its own {@code sourceOrdinals} indirection) only.
     */
    private static Optional<String> resolveToSourceColumn(PhysicalOperator operator, int ordinal, String stream) {
        if (operator instanceof ScanOperator scan) {
            if (!scan.streamName().equals(stream)
                    || ordinal < 0
                    || ordinal >= scan.outputSchema().fieldCount()) {
                return Optional.empty();
            }
            return Optional.of(scan.outputSchema().field(ordinal).name());
        }
        if (operator instanceof FilterOperator filter) {
            return resolveToSourceColumn(filter.input(), ordinal, stream);
        }
        if (operator instanceof ProjectOperator project) {
            if (ordinal < 0 || ordinal >= project.sourceOrdinals().size()) {
                return Optional.empty();
            }
            return resolveToSourceColumn(
                    project.input(), project.sourceOrdinals().get(ordinal), stream);
        }
        return Optional.empty();
    }
}
