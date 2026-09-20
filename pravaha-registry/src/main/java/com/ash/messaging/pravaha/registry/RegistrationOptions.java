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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.serving.Retention;

/**
 * What a plain {@code CREATE CONTINUOUS QUERY ... WITH (...)} can say.
 *
 * <p>The list is not a new surface. It is the arguments {@link QueryRegistry}'s registration
 * overloads already take, said in SQL rather than passed to a method: the view's retention, the
 * sink its changelog goes to, and the columns it is keyed by. Everything else a caller can choose
 * about a registration is chosen by the statement itself, and an option that would be a second way
 * to say the same thing is refused as saying it twice rather than quietly winning.
 *
 * <p><strong>Why these three and nothing else.</strong> A {@code WITH} list is a place where
 * unknown words go unnoticed, which is why {@code PRV-2072} refused the whole list until B8: an
 * ignored {@code 'retention' = '24h'} is a view kept for ever that somebody asked to keep for a
 * day. Accepting the list does not change that judgement, it moves it -- every option is either
 * built or refused by name with {@code PRV-8017} and the list of the ones that exist. The design's
 * own examples name two that are not built, {@code consistency.default} (section 17.2) and {@code
 * parallelism} (section 11.2), and both are refused rather than accepted and dropped.
 *
 * <p>A replacement's options -- {@code backfill}, {@code cutover} and the rest -- live in {@link
 * ReplacementOptions} and are read from the same {@code WITH (...)} written by the same parser.
 * Two vocabularies, one grammar: the statement decides which vocabulary applies, and each refuses
 * the other's options by name rather than silently accepting a word it has no use for.
 *
 * @param retention the view's retention, or empty for the registry's default
 * @param sink the sink binding the changelog is written to, or empty for a view-only registration
 * @param keyColumns the output columns the view is keyed by, by name, or empty when the statement
 *     said it with {@code KEYED BY}
 */
public record RegistrationOptions(Optional<Retention> retention, Optional<String> sink, List<String> keyColumns) {

    public RegistrationOptions {
        java.util.Objects.requireNonNull(retention, "retention");
        java.util.Objects.requireNonNull(sink, "sink");
        keyColumns = List.copyOf(keyColumns);
    }

    /** Nothing said: the registry's defaults, and the statement's own clauses. */
    public static RegistrationOptions defaults() {
        return new RegistrationOptions(Optional.empty(), Optional.empty(), List.of());
    }

    /** Every option this engine builds, in the order the refusal lists them. */
    public static final List<String> KNOWN = List.of("retention", "sink", "keys");

    /** Reads a whole {@code WITH (...)} list. */
    public static RegistrationOptions of(Map<String, String> options) {
        RegistrationOptions read = defaults();
        for (Map.Entry<String, String> option : options.entrySet()) {
            read = with(read, option.getKey(), option.getValue());
        }
        return read;
    }

    /**
     * One option by the name the {@code WITH (...)} list uses.
     *
     * @throws PravahaException {@code PRV-8017} for an option this engine does not build, named
     *     rather than ignored
     */
    public static RegistrationOptions with(RegistrationOptions options, String key, String value) {
        return switch (key) {
            case "retention" ->
                new RegistrationOptions(Optional.of(retention(value)), options.sink(), options.keyColumns());
            case "sink" -> {
                if (value.isBlank()) {
                    throw unknown("sink", "a sink option names a binding under pravaha.sinks; it cannot be empty");
                }
                yield new RegistrationOptions(options.retention(), Optional.of(value.strip()), options.keyColumns());
            }
            case "key", "keys" -> {
                List<String> columns = new ArrayList<>();
                for (String column : value.split(",")) {
                    if (!column.isBlank()) {
                        columns.add(column.strip());
                    }
                }
                if (columns.isEmpty()) {
                    throw unknown(
                            key,
                            "a keys option names the view's key columns, comma-separated, as the SELECT list "
                                    + "spells them; an empty one says nothing");
                }
                yield new RegistrationOptions(options.retention(), options.sink(), columns);
            }
            default ->
                throw unknown(
                        key,
                        "'" + key + "' is not an option a registration takes, and it is refused rather than "
                                + "ignored -- an ignored option is a setting somebody believes is in force. A "
                                + "registration takes retention (a duration, or 'forever'), sink (a binding "
                                + "under pravaha.sinks) and keys (the view's key columns, comma-separated). "
                                + "backfill, backfill.rate.limit, cutover and rollback.retention belong to "
                                + "CREATE OR REPLACE, which is the statement that runs one. The design's "
                                + "consistency.default, parallelism and allowed.lateness are not built: "
                                + "consistency is chosen by the reader and per read, and a query's "
                                + "parallelism and lateness are the engine's to decide.");
        };
    }

    /**
     * A retention, written as the design's examples write it.
     *
     * <p>{@code 'forever'}, an ISO-8601 duration ({@code PT24H}, {@code P7D}), or the short form
     * design section 17.2 uses: {@code 24h}, {@code 30m}, {@code 90s}, {@code 7d}. All three because
     * {@code RETAIN FOR} already accepts the first two and the design's own example is the third,
     * and a grammar that refuses the document it came from is a grammar somebody has to be warned
     * about.
     */
    static Retention retention(String value) {
        String text = value.strip();
        if (text.equalsIgnoreCase("forever") || text.equalsIgnoreCase("never")) {
            return Retention.forever();
        }
        Duration age = shortForm(text);
        if (age == null) {
            try {
                age = Duration.parse(text.toUpperCase(Locale.ROOT));
            } catch (java.time.format.DateTimeParseException e) {
                throw unknown(
                        "retention",
                        "retention = '" + value + "' is not a length of event time. Write it as ISO-8601 "
                                + "(PT24H, P7D, PT30M), in the short form (24h, 7d, 30m, 90s), or as 'forever'.");
            }
        }
        if (age.isZero() || age.isNegative()) {
            throw unknown("retention", "a retention must be a positive age of event time, not " + age);
        }
        return Retention.ofAge(age);
    }

    /** {@code 24h}, {@code 7d}, {@code 30m}, {@code 90s}, {@code 2w}; null when it is not one. */
    private static Duration shortForm(String text) {
        if (!text.matches("(?i)[0-9]{1,9}\\s*[smhdw]")) {
            return null;
        }
        long amount = Long.parseLong(text.substring(0, text.length() - 1).strip());
        return switch (Character.toLowerCase(text.charAt(text.length() - 1))) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> Duration.ofDays(amount * 7);
        };
    }

    private static PravahaException unknown(String key, String problem) {
        return new PravahaException(
                RegistryErrors.OPTION_UNKNOWN,
                problem + " The options a CREATE CONTINUOUS QUERY takes are " + KNOWN + ".");
    }
}
