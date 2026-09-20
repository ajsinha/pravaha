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
package com.ash.messaging.pravaha.sql;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Planning error codes. Stable, documented, and never renumbered. */
public final class SqlErrors {

    public static final ErrorCode PARSE_FAILED = new ErrorCode(2001, "SQL_PARSE_FAILED");
    public static final ErrorCode VALIDATION_FAILED = new ErrorCode(2002, "SQL_VALIDATION_FAILED");
    public static final ErrorCode UNKNOWN_STREAM = new ErrorCode(2003, "SQL_UNKNOWN_STREAM");
    public static final ErrorCode PLANNING_FAILED = new ErrorCode(2010, "SQL_PLANNING_FAILED");

    /**
     * A predicate (or other expression) is too large or too deeply nested for the planner to convert.
     *
     * <p>Split out of {@link #PLANNING_FAILED} (X-11 part B) because Calcite's own conversion
     * failure embeds the offending expression's full text in its message -- for a several-thousand
     * term {@code AND}/{@code OR} chain, that is the entire predicate, tens or hundreds of kilobytes,
     * interpolated verbatim into an error. "This predicate is too large to compile" is a specific,
     * actionable diagnosis in its own right, not just another way for planning to fail, so it gets a
     * code of its own rather than sharing the catch-all -- and the message that comes with this code
     * is a summary (the operator and the term count) rather than the predicate itself.
     */
    public static final ErrorCode PREDICATE_TOO_LARGE = new ErrorCode(2011, "SQL_PREDICATE_TOO_LARGE");

    public static final ErrorCode UNSUPPORTED_OPERATOR = new ErrorCode(2020, "SQL_UNSUPPORTED_OPERATOR");
    public static final ErrorCode UNSUPPORTED_EXPRESSION = new ErrorCode(2021, "SQL_UNSUPPORTED_EXPRESSION");
    /** The mismatch design section 15.5 exists to catch at registration rather than in production. */
    public static final ErrorCode EMIT_MODE_MISMATCH = new ErrorCode(2041, "SQL_EMIT_MODE_MISMATCH");

    /**
     * A query whose answer depends on how many times a row arrived -- an aggregate, a join, a sink
     * that cannot upsert -- over a source that repeats rows (SCAN-1). See {@code
     * RepeatedRowsAnalysis}.
     */
    public static final ErrorCode SOURCE_REPEATS_ROWS = new ErrorCode(2042, "SQL_SOURCE_REPEATS_ROWS");

    public static final ErrorCode UNBOUNDED_STATE = new ErrorCode(2050, "SQL_UNBOUNDED_STATE");

    /** A statement was executed with fewer values than it has placeholders. */
    public static final ErrorCode PARAMETER_NOT_BOUND = new ErrorCode(2060, "SQL_PARAMETER_NOT_BOUND");

    /** The number of values bound does not match the number of placeholders. */
    public static final ErrorCode PARAMETER_ARITY = new ErrorCode(2061, "SQL_PARAMETER_ARITY");

    /** A value was bound whose type is not the one the planner inferred for that placeholder. */
    public static final ErrorCode PARAMETER_TYPE = new ErrorCode(2062, "SQL_PARAMETER_TYPE");

    /**
     * A {@code ?} in a position that decides the shape of the plan rather than a value.
     *
     * <p>{@code GROUP BY ?}, a parameterised window size, a table name. These are not parameters;
     * they are different queries wearing the same syntax, and a window size in particular cannot
     * share state with another window size at all (ADR-032).
     */
    public static final ErrorCode PARAMETER_NOT_A_VALUE = new ErrorCode(2063, "SQL_PARAMETER_NOT_A_VALUE");

    /**
     * A statement that begins as one of the continuous-query statements ({@code CREATE CONTINUOUS
     * QUERY}, {@code DROP}/{@code PAUSE}/{@code RESUME CONTINUOUS QUERY}, {@code SHOW CONTINUOUS
     * QUERIES}) and does not have that statement's shape.
     *
     * <p>Refused by {@link ContinuousStatements} with the shape it expected and where it stopped
     * reading, and never handed to Calcite: Calcite has never heard of these statements and would
     * answer with a syntax error about the wrong word.
     */
    public static final ErrorCode STATEMENT_MALFORMED = new ErrorCode(2070, "SQL_STATEMENT_MALFORMED");

    /**
     * {@code KEYED BY} names a column the query does not produce, or names one twice.
     *
     * <p>Key columns are named as the {@code SELECT} list names them, and resolved to output
     * ordinals by planning the query -- so a name is checked against the columns the view will
     * actually have, not against the text.
     */
    public static final ErrorCode KEY_COLUMN_UNKNOWN = new ErrorCode(2071, "SQL_KEY_COLUMN_UNKNOWN");

    /**
     * A clause the design describes for {@code CREATE CONTINUOUS QUERY} and this engine does not
     * build: {@code EMIT CHANGES WITH (...)}, or a {@code SERVE AS VIEW} whose name differs from the
     * query's.
     *
     * <p>Refused by name rather than ignored. Ignoring {@code 'allowed.lateness' = '30s'} in an
     * {@code EMIT CHANGES WITH} list would drop late rows a caller asked to be waited for.
     *
     * <p>{@code OR REPLACE} was on this list until ADR-046, {@code INDEXED BY ... RANGE} and a
     * {@code WITH (...)} list on a plain {@code CREATE} until B8. What replaced them is not a
     * looser refusal: a {@code RANGE} this engine cannot order is {@link #RANGE_NOT_ORDERED}, and
     * an option that does not exist is {@code PRV-8011}, each by name.
     */
    public static final ErrorCode CLAUSE_NOT_BUILT = new ErrorCode(2072, "SQL_CLAUSE_NOT_BUILT");

    /**
     * {@code RANGE (column)} over a column this engine has no total order for.
     *
     * <p>An ordered index needs one, and three of the types on offer do not have one here that
     * would not be a guess: text needs a collation (which is why {@code <} on text is refused at
     * all, see {@code Predicate.CompareString}), {@code FLOAT}'s comparison is IEEE 754 and
     * {@code NaN} is ordered against nothing (TY-3), and {@code DECIMAL}'s {@code compareTo}
     * disagrees with its {@code equals}, so {@code 1.0} and {@code 1.00} would be one entry in the
     * index and two rows in the view.
     *
     * <p>Refused at registration, against the columns the view will actually have, rather than at
     * the first read that wanted the index.
     */
    public static final ErrorCode RANGE_NOT_ORDERED = new ErrorCode(2073, "SQL_RANGE_NOT_ORDERED");

    private SqlErrors() {}
}
