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
import java.util.List;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexVisitorImpl;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.sql.SqlErrors;
import com.ash.messaging.pravaha.sql.TypeMapping;

/**
 * What the {@code ?} placeholders in a statement are, and where they are allowed to be (ADR-032).
 *
 * <p>Two jobs. The first is to report the parameters a caller must bind, with the type the planner
 * inferred for each -- Calcite works those out from context, so {@code WHERE user_id = ?} yields
 * STRING without the caller declaring anything.
 *
 * <p>The second is to refuse the placeholders that are not parameters at all. A {@code ?} standing
 * where a value goes varies the answer; a {@code ?} standing where the plan's <em>shape</em> goes --
 * {@code GROUP BY ?}, a window size, a table name -- varies the query. The distinction is invisible
 * in the SQL text and enormous in what it costs: two bindings of a value share one computation and
 * its state, and two window sizes cannot share state at all, because a five-minute window and an
 * hourly one have no rows in common to share. Accepting the second silently would mean a caller
 * looping over window sizes and quietly creating a computation per size.
 */
public final class ParameterMetadata {

    private final List<TypeName> types;

    private ParameterMetadata(List<TypeName> types) {
        this.types = List.copyOf(types);
    }

    /** How many values this statement needs. */
    public int count() {
        return types.size();
    }

    /** The type inferred for one placeholder, counting from zero. */
    public TypeName typeOf(int index) {
        return types.get(index);
    }

    public List<TypeName> types() {
        return types;
    }

    public boolean isEmpty() {
        return types.isEmpty();
    }

    /**
     * Finds every placeholder in a planned statement, refusing the ones that are not values.
     *
     * @throws PravahaException {@link SqlErrors#PARAMETER_NOT_A_VALUE} if a placeholder decides the
     *     shape of the plan rather than a value in it
     */
    public static ParameterMetadata of(RelNode plan) {
        List<RexDynamicParam> found = new ArrayList<>();
        collect(plan, found);

        // Sorted and de-duplicated by index: the same ?1 may appear twice in a condition, and it is
        // still one value the caller binds once.
        found.sort((a, b) -> Integer.compare(a.getIndex(), b.getIndex()));
        List<TypeName> types = new ArrayList<>();
        for (RexDynamicParam param : found) {
            if (param.getIndex() < types.size()) {
                continue;
            }
            if (param.getIndex() != types.size()) {
                // Calcite numbers placeholders by position, so a gap means one of them was consumed
                // somewhere this walk does not reach -- which is exactly the case that must not be
                // guessed at.
                throw new PravahaException(
                        SqlErrors.PARAMETER_NOT_A_VALUE,
                        "?" + (param.getIndex() + 1) + " is not in a position this server can bind. "
                                + "A placeholder must stand where a value goes -- in a comparison, or "
                                + "an expression -- not where the shape of the query goes");
            }
            types.add(typeOf(param));
        }
        return new ParameterMetadata(types);
    }

    private static TypeName typeOf(RexDynamicParam param) {
        try {
            return TypeMapping.fromCalcite(param.getType()).typeName();
        } catch (RuntimeException e) {
            throw new PravahaException(
                    SqlErrors.PARAMETER_TYPE,
                    "?" + (param.getIndex() + 1) + " has type " + param.getType()
                            + ", which this server cannot carry as a parameter",
                    e);
        }
    }

    /**
     * Walks the plan, gathering placeholders from the positions that hold values.
     *
     * <p>Filters and projections hold values. A group key or an aggregate argument does not -- a
     * placeholder there would change what the computation <em>is</em> rather than which rows it
     * returns, so it is refused rather than collected.
     */
    private static void collect(RelNode node, List<RexDynamicParam> found) {
        if (node instanceof Filter filter) {
            gather(filter.getCondition(), found);
        } else if (node instanceof Project project) {
            project.getProjects().forEach(expression -> gather(expression, found));
        } else if (node instanceof Aggregate aggregate) {
            // An aggregate's group set and calls are ordinals into its input, so a placeholder
            // cannot appear in them directly -- but a windowing function underneath can carry one,
            // and that one decides the window size. Caught by the shape check below.
            rejectShapeParameters(aggregate);
        }
        node.getInputs().forEach(input -> collect(input, found));
    }

    private static void rejectShapeParameters(Aggregate aggregate) {
        for (RelNode input : aggregate.getInputs()) {
            if (input instanceof Project project) {
                for (RexNode expression : project.getProjects()) {
                    if (expression instanceof RexCall call && isWindowing(call) && hasParameter(call)) {
                        throw new PravahaException(
                                SqlErrors.PARAMETER_NOT_A_VALUE,
                                "a window size cannot be a parameter. Two window sizes have no rows in "
                                        + "common, so they cannot share a computation or its state -- "
                                        + "binding one would create a separate query per size, and the "
                                        + "first anyone would know of it is a memory alarm. Register the "
                                        + "windows you need as separate queries");
                    }
                }
            }
        }
    }

    private static boolean isWindowing(RexCall call) {
        String name = call.getOperator().getName();
        return name.startsWith("$TUMBLE") || name.startsWith("$HOP") || name.startsWith("$SESSION");
    }

    private static boolean hasParameter(RexNode node) {
        boolean[] seen = {false};
        node.accept(new RexVisitorImpl<Void>(true) {
            @Override
            public Void visitDynamicParam(RexDynamicParam param) {
                seen[0] = true;
                return null;
            }
        });
        return seen[0];
    }

    private static void gather(RexNode node, List<RexDynamicParam> found) {
        node.accept(new RexVisitorImpl<Void>(true) {
            @Override
            public Void visitDynamicParam(RexDynamicParam param) {
                found.add(param);
                return null;
            }
        });
    }
}
