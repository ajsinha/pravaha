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
 * <p>The soundness rules of ADR-031 hold unchanged: a filter naming a column the object does not carry
 * is {@code PRV-7003}, and so is one that restricts nothing -- true for every row, or dropping only the
 * rows where a compared value is NULL -- as {@link FilterVacuity} decides it over the compiled predicate
 * (TAUTOFILTER-1). A mask on a column the object does not carry masks nothing and is left out -- a
 * tag-bound mask names a column, and a tagged object without it has nothing of it to hide.
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
        return compile(schema, narrowing, Judgement.BOUND);
    }

    /**
     * How much of {@link FilterVacuity}'s verdict a caller acts on. A filter bound to a principal is
     * judged as it will run; a catalogue policy being created is judged only as far as its text allows.
     */
    public enum Judgement {
        /** Bound to a principal: a filter that restricts nothing is refused; one that keeps no row is enforced. */
        BOUND,
        /**
         * A policy that reads nothing about the session, being created: it is the same filter for everybody,
         * so one that keeps no row is refused as well -- it keeps none for anybody, and a deny says so.
         */
        SESSION_FREE_POLICY,
        /**
         * A policy that reads the session, being created and planned as {@code probe()} binds it (claims
         * {@code '0'}, memberships FALSE). Only whether it plans is decided: its vacuity under the probe says
         * nothing about any real principal ({@code NOT is_member('x') OR region = 'EU'} is TRUE under the
         * probe and a real filter for members of x), so it is judged when bound, at every read.
         */
        SESSION_POLICY
    }

    /**
     * {@code narrowing} compiled against {@code schema}, its filter judged as {@code judgement} says.
     *
     * @throws PravahaException as {@link #compile(StreamSchema, Narrowing)}
     */
    public static NarrowingPlan compile(StreamSchema schema, Narrowing narrowing, Judgement judgement) {
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
                narrowing.rowFilter().map(f -> filterOf(schema, f, judgement)).orElse(null);
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

    private static Predicate filterOf(StreamSchema schema, String filter, Judgement judgement) {
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
        if (judgement == Judgement.SESSION_POLICY) {
            return found;
        }
        // TAUTOFILTER-1. This used to refuse only a filter the planner folded away -- TRUE, and little
        // else -- so region = region and 1 = 1 OR region = 'x' were enforced as though they restricted
        // something. FilterVacuity judges the predicate that will run.
        String subject = "the row filter on " + schema.name() + " (" + filter + ")";
        FilterVacuity.Verdict verdict = FilterVacuity.requireRestricts(
                found,
                schema,
                subject,
                "if the reader may see every row, exempt them with EXCEPT ROLE rather than write a filter that "
                        + "restricts nothing");
        if (verdict == FilterVacuity.Verdict.ALWAYS_FALSE && judgement == Judgement.SESSION_FREE_POLICY) {
            throw new PravahaException(
                    SecurityErrors.FILTER_NOT_ENFORCEABLE,
                    subject + " is false for every row: it reads nothing about the reader, so it keeps no row for "
                            + "anybody, and a policy that hides everything is a deny wearing a filter's name. "
                            + "Refused as almost certainly a mistake (TAUTOFILTER-1); to deny reading, REVOKE the "
                            + "grant");
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
