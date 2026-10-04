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
package com.ash.messaging.pravaha.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;

/**
 * A row filter's predicate or a mask's expression, as a policy holds it (ADR-059 §4): checked when it is
 * created, and bound to a principal when it is applied.
 *
 * <p><strong>What an expression may say.</strong> Columns of the object it is bound to, literals, the
 * operators, a fixed list of pure scalar functions ({@link #FUNCTIONS}), {@code CASE} and {@code CAST},
 * and three session functions:
 *
 * <ul>
 *   <li>{@code session_attribute('claim')} -- the principal's claim, as a string; a principal without it
 *       is refused with {@code PRV-7039} rather than shown a guess;
 *   <li>{@code current_user()} -- the principal's id;
 *   <li>{@code is_member('role')} -- whether the principal holds the role.
 * </ul>
 *
 * <p>Refused at {@code CREATE} with {@code PRV-7038}, each named: a subquery (a policy is an expression
 * over the row in front of it, and a subquery reads other rows), a non-deterministic function (a filter
 * that answers differently for the same row twice is no boundary), and any function not on the list --
 * a function the engine cannot see into could carry the value somewhere, which is ADR-031's "no function
 * that could leak". Which columns exist is not known until the policy is bound to an object; the planner
 * checks that then. Whether a filter restricts anything (ADR-031, TAUTOFILTER-1) is decided over the
 * compiled predicate by the SQL module's {@code FilterVacuity}: at binding for an expression that reads
 * nothing about the session, and for one that does, each time it is bound to a principal.
 *
 * <p>Held and bound as tokens, never as text spliced into text: the claim a principal carries becomes one
 * SQL string literal with its quotes doubled, so no claim value can end the literal and add a clause.
 */
public final class PolicyExpression {

    /** The pure scalar functions a policy may call. */
    static final Set<String> FUNCTIONS = Set.of(
            "UPPER",
            "LOWER",
            "TRIM",
            "SUBSTRING",
            "SUBSTR",
            "CHAR_LENGTH",
            "CHARACTER_LENGTH",
            "LENGTH",
            "CONCAT",
            "COALESCE",
            "NULLIF",
            "CAST",
            "ABS",
            "ROUND",
            "FLOOR",
            "CEIL",
            "CEILING",
            "MOD",
            "LEFT",
            "RIGHT",
            "REPLACE",
            "POSITION",
            "REGEXP_EXTRACT",
            "SPLIT_INDEX",
            "SIGN");

    /** Functions refused by name because they do not answer the same way twice. */
    static final Set<String> NON_DETERMINISTIC = Set.of(
            "RAND",
            "RANDOM",
            "RAND_INTEGER",
            "UUID",
            "NOW",
            "CURRENT_TIMESTAMP",
            "CURRENT_DATE",
            "CURRENT_TIME",
            "LOCALTIME",
            "LOCALTIMESTAMP",
            "PROCTIME",
            "CURRENT_ROW_TIMESTAMP",
            "UNIX_TIMESTAMP");

    /** Words that would begin a query inside the expression. */
    static final Set<String> SUBQUERY = Set.of(
            "SELECT", "VALUES", "WITH", "TABLE", "LATERAL", "UNNEST", "EXISTS", "OVER", "WINDOW", "MATCH_RECOGNIZE");

    /** Words that are not column names: SQL's keywords and type names. */
    static final Set<String> KEYWORDS = Set.of(
            "AND",
            "OR",
            "NOT",
            "IS",
            "NULL",
            "TRUE",
            "FALSE",
            "UNKNOWN",
            "IN",
            "LIKE",
            "ILIKE",
            "SIMILAR",
            "TO",
            "ESCAPE",
            "BETWEEN",
            "SYMMETRIC",
            "CASE",
            "WHEN",
            "THEN",
            "ELSE",
            "END",
            "AS",
            "FROM",
            "FOR",
            "BOTH",
            "LEADING",
            "TRAILING",
            "DISTINCT",
            "INTERVAL",
            "YEAR",
            "MONTH",
            "DAY",
            "HOUR",
            "MINUTE",
            "SECOND",
            "VARCHAR",
            "CHAR",
            "CHARACTER",
            "VARYING",
            "STRING",
            "BIGINT",
            "INTEGER",
            "INT",
            "SMALLINT",
            "TINYINT",
            "DOUBLE",
            "PRECISION",
            "FLOAT",
            "REAL",
            "DECIMAL",
            "NUMERIC",
            "BOOLEAN",
            "DATE",
            "TIME",
            "TIMESTAMP",
            "BYTES",
            "VARBINARY",
            "BINARY",
            "WITHOUT",
            "ZONE",
            "LOCAL");

    /** Words that may stand before an opening parenthesis without being a function call. */
    private static final Set<String> BEFORE_PARENTHESIS = Set.of(
            "IN",
            "AND",
            "OR",
            "NOT",
            "WHEN",
            "THEN",
            "ELSE",
            "CASE",
            "BETWEEN",
            "LIKE",
            "IS",
            "AS",
            "VARCHAR",
            "CHAR",
            "CHARACTER",
            "DECIMAL",
            "NUMERIC",
            "VARBINARY",
            "BINARY",
            "FROM",
            "FOR",
            "ESCAPE",
            "TIMESTAMP",
            "TIME");

    static final String SESSION_ATTRIBUTE = "SESSION_ATTRIBUTE";
    static final String CURRENT_USER = "CURRENT_USER";
    static final String IS_MEMBER = "IS_MEMBER";

    /** The longest expression a policy holds. */
    static final int MAX_LENGTH = 4096;

    /** What {@code session_attribute} is bound to when a policy is checked against an object: castable to a number. */
    static final String PROBE_VALUE = "0";

    private final String text;
    private final List<Token> tokens;

    private PolicyExpression(String text, List<Token> tokens) {
        this.text = text;
        this.tokens = List.copyOf(tokens);
    }

    /**
     * {@code text} as a policy expression, checked.
     *
     * @param maskedColumn for a mask, the one column it may name; null for a row filter
     * @throws PravahaException {@code PRV-7038} naming what is wrong
     */
    public static PolicyExpression of(String text, String maskedColumn) {
        if (text == null || text.isBlank()) {
            throw invalid("a policy needs an expression after AS");
        }
        if (text.length() > MAX_LENGTH) {
            throw invalid("a policy's expression is at most " + MAX_LENGTH + " characters");
        }
        List<Token> tokens = lex(text);
        check(tokens, maskedColumn);
        return new PolicyExpression(text.strip(), tokens);
    }

    /** The expression as it was written. */
    public String text() {
        return text;
    }

    /** The expression in one canonical spelling, session functions left in place. */
    public String canonical() {
        return render(null, false);
    }

    /**
     * The expression bound to {@code principal}: plain SQL over the object's columns.
     *
     * @throws PravahaException {@code PRV-7039} when it reads a claim the principal does not carry
     */
    public String boundTo(Principal principal) {
        return render(principal, false);
    }

    /**
     * The expression bound to nobody in particular -- claims {@code '0'}, memberships FALSE, the user
     * {@code ''} -- for checking it plans against an object's columns.
     */
    public String probe() {
        return render(null, true);
    }

    /** Whether it reads anything about the principal. */
    public boolean readsSession() {
        return tokens.stream()
                .anyMatch(t -> t.kind == Kind.WORD
                        && Set.of(SESSION_ATTRIBUTE, CURRENT_USER, IS_MEMBER).contains(upper(t.text)));
    }

    @Override
    public String toString() {
        return text;
    }

    // ---------------------------------------------------------------------------- checking

    private static void check(List<Token> tokens, String maskedColumn) {
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind == Kind.SYMBOL) {
                switch (token.text) {
                    case "(" -> depth++;
                    case ")" -> {
                        if (--depth < 0) {
                            throw invalid("a ')' closes nothing");
                        }
                    }
                    case ";" -> throw invalid("a policy is one expression; ';' ends a statement");
                    case "?" -> throw invalid("a policy takes no parameters; its values come from the session");
                    default -> {
                        // an operator
                    }
                }
                continue;
            }
            if (token.kind != Kind.WORD) {
                if (token.kind == Kind.QUOTED && maskedColumn != null && !token.text.equals(maskedColumn)) {
                    throw invalid("a mask on " + maskedColumn + " may name only that column, and this one names \""
                            + token.text + "\"");
                }
                continue;
            }
            String word = upper(token.text);
            boolean call = i + 1 < tokens.size() && tokens.get(i + 1).isSymbol("(");
            if (SUBQUERY.contains(word)) {
                throw invalid("a policy is an expression over the row in front of it; '" + token.text
                        + "' would begin a query over other rows, and a subquery is refused");
            }
            if (NON_DETERMINISTIC.contains(word)) {
                throw invalid(token.text + " does not answer the same way twice, and a policy that could "
                        + "keep a row once and drop it the next time is no boundary");
            }
            switch (word) {
                case SESSION_ATTRIBUTE, IS_MEMBER -> {
                    requireSessionCall(tokens, i, true);
                    i += 3;
                    continue;
                }
                case CURRENT_USER -> {
                    requireSessionCall(tokens, i, false);
                    i += 2;
                    continue;
                }
                default -> {
                    // below
                }
            }
            if (call) {
                if (!FUNCTIONS.contains(word) && !BEFORE_PARENTHESIS.contains(word)) {
                    throw invalid(token.text + "() is not a function a policy may call: a policy may use only "
                            + "pure functions the engine can see into, since any other could carry the value "
                            + "elsewhere. Allowed: " + new java.util.TreeSet<>(FUNCTIONS)
                            + ", CASE, CAST, and session_attribute('claim'), current_user(), is_member('role')");
                }
                continue;
            }
            if (maskedColumn != null && !KEYWORDS.contains(word) && !token.text.equalsIgnoreCase(maskedColumn)) {
                throw invalid("a mask on " + maskedColumn + " may name only that column, and this one names "
                        + token.text + "; a mask that read another column would disclose it through this one");
            }
        }
        if (depth != 0) {
            throw invalid("a '(' is not closed");
        }
    }

    private static void requireSessionCall(List<Token> tokens, int at, boolean takesString) {
        String name = tokens.get(at).text;
        boolean shaped = takesString
                ? at + 3 < tokens.size()
                        && tokens.get(at + 1).isSymbol("(")
                        && tokens.get(at + 2).kind == Kind.STRING
                        && tokens.get(at + 3).isSymbol(")")
                : at + 2 < tokens.size()
                        && tokens.get(at + 1).isSymbol("(")
                        && tokens.get(at + 2).isSymbol(")");
        if (!shaped) {
            throw invalid(
                    takesString
                            ? name + " takes one quoted name: " + name.toLowerCase(Locale.ROOT) + "('region')"
                            : name + " is written " + name.toLowerCase(Locale.ROOT) + "(), with nothing between the "
                                    + "parentheses; without them SQL reads it as the connection's user, which is not "
                                    + "the principal");
        }
        if (takesString && tokens.get(at + 2).text.isBlank()) {
            throw invalid(name + " needs a name between the quotes");
        }
    }

    // ---------------------------------------------------------------------------- binding

    private String render(Principal principal, boolean probe) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            String piece;
            String word = token.kind == Kind.WORD ? upper(token.text) : "";
            if (word.equals(SESSION_ATTRIBUTE) && (principal != null || probe)) {
                String claim = tokens.get(i + 2).text;
                String value = probe
                        ? PROBE_VALUE
                        : principal
                                .claim(claim)
                                .orElseThrow(() -> new PravahaException(
                                        CatalogErrors.POLICY_CLAIM_MISSING,
                                        principal.id() + " carries no '" + claim
                                                + "' claim, and a policy that applies to "
                                                + "them reads it with session_attribute('" + claim
                                                + "'). Refused rather than "
                                                + "guessed: sign in with a credential that carries it, or have the policy "
                                                + "exempt them with EXCEPT ROLE"));
                piece = literal(value);
                i += 3;
            } else if (word.equals(IS_MEMBER) && (principal != null || probe)) {
                piece = !probe && principal.hasRole(tokens.get(i + 2).text) ? "TRUE" : "FALSE";
                i += 3;
            } else if (word.equals(CURRENT_USER) && (principal != null || probe)) {
                piece = literal(probe ? "" : principal.id());
                i += 2;
            } else {
                piece = switch (token.kind) {
                    case STRING -> literal(token.text);
                    case QUOTED -> '"' + token.text.replace("\"", "\"\"") + '"';
                    default -> token.text;
                };
            }
            // One space between every two tokens: a canonical spelling, so two policies that say the same
            // thing differently fingerprint the same, and nothing is glued to a bound literal.
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(piece);
        }
        return out.toString();
    }

    /** A SQL string literal holding {@code value}, its quotes doubled. */
    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String upper(String word) {
        return word.toUpperCase(Locale.ROOT);
    }

    private static PravahaException invalid(String why) {
        return new PravahaException(CatalogErrors.POLICY_INVALID, why);
    }

    // ------------------------------------------------------------------------------ lexing

    enum Kind {
        WORD,
        QUOTED,
        STRING,
        NUMBER,
        SYMBOL
    }

    record Token(Kind kind, String text) {
        boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }
    }

    private static final List<String> TWO_CHARACTER = List.of("<=", ">=", "<>", "!=", "||");

    static List<Token> lex(String text) {
        List<Token> tokens = new ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (Character.isWhitespace(c)) {
                at++;
            } else if (c == '-' && at + 1 < text.length() && text.charAt(at + 1) == '-') {
                while (at < text.length() && text.charAt(at) != '\n') {
                    at++;
                }
            } else if (Character.isLetter(c) || c == '_') {
                int start = at;
                while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
                    at++;
                }
                tokens.add(new Token(Kind.WORD, text.substring(start, at)));
            } else if (Character.isDigit(c)
                    || (c == '.' && at + 1 < text.length() && Character.isDigit(text.charAt(at + 1)))) {
                int start = at;
                while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '.')) {
                    at++;
                }
                tokens.add(new Token(Kind.NUMBER, text.substring(start, at)));
            } else if (c == '\'' || c == '"') {
                StringBuilder value = new StringBuilder();
                at++;
                boolean closed = false;
                while (at < text.length()) {
                    char d = text.charAt(at++);
                    if (d == c) {
                        if (at < text.length() && text.charAt(at) == c) {
                            value.append(c);
                            at++;
                            continue;
                        }
                        closed = true;
                        break;
                    }
                    value.append(d);
                }
                if (!closed) {
                    throw invalid("a quoted " + (c == '"' ? "name" : "string") + " is not closed");
                }
                tokens.add(new Token(c == '"' ? Kind.QUOTED : Kind.STRING, value.toString()));
            } else {
                String two = at + 1 < text.length() ? text.substring(at, at + 2) : "";
                if (TWO_CHARACTER.contains(two)) {
                    tokens.add(new Token(Kind.SYMBOL, two));
                    at += 2;
                } else if ("=<>+-*/%(),.;?".indexOf(c) >= 0) {
                    tokens.add(new Token(Kind.SYMBOL, String.valueOf(c)));
                    at++;
                } else {
                    throw invalid("'" + c + "' has no meaning in a policy expression");
                }
            }
        }
        if (tokens.isEmpty()) {
            throw invalid("a policy needs an expression after AS");
        }
        return tokens;
    }
}
