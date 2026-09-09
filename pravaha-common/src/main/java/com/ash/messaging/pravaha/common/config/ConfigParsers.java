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
package com.ash.messaging.pravaha.common.config;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * Converts configuration text into typed values.
 *
 * <p>Every failure names the key, the offending text and the accepted forms. A configuration error
 * is read by someone who is stuck, usually at an inconvenient hour, so "cannot parse" on its own is
 * not an acceptable message.
 */
final class ConfigParsers {

    private ConfigParsers() {}

    static boolean parseBoolean(String key, String text) {
        String v = text.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw fail(ConfigErrors.NOT_A_BOOLEAN, key, text, "true/false, yes/no, on/off or 1/0");
        };
    }

    static long parseLong(String key, String text) {
        String v = text.strip().replace("_", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw fail(ConfigErrors.NOT_A_NUMBER, key, text, "a whole number, e.g. 16 or 1_000_000");
        }
    }

    static double parseDouble(String key, String text) {
        try {
            return Double.parseDouble(text.strip().replace("_", ""));
        } catch (NumberFormatException e) {
            throw fail(ConfigErrors.NOT_A_NUMBER, key, text, "a number, e.g. 0.8 or 1e6");
        }
    }

    /**
     * Parses a duration such as {@code 200us}, {@code 5s}, {@code 30min}, {@code 1h}.
     *
     * <p>Nanoseconds through days, because this engine genuinely spans that range: a batch linger is
     * measured in microseconds and a checkpoint retention in hours. A bare number is rejected rather
     * than assumed to be milliseconds -- guessing the unit is how a 30-second timeout silently
     * becomes 30 milliseconds.
     */
    static Duration parseDuration(String key, String text) {
        String v = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        int split = 0;
        while (split < v.length()
                && (Character.isDigit(v.charAt(split))
                        || v.charAt(split) == '.'
                        || v.charAt(split) == '-'
                        || v.charAt(split) == '_')) {
            split++;
        }
        String number = v.substring(0, split).replace("_", "");
        String unit = v.substring(split);
        if (number.isEmpty() || unit.isEmpty()) {
            throw fail(
                    ConfigErrors.NOT_A_DURATION,
                    key,
                    text,
                    "a number with a unit: ns, us, ms, s, min, h or d (e.g. 200us, 30s, 5min)");
        }
        double amount;
        try {
            amount = Double.parseDouble(number);
        } catch (NumberFormatException e) {
            throw fail(ConfigErrors.NOT_A_DURATION, key, text, "a number with a unit, e.g. 30s");
        }
        long nanosPerUnit =
                switch (unit) {
                    case "ns", "nanos", "nanosecond", "nanoseconds" -> 1L;
                    case "us", "micros", "microsecond", "microseconds" -> 1_000L;
                    case "ms", "millis", "millisecond", "milliseconds" -> 1_000_000L;
                    case "s", "sec", "secs", "second", "seconds" -> 1_000_000_000L;
                    case "m", "min", "mins", "minute", "minutes" -> 60L * 1_000_000_000L;
                    case "h", "hr", "hrs", "hour", "hours" -> 3_600L * 1_000_000_000L;
                    case "d", "day", "days" -> 86_400L * 1_000_000_000L;
                    default ->
                        throw fail(ConfigErrors.NOT_A_DURATION, key, text, "a known unit: ns, us, ms, s, min, h or d");
                };
        return Duration.ofNanos(Math.round(amount * nanosPerUnit));
    }

    /**
     * Parses a byte size such as {@code 4MB}, {@code 256MB}, {@code 1GB}.
     *
     * <p>Units are binary (1 KB = 1024 B), because every setting this parses is a memory or buffer
     * size, where binary is what the underlying allocation actually does.
     */
    static long parseDataSize(String key, String text) {
        String v = text.strip().toUpperCase(Locale.ROOT).replace(" ", "").replace("_", "");
        int split = 0;
        while (split < v.length() && (Character.isDigit(v.charAt(split)) || v.charAt(split) == '.')) {
            split++;
        }
        String number = v.substring(0, split);
        String unit = v.substring(split);
        if (number.isEmpty()) {
            throw fail(ConfigErrors.NOT_A_DATA_SIZE, key, text, "a number with an optional unit, e.g. 4MB");
        }
        double amount;
        try {
            amount = Double.parseDouble(number);
        } catch (NumberFormatException e) {
            throw fail(ConfigErrors.NOT_A_DATA_SIZE, key, text, "a number with an optional unit, e.g. 4MB");
        }
        long multiplier =
                switch (unit) {
                    case "", "B", "BYTE", "BYTES" -> 1L;
                    case "K", "KB", "KIB" -> 1024L;
                    case "M", "MB", "MIB" -> 1024L * 1024L;
                    case "G", "GB", "GIB" -> 1024L * 1024L * 1024L;
                    case "T", "TB", "TIB" -> 1024L * 1024L * 1024L * 1024L;
                    default -> throw fail(ConfigErrors.NOT_A_DATA_SIZE, key, text, "a known unit: B, KB, MB, GB or TB");
                };
        return Math.round(amount * multiplier);
    }

    static <E extends Enum<E>> E parseEnum(String key, String text, Class<E> type) {
        String v = text.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(v)) {
                return constant;
            }
        }
        List<String> allowed =
                java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
        throw fail(ConfigErrors.NOT_AN_ENUM, key, text, "one of " + allowed);
    }

    /** Splits a comma-separated list, trimming blanks. An empty string is an empty list, not {@code [""]}. */
    static List<String> parseList(String text) {
        if (text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static ConfigurationException fail(ErrorCode code, String key, String text, String expected) {
        return new ConfigurationException(
                code, "'" + key + "' is " + Redaction.mask(key, text) + "; expected " + expected);
    }
}
