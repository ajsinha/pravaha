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
package com.ash.messaging.pravaha.embedded;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Turns a row of a view -- an {@code Object[]} laid out by a schema -- into something an
 * application reads by name: an ordered map, or a Java record.
 *
 * <p>Records are matched <em>by column name</em>, never by position, because position is what
 * changes silently when a column is added to a SELECT list. A component matches a column when the
 * two are equal ignoring case and underscores, so a {@code user_id} column fills a {@code userId}
 * component. Every component must match a column -- a component with nothing to fill it is refused
 * rather than left null, because a null there would be indistinguishable from a null answer. Columns
 * the record does not mention are ignored, which is what lets a record read the part of a view it
 * cares about.
 *
 * <p>Values convert only where the conversion loses nothing a caller could have meant: any number
 * to a wider or equal numeric component (and to {@code BigDecimal}), epoch nanoseconds to {@link
 * Instant}, epoch days to {@link LocalDate}, nanoseconds of day to {@link LocalTime}. Anything
 * else is refused naming the column, the component and both types.
 */
public final class RowMapping {

    private RowMapping() {}

    /** The row as column name to value, in the schema's column order. Nulls are kept. */
    public static Map<String, Object> toMap(StreamSchema schema, Object[] values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            map.put(schema.field(ordinal).name(), ordinal < values.length ? values[ordinal] : null);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * A reader that builds {@code type} from rows of {@code schema}.
     *
     * <p>Resolved once: which column fills each component, and the canonical constructor. A caller
     * reading many rows pays for the matching once, and a record that cannot be filled from this
     * schema is refused here, before the first row, rather than on it.
     *
     * @throws IllegalArgumentException if {@code type} is not a record, or has a component no column
     *     of {@code schema} matches
     */
    public static <R> Function<Object[], R> reader(Class<R> type, StreamSchema schema) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " is not a record. Rows map onto records, whose "
                    + "components name the columns they read; use RowChange.values() for a map of every column.");
        }
        RecordComponent[] components = type.getRecordComponents();
        int[] ordinals = new int[components.length];
        Class<?>[] parameterTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            ordinals[i] = columnFor(components[i].getName(), schema, type);
            parameterTypes[i] = components[i].getType();
        }
        Constructor<R> constructor;
        try {
            constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
        } catch (NoSuchMethodException | RuntimeException e) {
            throw new IllegalArgumentException(
                    "cannot use the canonical constructor of " + type.getName() + ": " + e, e);
        }
        return values -> {
            Object[] arguments = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                Object value = ordinals[i] < values.length ? values[ordinals[i]] : null;
                arguments[i] = convert(
                        value, parameterTypes[i], schema.field(ordinals[i]).name(), components[i], type);
            }
            try {
                return constructor.newInstance(arguments);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(type.getName() + " refused a row: " + cause, cause);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot construct " + type.getName() + ": " + e, e);
            }
        };
    }

    /** Maps one row; for many rows of one schema, {@link #reader} resolves the matching once. */
    public static <R> R toRecord(Class<R> type, StreamSchema schema, Object[] values) {
        return reader(type, schema).apply(values);
    }

    private static int columnFor(String component, StreamSchema schema, Class<?> type) {
        String wanted = normalise(component);
        List<String> columns = new ArrayList<>();
        for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
            String column = schema.field(ordinal).name();
            if (normalise(column).equals(wanted)) {
                return ordinal;
            }
            columns.add(column);
        }
        throw new IllegalArgumentException("record " + type.getSimpleName() + " has a component '" + component
                + "' and '" + schema.name() + "' has no column matching it (matching ignores case and "
                + "underscores). Its columns are " + columns + ".");
    }

    private static String normalise(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static @Nullable Object convert(
            @Nullable Object value, Class<?> target, String column, RecordComponent component, Class<?> type) {
        if (value == null) {
            if (target.isPrimitive()) {
                throw new IllegalArgumentException("column '" + column + "' is null and " + type.getSimpleName() + "."
                        + component.getName() + " is a " + target.getName()
                        + ", which cannot hold one; declare it with its boxed type");
            }
            return null;
        }
        Class<?> boxed = box(target);
        if (boxed.isInstance(value)) {
            return value;
        }
        if (value instanceof Number number) {
            Object converted = fromNumber(number, boxed);
            if (converted != null) {
                return converted;
            }
        }
        throw new IllegalArgumentException("column '" + column + "' holds a "
                + value.getClass().getSimpleName()
                + " and " + type.getSimpleName() + "." + component.getName() + " is a " + target.getSimpleName()
                + "; declare the component as " + value.getClass().getSimpleName() + " or a type it converts to");
    }

    private static @Nullable Object fromNumber(Number number, Class<?> target) {
        boolean integral = number instanceof Long
                || number instanceof Integer
                || number instanceof Short
                || number instanceof Byte
                || number instanceof BigInteger;
        if (target == Long.class && integral) {
            return number.longValue();
        }
        if (target == Integer.class && integral && fits(number, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            return number.intValue();
        }
        if (target == Short.class && integral && fits(number, Short.MIN_VALUE, Short.MAX_VALUE)) {
            return number.shortValue();
        }
        if (target == Byte.class && integral && fits(number, Byte.MIN_VALUE, Byte.MAX_VALUE)) {
            return number.byteValue();
        }
        if (target == Double.class) {
            return number.doubleValue();
        }
        if (target == Float.class && !(number instanceof Double)) {
            return number.floatValue();
        }
        if (target == BigDecimal.class) {
            return integral ? BigDecimal.valueOf(number.longValue()) : new BigDecimal(number.toString());
        }
        if (target == Instant.class && integral) {
            long nanos = number.longValue();
            return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
        }
        if (target == LocalDate.class && integral) {
            return LocalDate.ofEpochDay(number.longValue());
        }
        if (target == LocalTime.class && integral) {
            return LocalTime.ofNanoOfDay(number.longValue());
        }
        return null;
    }

    private static boolean fits(Number number, long min, long max) {
        long value = number.longValue();
        return value >= min && value <= max;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        return Character.class;
    }
}
