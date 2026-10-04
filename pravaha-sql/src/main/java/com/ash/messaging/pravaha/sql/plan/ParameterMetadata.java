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
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexVisitorImpl;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.PravahaType;
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
 * <p>The second is to refuse every placeholder that is not one. <b>A {@code ?} belongs in a WHERE
 * clause and nowhere else.</b> That is the whole rule, and it is stated as one position accepted
 * rather than a list of positions forbidden, so that a place nobody has thought of is refused by
 * default.
 *
 * <p>The reason is not tidiness. A placeholder in a WHERE clause varies which rows come back; one in
 * a window size, a group key or a table name varies what the query <em>is</em>. The distinction is
 * invisible in the SQL text and enormous in what it costs -- two bindings of a value share one
 * computation and its state, while two window sizes have no rows in common and cannot share state at
 * all. Accepting the second silently would let a caller loop over window sizes and quietly create a
 * computation per size.
 */
public final class ParameterMetadata {

    private final List<TypeName> types;

    /** The full types, which for a DECIMAL carry the precision and scale its column has (DECPARAM-1). */
    private final List<PravahaType> dataTypes;

    private ParameterMetadata(List<PravahaType> dataTypes) {
        this.dataTypes = List.copyOf(dataTypes);
        this.types = this.dataTypes.stream().map(PravahaType::typeName).toList();
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

    /**
     * The full type inferred for one placeholder: for a DECIMAL, the precision and scale of what it
     * is compared with, which a transport needs to declare the parameter exactly (DECPARAM-1).
     */
    public PravahaType dataTypeOf(int index) {
        return dataTypes.get(index);
    }

    public boolean isEmpty() {
        return types.isEmpty();
    }

    /**
     * Finds every placeholder in a planned statement, refusing any that is not in a WHERE clause.
     *
     * @throws PravahaException {@link SqlErrors#PARAMETER_NOT_A_VALUE} if a placeholder appears
     *     anywhere but a filter condition
     */
    public static ParameterMetadata of(RelNode plan) {
        List<RexDynamicParam> found = new ArrayList<>();
        collect(plan, found);

        // Sorted and de-duplicated by index: the same ?1 may appear twice in a condition, and it is
        // still one value the caller binds once.
        found.sort((a, b) -> Integer.compare(a.getIndex(), b.getIndex()));
        List<PravahaType> types = new ArrayList<>();
        for (RexDynamicParam param : found) {
            if (param.getIndex() < types.size()) {
                continue;
            }
            if (param.getIndex() != types.size()) {
                // Calcite numbers placeholders by position across the whole statement, so a gap
                // means one was consumed somewhere this walk deliberately does not accept.
                throw notAValue(param.getIndex());
            }
            types.add(typeOf(param));
        }
        return new ParameterMetadata(types);
    }

    private static PravahaException notAValue(int index) {
        return new PravahaException(
                SqlErrors.PARAMETER_NOT_A_VALUE,
                "?" + (index + 1) + " is not in a WHERE clause. A placeholder stands for a value that "
                        + "selects rows, and nothing else: a window size, a group key, an aggregate "
                        + "argument or a table name decides what the query *is* rather than which rows "
                        + "it returns. Two window sizes have no rows in common, so they cannot share a "
                        + "computation or its state -- binding one would create a separate query per "
                        + "size, and the first anyone would know of it is a memory alarm.");
    }

    private static PravahaType typeOf(RexDynamicParam param) {
        try {
            return TypeMapping.fromCalcite(param.getType());
        } catch (RuntimeException e) {
            throw new PravahaException(
                    SqlErrors.PARAMETER_TYPE,
                    "?" + (param.getIndex() + 1) + " has type " + param.getType()
                            + ", which this server cannot carry as a parameter",
                    e);
        }
    }

    /**
     * Walks the plan: placeholders in a filter condition are parameters, and everywhere else is a
     * refusal.
     *
     * <p>Uniform rather than a list of forbidden positions. An earlier version recognised windowing
     * functions by operator name and refused those specifically, which meant a brittle dependency on
     * Calcite's internal spelling and a hole for every position nobody had thought of. Accepting one
     * position and refusing the rest inverts that: the new place a placeholder could appear is
     * refused by default, and adding support for it is a deliberate act.
     */
    private static void collect(RelNode node, List<RexDynamicParam> found) {
        if (node instanceof Filter filter) {
            gather(filter.getCondition(), found);
        } else {
            // Every other node's expressions, whatever kind of node it is: RelNode.accept applies a
            // shuttle to the expressions that node holds, so this needs no per-operator knowledge.
            node.accept(new RexShuttle() {
                @Override
                public RexNode visitDynamicParam(RexDynamicParam param) {
                    throw notAValue(param.getIndex());
                }
            });
        }
        node.getInputs().forEach(input -> collect(input, found));
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
