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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;

/**
 * Where a continuous query's parameter has to be applied, and what that costs (ADR-032).
 *
 * <p>A parameter on a request/response query is simple: bind it, run it, forget it. On a
 * <em>continuous</em> query it is the most consequential thing in this codebase that looks harmless,
 * because it decides how many computations a deployment ends up running.
 *
 * <p>The rule is ADR-031's soundness rule, unchanged. A binding can be applied at the <b>tap</b> --
 * where a subscriber attaches -- if and only if the view carries every column it names. Then one
 * computation serves every binding, and a thousand users watching their own slice cost one read of
 * the source and one copy of the state.
 *
 * <p>If the query aggregated that column away, the view's rows already mix the values the binding is
 * meant to separate, and nothing applied afterwards can unmix them. The filter has to go into the
 * query, which makes each distinct binding <b>a separate computation with its own state</b>. That is
 * sometimes exactly right and is never something to discover from a memory alarm, so it is reported
 * rather than done quietly.
 *
 * <p>The two cases are one word apart in the SQL:
 *
 * <pre>
 *   SELECT user_id, SUM(amount) ... GROUP BY user_id, TUMBLE(...)   WHERE user_id = ?   -- tap
 *   SELECT tier,    SUM(amount) ... GROUP BY tier,    TUMBLE(...)   WHERE user_id = ?   -- forks
 * </pre>
 */
public record ParameterPlacement(int index, String column, Placement placement, String reason) {

    /** Where a binding can legally be applied. */
    public enum Placement {

        /**
         * At the subscription. Free: one computation, shared by every binding.
         */
        TAP,

        /**
         * In the query, before the aggregate. Correct, and costs a separate computation and a
         * separate copy of the state for every distinct value bound.
         */
        REGISTRATION
    }

    /** Human-readable, for a log line, a console, or the message on a registration. */
    public String describe() {
        return "?" + (index + 1) + " on '" + column + "': " + placement + " -- " + reason;
    }

    /**
     * Classifies every parameter in a planned continuous query.
     *
     * @param plan the query as planned, before any binding
     */
    public static List<ParameterPlacement> of(RelNode plan) {
        Set<String> surviving = new LinkedHashSet<>();
        plan.getRowType().getFieldNames().forEach(name -> surviving.add(name.toLowerCase(Locale.ROOT)));

        List<ParameterPlacement> placements = new ArrayList<>();
        collect(plan, surviving, placements);
        placements.sort((a, b) -> Integer.compare(a.index(), b.index()));
        return List.copyOf(placements);
    }

    /** True when every parameter can be applied at the tap, so one computation serves all of them. */
    public static boolean allTappable(List<ParameterPlacement> placements) {
        return placements.stream().allMatch(p -> p.placement() == Placement.TAP);
    }

    private static void collect(RelNode node, Set<String> surviving, List<ParameterPlacement> into) {
        if (node instanceof Filter filter) {
            // The filter's *input* row type is where the parameter's column lives; the output of the
            // whole plan is where we check whether it survived. Comparing the two is the rule.
            List<String> inputNames = filter.getInput().getRowType().getFieldNames();
            gather(filter.getCondition(), inputNames, surviving, into);
        }
        node.getInputs().forEach(input -> collect(input, surviving, into));
    }

    private static void gather(
            RexNode node, List<String> inputNames, Set<String> surviving, List<ParameterPlacement> into) {
        if (!(node instanceof RexCall call)) {
            return;
        }
        RexDynamicParam parameter = null;
        RexInputRef reference = null;
        for (RexNode operand : call.getOperands()) {
            if (operand instanceof RexDynamicParam found) {
                parameter = found;
            } else if (operand instanceof RexInputRef found) {
                reference = found;
            }
        }
        if (parameter != null && reference != null && reference.getIndex() < inputNames.size()) {
            String column = inputNames.get(reference.getIndex());
            boolean survives = surviving.contains(column.toLowerCase(Locale.ROOT));
            into.add(new ParameterPlacement(
                    parameter.getIndex(),
                    column,
                    survives ? Placement.TAP : Placement.REGISTRATION,
                    survives
                            ? "the view carries '" + column + "', so subscribers can filter on it and share "
                                    + "one computation"
                            : "'" + column + "' is not in the view -- the query aggregates it away, so each "
                                    + "distinct value needs its own computation and its own state"));
            return;
        }
        call.getOperands().forEach(operand -> gather(operand, inputNames, surviving, into));
    }
}
