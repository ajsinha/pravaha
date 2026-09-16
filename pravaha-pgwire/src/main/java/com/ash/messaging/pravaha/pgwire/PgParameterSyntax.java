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
package com.ash.messaging.pravaha.pgwire;

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * {@code $1}, {@code $2}, ... to {@code ?}, the one syntax difference between PostgreSQL's
 * placeholders and Pravaha's own SQL dialect (ADR-032).
 *
 * <p>A driver's {@code Parse} message never carries a JDBC-style {@code ?}: {@code
 * PreparedStatement}'s own {@code ?} is a client-side convention that pgjdbc translates to {@code
 * $1}, {@code $2}, ... before the bytes ever leave the process, because that is what a real
 * PostgreSQL backend's grammar expects. {@code SqlPlanner} expects the other one -- {@code ?},
 * positional, one per occurrence -- so this is the seam between them.
 *
 * <h2>Why this is a text rewrite and not a second parameter syntax taught to the planner</h2>
 *
 * <p>Teaching Calcite a second placeholder spelling would duplicate {@code ParameterMetadata}'s
 * numbering logic in two dialects that then have to agree forever. A rewrite before the SQL ever
 * reaches the planner keeps there being exactly one placeholder syntax the planner has ever seen,
 * at the cost of this class having to say precisely when the rewrite is safe.
 *
 * <h2>Why reuse and gaps are refused rather than handled</h2>
 *
 * <p>{@code $1 = $1} is legal PostgreSQL and means "compare this column to one bound value, used
 * twice." Calcite's {@code ?} has no equivalent: each occurrence is its own placeholder, numbered by
 * position, with no way to say two of them share a value. A driver never generates this shape --
 * {@code PreparedStatement.setInt(1, x)} sets a slot, and reusing a value means calling {@code
 * setInt} again with a new slot number, which becomes a new, distinct {@code $n} -- so a real
 * driver's output is always {@code $1, $2, ..., $n} in first-occurrence order with no repeats and no
 * gaps. A statement that is not shaped that way is refused by name, naming {@link
 * PgWireErrors#UNSUPPORTED_PARAMETER_SYNTAX}, rather than rewritten into a statement with a
 * different, silently wrong meaning.
 */
final class PgParameterSyntax {

    private PgParameterSyntax() {}

    /** {@code sql} with every {@code $n} replaced, and how many there were. */
    private record Result(String sql, int parameterCount) {}

    /** What to write in place of the {@code n}th placeholder (1-based, in order of appearance). */
    @FunctionalInterface
    private interface Replacement {
        String forPlaceholder(int number);
    }

    /**
     * Rewrites every {@code $n} placeholder in {@code sql} to {@code ?}, outside string literals,
     * quoted identifiers and comments.
     *
     * @throws PravahaException {@link PgWireErrors#UNSUPPORTED_PARAMETER_SYNTAX} if the {@code $n}
     *     placeholders are not exactly {@code $1, $2, ..., $k} in order of first appearance
     */
    static String toQuestionMarks(String sql) {
        return rewrite(sql, number -> "?").sql();
    }

    /**
     * How many {@code $n} placeholders {@code sql} has -- used for a {@code pg_catalog} statement,
     * which never reaches {@link #toQuestionMarks} because it never reaches {@code SqlPlanner}, but
     * whose placeholder count {@code Describe} and {@code Bind} still need to know.
     *
     * @throws PravahaException {@link PgWireErrors#UNSUPPORTED_PARAMETER_SYNTAX}, on the same terms
     *     as {@link #toQuestionMarks}
     */
    static int placeholderCount(String sql) {
        return rewrite(sql, number -> "?").parameterCount();
    }

    /**
     * Replaces every {@code $n} placeholder in {@code sql} with the quoted, escaped SQL literal for
     * {@code values.get(n - 1)}.
     *
     * <p>What a {@code pg_catalog} statement's own placeholders need instead of {@link
     * #toQuestionMarks}: {@link PgCatalogShim} recognises a query by matching its text, including the
     * literal it filters on ({@code c.relname LIKE 'user_volume'}) -- it has no placeholder syntax of
     * its own to bind against, because it is a recognizer, not a second SQL engine (see its own
     * documentation). Substituting the bound value back into the text before handing it to {@code
     * PgCatalogShim} is what lets a driver's own metadata call, which does bind this filter as a real
     * parameter rather than inlining it, still be answered.
     *
     * @throws PravahaException {@link PgWireErrors#UNSUPPORTED_PARAMETER_SYNTAX}, on the same terms
     *     as {@link #toQuestionMarks}
     */
    static String substituteLiterals(String sql, List<String> values) {
        return rewrite(sql, number -> quoteLiteral(values.get(number - 1))).sql();
    }

    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static Result rewrite(String sql, Replacement replacement) {
        StringBuilder out = new StringBuilder(sql.length());
        int expectedNext = 1;
        int at = 0;
        while (at < sql.length()) {
            char c = sql.charAt(at);
            if (c == '\'' || c == '"') {
                at = copyQuoted(sql, at, c, out);
            } else if (c == '-' && at + 1 < sql.length() && sql.charAt(at + 1) == '-') {
                at = copyLineComment(sql, at, out);
            } else if (c == '/' && at + 1 < sql.length() && sql.charAt(at + 1) == '*') {
                at = copyBlockComment(sql, at, out);
            } else if (c == '$' && at + 1 < sql.length() && Character.isDigit(sql.charAt(at + 1))) {
                int end = at + 1;
                while (end < sql.length() && Character.isDigit(sql.charAt(end))) {
                    end++;
                }
                int number = Integer.parseInt(sql.substring(at + 1, end));
                if (number != expectedNext) {
                    throw new PravahaException(
                            PgWireErrors.UNSUPPORTED_PARAMETER_SYNTAX,
                            "this statement's placeholders are not $1, $2, ... in order: expected $" + expectedNext
                                    + " but found $" + number + ". A value used twice, or placeholders out of "
                                    + "order, cannot be rewritten without changing which value each one means -- "
                                    + "a driver-generated PreparedStatement never produces this shape, so a real "
                                    + "one reaching here is worth looking at directly: " + sql);
                }
                out.append(replacement.forPlaceholder(number));
                expectedNext++;
                at = end;
            } else {
                out.append(c);
                at++;
            }
        }
        return new Result(out.toString(), expectedNext - 1);
    }

    /** Copies a quoted run verbatim, closing quote included. Doubled quotes are the escape, not the end. */
    private static int copyQuoted(String sql, int start, char quote, StringBuilder out) {
        out.append(quote);
        int at = start + 1;
        while (at < sql.length()) {
            char c = sql.charAt(at);
            if (c == quote) {
                if (at + 1 < sql.length() && sql.charAt(at + 1) == quote) {
                    out.append(quote).append(quote);
                    at += 2;
                    continue;
                }
                out.append(quote);
                return at + 1;
            }
            out.append(c);
            at++;
        }
        return at;
    }

    private static int copyLineComment(String sql, int start, StringBuilder out) {
        int at = start;
        while (at < sql.length() && sql.charAt(at) != '\n') {
            out.append(sql.charAt(at));
            at++;
        }
        return at;
    }

    /** Nested, matching {@code SimpleQueryText}'s own block comment scan. */
    private static int copyBlockComment(String sql, int start, StringBuilder out) {
        int at = start;
        int depth = 0;
        do {
            if (at + 1 < sql.length() && sql.charAt(at) == '/' && sql.charAt(at + 1) == '*') {
                depth++;
                out.append("/*");
                at += 2;
            } else if (at + 1 < sql.length() && sql.charAt(at) == '*' && sql.charAt(at + 1) == '/') {
                depth--;
                out.append("*/");
                at += 2;
            } else if (at < sql.length()) {
                out.append(sql.charAt(at));
                at++;
            } else {
                break;
            }
        } while (depth > 0 && at < sql.length());
        return at;
    }
}
