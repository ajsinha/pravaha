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

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Recognises the statements that register and manage continuous queries, before Calcite sees
 * anything.
 *
 * <p>The canonical grammar -- keywords in any case, names plain or double-quoted:
 *
 * <pre>
 * CREATE CONTINUOUS QUERY name
 *     KEYED BY (column [, column]...)
 *     [WRITING TO sink]
 *     [RETAIN FOR duration | RETAIN FOREVER]
 *     AS select
 *
 * DROP   CONTINUOUS QUERY name
 * PAUSE  CONTINUOUS QUERY name
 * RESUME CONTINUOUS QUERY name
 * SHOW   CONTINUOUS QUERIES
 * </pre>
 *
 * <p>The clauses before {@code AS} may come in any order, each once. A duration is an ISO-8601
 * duration, bare or quoted ({@code PT24H}, {@code 'P7D'}), or an interval literal in one unit
 * ({@code INTERVAL '24' HOUR}). One trailing semicolon is allowed. The design's spellings (section
 * 11.2) are accepted as aliases where they mean the same thing: {@code INTO sink} for {@code WRITING
 * TO sink}, {@code INDEXED BY (...)} for {@code KEYED BY (...)}, {@code SERVE AS VIEW name} when it
 * names the query itself, and a trailing {@code EMIT CHANGES} -- which every continuous query does.
 *
 * <p><strong>Why a recognizer and not a Calcite parser extension.</strong> The design planned these
 * as Freemarker/JavaCC extensions. That means building and maintaining a fork of Calcite's grammar
 * for five statements whose only non-trivial part -- the {@code SELECT} -- Calcite already parses. So
 * the head of the statement is read here, strictly, and the {@code SELECT} is handed to the planner
 * exactly as a registration argument would be. A statement that starts as one of these and goes
 * wrong is refused here with the shape that was expected ({@code PRV-2070}); it never reaches
 * Calcite, which would answer with a syntax error about a word it has never heard of.
 *
 * <p>Recognition is by the leading words only -- {@code CREATE CONTINUOUS}, {@code DROP
 * CONTINUOUS}, {@code SHOW CONTINUOUS}, {@code PAUSE}, {@code RESUME} (and {@code CREATE OR REPLACE
 * CONTINUOUS}, to refuse it by name). Anything else is ordinary SQL and this class says nothing
 * about it.
 */
public final class ContinuousStatements {

    static final String CREATE_SHAPE = "CREATE CONTINUOUS QUERY <name> KEYED BY (<column>, ...) "
            + "[WRITING TO <sink>] [RETAIN FOR <duration> | RETAIN FOREVER] AS <select>";
    static final String DROP_SHAPE = "DROP CONTINUOUS QUERY <name>";
    static final String PAUSE_SHAPE = "PAUSE CONTINUOUS QUERY <name>";
    static final String RESUME_SHAPE = "RESUME CONTINUOUS QUERY <name>";
    static final String SHOW_SHAPE = "SHOW CONTINUOUS QUERIES";

    private ContinuousStatements() {}

    /**
     * Whether {@code sql} begins as one of these statements, well-formed or not. Never throws.
     *
     * <p>What a read-only surface asks, so that it can refuse the statement by name rather than
     * hand it to a planner that would call it a syntax error.
     */
    public static boolean isContinuousStatement(String sql) {
        return sql != null && shapeOf(sql).isPresent();
    }

    /**
     * The statement {@code sql} is, or empty when it is not one of these.
     *
     * @throws PravahaException {@code PRV-2070} when it begins as one of these statements and does
     *     not have its shape; {@code PRV-2072} when it uses a clause the design describes and this
     *     engine does not build
     */
    public static Optional<ContinuousStatement> recognize(String sql) {
        if (sql == null) {
            return Optional.empty();
        }
        Optional<String> shape = shapeOf(sql);
        if (shape.isEmpty()) {
            return Optional.empty();
        }
        Reader reader = new Reader(sql, shape.get());
        try {
            return Optional.of(reader.statement());
        } catch (StatementLexer.Unreadable e) {
            throw reader.malformed(e.offset, e.getMessage());
        }
    }

    /** The shape of the statement {@code sql} starts as, from its leading words alone. */
    private static Optional<String> shapeOf(String sql) {
        List<StatementLexer.Token> head = new ArrayList<>();
        StatementLexer lexer = new StatementLexer(sql);
        try {
            for (int i = 0; i < 4; i++) {
                head.add(lexer.next());
            }
        } catch (StatementLexer.Unreadable e) {
            // Whatever could be read is enough to decide on; the rest is reported by whoever owns it.
        }
        String first = wordAt(head, 0);
        String second = wordAt(head, 1);
        return Optional.ofNullable(
                switch (first) {
                    case "CREATE" ->
                        "CONTINUOUS".equals(second)
                                        || ("OR".equals(second)
                                                && "REPLACE".equals(wordAt(head, 2))
                                                && "CONTINUOUS".equals(wordAt(head, 3)))
                                ? CREATE_SHAPE
                                : null;
                    case "DROP" -> "CONTINUOUS".equals(second) ? DROP_SHAPE : null;
                    case "SHOW" -> "CONTINUOUS".equals(second) ? SHOW_SHAPE : null;
                    case "PAUSE" -> PAUSE_SHAPE;
                    case "RESUME" -> RESUME_SHAPE;
                    default -> null;
                });
    }

    private static String wordAt(List<StatementLexer.Token> tokens, int index) {
        if (index >= tokens.size() || tokens.get(index).kind() != StatementLexer.Kind.WORD) {
            return "";
        }
        return tokens.get(index).text().toUpperCase(Locale.ROOT);
    }

    /** Reads one statement whose shape is already known, refusing anything else with that shape. */
    private static final class Reader {

        private final String sql;
        private final String shape;
        private final StatementLexer lexer;
        private StatementLexer.Token peeked;

        Reader(String sql, String shape) {
            this.sql = sql;
            this.shape = shape;
            this.lexer = new StatementLexer(sql);
        }

        ContinuousStatement statement() {
            StatementLexer.Token first = next();
            return switch (first.text().toUpperCase(Locale.ROOT)) {
                case "CREATE" -> create();
                case "DROP" -> new ContinuousStatement.Drop(named());
                case "PAUSE" -> new ContinuousStatement.Pause(named());
                case "RESUME" -> new ContinuousStatement.Resume(named());
                default -> show();
            };
        }

        private String named() {
            keyword("CONTINUOUS");
            keyword("QUERY");
            String name = identifier("the query's name");
            end();
            return name;
        }

        private ContinuousStatement show() {
            keyword("CONTINUOUS");
            keyword("QUERIES");
            end();
            return new ContinuousStatement.Show();
        }

        private ContinuousStatement create() {
            if (isWord(peek(), "OR")) {
                StatementLexer.Token or = next();
                throw notBuilt(
                        or,
                        "CREATE OR REPLACE is not built: replacing a running query would take its answers "
                                + "away from whoever is reading them. DROP CONTINUOUS QUERY it first, then "
                                + "CREATE it again.");
            }
            keyword("CONTINUOUS");
            keyword("QUERY");
            String name = identifier("the query's name");
            List<String> keys = null;
            String sink = null;
            ContinuousStatement.Retain retain = null;
            boolean served = false;
            while (true) {
                StatementLexer.Token clause = next();
                String word = clause.kind() == StatementLexer.Kind.WORD
                        ? clause.text().toUpperCase(Locale.ROOT)
                        : "";
                switch (word) {
                    case "AS" -> {
                        if (keys == null) {
                            throw malformed(
                                    clause.start(),
                                    "query '" + name + "' has no key: KEYED BY (<column>, ...) must come before "
                                            + "AS. A view with no key is a log, and a point read against it has "
                                            + "nothing to look up");
                        }
                        String select = select(clause);
                        return new ContinuousStatement.Create(
                                name, keys, Optional.ofNullable(sink), Optional.ofNullable(retain), select);
                    }
                    case "KEYED", "INDEXED" -> {
                        once(keys == null, clause, "the key");
                        keyword("BY");
                        keys = columns();
                        if (isWord(peek(), "RANGE")) {
                            throw notBuilt(
                                    next(),
                                    "INDEXED BY ... RANGE (...) is not built: a view is read by its whole key. "
                                            + "Put the range column in KEYED BY and read a range with WHERE.");
                        }
                    }
                    case "WRITING", "INTO" -> {
                        once(sink == null, clause, "the sink");
                        if (word.equals("WRITING")) {
                            keyword("TO");
                        }
                        sink = identifier("the sink's name");
                    }
                    case "RETAIN" -> {
                        once(retain == null, clause, "the retention");
                        retain = retention();
                    }
                    case "SERVE" -> {
                        once(!served, clause, "SERVE AS VIEW");
                        keyword("AS");
                        keyword("VIEW");
                        StatementLexer.Token at = peek();
                        String view = identifier("the view's name");
                        if (!view.equals(name)) {
                            throw notBuilt(
                                    at,
                                    "SERVE AS VIEW '" + view + "' names a view other than the query '" + name
                                            + "'. Here a query and its view are one name -- the name clients put "
                                            + "in FROM -- so write CREATE CONTINUOUS QUERY " + view + " ...");
                        }
                        served = true;
                    }
                    case "WITH" ->
                        throw notBuilt(
                                clause,
                                "a WITH (...) option list is not built, and its options are refused rather than "
                                        + "ignored. Say the retention with RETAIN FOR <duration>; other options "
                                        + "have no equivalent yet.");
                    default ->
                        throw unexpected(
                                clause,
                                "KEYED BY (...), WRITING TO <sink>, RETAIN FOR <duration>, "
                                        + "RETAIN FOREVER or AS <select>");
                }
            }
        }

        /** Everything after {@code AS}, less a trailing {@code EMIT CHANGES} and semicolon. */
        private String select(StatementLexer.Token as) {
            int from = as.end();
            int to = sql.length();
            List<StatementLexer.Token> body = new ArrayList<>();
            try {
                for (StatementLexer.Token token = next(); token.kind() != StatementLexer.Kind.END; token = next()) {
                    body.add(token);
                }
            } catch (StatementLexer.Unreadable e) {
                // An unterminated string or comment inside the SELECT is the planner's to report, with
                // its own position; the text goes to it unchanged.
                return sql.substring(from).strip();
            }
            if (!body.isEmpty() && body.get(body.size() - 1).text().equals(";")) {
                to = body.get(body.size() - 1).start();
                body.remove(body.size() - 1);
            }
            for (int i = 0; i + 1 < body.size(); i++) {
                if (isWord(body.get(i), "EMIT") && isWord(body.get(i + 1), "CHANGES")) {
                    if (i + 2 == body.size()) {
                        to = body.get(i).start();
                        body = body.subList(0, i);
                        break;
                    }
                    if (isWord(body.get(i + 2), "WITH")) {
                        throw notBuilt(
                                body.get(i + 2),
                                "EMIT CHANGES WITH (...) is not built, and its options are refused rather than "
                                        + "ignored. Every continuous query emits its changes; drop the WITH list, "
                                        + "and say a retention with RETAIN FOR <duration> before AS.");
                    }
                }
            }
            if (body.isEmpty()) {
                throw malformed(as.end(), "AS must be followed by the query's SELECT");
            }
            StatementLexer.Token first = body.get(0);
            if (!isWord(first, "SELECT")
                    && !isWord(first, "WITH")
                    && !first.text().equals("(")) {
                throw unexpected(first, "the query's SELECT after AS");
            }
            return sql.substring(from, to).strip();
        }

        private List<String> columns() {
            symbol("(");
            List<String> columns = new ArrayList<>();
            while (true) {
                columns.add(identifier("a key column's name"));
                StatementLexer.Token after = next();
                if (after.text().equals(")") && after.kind() == StatementLexer.Kind.SYMBOL) {
                    return columns;
                }
                if (!after.text().equals(",") || after.kind() != StatementLexer.Kind.SYMBOL) {
                    throw unexpected(after, "',' or ')' in the key's column list");
                }
            }
        }

        private ContinuousStatement.Retain retention() {
            StatementLexer.Token how = next();
            if (isWord(how, "FOREVER")) {
                return ContinuousStatement.Retain.forever();
            }
            if (!isWord(how, "FOR")) {
                throw unexpected(how, "FOR <duration> or FOREVER after RETAIN");
            }
            StatementLexer.Token value = next();
            if (isWord(value, "INTERVAL")) {
                return interval();
            }
            if (value.kind() == StatementLexer.Kind.STRING
                    || (value.kind() == StatementLexer.Kind.WORD
                            && value.text().toUpperCase(Locale.ROOT).startsWith("P"))) {
                return iso(value);
            }
            throw unexpected(value, "a duration: ISO-8601 such as PT24H or 'P7D', or INTERVAL '24' HOUR");
        }

        private ContinuousStatement.Retain iso(StatementLexer.Token value) {
            try {
                Duration age = Duration.parse(value.text().strip().toUpperCase(Locale.ROOT));
                return positive(value, age);
            } catch (DateTimeParseException e) {
                throw malformed(
                        value.start(),
                        "'" + value.text() + "' is not an ISO-8601 duration. Write PT24H for a day of hours, P7D "
                                + "for a week, PT30M for half an hour -- or INTERVAL '24' HOUR");
            }
        }

        private ContinuousStatement.Retain interval() {
            StatementLexer.Token amount = next();
            if (amount.kind() != StatementLexer.Kind.STRING
                    || !amount.text().strip().matches("[0-9]{1,9}")) {
                throw unexpected(amount, "a whole number in quotes, as in INTERVAL '24' HOUR");
            }
            StatementLexer.Token unit = next();
            long count = Long.parseLong(amount.text().strip());
            String name = unit.kind() == StatementLexer.Kind.WORD ? unit.text().toUpperCase(Locale.ROOT) : "";
            Duration age =
                    switch (name) {
                        case "SECOND", "SECONDS" -> Duration.ofSeconds(count);
                        case "MINUTE", "MINUTES" -> Duration.ofMinutes(count);
                        case "HOUR", "HOURS" -> Duration.ofHours(count);
                        case "DAY", "DAYS" -> Duration.ofDays(count);
                        case "WEEK", "WEEKS" -> Duration.ofDays(count * 7);
                        case "MONTH", "MONTHS", "YEAR", "YEARS" ->
                            throw malformed(
                                    unit.start(),
                                    "a " + name.toLowerCase(Locale.ROOT).replaceAll("s$", "")
                                            + " is not a fixed length of event time, so a view cannot be told to "
                                            + "keep one. Give it in days: INTERVAL '30' DAY");
                        default -> throw unexpected(unit, "SECOND, MINUTE, HOUR, DAY or WEEK after the interval");
                    };
            return positive(amount, age);
        }

        private ContinuousStatement.Retain positive(StatementLexer.Token at, Duration age) {
            if (age.isZero() || age.isNegative()) {
                throw malformed(at.start(), "a retention must be a positive age of event time, not " + age);
            }
            return ContinuousStatement.Retain.of(age);
        }

        private void once(boolean first, StatementLexer.Token clause, String what) {
            if (!first) {
                throw malformed(clause.start(), what + " is given twice; each clause may appear once");
            }
        }

        private String identifier(String what) {
            StatementLexer.Token token = next();
            if (token.kind() == StatementLexer.Kind.WORD || token.kind() == StatementLexer.Kind.QUOTED) {
                if (token.text().isEmpty()) {
                    throw malformed(token.start(), what + " is an empty quoted name");
                }
                return token.text();
            }
            throw unexpected(token, what);
        }

        private void keyword(String word) {
            StatementLexer.Token token = next();
            if (!isWord(token, word)) {
                throw unexpected(token, word);
            }
        }

        private void symbol(String text) {
            StatementLexer.Token token = next();
            if (token.kind() != StatementLexer.Kind.SYMBOL || !token.text().equals(text)) {
                throw unexpected(token, "'" + text + "'");
            }
        }

        private void end() {
            StatementLexer.Token token = next();
            if (token.kind() == StatementLexer.Kind.SYMBOL && token.text().equals(";")) {
                token = next();
            }
            if (token.kind() != StatementLexer.Kind.END) {
                throw unexpected(token, "the end of the statement");
            }
        }

        private StatementLexer.Token next() {
            if (peeked != null) {
                StatementLexer.Token token = peeked;
                peeked = null;
                return token;
            }
            return lexer.next();
        }

        private StatementLexer.Token peek() {
            if (peeked == null) {
                peeked = lexer.next();
            }
            return peeked;
        }

        private static boolean isWord(StatementLexer.Token token, String word) {
            return token.kind() == StatementLexer.Kind.WORD && token.text().equalsIgnoreCase(word);
        }

        private PravahaException unexpected(StatementLexer.Token found, String expected) {
            String what =
                    found.kind() == StatementLexer.Kind.END ? "the end of the statement" : "'" + found.text() + "'";
            return malformed(found.start(), "expected " + expected + ", found " + what);
        }

        PravahaException malformed(int offset, String problem) {
            return refusal(SqlErrors.STATEMENT_MALFORMED, offset, problem);
        }

        private PravahaException notBuilt(StatementLexer.Token at, String problem) {
            return refusal(SqlErrors.CLAUSE_NOT_BUILT, at.start(), problem);
        }

        private PravahaException refusal(ErrorCode code, int offset, String problem) {
            int line = 1;
            int column = 1;
            for (int i = 0; i < Math.min(offset, sql.length()); i++) {
                if (sql.charAt(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            return new PravahaException(
                    code,
                    problem + " (at line " + line + ", column " + column + "). The statement's shape is: " + shape);
        }
    }
}
