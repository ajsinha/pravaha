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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * A {@code SELECT} with no {@code FROM}: the probe a connection pool, a BI tool or a health check
 * sends to ask "is this connection alive?" (PGVALIDATE-1).
 *
 * <p>HikariCP's {@code connectionTestQuery}, DBeaver's "Test connection", Grafana's data-source check
 * and many ORMs' pre-ping send {@code SELECT 1}; others send {@code SELECT now()}, {@code SELECT
 * 'x'::text} or {@code SELECT current_user}. None of these reads a view, so the view planner refused
 * them (a {@code FROM}-less {@code SELECT} has nothing for it to maintain) and a pool's default
 * validation failed every connection. This class answers them here, before the planner, with
 * PostgreSQL's own column names and types.
 *
 * <p>What is answered is a short, closed list -- each select item one of:
 *
 * <ul>
 *   <li>an integer, decimal or {@code '...'} string literal, {@code TRUE} or {@code FALSE};
 *   <li>a cast of one of those to {@code text}, {@code varchar}, {@code int2}/{@code int4}/{@code
 *       int8} (and their SQL spellings), {@code bool}/{@code boolean} or {@code numeric}, written
 *       {@code x::type} or {@code CAST(x AS type)};
 *   <li>{@code now()}, {@code current_timestamp}, {@code transaction_timestamp()}, {@code
 *       statement_timestamp()}, {@code clock_timestamp()}, {@code current_date};
 *   <li>{@code current_user}, {@code session_user}, {@code user}, {@code current_role} -- the
 *       principal the connection's credential stands for;
 *   <li>{@code version()}, {@code current_schema()}, {@code current_database()}, {@code
 *       current_catalog}, {@code pg_backend_pid()}
 * </ul>
 *
 * <p>each optionally followed by an alias. Anything else -- an arithmetic expression, a function
 * not on the list, a {@code WHERE} -- is not recognised and reaches the planner exactly as before,
 * which refuses it by name. The rule the gateway keeps everywhere else holds here: nothing is
 * approximated. A recognised probe gets PostgreSQL's answer; an unrecognised one gets a refusal,
 * never a guess.
 */
final class PgConstantSelect {

    private PgConstantSelect() {}

    private static final Pattern SELECT_LIST =
            Pattern.compile("^SELECT\\s+(.+?)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*";

    private static final Pattern EXPLICIT_ALIAS = Pattern.compile(
            "^(.+?)\\s+AS\\s+(\"[^\"]+\"|" + IDENTIFIER + ")$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern IMPLICIT_ALIAS =
            Pattern.compile("^(.+?)\\s+(\"[^\"]+\"|" + IDENTIFIER + ")$", Pattern.DOTALL);

    private static final Pattern INTEGER = Pattern.compile("^[+-]?\\d+$");

    private static final Pattern DECIMAL = Pattern.compile("^[+-]?(\\d+\\.\\d*|\\.\\d+)$");

    private static final Pattern STRING = Pattern.compile("^'((?:[^']|'')*)'$", Pattern.DOTALL);

    private static final Pattern POSTFIX_CAST =
            Pattern.compile("^(.+?)\\s*::\\s*([A-Za-z][A-Za-z0-9 ]*?)$", Pattern.DOTALL);

    private static final Pattern CAST_CALL = Pattern.compile(
            "^CAST\\s*\\(\\s*(.+?)\\s+AS\\s+([A-Za-z][A-Za-z0-9 ]*?)\\s*\\)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** PostgreSQL's name for a column computed from a literal: {@code SELECT 1} answers {@code ?column?}. */
    static final String ANONYMOUS_COLUMN = "?column?";

    /** One select item's value, type and the column name PostgreSQL would give it. */
    private record Constant(PravahaType type, Object value, String name) {}

    /**
     * The one-row answer to {@code statement}, or empty if it is not a constant {@code SELECT} this
     * class recognises.
     */
    static Optional<ViewQuery.Result> answer(String statement, String serverVersion, Principal principal) {
        Matcher select = SELECT_LIST.matcher(statement.strip());
        if (!select.matches()) {
            return Optional.empty();
        }
        List<String> items = splitTopLevel(select.group(1));
        if (items.isEmpty()) {
            return Optional.empty();
        }
        StreamSchema.Builder schema = StreamSchema.builder("constant");
        Object[] row = new Object[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Optional<Constant> item = item(items.get(i).strip(), serverVersion, principal);
            if (item.isEmpty()) {
                return Optional.empty();
            }
            schema.field(item.get().name(), item.get().type());
            row[i] = item.get().value();
        }
        return Optional.of(new ViewQuery.Result(schema.build(), java.util.Collections.singletonList(row)));
    }

    private static Optional<Constant> item(String text, String serverVersion, Principal principal) {
        Optional<Constant> whole = expression(text, serverVersion, principal);
        if (whole.isPresent()) {
            return whole;
        }
        for (Pattern aliased : List.of(EXPLICIT_ALIAS, IMPLICIT_ALIAS)) {
            Matcher m = aliased.matcher(text);
            if (m.matches()) {
                Optional<Constant> expr = expression(m.group(1).strip(), serverVersion, principal);
                if (expr.isPresent()) {
                    return Optional.of(
                            new Constant(expr.get().type(), expr.get().value(), aliasName(m.group(2))));
                }
            }
        }
        return Optional.empty();
    }

    private static String aliasName(String alias) {
        // A quoted alias keeps its case; an unquoted one folds to lower case, as PostgreSQL folds it.
        return alias.startsWith("\"") ? alias.substring(1, alias.length() - 1) : alias.toLowerCase(Locale.ROOT);
    }

    private static Optional<Constant> expression(String text, String serverVersion, Principal principal) {
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Matcher cast = CAST_CALL.matcher(text);
        if (!cast.matches()) {
            cast = POSTFIX_CAST.matcher(text);
            if (!cast.matches()) {
                return primary(text, serverVersion, principal);
            }
        }
        String target = cast.group(2);
        Optional<Constant> inner = expression(cast.group(1).strip(), serverVersion, principal);
        return inner.flatMap(value -> castTo(value, target));
    }

    private static Optional<Constant> primary(String text, String serverVersion, Principal principal) {
        if (INTEGER.matcher(text).matches()) {
            try {
                long value = Long.parseLong(text);
                if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    return Optional.of(new Constant(Types.int32(), (int) value, ANONYMOUS_COLUMN));
                }
                return Optional.of(new Constant(Types.int64(), value, ANONYMOUS_COLUMN));
            } catch (NumberFormatException tooLarge) {
                return decimal(text);
            }
        }
        if (DECIMAL.matcher(text).matches()) {
            return decimal(text);
        }
        Matcher string = STRING.matcher(text);
        if (string.matches()) {
            return Optional.of(new Constant(Types.string(), string.group(1).replace("''", "'"), ANONYMOUS_COLUMN));
        }
        String call = text.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        return switch (call) {
            case "TRUE" -> Optional.of(new Constant(Types.bool(), true, "bool"));
            case "FALSE" -> Optional.of(new Constant(Types.bool(), false, "bool"));
            case "NOW()" -> timestamp("now");
            case "CURRENT_TIMESTAMP" -> timestamp("current_timestamp");
            case "TRANSACTION_TIMESTAMP()" -> timestamp("transaction_timestamp");
            case "STATEMENT_TIMESTAMP()" -> timestamp("statement_timestamp");
            case "CLOCK_TIMESTAMP()" -> timestamp("clock_timestamp");
            case "CURRENT_DATE" ->
                Optional.of(
                        new Constant(Types.date(), LocalDate.now(ZoneOffset.UTC).toEpochDay(), "current_date"));
            case "CURRENT_USER" -> Optional.of(new Constant(Types.string(), principal.id(), "current_user"));
            case "CURRENT_ROLE" -> Optional.of(new Constant(Types.string(), principal.id(), "current_role"));
            case "SESSION_USER" -> Optional.of(new Constant(Types.string(), principal.id(), "session_user"));
            case "USER" -> Optional.of(new Constant(Types.string(), principal.id(), "user"));
            case "VERSION()" ->
                Optional.of(new Constant(
                        Types.string(),
                        "PostgreSQL " + serverVersion + " -- Pravaha's PostgreSQL wire gateway",
                        "version"));
            case "CURRENT_SCHEMA()", "CURRENT_SCHEMA" ->
                Optional.of(new Constant(Types.string(), "public", "current_schema"));
            case "CURRENT_DATABASE()" -> Optional.of(new Constant(Types.string(), "pravaha", "current_database"));
            case "CURRENT_CATALOG" -> Optional.of(new Constant(Types.string(), "pravaha", "current_catalog"));
            case "PG_BACKEND_PID()" ->
                Optional.of(new Constant(
                        Types.string(), String.valueOf(ProcessHandle.current().pid()), "pg_backend_pid"));
            default -> Optional.empty();
        };
    }

    private static Optional<Constant> decimal(String text) {
        BigDecimal value = new BigDecimal(text);
        int scale = Math.max(0, value.scale());
        int precision = Math.max(Math.max(1, value.precision()), scale);
        return Optional.of(new Constant(Types.decimal(precision, scale), value.setScale(scale), ANONYMOUS_COLUMN));
    }

    private static Optional<Constant> timestamp(String name) {
        Instant now = Instant.now();
        long epochNanos = Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
        return Optional.of(new Constant(Types.timestamp(), epochNanos, name));
    }

    /**
     * {@code value} cast to {@code typeName}, named as PostgreSQL names a cast: after the type for a
     * literal ({@code SELECT 'a'::text} answers a column called {@code text}), after the inner
     * expression otherwise ({@code SELECT now()::text} answers {@code now}).
     */
    private static Optional<Constant> castTo(Constant value, String typeName) {
        String type = typeName.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String text = textOf(value);
        try {
            Constant cast =
                    switch (type) {
                        case "text" -> new Constant(Types.string(), text, "text");
                        case "varchar", "character varying" -> new Constant(Types.string(), text, "varchar");
                        case "name" -> new Constant(Types.string(), text, "name");
                        case "int2", "smallint" -> new Constant(Types.int16(), Short.parseShort(text.strip()), "int2");
                        case "int", "int4", "integer" ->
                            new Constant(Types.int32(), Integer.parseInt(text.strip()), "int4");
                        case "int8", "bigint" -> new Constant(Types.int64(), Long.parseLong(text.strip()), "int8");
                        case "bool", "boolean" ->
                            booleanOf(text)
                                    .map(b -> new Constant(Types.bool(), b, "bool"))
                                    .orElse(null);
                        case "numeric", "decimal" -> {
                            Constant number = decimal(text.strip()).orElseThrow();
                            yield new Constant(number.type(), number.value(), "numeric");
                        }
                        default -> null;
                    };
            if (cast == null) {
                return Optional.empty();
            }
            String name =
                    ANONYMOUS_COLUMN.equals(value.name()) || "bool".equals(value.name()) ? cast.name() : value.name();
            return Optional.of(new Constant(cast.type(), cast.value(), name));
        } catch (NumberFormatException notThatType) {
            // '1.5'::int, 'x'::bigint: PostgreSQL refuses these too. Left to the planner, which
            // refuses them by name, rather than answered with a guess.
            return Optional.empty();
        }
    }

    private static String textOf(Constant value) {
        Object raw = value.value();
        return switch (value.type().typeName()) {
            case BOOLEAN -> ((Boolean) raw) ? "true" : "false";
            case DECIMAL -> ((BigDecimal) raw).toPlainString();
            case TIMESTAMP_LTZ -> {
                long nanos = ((Number) raw).longValue();
                yield Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L))
                        .toString();
            }
            case DATE -> LocalDate.ofEpochDay(((Number) raw).longValue()).toString();
            default -> String.valueOf(raw);
        };
    }

    private static Optional<Boolean> booleanOf(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "t", "true", "y", "yes", "on", "1" -> Optional.of(true);
            case "f", "false", "n", "no", "off", "0" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /**
     * {@code list} split at its top-level commas -- not inside a string literal, a quoted identifier
     * or parentheses. Empty if the text is unbalanced, which no constant select is.
     */
    static List<String> splitTopLevel(String list) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        boolean inIdentifier = false;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\'') {
                    inString = false; // A doubled '' re-enters at once on the next character.
                }
                continue;
            }
            if (inIdentifier) {
                current.append(c);
                if (c == '"') {
                    inIdentifier = false;
                }
                continue;
            }
            switch (c) {
                case '\'' -> inString = true;
                case '"' -> inIdentifier = true;
                case '(' -> depth++;
                case ')' -> depth--;
                default -> {}
            }
            if (depth < 0) {
                return List.of();
            }
            if (c == ',' && depth == 0) {
                items.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (inString || inIdentifier || depth != 0) {
            return List.of();
        }
        items.add(current.toString());
        return items;
    }
}
