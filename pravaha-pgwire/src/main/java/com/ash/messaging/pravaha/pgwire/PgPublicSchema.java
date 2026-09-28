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

import java.util.Locale;

/**
 * {@code FROM public.view} read as {@code FROM view}.
 *
 * <p>This gateway tells every client that each view is a table in schema {@code public} -- {@code
 * \d}, {@code pg_namespace}, {@code information_schema.tables} and {@code current_schema()} all say
 * so -- and a client that believes it writes the name qualified: Power BI's generated SQL is always
 * {@code from "public"."view" "_"}. The engine's planner has no schemas, so it read {@code public} as
 * a view name and refused it ({@code PRV-2002}, "Object 'public' not found"). Dropping the qualifier
 * makes the name mean what the catalogue said it means, and nothing else: the view is then resolved,
 * authorized and audited exactly as if the client had written the bare name.
 *
 * <p>Only immediately after {@code FROM} or {@code JOIN}, and only {@code public} (unquoted in any
 * case, or quoted exactly {@code "public"}) followed by a dot. That is the one position where it can
 * only be a schema; a column or alias that happens to be called {@code public} anywhere else is left
 * alone. Any other schema name reaches the planner unchanged and is refused there, which is right:
 * there is no other schema.
 */
final class PgPublicSchema {

    private PgPublicSchema() {}

    /** {@code sql} with every {@code public.} qualifier after {@code FROM}/{@code JOIN} removed. */
    static String unqualify(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        String previousWord = "";
        int at = 0;
        while (at < sql.length()) {
            char c = sql.charAt(at);
            if (c == '\'') {
                int end = skipQuoted(sql, at, '\'');
                out.append(sql, at, end);
                previousWord = "";
                at = end;
                continue;
            }
            boolean afterFromOrJoin = previousWord.equals("from") || previousWord.equals("join");
            if (afterFromOrJoin) {
                int qualifierEnd = publicQualifierEnd(sql, at);
                if (qualifierEnd > 0) {
                    at = qualifierEnd; // drop `public.` / `"public".`; the name itself follows
                    previousWord = "";
                    continue;
                }
            }
            if (c == '"') {
                int end = skipQuoted(sql, at, '"');
                out.append(sql, at, end);
                previousWord = "";
                at = end;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = at;
                while (end < sql.length() && isIdentifierChar(sql.charAt(end))) {
                    end++;
                }
                out.append(sql, at, end);
                previousWord = sql.substring(at, end).toLowerCase(Locale.ROOT);
                at = end;
                continue;
            }
            if (!Character.isWhitespace(c)) {
                previousWord = "";
            }
            out.append(c);
            at++;
        }
        return out.toString();
    }

    /** The index just past {@code public.} or {@code "public".} at {@code at}, or -1. */
    private static int publicQualifierEnd(String sql, int at) {
        int end;
        if (sql.startsWith("\"public\"", at)) {
            end = at + "\"public\"".length();
        } else if (sql.regionMatches(true, at, "public", 0, "public".length())) {
            end = at + "public".length();
            if (end < sql.length() && isIdentifierChar(sql.charAt(end))) {
                return -1; // publications, public_view: a longer name, not the schema
            }
        } else {
            return -1;
        }
        if (end < sql.length() && sql.charAt(end) == '.') {
            return end + 1;
        }
        return -1;
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
