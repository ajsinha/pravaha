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
 * CREATE [OR REPLACE] CONTINUOUS QUERY name
 *     KEYED BY (column [, column]...)
 *     [RANGE (column)]
 *     [INDEX (column)]
 *     [WRITING TO sink]
 *     [RETAIN FOR duration | RETAIN FOREVER]
 *     [WITH (option = value [, option = value]...)]
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
 * <p>{@code RANGE (column)} asks for the ordered index design section 17.2 calls for, and the
 * column it names is the key's last one: the design writes {@code INDEXED BY (user_id) RANGE
 * (window_end)}, where {@code user_id} is probed and {@code window_end} is scanned between bounds,
 * and the two together are the key. Under {@code INDEXED BY} a column the key list does not already
 * end with is appended, so that spelling means what it reads as. Under {@code KEYED BY} it is
 * refused -- the one place the two words are not aliases, because widening a key somebody has just
 * written out changes what the view conflates and therefore every count over it.
 *
 * <p>{@code INDEX (column)} asks for an equality index over one column outside the key: value to
 * keys, kept in the view's own commit, so that {@code WHERE column = literal} (or {@code IN} a
 * list of them) probes rather than scans (ADR-055). One column, because a list would read as a
 * composite index, which this is not; whether the column can be indexed is judged against the
 * planned output, not here.
 *
 * <p>{@code WITH (...)} is read here and judged elsewhere, because which options exist depends on
 * what the statement is: a plain {@code CREATE} takes a registration's ({@code
 * RegistrationOptions}), {@code CREATE OR REPLACE} takes a replacement's ({@code
 * ReplacementOptions}). One list parser, so the two spellings cannot drift apart; two vocabularies,
 * each refusing an option it does not build by name rather than ignoring it.
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

    static final String CREATE_SHAPE = "CREATE [OR REPLACE] CONTINUOUS QUERY <name> KEYED BY (<column>, ...) "
            + "[RANGE (<column>)] [INDEX (<column>)] [WRITING TO <sink>] [RETAIN FOR <duration> | RETAIN FOREVER] "
            + "[WITH (<option> = <value>, ...)] AS <select>";
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
            // OR REPLACE is built (ADR-046): the new version is registered beside the running one,
            // backfilled, and cut over to at a position both have consumed exactly. What it never
            // does is take the answer away while the new one warms up, which is why it was refused
            // until there was a mechanism that does not.
            boolean orReplace = false;
            if (isWord(peek(), "OR")) {
                next();
                keyword("REPLACE");
                orReplace = true;
            }
            keyword("CONTINUOUS");
            keyword("QUERY");
            String name = identifier("the query's name");
            List<String> keys = null;
            boolean keyedBy = false;
            String range = null;
            StatementLexer.Token rangeAt = null;
            String index = null;
            String sink = null;
            ContinuousStatement.Retain retain = null;
            java.util.Map<String, String> options = new java.util.LinkedHashMap<>();
            boolean served = false;
            while (true) {
                StatementLexer.Token clause = next();
                String word = clause.kind() == StatementLexer.Kind.WORD
                        ? clause.text().toUpperCase(Locale.ROOT)
                        : "";
                switch (word) {
                    case "AS" -> {
                        if (namesAKey(options) && (keys != null || range != null)) {
                            throw malformed(
                                    clause.start(),
                                    "query '" + name + "' says what it is keyed by twice: in the statement and "
                                            + "again as a WITH option. The option exists for a caller that has "
                                            + "the key as a value rather than as text; when the statement says "
                                            + "it, drop the option");
                        }
                        if (keys == null && range == null && !namesAKey(options)) {
                            throw malformed(
                                    clause.start(),
                                    "query '" + name + "' has no key: KEYED BY (<column>, ...) -- or WITH (keys = "
                                            + "'<column>, ...') -- must come before AS. A view with no key is a "
                                            + "log, and a point read against it has nothing to look up");
                        }
                        String select = select(clause);
                        return new ContinuousStatement.Create(
                                name,
                                withRange(keys, keyedBy, range, rangeAt),
                                Optional.ofNullable(range),
                                Optional.ofNullable(index),
                                Optional.ofNullable(sink),
                                Optional.ofNullable(retain),
                                select,
                                orReplace,
                                options);
                    }
                    case "KEYED", "INDEXED" -> {
                        once(keys == null, clause, "the key");
                        keyword("BY");
                        keys = columns();
                        // Which word was used matters for exactly one thing, below: whether a RANGE
                        // column the list does not hold may be appended to the key.
                        keyedBy = word.equals("KEYED");
                    }
                    case "RANGE" -> {
                        once(range == null, clause, "RANGE");
                        rangeAt = peek();
                        List<String> ranged = columns();
                        if (ranged.size() != 1) {
                            throw malformed(
                                    clause.start(),
                                    "RANGE names " + ranged.size() + " columns and an ordered index is kept over "
                                            + "one: the key's last column, which the columns before it are probed "
                                            + "by. Two ordered columns would be two indexes, and this engine "
                                            + "builds one");
                        }
                        range = ranged.get(0);
                    }
                    case "INDEX" -> {
                        once(index == null, clause, "INDEX");
                        List<String> indexed = columns();
                        if (indexed.size() != 1) {
                            throw malformed(
                                    clause.start(),
                                    "INDEX names " + indexed.size() + " columns and an equality index is "
                                            + "kept over one. A list would read as a composite index, which "
                                            + "this is not: name the one column a read will pin with = or IN");
                        }
                        index = indexed.get(0);
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
                    case "WITH" -> {
                        once(options.isEmpty(), clause, "the options");
                        options.putAll(options(clause));
                    }
                    default ->
                        throw unexpected(
                                clause,
                                "KEYED BY (...), RANGE (<column>), INDEX (<column>), WRITING TO <sink>, RETAIN FOR <duration>, "
                                        + "RETAIN FOREVER, WITH (...) or AS <select>");
                }
            }
        }

        /** Whether a {@code WITH (...)} list says what the view is keyed by. */
        private static boolean namesAKey(java.util.Map<String, String> options) {
            return options.containsKey("key") || options.containsKey("keys");
        }

        /**
         * The key, with the {@code RANGE} column at the end of it.
         *
         * <p>{@code INDEXED BY (user_id) RANGE (window_end)} is design section 17.2's spelling and
         * means a key of {@code (user_id, window_end)} whose last column is ordered, so under that
         * spelling a column the key does not already end with is appended. Under {@code KEYED BY}
         * it is refused instead, which is the one place the two words differ: a writer who has
         * spelled out the key has said what the view conflates, and widening it silently would
         * change every count over that view.
         *
         * <p>A column the key holds somewhere other than at the end is refused either way: the
         * ordered column has to be the last one, or the columns after it would be what an index
         * entry is sorted by.
         */
        private List<String> withRange(List<String> keys, boolean keyedBy, String range, StatementLexer.Token at) {
            if (range == null) {
                return keys == null ? List.of() : keys;
            }
            if (keys == null) {
                return List.of(range);
            }
            int held = keys.indexOf(range);
            if (held < 0) {
                if (keyedBy) {
                    // KEYED BY (a) RANGE (b) is refused rather than read as a key of (a, b). The
                    // two are not the same view: widening a key changes what it conflates, so two
                    // rows that share 'a' and differ in 'b' stop being one row and every count over
                    // the view changes. Appending to a list the writer has just spelled out would
                    // be this engine deciding that for them, silently.
                    throw malformed(
                            at == null ? 0 : at.start(),
                            "RANGE names '" + range + "', which KEYED BY does not. The ordered column is part "
                                    + "of the key, and adding it to one that is already written out would "
                                    + "change what the view conflates: two rows sharing " + keys
                                    + " and differing in '" + range + "' would stop being one row. Write "
                                    + "KEYED BY (" + String.join(", ", keys) + ", " + range + ") RANGE ("
                                    + range + ") if that is the view you want, or INDEXED BY ("
                                    + String.join(", ", keys) + ") RANGE (" + range + "), which is design "
                                    + "section 17.2's spelling for exactly that key");
                }
                List<String> extended = new ArrayList<>(keys);
                extended.add(range);
                return extended;
            }
            if (held != keys.size() - 1) {
                throw malformed(
                        at == null ? 0 : at.start(),
                        "RANGE names '" + range + "', which the key holds at position " + (held + 1) + " of "
                                + keys.size() + ". An ordered index is over the key's last column -- the ones "
                                + "before it are probed for equality and it is scanned between bounds -- so "
                                + "write the key with '" + range + "' last");
            }
            return keys;
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

        /**
         * {@code WITH (backfill = 'history', backfill.rate.limit = 1000)}, or the design's {@code
         * WITH ('retention' = '24h')}.
         *
         * <p>Read here and judged elsewhere: which options exist depends on the statement -- a
         * plain {@code CREATE} takes {@code RegistrationOptions}, a replacement takes {@code
         * ReplacementOptions} -- and an option the engine does not build is refused by name with
         * its own code rather than silently ignored. An ignored rate limit is a backfill that took
         * a production store down at full speed; an ignored retention is a view kept for ever that
         * somebody asked to keep for a day.
         *
         * <p>An option's name may be written bare, double-quoted, or single-quoted, because design
         * sections 11.2 and 17.2 write these lists with single-quoted names and a grammar that
         * refused the design's own spelling would be a second grammar.
         */
        private java.util.Map<String, String> options(StatementLexer.Token with) {
            symbol("(");
            java.util.Map<String, String> read = new java.util.LinkedHashMap<>();
            while (true) {
                StringBuilder key = new StringBuilder(optionName());
                StatementLexer.Token after = next();
                // Dotted names are the design's spelling -- backfill.rate.limit -- and the lexer
                // hands back the dot as a symbol of its own.
                while (after.kind() == StatementLexer.Kind.SYMBOL
                        && after.text().equals(".")) {
                    key.append('.').append(optionName());
                    after = next();
                }
                if (after.kind() != StatementLexer.Kind.SYMBOL || !after.text().equals("=")) {
                    throw unexpected(after, "'=' after the option '" + key + "'");
                }
                StatementLexer.Token value = next();
                if (value.kind() != StatementLexer.Kind.WORD
                        && value.kind() != StatementLexer.Kind.STRING
                        && value.kind() != StatementLexer.Kind.NUMBER) {
                    throw unexpected(value, "a value for the option '" + key + "'");
                }
                if (read.put(key.toString().toLowerCase(Locale.ROOT), value.text()) != null) {
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

        /** An option's name: bare, double-quoted, or single-quoted as the design writes them. */
        private String optionName() {
            StatementLexer.Token token = peek();
            if (token.kind() == StatementLexer.Kind.STRING) {
                next();
                if (token.text().isEmpty()) {
                    throw malformed(token.start(), "an option's name is empty");
                }
                return token.text();
            }
            return identifier("an option's name");
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
