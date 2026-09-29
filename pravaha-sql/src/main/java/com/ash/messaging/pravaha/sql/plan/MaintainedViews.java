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
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.ComputeOperator;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.runtime.plan.SinkOperator;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * The registered queries' views a continuous query may read as inputs, and what may be done to them
 * (ADR-056).
 *
 * <p>An input that is another query's answer retracts: every change to that answer is a row leaving
 * and a row entering. So a plan over one may use only what is maintained exactly under retractions --
 * filters, projections, computed columns and unwindowed {@code COUNT}, {@code SUM} and {@code AVG},
 * with or without a {@code GROUP BY}. A keyed {@code GROUP BY} is admitted here although it is
 * refused over a stream ({@code PRV-2050}): its keys come from a view, which is bounded by its own
 * key ceiling. Everything else is refused with {@code PRV-2075}, naming the construct, rather than run
 * over an input it would answer wrongly.
 */
public final class MaintainedViews {

    /** No views: every scan is a stream, as before ADR-056. */
    public static final MaintainedViews NONE = new MaintainedViews(Set.of());

    /** Column types {@code KeyedAggregate} can group by. */
    private static final Set<TypeName> GROUPABLE = EnumSet.of(
            TypeName.BOOLEAN,
            TypeName.INT8,
            TypeName.INT16,
            TypeName.INT32,
            TypeName.INT64,
            TypeName.FLOAT32,
            TypeName.FLOAT64,
            TypeName.DATE,
            TypeName.TIME,
            TypeName.TIMESTAMP_LTZ,
            TypeName.STRING,
            // By its whole unscaled value (DECKEYGROUP-1).
            TypeName.DECIMAL);

    private final Set<String> names;

    private MaintainedViews(Set<String> names) {
        this.names = names;
    }

    public static MaintainedViews of(Collection<String> names) {
        return names == null || names.isEmpty() ? NONE : new MaintainedViews(Set.copyOf(names));
    }

    /** The views {@code plan} reads, in plan order. */
    public List<String> readBy(PhysicalOperator plan) {
        List<String> found = new ArrayList<>();
        collect(plan, found);
        return found;
    }

    private void collect(PhysicalOperator operator, List<String> into) {
        if (operator instanceof ScanOperator scan && names.contains(scan.streamName())) {
            if (!into.contains(scan.streamName())) {
                into.add(scan.streamName());
            }
        }
        operator.inputs().forEach(input -> collect(input, into));
    }

    /** Whether every scan beneath {@code operator} reads a view, and there is at least one. */
    boolean readsOnlyViews(PhysicalOperator operator) {
        if (names.isEmpty()) {
            return false;
        }
        if (operator instanceof ScanOperator scan) {
            return names.contains(scan.streamName());
        }
        if (operator.inputs().isEmpty()) {
            return false;
        }
        for (PhysicalOperator input : operator.inputs()) {
            if (!readsOnlyViews(input)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Refuses {@code construct} over an input that reads a view, before the construct's own checks
     * can refuse it for a reason about streams -- a window over a view is otherwise told to declare
     * an event-time column, which is advice about the wrong thing.
     */
    void refuseOver(PhysicalOperator input, String construct) {
        if (!names.isEmpty() && !readBy(input).isEmpty()) {
            throw refusal(construct, readBy(input));
        }
    }

    /** Refuses the whole plan when it reads a view and does anything not maintained exactly. */
    public void check(PhysicalOperator plan) {
        List<String> read = readBy(plan);
        if (read.isEmpty()) {
            return;
        }
        walk(plan, read);
    }

    private void walk(PhysicalOperator operator, List<String> read) {
        switch (operator) {
            case ScanOperator scan -> {
                if (!names.contains(scan.streamName())) {
                    throw refusal("reading the stream '" + scan.streamName() + "' beside it", read);
                }
            }
            case FilterOperator filter -> walk(filter.input(), read);
            case ProjectOperator project -> walk(project.input(), read);
            case ComputeOperator compute -> walk(compute.input(), read);
            case SinkOperator sink -> walk(sink.input(), read);
            case AggregateOperator aggregate -> {
                for (AggregateOperator.AggregateCall call : aggregate.aggregates()) {
                    switch (call.kind()) {
                        case COUNT, SUM, AVG -> {}
                        case MIN, MAX ->
                            throw refusal(
                                    call.kind() + " (retracting the extreme needs every value of the group, "
                                            + "ordered)",
                                    read);
                        case COUNT_DISTINCT ->
                            throw refusal("COUNT(DISTINCT ...) (its value sets are not weighted)", read);
                    }
                }
                for (int ordinal : aggregate.groupKeyOrdinals()) {
                    var field = aggregate.input().outputSchema().field(ordinal);
                    if (!GROUPABLE.contains(field.type().typeName())) {
                        throw refusal(
                                "GROUP BY " + field.name() + ", a "
                                        + field.type().typeName() + " column the grouped aggregate cannot hold",
                                read);
                    }
                }
                walk(aggregate.input(), read);
            }
            default -> throw refusal(describe(operator), read);
        }
    }

    private static String describe(PhysicalOperator operator) {
        String kind = operator.getClass().getSimpleName().replace("Operator", "");
        return switch (kind) {
            case "WindowAssign", "WindowedAggregate" -> "a window";
            case "Join" -> "a join";
            case "LookupJoin" -> "a lookup join";
            case "TopN" -> "a top-N";
            default -> "a " + kind;
        };
    }

    private static PravahaException refusal(String construct, List<String> read) {
        return new PravahaException(
                SqlErrors.VIEW_INPUT_UNSUPPORTED,
                "this query reads the maintained view " + String.join(", ", read) + ", and uses " + construct
                        + ". A query over another query's answer is fed every change to that answer as a row "
                        + "leaving and a row entering, so it may filter, project, compute, and aggregate with "
                        + "COUNT, SUM and AVG -- with or without GROUP BY. Windows, joins, top-N, MIN, MAX and "
                        + "COUNT(DISTINCT) over a view are not maintained exactly under retractions and are "
                        + "refused rather than approximated (ADR-056). Put that step in the upstream query, "
                        + "or read the view with a plain SELECT.");
    }
}
