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
package com.ash.messaging.pravaha.serving;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

/**
 * Which rows of a view a read has to look at: all of them, one of them, or a run of them.
 *
 * <p>Design section 17.2 lists three access paths and, until B8, every read took the first. A read
 * of a view -- over Flight SQL, over {@code /api/v1/views/{name}/query}, or over the PostgreSQL
 * gateway, all three of which run through {@link ViewQuery} -- walked every committed row and let
 * the filter throw almost all of them away. That is the right answer for a predicate on an ordinary
 * column and the wrong one for a lookup by the key, which the view is already a hash map of.
 *
 * <p><strong>This chooses a set of rows, never an answer.</strong> The plan is not rewritten and the
 * filter is not removed: whatever comes back here is fed through the same pipeline as a scan's rows
 * would be, and is filtered again. So the rule this class has to obey is a one-sided one -- the rows
 * it returns must be a <em>superset</em> of the rows that satisfy the predicate -- and a bug in it
 * can make a read slow, or return rows the filter then drops, but cannot change an answer. When it
 * cannot prove a superset it returns empty, which means "scan". {@code ViewIndexEquivalenceTest}
 * asserts that equivalence over generated predicates rather than trusting the argument.
 *
 * <p>The two paths it can prove:
 *
 * <ul>
 *   <li><strong>The whole key by equality</strong> -- {@code WHERE user_id = 'u_42'} on a view keyed
 *       by {@code user_id}. One hash probe, no index, no declaration.
 *   <li><strong>The key's leading columns by equality and its last column between bounds</strong> --
 *       {@code WHERE user_id = 'u_42' AND window_end >= 1000 AND window_end < 2000}. The ordered
 *       index, which {@code RANGE (window_end)} declares and which {@link ServedView} builds on
 *       first use.
 * </ul>
 *
 * <p>Anything else scans, including a predicate on a column outside the key, which is exactly what
 * design section 17.2's last row says it is: best effort, scan and filter.
 */
final class ViewAccessPath {

    private ViewAccessPath() {}

    /**
     * The rows {@code plan} could possibly want from {@code view}, or empty when that is all of
     * them.
     */
    static Optional<List<Object[]>> rowsFor(PhysicalOperator plan, ServedView view) {
        Optional<Predicate> predicate = filterOverTheScan(plan, view.schema());
        if (predicate.isEmpty()) {
            return Optional.empty();
        }
        List<Integer> key = view.keyOrdinals();
        StreamSchema schema = view.schema();
        List<Predicate> conjuncts = conjunctsOf(predicate.get());

        // An equality for each key column, and bounds for the last one. Nulls mean "not said".
        Object[] equal = new Object[key.size()];
        Object low = null;
        Object high = null;
        boolean lowInclusive = false;
        boolean highInclusive = false;
        int lastKeyOrdinal = key.get(key.size() - 1);

        for (Predicate conjunct : conjuncts) {
            Comparison comparison = comparisonOf(conjunct);
            if (comparison == null) {
                continue;
            }
            int position = key.indexOf(comparison.ordinal());
            if (position < 0) {
                continue;
            }
            Object value = asStored(schema, comparison.ordinal(), comparison.value());
            if (value == null) {
                // The literal does not fit the column's own type -- WHERE an_int8 = 300. It cannot
                // be turned into a probe, and claiming "no rows" would be an answer rather than an
                // access path, so the read scans and the filter decides.
                return Optional.empty();
            }
            switch (comparison.op()) {
                case EQ -> {
                    if (equal[position] != null && !equal[position].equals(value)) {
                        return Optional.empty();
                    }
                    equal[position] = value;
                }
                case GT, GE -> {
                    if (comparison.ordinal() != lastKeyOrdinal || low != null || !orderable(value)) {
                        return Optional.empty();
                    }
                    low = value;
                    lowInclusive = comparison.op() == Predicate.Op.GE;
                }
                case LT, LE -> {
                    if (comparison.ordinal() != lastKeyOrdinal || high != null || !orderable(value)) {
                        return Optional.empty();
                    }
                    high = value;
                    highInclusive = comparison.op() == Predicate.Op.LE;
                }
                default -> {
                    // <> narrows nothing that a probe or a range can express.
                }
            }
        }

        boolean allEqual = true;
        for (int i = 0; i < equal.length; i++) {
            if (equal[i] == null) {
                allEqual = false;
                // Every column before the ordered one has to be pinned for the index to be usable:
                // the index is bucketed by that prefix, and a missing one is every bucket.
                if (i < equal.length - 1) {
                    return Optional.empty();
                }
            }
        }
        if (allEqual) {
            Optional<Object[]> found = view.committedRow(equal);
            List<Object[]> one = new ArrayList<>(1);
            found.ifPresent(one::add);
            return Optional.of(one);
        }
        if (low == null && high == null) {
            return Optional.empty();
        }
        Object[] prefix = new Object[equal.length - 1];
        System.arraycopy(equal, 0, prefix, 0, prefix.length);
        return Optional.of(view.committedRange(prefix, low, lowInclusive, high, highInclusive));
    }

    /** {@code column op literal}, whatever width the literal was compiled at. */
    private record Comparison(int ordinal, Predicate.Op op, Object value) {}

    private static Comparison comparisonOf(Predicate predicate) {
        return switch (predicate) {
            case Predicate.CompareLong p -> new Comparison(p.ordinal(), p.op(), p.value());
            case Predicate.CompareInt p -> new Comparison(p.ordinal(), p.op(), p.value());
            case Predicate.CompareString p -> new Comparison(p.ordinal(), p.op(), p.value());
            case Predicate.CompareBoolean p -> new Comparison(p.ordinal(), Predicate.Op.EQ, p.value());
            default -> null;
        };
    }

    /** Whether the ordered index can sort this value; see {@code ServedView.RANGE_ORDER}. */
    private static boolean orderable(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte;
    }

    /**
     * The literal as the view stores that column, or null when it cannot be one.
     *
     * <p>The view's keys are compared by value and by class: a {@code Long} 5 is not the {@code
     * Integer} 5 that an {@code INT32} column holds, so a probe built from the predicate's own
     * width would miss every row. Narrowing is refused rather than truncated, and the floating and
     * decimal types are refused outright -- {@code -0.0} and {@code 0.0} are one value to the
     * filter and two keys to the map, and {@code 1.0} and {@code 1.00} are the other way round.
     */
    private static Object asStored(StreamSchema schema, int ordinal, Object literal) {
        TypeName type = schema.field(ordinal).type().typeName();
        if (literal instanceof String text) {
            return type == TypeName.STRING ? text : null;
        }
        if (literal instanceof Boolean flag) {
            return type == TypeName.BOOLEAN ? flag : null;
        }
        if (!(literal instanceof Number number)) {
            return null;
        }
        long value = number.longValue();
        return switch (type) {
            case INT8 -> value == (byte) value ? Byte.valueOf((byte) value) : null;
            case INT16 -> value == (short) value ? Short.valueOf((short) value) : null;
            case INT32, DATE -> value == (int) value ? Integer.valueOf((int) value) : null;
            case INT64, TIME, TIMESTAMP_LTZ -> Long.valueOf(value);
            default -> null;
        };
    }

    /** A conjunction flattened to its terms; anything else is one term. */
    private static List<Predicate> conjunctsOf(Predicate predicate) {
        List<Predicate> terms = new ArrayList<>();
        collect(predicate, terms);
        return terms;
    }

    private static void collect(Predicate predicate, List<Predicate> into) {
        if (predicate instanceof Predicate.And and) {
            and.parts().forEach(part -> collect(part, into));
            return;
        }
        into.add(predicate);
    }

    /**
     * The filter sitting directly on the view's rows, if there is one.
     *
     * <p>Directly, and nothing higher up: a filter above an aggregate or a projection is written in
     * that operator's ordinals, and reading it as if it were in the view's would pin the wrong
     * column. A scan that absorbed a projection or a predicate is left alone for the same reason --
     * this path only exists over a scan whose rows are the view's rows, in the view's order.
     */
    private static Optional<Predicate> filterOverTheScan(PhysicalOperator plan, StreamSchema viewSchema) {
        PhysicalOperator node = plan;
        while (node != null) {
            if (node instanceof FilterOperator filter && filter.input() instanceof ScanOperator scan) {
                boolean untouched = scan.projectedFields().isEmpty()
                        && scan.pushedFilters().isEmpty()
                        && scan.outputSchema().fieldCount() == viewSchema.fieldCount();
                return untouched ? Optional.of(filter.predicate()) : Optional.empty();
            }
            node = node.inputs().size() == 1 ? node.inputs().get(0) : null;
        }
        return Optional.empty();
    }
}
