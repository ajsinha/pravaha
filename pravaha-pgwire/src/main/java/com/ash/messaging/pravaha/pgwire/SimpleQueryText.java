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

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The one thing this gateway has to know about SQL text: where a statement ends.
 *
 * <p>A simple-protocol {@code Query} message may legally carry several statements separated by
 * semicolons, and {@code psql} sends exactly what was typed -- including its trailing {@code ;}.
 * {@code ViewQuery} takes one statement and no trailing semicolon, so something has to reconcile
 * the two, and this is the smallest thing that can.
 *
 * <p><strong>It is not a parser and must not become one.</strong> It recognises the four places a
 * semicolon does not end a statement -- inside a string literal, inside a quoted identifier, inside
 * a line comment, inside a block comment -- and nothing else. That is enough to avoid the one
 * failure that matters, which is {@code SELECT * FROM t WHERE note = 'a;b'} being torn in half and
 * refused as two statements. Everything beyond that is the planner's job, and a second opinion
 * about SQL syntax living in a transport is how two answers to the same query come about.
 *
 * <p>Dollar-quoted strings ({@code $$...$$}) are not recognised, deliberately: they exist for
 * function bodies, this gateway has no write path and so no {@code CREATE FUNCTION}, and the code
 * to handle them would be untested surface. A dollar-quoted body containing a semicolon would be
 * refused as multi-statement -- refused, that is, and not silently mangled.
 */
final class SimpleQueryText {

    private SimpleQueryText() {}

    /**
     * The single statement in a {@code Query} payload, without its trailing semicolon.
     *
     * @return the statement, or an empty string for a query that is entirely whitespace, comments
     *     or semicolons -- which the protocol answers with {@code EmptyQueryResponse}, not an error
     * @throws PravahaException if the payload carries more than one statement
     */
    static String singleStatement(String query) {
        List<String> statements = split(query);
        if (statements.isEmpty()) {
            return "";
        }
        if (statements.size() > 1) {
            throw new PravahaException(
                    PgWireErrors.UNSUPPORTED_REQUEST,
                    "this Query message carries " + statements.size() + " statements; this gateway runs one at a "
                            + "time. Multi-statement queries are not implemented in this slice: each statement "
                            + "produces its own result set, and a client that got one merged answer for several "
                            + "questions would have no way to tell which rows answered which.");
        }
        return statements.get(0);
    }

    /** Statements, in order, with empty ones dropped. */
    private static List<String> split(String query) {
        List<String> statements = new ArrayList<>(1);
        StringBuilder current = new StringBuilder(query.length());
        int at = 0;
        while (at < query.length()) {
            char c = query.charAt(at);
            if (c == '\'' || c == '"') {
                at = copyQuoted(query, at, c, current);
            } else if (c == '-' && at + 1 < query.length() && query.charAt(at + 1) == '-') {
                at = skipLineComment(query, at);
            } else if (c == '/' && at + 1 < query.length() && query.charAt(at + 1) == '*') {
                at = skipBlockComment(query, at);
            } else if (c == ';') {
                add(statements, current);
                at++;
            } else {
                current.append(c);
                at++;
            }
        }
        add(statements, current);
        return statements;
    }

    private static void add(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    /**
     * Copies a quoted run, closing quote included.
     *
     * <p>A doubled quote inside is an escaped quote and not the end, in both SQL's string literals
     * ({@code 'it''s'}) and its quoted identifiers ({@code "say ""hi"""}). Getting that wrong ends
     * the statement in the middle of a value.
     *
     * <p>An unterminated quote copies to the end of the text and lets the planner produce the
     * syntax error. That refusal is the planner's to write: it knows the dialect and can point at
     * the offset, and a message from here would be a second, worse one.
     */
    private static int copyQuoted(String query, int start, char quote, StringBuilder out) {
        out.append(quote);
        int at = start + 1;
        while (at < query.length()) {
            char c = query.charAt(at);
            if (c == quote) {
                if (at + 1 < query.length() && query.charAt(at + 1) == quote) {
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

    /** Past the end of a {@code --} comment, which is the end of the line or of the text. */
    private static int skipLineComment(String query, int start) {
        int at = start + 2;
        while (at < query.length() && query.charAt(at) != '\n') {
            at++;
        }
        return at;
    }

    /**
     * Past the end of a block comment.
     *
     * <p>Nested, because PostgreSQL's block comments nest and SQL's specification says they do. A
     * non-nesting scan ends the comment at the first inner close and hands the planner the second
     * half of something the user meant as one comment.
     */
    private static int skipBlockComment(String query, int start) {
        int at = start + 2;
        int depth = 1;
        while (at < query.length() && depth > 0) {
            if (at + 1 < query.length() && query.charAt(at) == '/' && query.charAt(at + 1) == '*') {
                depth++;
                at += 2;
            } else if (at + 1 < query.length() && query.charAt(at) == '*' && query.charAt(at + 1) == '/') {
                depth--;
                at += 2;
            } else {
                at++;
            }
        }
        return at;
    }
}
