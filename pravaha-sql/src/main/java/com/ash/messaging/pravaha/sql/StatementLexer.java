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

/**
 * SQL's lexical structure, and nothing of its grammar: enough for {@link ContinuousStatements} to
 * read a statement's head and to know whether a trailing {@code EMIT CHANGES} is really at the end
 * or inside a string or a comment.
 *
 * <p>Words, double-quoted identifiers ({@code ""} escapes a quote), string literals ({@code ''}
 * escapes one), numbers and single-character symbols. Whitespace, {@code --} line comments and
 * {@code /* *\/} block comments separate tokens and are otherwise skipped.
 */
final class StatementLexer {

    enum Kind {
        WORD,
        QUOTED,
        STRING,
        NUMBER,
        SYMBOL,
        END
    }

    /**
     * One token. {@code text} is the word as written, or a quoted token's contents with its escapes
     * undone; {@code start} and {@code end} are offsets into the statement.
     */
    record Token(Kind kind, String text, int start, int end) {}

    /** Text that cannot be split into tokens: an unterminated string, identifier or comment. */
    static final class Unreadable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int offset;

        Unreadable(int offset, String message) {
            super(message, null, false, false);
            this.offset = offset;
        }
    }

    private final String sql;
    private int position;

    StatementLexer(String sql) {
        this.sql = sql;
    }

    Token next() {
        skipSpaceAndComments();
        int start = position;
        if (position >= sql.length()) {
            return new Token(Kind.END, "", start, start);
        }
        int c = sql.codePointAt(position);
        if (c == '\'') {
            return new Token(Kind.STRING, quoted('\'', "a string"), start, position);
        }
        if (c == '"') {
            return new Token(Kind.QUOTED, quoted('"', "a quoted name"), start, position);
        }
        if (Character.isLetter(c) || c == '_') {
            while (position < sql.length()) {
                int d = sql.codePointAt(position);
                if (!Character.isLetterOrDigit(d) && d != '_' && d != '$') {
                    break;
                }
                position += Character.charCount(d);
            }
            return new Token(Kind.WORD, sql.substring(start, position), start, position);
        }
        if (Character.isDigit(c)) {
            while (position < sql.length()
                    && (Character.isDigit(sql.charAt(position)) || sql.charAt(position) == '.')) {
                position++;
            }
            return new Token(Kind.NUMBER, sql.substring(start, position), start, position);
        }
        position += Character.charCount(c);
        return new Token(Kind.SYMBOL, sql.substring(start, position), start, position);
    }

    private String quoted(char quote, String what) {
        int start = position;
        position++;
        StringBuilder text = new StringBuilder();
        while (true) {
            if (position >= sql.length()) {
                throw new Unreadable(start, what + " is opened with " + quote + " and never closed");
            }
            char c = sql.charAt(position++);
            if (c == quote) {
                if (position < sql.length() && sql.charAt(position) == quote) {
                    text.append(quote);
                    position++;
                    continue;
                }
                return text.toString();
            }
            text.append(c);
        }
    }

    private void skipSpaceAndComments() {
        while (position < sql.length()) {
            char c = sql.charAt(position);
            if (Character.isWhitespace(c)) {
                position++;
            } else if (sql.startsWith("--", position)) {
                int newline = sql.indexOf('\n', position);
                position = newline < 0 ? sql.length() : newline + 1;
            } else if (sql.startsWith("/*", position)) {
                int close = sql.indexOf("*/", position + 2);
                if (close < 0) {
                    throw new Unreadable(position, "a comment is opened with /* and never closed");
                }
                position = close + 2;
            } else {
                return;
            }
        }
    }
}
