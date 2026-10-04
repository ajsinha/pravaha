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
package com.ash.messaging.pravaha.registry.alert;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.Field;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.sql.AlertStatement;

/**
 * An alert's {@code WHERE}, compiled against the view's schema: comparisons of the view's own columns
 * with literals, all of which must hold. SQL's rule for null -- a comparison with it is not true -- so a
 * sparse column never fires an alert by being empty; {@code IS NULL} says so when that is what is meant.
 *
 * <p>Judged once, at {@code CREATE}: a column the view does not have, or a number compared with text,
 * is refused then ({@code PRV-8042}) rather than being a condition that silently never holds.
 */
final class AlertCondition {

    private record Test(
            int ordinal, String operator, @Nullable Object literal) {}

    private final List<Test> tests;
    private final String text;

    private AlertCondition(List<Test> tests, String text) {
        this.tests = tests;
        this.text = text;
    }

    static AlertCondition always() {
        return new AlertCondition(List.of(), "");
    }

    static AlertCondition compile(List<AlertStatement.Condition> conditions, StreamSchema schema) {
        List<Test> tests = new ArrayList<>();
        List<String> text = new ArrayList<>();
        for (AlertStatement.Condition condition : conditions) {
            int ordinal = ordinalOf(schema, condition.column());
            Field field = schema.field(ordinal);
            text.add(condition.toString());
            if (condition.operator().startsWith("IS_")) {
                tests.add(new Test(ordinal, condition.operator(), null));
                continue;
            }
            tests.add(new Test(ordinal, condition.operator(), literal(field, condition)));
        }
        return new AlertCondition(List.copyOf(tests), String.join(" AND ", text));
    }

    /** The condition as it would be written; empty when every row of the view counts. */
    String text() {
        return text;
    }

    boolean holds(Object[] row) {
        for (Test test : tests) {
            Object value = test.ordinal() < row.length ? row[test.ordinal()] : null;
            if (!holds(test, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean holds(Test test, @Nullable Object value) {
        return switch (test.operator()) {
            case "IS_NULL" -> value == null;
            case "IS_NOT_NULL" -> value != null;
            default -> {
                if (value == null) {
                    yield false;
                }
                int order = compare(value, test.literal());
                yield switch (test.operator()) {
                    case "=" -> order == 0;
                    case "!=" -> order != 0;
                    case "<" -> order < 0;
                    case "<=" -> order <= 0;
                    case ">" -> order > 0;
                    default -> order >= 0;
                };
            }
        };
    }

    private static int compare(Object value, @Nullable Object literal) {
        if (literal instanceof BigDecimal number && value instanceof Number actual) {
            BigDecimal left = actual instanceof BigDecimal d ? d : new BigDecimal(actual.toString());
            return left.compareTo(number);
        }
        if (literal instanceof Boolean flag && value instanceof Boolean actual) {
            return Boolean.compare(actual, flag);
        }
        return String.valueOf(value).compareTo(String.valueOf(literal));
    }

    private static @Nullable Object literal(Field field, AlertStatement.Condition condition) {
        TypeName type = field.type().typeName();
        String written = java.util.Objects.requireNonNull(condition.literal(), "a comparison has its literal");
        switch (type) {
            case INT8, INT16, INT32, INT64, FLOAT32, FLOAT64, DECIMAL -> {
                try {
                    return new BigDecimal(written.strip());
                } catch (NumberFormatException e) {
                    throw AlertOptions.invalid("'" + field.name() + "' holds numbers and " + condition
                            + " compares it with '" + written + "', which is not one, so it could never hold");
                }
            }
            case BOOLEAN -> {
                String upper = written.strip().toUpperCase(Locale.ROOT);
                if (!upper.equals("TRUE") && !upper.equals("FALSE")) {
                    throw AlertOptions.invalid("'" + field.name() + "' is TRUE or FALSE, and " + condition
                            + " compares it with '" + written + "'");
                }
                if (!condition.operator().equals("=") && !condition.operator().equals("!=")) {
                    throw AlertOptions.invalid("'" + field.name() + "' is TRUE or FALSE; compare it with = or !=");
                }
                return Boolean.valueOf(upper.equals("TRUE"));
            }
            case STRING -> {
                return written;
            }
            default ->
                throw AlertOptions.invalid("'" + field.name() + "' is " + type + ", which an alert's condition "
                        + "cannot compare; compare it in the view and alert on the result");
        }
    }

    private static int ordinalOf(StreamSchema schema, String column) {
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            if (schema.field(ordinal).name().equalsIgnoreCase(column)) {
                return ordinal;
            }
        }
        throw AlertOptions.invalid("'" + column + "' is not a column of the view, which has "
                + schema.fields().stream().map(Field::name).toList());
    }
}
