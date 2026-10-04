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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Reads the alert statements (ADR-057) before Calcite sees anything, as {@link ContinuousStatements}
 * reads the continuous-query ones -- and for the same reason: a handful of statements whose only
 * expression is a condition this reader can hold whole.
 *
 * <pre>
 * CREATE ALERT [IF NOT EXISTS] name ON view
 *     [WHERE column op literal [AND column op literal]...]
 *     NOTIFY channel [, channel]...
 *     [WITH (option = value [, option = value]...)]
 * ALTER  ALERT name SET (option = value [, ...])
 * ALTER  ALERT name NOTIFY channel [, channel]...
 * DROP   ALERT [IF EXISTS] name
 * PAUSE  ALERT name
 * RESUME ALERT name
 * SNOOZE ALERT name FOR duration
 * ACK    ALERT name
 * SHOW   ALERTS
 * </pre>
 *
 * <p><strong>The condition is deliberately not a language.</strong> Comparisons of the view's own
 * columns with literals ({@code = != <> < <= > >=}, {@code IS [NOT] NULL}), joined by {@code AND}.
 * Anything richer -- {@code OR}, arithmetic, a function, one column against another -- is a question,
 * and a question belongs in a continuous query the alert then watches (ADR-056): the planner can reason
 * about it, the cost is visible, and the answer can be read by everyone rather than only by the alert.
 * {@code OR} is refused by name and says so.
 *
 * <p>{@code ALTER ALERT name SET TAGS}, {@code UNSET TAGS} and {@code OWNER TO} are the catalogue's
 * (ADR-059) and are not recognised here.
 */
public final class AlertStatements {

    static final String CREATE_SHAPE = "CREATE ALERT [IF NOT EXISTS] <name> ON <view> [WHERE <column> <op> <literal> "
            + "[AND ...]] NOTIFY <channel> [, <channel>...] [WITH (<option> = <value>, ...)]";
    static final String ALTER_SHAPE =
            "ALTER ALERT <name> SET (<option> = <value>, ...) | ALTER ALERT <name> NOTIFY <channel> [, ...]";
    static final String DROP_SHAPE = "DROP ALERT [IF EXISTS] <name>";
    static final String PAUSE_SHAPE = "PAUSE ALERT <name>";
    static final String RESUME_SHAPE = "RESUME ALERT <name>";
    static final String SNOOZE_SHAPE = "SNOOZE ALERT <name> FOR <duration>";
    static final String ACK_SHAPE = "ACK ALERT <name>";
    static final String SHOW_SHAPE = "SHOW ALERTS";

    private AlertStatements() {}

    /** Whether {@code sql} begins as an alert statement, well-formed or not. Never throws. */
    public static boolean recognizes(String sql) {
        return sql != null && shapeOf(sql).isPresent();
    }

    /**
     * The statement {@code sql} is, or empty when it is not an alert statement.
     *
     * @throws PravahaException {@code PRV-2070} when it begins as one and does not have its shape;
     *     {@code PRV-2072} for {@code OR} in a condition
     */
    public static Optional<AlertStatement> recognize(String sql) {
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
            throw reader.malformed(e.offset, String.valueOf(e.getMessage()));
        }
    }

    private static Optional<String> shapeOf(String sql) {
        List<StatementLexer.Token> head = new ArrayList<>();
        StatementLexer lexer = new StatementLexer(sql);
        try {
            for (int i = 0; i < 5; i++) {
                head.add(lexer.next());
            }
        } catch (StatementLexer.Unreadable e) {
            // What could be read decides.
        }
        String first = wordAt(head, 0);
        String second = wordAt(head, 1);
        if (first.equals("SHOW")) {
            return second.equals("ALERTS") ? Optional.of(SHOW_SHAPE) : Optional.empty();
        }
        if (!second.equals("ALERT")) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                switch (first) {
                    case "CREATE" -> CREATE_SHAPE;
                    case "DROP" -> DROP_SHAPE;
                    case "PAUSE" -> PAUSE_SHAPE;
                    case "RESUME" -> RESUME_SHAPE;
                    case "SNOOZE" -> SNOOZE_SHAPE;
                    case "ACK", "ACKNOWLEDGE" -> ACK_SHAPE;
                    // SET TAGS, UNSET TAGS and OWNER TO are the catalogue's; SET ( and NOTIFY are ours.
                    case "ALTER" -> {
                        String verb = wordAt(head, 3);
                        boolean ours = verb.equals("NOTIFY")
                                || (verb.equals("SET")
                                        && head.size() > 4
                                        && head.get(4).kind() == StatementLexer.Kind.SYMBOL
                                        && head.get(4).text().equals("("));
                        yield ours ? ALTER_SHAPE : null;
                    }
                    default -> null;
                });
    }

    private static String wordAt(List<StatementLexer.Token> tokens, int index) {
        if (index >= tokens.size() || tokens.get(index).kind() != StatementLexer.Kind.WORD) {
            return "";
        }
        return tokens.get(index).text().toUpperCase(Locale.ROOT);
    }

    private static final class Reader {

        private final String sql;
        private final String shape;
        private final StatementLexer lexer;
        private StatementLexer.@Nullable Token peeked;

        Reader(String sql, String shape) {
            this.sql = sql;
            this.shape = shape;
            this.lexer = new StatementLexer(sql);
        }

        AlertStatement statement() {
            String first = next().text().toUpperCase(Locale.ROOT);
            if (first.equals("SHOW")) {
                keyword("ALERTS");
                end();
                return new AlertStatement.Show();
            }
            keyword("ALERT");
            return switch (first) {
                case "CREATE" -> create();
                case "ALTER" -> alter();
                case "DROP" -> {
                    boolean ifExists = false;
                    if (isWord(peek(), "IF")) {
                        next();
                        keyword("EXISTS");
                        ifExists = true;
                    }
                    String name = identifier("the alert's name");
                    end();
                    yield new AlertStatement.Drop(name, ifExists);
                }
                case "PAUSE" -> new AlertStatement.Pause(named());
                case "RESUME" -> new AlertStatement.Resume(named());
                case "ACK", "ACKNOWLEDGE" -> new AlertStatement.Ack(named());
                default -> snooze();
            };
        }

        private String named() {
            String name = identifier("the alert's name");
            end();
            return name;
        }

        private AlertStatement snooze() {
            String name = identifier("the alert's name");
            keyword("FOR");
            StatementLexer.Token value = next();
            String duration;
            if (value.kind() == StatementLexer.Kind.STRING || value.kind() == StatementLexer.Kind.WORD) {
                duration = value.text();
            } else if (value.kind() == StatementLexer.Kind.NUMBER) {
                // SNOOZE ALERT x FOR 30 MINUTES, or FOR 30m (the lexer splits the unit off the digits).
                StatementLexer.Token unit = next();
                if (unit.kind() != StatementLexer.Kind.WORD) {
                    throw unexpected(unit, "a unit after " + value.text() + ": s, m, h or d");
                }
                duration = value.text() + unit.text();
            } else {
                throw unexpected(value, "a duration such as '30m', '2h' or PT2H");
            }
            end();
            return new AlertStatement.Snooze(name, duration);
        }

        private AlertStatement create() {
            boolean ifNotExists = false;
            if (isWord(peek(), "IF")) {
                next();
                keyword("NOT");
                keyword("EXISTS");
                ifNotExists = true;
            }
            String name = identifier("the alert's name");
            keyword("ON");
            String view = dottedName("the view the alert watches");
            List<AlertStatement.Condition> where = List.of();
            if (isWord(peek(), "WHERE")) {
                next();
                where = conditions();
            }
            keyword("NOTIFY");
            List<String> channels = channels();
            Map<String, String> options = Map.of();
            if (isWord(peek(), "WITH")) {
                next();
                options = options();
            }
            end();
            return new AlertStatement.Create(name, ifNotExists, view, where, channels, options);
        }

        private AlertStatement alter() {
            String name = identifier("the alert's name");
            StatementLexer.Token verb = next();
            if (isWord(verb, "NOTIFY")) {
                List<String> channels = channels();
                end();
                return new AlertStatement.Alter(name, channels, Map.of());
            }
            if (!isWord(verb, "SET")) {
                throw unexpected(verb, "SET (...) or NOTIFY");
            }
            Map<String, String> options = options();
            end();
            return new AlertStatement.Alter(name, List.of(), options);
        }

        private List<AlertStatement.Condition> conditions() {
            List<AlertStatement.Condition> read = new ArrayList<>();
            while (true) {
                read.add(condition());
                StatementLexer.Token after = peek();
                if (isWord(after, "AND")) {
                    next();
                    continue;
                }
                if (isWord(after, "OR")) {
                    throw refusal(
                            SqlErrors.CLAUSE_NOT_BUILT,
                            after.start(),
                            "an alert's condition is comparisons joined by AND; OR is not built. A condition "
                                    + "with OR is a question: register it as a continuous query over the view "
                                    + "(ADR-056) and put the alert ON that query");
                }
                return read;
            }
        }

        private AlertStatement.Condition condition() {
            String column = identifier("a column of the view");
            StatementLexer.Token op = next();
            if (isWord(op, "IS")) {
                boolean not = false;
                if (isWord(peek(), "NOT")) {
                    next();
                    not = true;
                }
                keyword("NULL");
                return new AlertStatement.Condition(column, not ? "IS_NOT_NULL" : "IS_NULL", null, false);
            }
            if (op.kind() != StatementLexer.Kind.SYMBOL) {
                throw unexpected(op, "a comparison (= != <> < <= > >=) after '" + column + "'");
            }
            String operator = op.text();
            StatementLexer.Token more = peek();
            if (more.kind() == StatementLexer.Kind.SYMBOL
                    && more.start() == op.end()
                    && (more.text().equals("=") || more.text().equals(">"))) {
                next();
                operator += more.text();
            }
            operator = operator.equals("<>") ? "!=" : operator;
            if (!List.of("=", "!=", "<", "<=", ">", ">=").contains(operator)) {
                throw unexpected(op, "a comparison (= != <> < <= > >=) after '" + column + "'");
            }
            StatementLexer.Token value = next();
            boolean negative = false;
            if (value.kind() == StatementLexer.Kind.SYMBOL && value.text().equals("-")) {
                negative = true;
                value = next();
            }
            return switch (value.kind()) {
                case STRING -> {
                    if (negative) {
                        throw unexpected(value, "a number after '-'");
                    }
                    yield new AlertStatement.Condition(column, operator, value.text(), true);
                }
                case NUMBER ->
                    new AlertStatement.Condition(column, operator, (negative ? "-" : "") + value.text(), false);
                case WORD -> {
                    String word = value.text().toUpperCase(Locale.ROOT);
                    if (negative || !(word.equals("TRUE") || word.equals("FALSE"))) {
                        throw refusal(
                                SqlErrors.STATEMENT_MALFORMED,
                                value.start(),
                                "an alert's condition compares a column with a literal -- a number, a 'string', "
                                        + "TRUE or FALSE -- and '" + value.text() + "' is none of those. Comparing "
                                        + "two columns is a question for the view: put it in the view's WHERE");
                    }
                    yield new AlertStatement.Condition(column, operator, word, false);
                }
                default -> throw unexpected(value, "a literal to compare '" + column + "' with");
            };
        }

        private List<String> channels() {
            List<String> channels = new ArrayList<>();
            while (true) {
                String channel = identifier("a notifier channel's name");
                if (channels.contains(channel)) {
                    throw malformed(peek().start(), "the channel '" + channel + "' is named twice");
                }
                channels.add(channel);
                StatementLexer.Token after = peek();
                if (after.kind() == StatementLexer.Kind.SYMBOL && after.text().equals(",")) {
                    next();
                    continue;
                }
                return channels;
            }
        }

        /**
         * {@code (severity = 'warning', dedupe = '10m', include = (sku, on_hand))}: a value is a word, a
         * number, a string, or -- for {@code include} -- a parenthesised list of columns, kept as their
         * names joined by commas.
         */
        private Map<String, String> options() {
            symbol("(");
            Map<String, String> read = new LinkedHashMap<>();
            while (true) {
                StatementLexer.Token nameToken = next();
                if (nameToken.kind() != StatementLexer.Kind.WORD
                        && nameToken.kind() != StatementLexer.Kind.QUOTED
                        && nameToken.kind() != StatementLexer.Kind.STRING) {
                    throw unexpected(nameToken, "an option's name");
                }
                String key = nameToken.text().toLowerCase(Locale.ROOT);
                symbol("=");
                StatementLexer.Token value = next();
                String text;
                if (value.kind() == StatementLexer.Kind.SYMBOL && value.text().equals("(")) {
                    List<String> columns = new ArrayList<>();
                    while (true) {
                        columns.add(identifier("a column's name"));
                        StatementLexer.Token after = next();
                        if (after.kind() == StatementLexer.Kind.SYMBOL
                                && after.text().equals(")")) {
                            break;
                        }
                        if (after.kind() != StatementLexer.Kind.SYMBOL
                                || !after.text().equals(",")) {
                            throw unexpected(after, "',' or ')' in the list of columns");
                        }
                    }
                    text = String.join(",", columns);
                } else if (value.kind() == StatementLexer.Kind.NUMBER) {
                    // '10m' written bare: the lexer splits 10 from m.
                    StatementLexer.Token unit = peek();
                    text = value.text();
                    if (unit.kind() == StatementLexer.Kind.WORD && unit.start() == value.end()) {
                        next();
                        text += unit.text();
                    }
                } else if (value.kind() == StatementLexer.Kind.WORD
                        || value.kind() == StatementLexer.Kind.STRING
                        || value.kind() == StatementLexer.Kind.QUOTED) {
                    text = value.text();
                } else {
                    throw unexpected(value, "a value for the option '" + key + "'");
                }
                if (read.put(key, text) != null) {
                    throw malformed(value.start(), "the option '" + key + "' is given twice");
                }
                StatementLexer.Token separator = next();
                if (separator.kind() == StatementLexer.Kind.SYMBOL
                        && separator.text().equals(")")) {
                    return read;
                }
                if (separator.kind() != StatementLexer.Kind.SYMBOL
                        || !separator.text().equals(",")) {
                    throw unexpected(separator, "',' or ')' in the option list");
                }
            }
        }

        private String dottedName(String what) {
            StringBuilder name = new StringBuilder(identifier(what));
            for (int parts = 1; parts < 3; parts++) {
                StatementLexer.Token dot = peek();
                if (dot.kind() != StatementLexer.Kind.SYMBOL || !dot.text().equals(".")) {
                    break;
                }
                next();
                name.append('.').append(identifier(what));
            }
            return name.toString();
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
