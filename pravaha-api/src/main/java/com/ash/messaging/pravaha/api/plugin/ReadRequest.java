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
package com.ash.messaging.pravaha.api.plugin;

import java.util.List;

/**
 * What the engine would like a source to do on its behalf.
 *
 * <p>A request, not an instruction. A plugin may honour all of it, some of it or none of it, and
 * the engine's answer is the same either way -- because the engine keeps its own filter regardless
 * (see {@link Filter}). Pushdown here buys bytes not read and rows not decoded, which is where
 * nearly all of the cost is; it never buys correctness, and a design that let it try would have to
 * verify every plugin's predicate evaluation to stay sound.
 *
 * <p>The filters are a conjunction: a row is wanted only if it satisfies all of them. Anything the
 * planner could not reduce to this shape is simply absent, so a plugin never has to reason about
 * what it was not told.
 *
 * <p>Deliberately not the engine's predicate IR. That lives in the runtime, which the plugin API
 * sits underneath, and exposing it would make every plugin author depend on -- and every IR change
 * break -- the engine's internals.
 *
 * @param filters simple column-against-literal comparisons, all of which must hold
 */
public record ReadRequest(List<Filter> filters) {

    /** A request that asks for nothing, which is what a query with no WHERE clause sends. */
    public static final ReadRequest NOTHING = new ReadRequest(List.of());

    public ReadRequest {
        filters = List.copyOf(filters);
    }

    /** Whether there is anything here worth acting on. */
    public boolean isEmpty() {
        return filters.isEmpty();
    }

    /**
     * One comparison between a column and a constant.
     *
     * <p><strong>A plugin that cannot express one of these must ignore it, never approximate it.</strong>
     * Returning fewer rows than the filter allows is the one failure the engine cannot detect: its
     * own filter will happily pass everything it is given, and the missing rows look exactly like
     * data that was never in the source. Returning <em>more</em> is always safe -- the engine
     * filters them out -- so when in doubt, ignore the filter.
     *
     * @param column the column's name in the stream's schema, not its ordinal: a store addresses
     *     its own columns by name, and an ordinal would make the plugin depend on the engine's
     *     layout
     * @param value a boxed Java value matching the column's type; {@code null} only for
     *     {@link Comparison#IS_NULL} and {@link Comparison#IS_NOT_NULL}, where it is ignored
     */
    public record Filter(String column, Comparison comparison, Object value) {

        public Filter {
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException("a pushed filter needs a column name");
            }
            if (comparison == null) {
                throw new IllegalArgumentException("a pushed filter needs a comparison");
            }
            boolean nullCheck = comparison == Comparison.IS_NULL || comparison == Comparison.IS_NOT_NULL;
            if (!nullCheck && value == null) {
                throw new IllegalArgumentException(
                        "comparison " + comparison + " on '" + column + "' has no value; a null here would mean "
                                + "UNKNOWN for every row, which is not what any caller intends");
            }
        }
    }

    /** The comparisons the planner will push. Deliberately the ones every store has. */
    public enum Comparison {
        EQ("="),
        NE("<>"),
        LT("<"),
        LE("<="),
        GT(">"),
        GE(">="),
        IS_NULL("IS NULL"),
        IS_NOT_NULL("IS NOT NULL");

        private final String sql;

        Comparison(String sql) {
            this.sql = sql;
        }

        /** The SQL spelling, which is what a store that speaks SQL needs and what an EXPLAIN shows. */
        public String sql() {
            return sql;
        }
    }
}
