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
package com.ash.messaging.pravaha.registry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.serving.ViewChange;

/**
 * A subscriber's own filter, applied where it taps the stream (ADR-031, ADR-032).
 *
 * <p>This is the cheap half of a rule that is usually expensive. A filter supplied from outside the
 * query can be applied at the tap <strong>iff the view carries every column it names</strong>. When
 * the query aggregated that column away, the view's rows already mix the values the filter is meant
 * to separate and nothing applied afterwards can unmix them -- so the filter has to go into the
 * query, which means a separate computation with its own state.
 *
 * <p>A query that does not aggregate keeps every column, so every filter over it is tap-applicable,
 * and <em>one computation serves every subscriber</em> however differently they each filter. Ten
 * desks watching ten product types are one read of the source and one copy of the state. That is the
 * whole argument for separating registration from subscription (ADR-025), and a pass-through query
 * is where it is most visible.
 *
 * <p>A value given as text is read as its column's type, so a filter from the wire can name a
 * number or a boolean (HLP-9).
 *
 * <p>Equality only, deliberately. Ranges and text matching invite the expectation that this is a
 * query language, and it is not -- it is a tap. Anything richer belongs in the registered query,
 * where the planner can reason about it and the cost is visible.
 */
public final class SubscriptionFilter {

    private static final SubscriptionFilter NONE = new SubscriptionFilter(Map.of(), new int[0], new Object[0]);

    private final Map<String, Object> declared;
    private final int[] ordinals;
    private final Object[] values;

    private SubscriptionFilter(Map<String, Object> declared, int[] ordinals, Object[] values) {
        this.declared = declared;
        this.ordinals = ordinals;
        this.values = values;
    }

    /** Everything. */
    public static SubscriptionFilter none() {
        return NONE;
    }

    /**
     * Matches rows whose named columns equal the given values.
     *
     * @throws PravahaException if a column is not in the view. Refused rather than ignored: a filter
     *     silently dropped because of a typo is a subscriber receiving everything while believing it
     *     asked for a slice, which is the failure mode this whole rule exists to prevent
     */
    public static SubscriptionFilter matching(StreamSchema schema, Map<String, Object> equals) {
        if (equals == null || equals.isEmpty()) {
            return NONE;
        }
        Map<String, Object> declared = new LinkedHashMap<>(equals);
        int[] ordinals = new int[declared.size()];
        Object[] values = new Object[declared.size()];
        int index = 0;
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            int ordinal = ordinalOf(schema, entry.getKey());
            if (ordinal < 0) {
                throw new PravahaException(
                        RegistryErrors.NO_SUCH_QUERY,
                        "this view has no column '" + entry.getKey() + "', so that filter cannot be applied "
                                + "to it. Its columns are "
                                + schema.fields().stream()
                                        .map(field -> field.name())
                                        .toList()
                                + ". A filter that was quietly ignored would leave you receiving "
                                + "everything while believing you asked for a slice");
            }
            ordinals[index] = ordinal;
            values[index] = asColumnValue(schema, ordinal, entry.getValue());
            index++;
        }
        return new SubscriptionFilter(Map.copyOf(declared), ordinals, values);
    }

    /**
     * The value as the view holds it for this column.
     *
     * <p>HLP-9. A filter arrives over the wire as text -- the Flight subscribe ticket carries
     * strings -- and was compared as given, so {@code "20"} never equalled a view's {@code Long} 20
     * and a filter on anything but a text column matched nothing, with nothing to say so. Text is
     * now read as the column's type; a value that is not one is refused, for the same reason an
     * unknown column is. A value already of the column's type passes through.
     */
    private static Object asColumnValue(StreamSchema schema, int ordinal, Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        com.ash.messaging.pravaha.api.data.Field field = schema.field(ordinal);
        String trimmed = text.strip();
        try {
            return switch (field.type().typeName()) {
                case BOOLEAN -> {
                    if (!trimmed.equalsIgnoreCase("true") && !trimmed.equalsIgnoreCase("false")) {
                        throw new NumberFormatException("not true or false");
                    }
                    yield Boolean.parseBoolean(trimmed);
                }
                case INT8 -> Byte.parseByte(trimmed);
                case INT16 -> Short.parseShort(trimmed);
                case INT32, DATE -> Integer.parseInt(trimmed);
                case INT64, TIME, TIMESTAMP_LTZ -> Long.parseLong(trimmed);
                case FLOAT32 -> Float.parseFloat(trimmed);
                case FLOAT64 -> Double.parseDouble(trimmed);
                case DECIMAL ->
                    new java.math.BigDecimal(trimmed)
                            .setScale(
                                    ((com.ash.messaging.pravaha.api.data.DecimalType) field.type()).scale(),
                                    java.math.RoundingMode.UNNECESSARY);
                default -> text;
            };
        } catch (NumberFormatException | ArithmeticException e) {
            throw new PravahaException(
                    RegistryErrors.NO_SUCH_QUERY,
                    "the filter on '" + field.name() + "' asks for '" + text + "', which is not a "
                            + field.type().typeName() + " value, so no row of this view could match it. A filter "
                            + "that quietly matched nothing would look like a quiet view",
                    e);
        }
    }

    /** A filter on one column. */
    public static SubscriptionFilter matching(StreamSchema schema, String column, Object value) {
        return matching(schema, Map.of(column, value));
    }

    private static int ordinalOf(StreamSchema schema, String column) {
        for (int i = 0; i < schema.fieldCount(); i++) {
            if (schema.field(i).name().equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether this change is one the subscriber asked for. */
    public boolean accepts(ViewChange change) {
        if (ordinals.length == 0) {
            return true;
        }
        Object[] row = change.values();
        for (int i = 0; i < ordinals.length; i++) {
            int ordinal = ordinals[i];
            if (ordinal >= row.length || !Objects.equals(row[ordinal], values[i])) {
                return false;
            }
        }
        return true;
    }

    public boolean isEmpty() {
        return ordinals.length == 0;
    }

    /** What was asked for, for logs and for the console. */
    public Map<String, Object> declared() {
        return declared;
    }

    @Override
    public String toString() {
        return declared.isEmpty() ? "all rows" : declared.toString();
    }
}
