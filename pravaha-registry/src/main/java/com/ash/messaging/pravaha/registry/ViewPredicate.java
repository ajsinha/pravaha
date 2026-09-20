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
package com.ash.messaging.pravaha.registry;

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * One column against one value: "stop when {@code balance < 0}" (ADR-047).
 *
 * <p><strong>Deliberately not a language.</strong> The design's sketch of the debugger shows a
 * conditional breakpoint reading {@code group = "user_42" AND SUM(amount) < 0}, and the obvious
 * next step from there is a little expression grammar -- with its own parser, its own type
 * coercions, its own null semantics and its own corner where it disagrees with SQL. Pravaha already
 * has an expression language, and a second one that is <em>nearly</em> it is a source of wrong
 * answers in the one tool somebody is using because they already have a wrong answer.
 *
 * <p>So: one comparison, over one of the view's own columns, and the refusal for anything else says
 * to step to the row and read the view instead. The single comparison covers the case the feature
 * is for -- "run until this group goes negative" -- because the group is a column of the view.
 *
 * <p>A predicate holds when <em>any</em> row of the view satisfies it, which is what "stop when
 * user_42's sum goes negative" means. The row that satisfied it is reported with the step.
 */
final class ViewPredicate {

    /** How two values are compared. Six, and no {@code LIKE}: see the class comment. */
    enum Comparison {
        EQ("="),
        NE("!="),
        LT("<"),
        LE("<="),
        GT(">"),
        GE(">=");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        String symbol() {
            return symbol;
        }

        static Comparison of(String text) {
            for (Comparison comparison : values()) {
                if (comparison.symbol.equals(text)) {
                    return comparison;
                }
            }
            throw new PravahaException(
                    DebugErrors.BAD_STEP,
                    "'" + text + "' is not a comparison. A debug predicate is one column against one value, "
                            + "with one of = != < <= > >=; anything more is a query, and the way to ask it is "
                            + "to step to the row and read the view.");
        }
    }

    private final int ordinal;
    private final String column;
    private final Comparison comparison;
    private final String value;

    private ViewPredicate(int ordinal, String column, Comparison comparison, String value) {
        this.ordinal = ordinal;
        this.column = column;
        this.comparison = comparison;
        this.value = value;
    }

    /**
     * Reads {@code column op value} against {@code schema}.
     *
     * <p>Refuses an unknown column by name and lists the ones there are: a predicate over a column
     * that is not in the view would otherwise never hold, and "it never stopped" is the hardest
     * kind of wrong to notice.
     */
    static ViewPredicate of(String column, String comparison, String value, StreamSchema schema) {
        if (column == null || column.isBlank()) {
            throw new PravahaException(
                    DebugErrors.BAD_STEP, "a predicate needs a column of the view to compare; none was given");
        }
        String wanted = column.strip();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            if (schema.field(ordinal).name().equalsIgnoreCase(wanted)) {
                return new ViewPredicate(ordinal, schema.field(ordinal).name(), Comparison.of(comparison), value);
            }
        }
        throw new PravahaException(
                DebugErrors.BAD_STEP,
                "'" + wanted + "' is not a column of this query's view, which has "
                        + schema.fields().stream()
                                .map(com.ash.messaging.pravaha.api.data.Field::name)
                                .toList()
                        + ". A debug predicate reads the view's own columns, so it can only name one of those.");
    }

    /** Whether any row of {@code rows} satisfies this, and which. */
    java.util.Optional<Object[]> firstMatch(List<Object[]> rows) {
        for (Object[] row : rows) {
            if (ordinal < row.length && holds(row[ordinal])) {
                return java.util.Optional.of(row);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Compares one value.
     *
     * <p>Numbers numerically, everything else as text, and null never satisfies anything -- SQL's
     * three-valued logic, where a comparison with NULL is UNKNOWN rather than true. A predicate
     * that stopped on a null would stop on every row of a sparse view.
     */
    private boolean holds(Object actual) {
        if (actual == null) {
            return false;
        }
        int order;
        if (actual instanceof Number number) {
            double wanted;
            try {
                wanted = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new PravahaException(
                        DebugErrors.BAD_STEP,
                        "'" + column + "' holds numbers and '" + value + "' is not one, so this predicate could "
                                + "never hold. Compare it with a number, or name a text column.");
            }
            order = Double.compare(number.doubleValue(), wanted);
        } else if (actual instanceof Boolean flag) {
            order = Boolean.compare(flag, Boolean.parseBoolean(value));
        } else {
            order = String.valueOf(actual).compareTo(value);
        }
        return switch (comparison) {
            case EQ -> order == 0;
            case NE -> order != 0;
            case LT -> order < 0;
            case LE -> order <= 0;
            case GT -> order > 0;
            case GE -> order >= 0;
        };
    }

    @Override
    public String toString() {
        return column + " " + comparison.symbol() + " " + value;
    }
}
