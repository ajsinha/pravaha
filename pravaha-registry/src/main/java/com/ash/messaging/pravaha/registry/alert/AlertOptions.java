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

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * An alert's {@code WITH (...)} list, read and judged once (ADR-057). An option this engine does not
 * build is refused by name rather than accepted and ignored: an ignored {@code dedupe} is a pager that
 * goes off every second.
 *
 * @param severity {@code info}, {@code warning} (the default) or {@code critical}
 * @param fireAfter how long a key must be in the condition before it fires ({@code fire_after}, or {@code
 *     for}); zero fires at once
 * @param clearAfter how long a firing key must be out of it before it clears; zero clears at once
 * @param dedupe the least time between two notifications about one key; a transition inside it is sent
 *     when it ends, and only if the key's state then differs from what was last said
 * @param resendEvery how often a firing, unacknowledged key is re-announced ({@code REMINDER}); zero never
 * @param include the columns a notification carries; empty for every column of the view
 * @param snooze on {@code CREATE}, a snooze to start with; zero for none
 */
public record AlertOptions(
        String severity,
        Duration fireAfter,
        Duration clearAfter,
        Duration dedupe,
        Duration resendEvery,
        List<String> include,
        Duration snooze) {

    /** The options that exist, in the order they are shown. */
    public static final List<String> NAMES =
            List.of("severity", "fire_after", "clear_after", "dedupe", "resend_every", "include", "snooze");

    private static final Set<String> SEVERITIES = Set.of("info", "warning", "critical");

    public AlertOptions {
        include = List.copyOf(include);
    }

    public static AlertOptions defaults() {
        return new AlertOptions(
                "warning", Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO, List.of(), Duration.ZERO);
    }

    /** {@code base} with {@code written} applied over it. */
    public static AlertOptions of(AlertOptions base, Map<String, String> written) {
        AlertOptions options = base;
        for (Map.Entry<String, String> entry : written.entrySet()) {
            options = options.with(entry.getKey(), entry.getValue());
        }
        return options;
    }

    private AlertOptions with(String name, String value) {
        String key = name.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "severity" -> {
                String level = value.strip().toLowerCase(Locale.ROOT);
                if (!SEVERITIES.contains(level)) {
                    throw invalid("severity is info, warning or critical, not '" + value + "'");
                }
                yield new AlertOptions(level, fireAfter, clearAfter, dedupe, resendEvery, include, snooze);
            }
            case "fire_after", "for" ->
                new AlertOptions(severity, duration(key, value), clearAfter, dedupe, resendEvery, include, snooze);
            case "clear_after" ->
                new AlertOptions(severity, fireAfter, duration(key, value), dedupe, resendEvery, include, snooze);
            case "dedupe" ->
                new AlertOptions(severity, fireAfter, clearAfter, duration(key, value), resendEvery, include, snooze);
            case "resend_every" ->
                new AlertOptions(severity, fireAfter, clearAfter, dedupe, duration(key, value), include, snooze);
            case "snooze" ->
                new AlertOptions(severity, fireAfter, clearAfter, dedupe, resendEvery, include, duration(key, value));
            case "include" -> {
                List<String> columns = new ArrayList<>();
                for (String column : value.split(",", -1)) {
                    if (!column.isBlank()) {
                        columns.add(column.strip());
                    }
                }
                yield new AlertOptions(severity, fireAfter, clearAfter, dedupe, resendEvery, columns, snooze);
            }
            default ->
                throw invalid("'" + name + "' is not an alert option; the options are " + NAMES
                        + " (for is fire_after's other name)");
        };
    }

    /** The options as text, one entry per option that differs from the default, for the journal. */
    public Map<String, String> written() {
        Map<String, String> written = new LinkedHashMap<>();
        AlertOptions d = defaults();
        if (!severity.equals(d.severity)) {
            written.put("severity", severity);
        }
        put(written, "fire_after", fireAfter);
        put(written, "clear_after", clearAfter);
        put(written, "dedupe", dedupe);
        put(written, "resend_every", resendEvery);
        if (!include.isEmpty()) {
            written.put("include", String.join(",", include));
        }
        return written;
    }

    private static void put(Map<String, String> written, String name, Duration value) {
        if (!value.isZero()) {
            written.put(name, value.toString());
        }
    }

    /**
     * {@code '10m'}, {@code '90s'}, {@code '2h'}, {@code '1d'}, {@code '250ms'}, ISO-8601 ({@code PT10M}),
     * or {@code 0}. Never negative.
     */
    public static Duration duration(String name, @Nullable String text) {
        String value = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (value.equals("0")) {
            return Duration.ZERO;
        }
        try {
            if (value.startsWith("p")) {
                return nonNegative(name, Duration.parse(value.toUpperCase(Locale.ROOT)));
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]{1,9})\\s*(ms|s|m|h|d)")
                    .matcher(value);
            if (m.matches()) {
                long n = Long.parseLong(m.group(1));
                return switch (m.group(2)) {
                    case "ms" -> Duration.ofMillis(n);
                    case "s" -> Duration.ofSeconds(n);
                    case "m" -> Duration.ofMinutes(n);
                    case "h" -> Duration.ofHours(n);
                    default -> Duration.ofDays(n);
                };
            }
        } catch (DateTimeParseException e) {
            // Refused below, with the forms that are read.
        }
        throw invalid("'" + text + "' is not a duration for " + name + "; write '30s', '10m', '2h', '1d' or PT10M");
    }

    private static Duration nonNegative(String name, Duration duration) {
        if (duration.isNegative()) {
            throw invalid(name + " cannot be negative");
        }
        return duration;
    }

    static PravahaException invalid(String message) {
        return new PravahaException(AlertErrors.DEFINITION_INVALID, message);
    }
}
