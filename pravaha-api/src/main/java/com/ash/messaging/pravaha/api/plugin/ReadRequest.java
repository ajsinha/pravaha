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
 * <p>{@code columns} is the same kind of request applied to shape rather than to rows: the names of
 * the fields the engine still needs from this stream, or empty when it needs all of them. A source
 * that ignores it and returns every column is exactly as correct as one that ignores a filter and
 * returns every row -- the engine reads the columns it asked for and nothing forces it to notice
 * the rest arrived too. What it must never do is return <em>fewer</em> columns than were asked for;
 * that is the one failure this request cannot detect, the same asymmetry {@link Filter} documents
 * for rows.
 *
 * <p>{@code aggregates} asks a source to pre-combine rows into partial groups instead of returning
 * them individually. It is offered only for aggregates whose partial result is a monoid the engine
 * can both combine <em>and retract</em> -- see {@link PartialAggregate} for which ones qualify and
 * why the list is short.
 *
 * <p>{@code alternatives} is the one disjunction a request carries, and it exists for one caller: a
 * reader shared by several queries (SRC-3) whose WHERE clauses differ. Such a reader must return
 * every row <em>any</em> of them wants, which is an OR of their conjunctions; before this field it
 * could only be expressed by pushing nothing at all. A row is wanted when every one of {@code
 * filters} holds <em>and</em>, if {@code alternatives} is not empty, every filter of at least one
 * of its conjunctions holds. A plugin that ignores it returns more rows than asked, which is safe
 * for exactly the reason ignoring {@code filters} is; a plugin that honours it must honour each
 * conjunction exactly or not at all, and <strong>dropping a filter from inside one alternative
 * widens that alternative</strong> -- safe -- while dropping a whole alternative narrows the OR,
 * which is the one thing it must never do. An alternative left with no filter it can express
 * therefore makes the whole disjunction true, and the plugin must then drop all of it.
 *
 * <p>Deliberately not the engine's predicate IR. That lives in the runtime, which the plugin API
 * sits underneath, and exposing it would make every plugin author depend on -- and every IR change
 * break -- the engine's internals.
 *
 * @param filters simple column-against-literal comparisons, all of which must hold
 * @param columns the columns still needed from this stream, or empty for all of them
 * @param aggregates a partial aggregate this stream's rows may be pre-combined into, or empty for
 *     none; carried as a list only so {@code NOTHING} and an unpopulated request need no separate
 *     "absent" representation, but a source is never asked to honour more than one
 * @param alternatives conjunctions of which at least one must also hold, or empty for no
 *     disjunction; never contains an empty conjunction, since that would be "true" and is expressed
 *     by leaving the list empty instead
 */
public record ReadRequest(
        List<Filter> filters,
        List<String> columns,
        List<PartialAggregate> aggregates,
        List<List<Filter>> alternatives) {

    /** A request that asks for nothing, which is what a query with no WHERE clause sends. */
    public static final ReadRequest NOTHING = new ReadRequest(List.of(), List.of(), List.of(), List.of());

    public ReadRequest {
        filters = List.copyOf(filters);
        columns = List.copyOf(columns);
        aggregates = List.copyOf(aggregates);
        List<List<Filter>> copied = new java.util.ArrayList<>(alternatives.size());
        for (List<Filter> conjunction : alternatives) {
            if (conjunction.isEmpty()) {
                throw new IllegalArgumentException(
                        "an alternative with no filters is always true, which makes the whole disjunction true; "
                                + "leave alternatives empty to say that");
            }
            copied.add(List.copyOf(conjunction));
        }
        alternatives = List.copyOf(copied);
        if (alternatives.size() == 1) {
            throw new IllegalArgumentException(
                    "a single alternative is just more filters; add it to filters rather than to alternatives");
        }
    }

    /**
     * Filters, columns and a partial aggregate, with no disjunction -- the shape every caller built
     * before shared readers learned to push an OR, kept so it still compiles and still means what it
     * meant.
     */
    public ReadRequest(List<Filter> filters, List<String> columns, List<PartialAggregate> aggregates) {
        this(filters, columns, aggregates, List.of());
    }

    /**
     * Filters only -- the shape every caller built before projection and partial-aggregate
     * pushdown existed, kept so {@code new ReadRequest(myFilters)} still compiles and still means
     * exactly what it meant before.
     */
    public ReadRequest(List<Filter> filters) {
        this(filters, List.of(), List.of(), List.of());
    }

    /** Whether there is anything here worth acting on. */
    public boolean isEmpty() {
        return filters.isEmpty() && columns.isEmpty() && aggregates.isEmpty() && alternatives.isEmpty();
    }

    /**
     * This request with no partial aggregate in it: what to send a reader whose rows will be fed to
     * the engine one at a time -- a shared reader, a catch-up, or any path that has nowhere to put a
     * pre-combined partial.
     */
    public ReadRequest withoutAggregates() {
        return aggregates.isEmpty() ? this : new ReadRequest(filters, columns, List.of(), alternatives);
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

    /**
     * A request to pre-combine this stream's rows into partial groups rather than return them one
     * at a time.
     *
     * <p><strong>Only {@code COUNT} and {@code SUM} are ever carried here, and that is not an
     * arbitrary limit.</strong> The engine's own incremental form keeps every aggregate as a
     * Z-set: a row's contribution can arrive as a retraction as well as an addition, and combining
     * partials only works if undoing one is also defined. {@code COUNT} and {@code SUM} accumulate
     * as a running total that addition and subtraction both act on -- a monoid with an inverse, in
     * the language the engine's own aggregate operator uses to describe it. {@code MIN} and
     * {@code MAX} combine forwards (the minimum of two partial minimums is correct) but cannot be
     * retracted: undoing a row that was the minimum of its group needs to know what the next
     * smallest value was, and a partial minimum has already thrown that away. This is the same fact
     * {@code PRV-3020} exists to report at the engine's own aggregate operator, arrived at
     * independently here because a source that pre-computed a partial minimum would hand the engine
     * exactly the value it cannot retract from -- so this type does not let one be built.
     *
     * <p><strong>What a source that honours one must do</strong>, because nothing downstream can
     * check it (see {@code docs/CONNECTORS.md} section 6):
     *
     * <ul>
     *   <li>Apply <em>every</em> filter in the request, exactly. A partial replaces the rows, so the
     *       engine's own filter has nothing left to run against; a source that cannot express one of
     *       the filters must decline the partial and return rows instead. The planner only asks for
     *       a partial when every predicate between the aggregate and the scan was pushable, so
     *       declining is the rare case, not the common one.
     *   <li>Say so: a reader delivering partials answers {@code true} from {@link
     *       PartitionReader#deliversPartialAggregate()}, and one that declined answers {@code false}
     *       and returns rows. The engine routes by that answer and never by guessing.
     *   <li>Write each partial through the {@link com.ash.messaging.pravaha.api.data.RowWriter} it
     *       is handed, whose {@code schema()} is then the aggregate's own output: the group-by values
     *       in {@code groupByColumns} order, then one {@code BIGINT} per call in {@code aggregates}
     *       order, with weight {@code +1} for rows that arrived and {@code -1} for rows that left. A
     *       group with no rows must not be written at all -- a partial of zero is still "something
     *       arrived" to an aggregate deciding whether it has an answer yet.
     * </ul>
     *
     * @param groupByColumns the columns to group by, or empty for one partial covering everything
     *     the source was asked to read
     * @param aggregates the partial aggregates to compute per group; never empty, since a group-by
     *     with nothing to aggregate is simply {@link #columns} naming the group-by columns
     */
    public record PartialAggregate(List<String> groupByColumns, List<AggregateCall> aggregates) {

        public PartialAggregate {
            groupByColumns = List.copyOf(groupByColumns);
            aggregates = List.copyOf(aggregates);
            if (aggregates.isEmpty()) {
                throw new IllegalArgumentException(
                        "a partial aggregate needs at least one aggregate call; group-by columns alone belong in "
                                + "ReadRequest.columns, not here");
            }
        }

        /**
         * One {@code COUNT} or {@code SUM} to compute per group.
         *
         * @param column the column to sum, or the column {@code COUNT} counts non-null values of;
         *     {@code null} only for {@code COUNT(*)}, which {@link Kind#COUNT} also covers
         * @param outputName the name this partial's value is returned under, matching the engine's
         *     own aggregate output naming so the source need not invent one
         */
        public record AggregateCall(Kind kind, String column, String outputName) {

            public AggregateCall {
                if (outputName == null || outputName.isBlank()) {
                    throw new IllegalArgumentException("a pushed aggregate needs an output name");
                }
                if (kind == null) {
                    throw new IllegalArgumentException("a pushed aggregate needs a kind");
                }
                if (kind == Kind.SUM && (column == null || column.isBlank())) {
                    throw new IllegalArgumentException(
                            "SUM needs a column to sum; COUNT(*) is the only one of these pushed with none");
                }
            }
        }

        /**
         * The aggregates safe to push: see this record's own javadoc for why {@code MIN}/{@code MAX}
         * are not here despite composing forwards, and why {@code AVG} and {@code COUNT DISTINCT}
         * are not either -- neither is a single accumulator the engine can add a partial into.
         */
        public enum Kind {
            COUNT,
            SUM
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
