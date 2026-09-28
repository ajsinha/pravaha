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
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * A statement's trailing, top-level {@code LIMIT n}: taken off before planning, applied to the
 * answer after.
 *
 * <h2>Why the gateway does this, and only this</h2>
 *
 * <p>Power BI ends every DirectQuery statement with {@code LIMIT 1000001} -- its own "more than a
 * million rows" sentinel -- and its navigator previews a table with {@code LIMIT 4096}. The read
 * planner refuses {@code LIMIT} everywhere ({@code PRV-2020}), because over a continuous query a limit
 * names a first-n of rows that have not all arrived. Over a <em>read</em> the answer is bounded, and a
 * {@code LIMIT} with no {@code ORDER BY} beside it means exactly "any n rows of the answer": the first
 * n of the rows the read returned is one correct answer, whichever order those came in. So a
 * {@code LIMIT} that is the very last clause of the outermost {@code SELECT} is removed here, the rest
 * of the statement is planned, authorized and audited exactly as sent, and the result is cut to n.
 *
 * <p><strong>Nothing else.</strong> A {@code LIMIT} inside a derived table, one followed by {@code
 * OFFSET}, {@code FETCH FIRST}, and every {@code ORDER BY} are left in the statement, where the planner
 * refuses them by name as before: a limit inside a subquery changes what the outer query aggregates,
 * and an order is a total order this gateway would have to invent a collation for. A top-level {@code
 * ORDER BY} before the {@code LIMIT} still reaches the planner too -- stripping the {@code LIMIT}
 * cannot make an ordered statement run, it only makes the refusal name the {@code ORDER BY}, which is
 * the part a person has to change.
 */
final class PgTrailingLimit {

    /** No trailing limit: the whole answer. */
    static final long NONE = -1;

    private PgTrailingLimit() {}

    /** The statement without its trailing limit, and the limit; or empty if it has none this class takes. */
    record Split(String sql, long limit) {}

    private static final Pattern LIMIT_TAIL = Pattern.compile("\\s+(\\d{1,18})\\s*", Pattern.DOTALL);

    /**
     * Splits a trailing top-level {@code LIMIT n} off {@code sql} (a single statement, comments
     * already removed by {@link SimpleQueryText}).
     */
    static Optional<Split> split(String sql) {
        int lastLimit = -1;
        int depth = 0;
        int at = 0;
        while (at < sql.length()) {
            char c = sql.charAt(at);
            if (c == '\'' || c == '"') {
                at = skipQuoted(sql, at, c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && isKeywordAt(sql, at, "limit")) {
                lastLimit = at;
            }
            at++;
        }
        if (lastLimit < 0) {
            return Optional.empty();
        }
        Matcher tail = LIMIT_TAIL.matcher(sql.substring(lastLimit + "limit".length()));
        if (!tail.matches()) {
            return Optional.empty(); // LIMIT ALL, LIMIT $1, LIMIT n OFFSET m, ...: left for the planner
        }
        String rest = sql.substring(0, lastLimit).strip();
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Split(rest, Long.parseLong(tail.group(1))));
    }

    /** {@code result} cut to its first {@code limit} rows; unchanged for {@link #NONE}. */
    static ViewQuery.Result apply(ViewQuery.Result result, long limit) {
        if (limit == NONE || result.rows().size() <= limit) {
            return result;
        }
        List<Object[]> kept = List.copyOf(result.rows().subList(0, (int) limit));
        return new ViewQuery.Result(result.schema(), kept);
    }

    private static boolean isKeywordAt(String sql, int at, String keyword) {
        int end = at + keyword.length();
        if (end > sql.length()
                || !sql.substring(at, end).toLowerCase(Locale.ROOT).equals(keyword)) {
            return false;
        }
        boolean startsWord = at == 0 || !isIdentifierChar(sql.charAt(at - 1));
        boolean endsWord = end == sql.length() || !isIdentifierChar(sql.charAt(end));
        return startsWord && endsWord;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int at = start + 1;
        while (at < sql.length()) {
            if (sql.charAt(at) == quote) {
                if (at + 1 < sql.length() && sql.charAt(at + 1) == quote) {
                    at += 2;
                    continue;
                }
                return at + 1;
            }
            at++;
        }
        return at;
    }
}
