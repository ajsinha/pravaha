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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.catalog.CatalogErrors;
import com.ash.messaging.pravaha.runtime.plan.ComputeOperator;
import com.ash.messaging.pravaha.runtime.plan.Expression;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.sql.SqlPlanner;

/**
 * A principal's {@link Narrowing} of one object, compiled against that object's columns into the two
 * operators that enforce it: a filter directly above the scan, then a computation that replaces each
 * masked column and passes every other through (ADR-059 §4).
 *
 * <p><strong>Filter first, then mask.</strong> A row filter is about the real values -- {@code region =
 * 'EU'} keeps EU rows whatever a mask later shows of {@code region} -- and everything above the mask sees
 * only masked values. So nothing a query does with a masked column can compare its real value: a
 * {@code WHERE}, a {@code GROUP BY} or a join above the scan works on what the reader is shown. The
 * refusals in {@link MaskedColumnUse} are the second line, and name the misuse rather than answer it
 * with values that mean nothing.
 *
 * <p>The output keeps the scan's own schema, field for field -- names, types, event time, lateness,
 * identity -- so every operator above sees exactly the input it was planned for. A mask whose expression
 * produces another type is refused ({@code PRV-7038}) rather than cast: {@code 'XXXX'} is not a mask for
 * a {@code BIGINT}.
 *
 * <p>The soundness rules of ADR-031 hold unchanged: a filter naming a column the object does not carry,
 * or one the planner folds to true for every row, is {@code PRV-7003}. A mask on a column the object does
 * not carry masks nothing and is left out -- a tag-bound mask names a column, and a tagged object without
 * it has nothing of it to hide.
 */
public final class NarrowingPlan {

    /** Nothing narrowed. */
    public static final NarrowingPlan NONE = new NarrowingPlan(null, null, Set.of(), Narrowing.NONE);

    private final Predicate predicate;
    private final List<Expression> expressions;
    private final Set<String> masked;
    private final Narrowing narrowing;

    private NarrowingPlan(Predicate predicate, List<Expression> expressions, Set<String> masked, Narrowing narrowing) {
        this.predicate = predicate;
        this.expressions = expressions == null ? null : List.copyOf(expressions);
        this.masked = Collections.unmodifiableSet(new LinkedHashSet<>(masked));
        this.narrowing = narrowing;
    }

    /**
     * {@code narrowing} compiled against {@code schema}.
     *
     * @throws PravahaException {@code PRV-7003} for a filter that cannot be enforced on these columns;
     *     {@code PRV-7038} for a mask that names another column or changes the column's type
     */
    public static NarrowingPlan compile(StreamSchema schema, Narrowing narrowing) {
        if (narrowing == null || narrowing.isNone()) {
            return NONE;
        }
        Map<Integer, String> masks = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            String column = schema.field(ordinal).name();
            int at = ordinal;
            narrowing.maskOf(column).ifPresent(mask -> masks.put(at, mask));
        }
        Predicate predicate =
                narrowing.rowFilter().map(f -> filterOf(schema, f)).orElse(null);
        if (masks.isEmpty()) {
            return predicate == null ? NONE : new NarrowingPlan(predicate, null, Set.of(), narrowing);
        }
        List<String> selected = new ArrayList<>(schema.fieldCount());
        Set<String> masked = new LinkedHashSet<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            Field field = schema.field(ordinal);
            String mask = masks.get(ordinal);
            if (mask == null) {
                selected.add(quoted(field.name()));
                continue;
            }
            requireOwnColumnOnly(schema, field, mask);
            selected.add("(" + mask + ") AS " + quoted(field.name()));
            masked.add(field.name());
        }
        List<Expression> expressions = expressionsOf(schema, selected, masks);
        return new NarrowingPlan(predicate, expressions, masked, narrowing);
    }

    /** Whether this narrows nothing. */
    public boolean isNone() {
        return predicate == null && expressions == null;
    }

    /** The columns replaced by a mask, as the schema names them. */
    public Set<String> maskedColumns() {
        return masked;
    }

    /** What was compiled. */
    public Narrowing narrowing() {
        return narrowing;
    }

    /**
     * {@code input} -- a scan, or the rows of a view -- with the filter and the masks above it. The
     * computation keeps {@code input}'s schema, so nothing above can tell it is there.
     */
    public PhysicalOperator over(PhysicalOperator input) {
        PhysicalOperator plan = input;
        if (predicate != null) {
            plan = new FilterOperator(plan, predicate);
        }
        if (expressions != null) {
            plan = new ComputeOperator(plan, input.outputSchema(), expressions);
        }
        return plan;
    }

    // ------------------------------------------------------------------------------ compiling

    private static Predicate filterOf(StreamSchema schema, String filter) {
        PhysicalOperator plan;
        try {
            plan = new PhysicalPlanBuilder()
                    .overBoundedInput()
                    .build(SqlPlanner.withStreams(schema)
                            .plan("SELECT * FROM " + quoted(schema.name()) + " WHERE " + filter));
        } catch (PravahaException e) {
            throw new PravahaException(
                    SecurityErrors.FILTER_NOT_ENFORCEABLE,
                    "the row filter on " + schema.name() + " (" + filter + ") cannot be applied to it: "
                            + e.getMessage()
                            + ". A filter naming a column this object does not carry cannot be enforced on it -- "
                            + "for a view, the column was aggregated away and each row already mixes values the "
                            + "reader may and may not see. Refused rather than served unfiltered.",
                    e);
        }
        Predicate found = predicateOf(plan);
        if (found == null) {
            // ADR-031's always-true refusal, reused: the planner folded the filter away, so nothing would
            // restrict the read while the audit said a filter applied.
            throw new PravahaException(
                    SecurityErrors.FILTER_NOT_ENFORCEABLE,
                    "the row filter on " + schema.name() + " (" + filter + ") left no predicate in the plan: it is "
                            + "true for every row, so it restricts nothing. Refused rather than served as though it "
                            + "restricted something; exempt the reader with EXCEPT ROLE instead");
        }
        return found;
    }

    private static void requireOwnColumnOnly(StreamSchema schema, Field field, String mask) {
        StreamSchema alone = StreamSchema.builder(schema.name())
                .field(field.name(), field.type())
                .build();
        try {
            SqlPlanner.withStreams(alone)
                    .plan("SELECT " + mask + " AS " + quoted(field.name()) + " FROM " + quoted(schema.name()));
        } catch (PravahaException e) {
            throw new PravahaException(
                    CatalogErrors.POLICY_INVALID,
                    "the mask on " + schema.name() + "." + field.name() + " (" + mask + ") does not plan over that "
                            + "column alone: " + e.getMessage() + ". A mask may read only the column it masks",
                    e);
        }
    }

    /**
     * The select list planned as the builder plans any projection -- so a DECIMAL column passes through
     * as the builder's own decimal read, not a guess at one -- with each mask held to its column's type.
     */
    private static List<Expression> expressionsOf(
            StreamSchema schema, List<String> selected, Map<Integer, String> masks) {
        String sql = "SELECT " + String.join(", ", selected) + " FROM " + quoted(schema.name());
        PhysicalOperator plan;
        try {
            plan = new PhysicalPlanBuilder()
                    .overBoundedInput()
                    .build(SqlPlanner.withStreams(schema).plan(sql));
        } catch (PravahaException e) {
            throw new PravahaException(
                    CatalogErrors.POLICY_INVALID,
                    "the masks on " + schema.name() + " " + masks.values() + " cannot be planned: " + e.getMessage(),
                    e);
        }
        List<Expression> expressions = new ArrayList<>(schema.fieldCount());
        if (plan instanceof ComputeOperator compute && compute.expressions().size() == schema.fieldCount()) {
            expressions.addAll(compute.expressions());
        } else if (plan instanceof ProjectOperator project
                && project.sourceOrdinals().size() == schema.fieldCount()) {
            // Every mask was the column itself: nothing is replaced, and each column is read as it is.
            for (int i = 0; i < schema.fieldCount(); i++) {
                Field field = schema.field(project.sourceOrdinals().get(i));
                expressions.add(new Expression.Column(
                        project.sourceOrdinals().get(i),
                        field.name(),
                        field.type().typeName()));
            }
        } else {
            throw new PravahaException(
                    CatalogErrors.POLICY_INVALID,
                    "the masks on " + schema.name() + " planned as " + plan.label() + ", not as one value per column "
                            + "of each row; a mask is a value computed from its column");
        }
        for (Map.Entry<Integer, String> mask : masks.entrySet()) {
            Field field = schema.field(mask.getKey());
            Expression expression = expressions.get(mask.getKey());
            if (expression.type() != field.type().typeName()) {
                throw new PravahaException(
                        CatalogErrors.POLICY_INVALID,
                        "the mask on " + schema.name() + "." + field.name() + " (" + mask.getValue() + ") produces "
                                + expression.type() + " and the column is "
                                + field.type().typeName() + "; a mask keeps "
                                + "the column's type, so every reader and every query planned over it sees the same "
                                + "shape. CAST it, or write a value of that type");
            }
        }
        return expressions;
    }

    private static Predicate predicateOf(PhysicalOperator plan) {
        if (plan instanceof FilterOperator filter) {
            return filter.predicate();
        }
        for (PhysicalOperator input : plan.inputs()) {
            Predicate found = predicateOf(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** A name as a quoted SQL identifier. */
    static String quoted(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }
}
