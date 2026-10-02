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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.backfill.BackfillErrors;

/**
 * How a blue/green replacement is to be run (ADR-046, design section 16.2-16.3).
 *
 * <p>Four decisions, and each has a default that is the safe direction rather than the fast one:
 * replay the history, read it as fast as the source allows, wait for a human to cut over, and keep
 * the replaced version for an hour afterwards.
 *
 * @param backfill whether the new version replays history or starts from where the running version
 *     is
 * @param rateLimit records a second the backfill may read, or zero for no limit. The ceiling, not a
 *     suggestion: an operator may lower it while it runs and may not raise it above this
 * @param cutover whether the cutover waits for somebody to ask for it, or happens as soon as the
 *     new version has caught up
 * @param rollbackRetention how long the replaced version keeps running after the cutover so a
 *     rollback is instant
 * @param lane which lane the new version runs on: {@link Lane#KEEP}, the default, keeps the running
 *     version's choice; {@code lane = 'dedicated'} or {@code 'shared'} changes it, and is how a running
 *     query moves between a shared lane and one of its own -- even with the SQL unchanged
 */
public record ReplacementOptions(
        Backfill backfill, long rateLimit, Cutover cutover, Duration rollbackRetention, Lane lane) {

    /** The lane the new version runs on, as {@code lane = '...'} says it. */
    public enum Lane {
        /** Whatever the running version was registered with. */
        KEEP,

        /** A lane of its own, whatever the node's lane-sharing mode. */
        DEDICATED,

        /** The node's lane-sharing mode decides, as for a registration that did not say. */
        SHARED,

        /**
         * A lane of its own for this version, without pinning it there: what an administrator's
         * rebalance moves a query from a shared lane onto. Not journalled as {@code dedicated}, so a
         * restart places the query by the node's mode again.
         */
        OWN
    }

    /** The first four decisions, keeping the running version's lane. */
    public ReplacementOptions(Backfill backfill, long rateLimit, Cutover cutover, Duration rollbackRetention) {
        this(backfill, rateLimit, cutover, rollbackRetention, Lane.KEEP);
    }

    /** Where the new version's state comes from. */
    public enum Backfill {
        /**
         * Replay the source from the beginning, to the position the running version has reached,
         * and splice onto the live stream there. What makes the new version's answer the one it
         * would have had if it had always been running.
         */
        HISTORY,

        /**
         * Start at the running version's position with empty state. Cheap, and correct only for a
         * query whose answer does not depend on history -- a filter or a projection. An aggregate
         * started this way is missing everything before the cutover and says nothing about it.
         */
        NONE
    }

    /** Who decides the moment. */
    public enum Cutover {
        /** Somebody asks. The default, because a cutover is a decision with consequences. */
        MANUAL,

        /** As soon as the new version has caught up. For a pipeline that has already been reviewed. */
        AUTO
    }

    /** An hour, as design section 16.3 specifies for {@code rollback.retention}. */
    public static final Duration DEFAULT_ROLLBACK_RETENTION = Duration.ofHours(1);

    public ReplacementOptions {
        if (backfill == null || cutover == null) {
            throw new IllegalArgumentException("a replacement needs a backfill mode and a cutover mode");
        }
        if (rateLimit < 0) {
            throw new IllegalArgumentException("a backfill rate limit cannot be negative, got " + rateLimit);
        }
        if (rollbackRetention == null || rollbackRetention.isNegative()) {
            throw new IllegalArgumentException("a rollback retention cannot be negative");
        }
        lane = lane == null ? Lane.KEEP : lane;
    }

    public static ReplacementOptions defaults() {
        return new ReplacementOptions(Backfill.HISTORY, 0, Cutover.MANUAL, DEFAULT_ROLLBACK_RETENTION);
    }

    public ReplacementOptions withBackfill(Backfill mode) {
        return new ReplacementOptions(mode, rateLimit, cutover, rollbackRetention, lane);
    }

    public ReplacementOptions withRateLimit(long recordsPerSecond) {
        return new ReplacementOptions(backfill, recordsPerSecond, cutover, rollbackRetention, lane);
    }

    public ReplacementOptions withCutover(Cutover mode) {
        return new ReplacementOptions(backfill, rateLimit, mode, rollbackRetention, lane);
    }

    public ReplacementOptions withLane(Lane choice) {
        return new ReplacementOptions(backfill, rateLimit, cutover, rollbackRetention, choice);
    }

    public ReplacementOptions withRollbackRetention(Duration retention) {
        return new ReplacementOptions(backfill, rateLimit, cutover, retention, lane);
    }

    /**
     * The options as {@code CREATE OR REPLACE ... WITH (...)} writes them, and as the journal keeps
     * them: {@code backfill=history;backfill.rate.limit=1000;cutover=manual;rollback.retention=PT1H}.
     */
    @Override
    public String toString() {
        return "backfill=" + backfill.name().toLowerCase(Locale.ROOT)
                + ";backfill.rate.limit=" + rateLimit
                + ";cutover=" + cutover.name().toLowerCase(Locale.ROOT)
                + ";rollback.retention=" + rollbackRetention
                // Only when said, so a replacement that did not say reads as it always has.
                + (lane == Lane.KEEP ? "" : ";lane=" + lane.name().toLowerCase(Locale.ROOT));
    }

    /** Reads back what {@link #toString} wrote. Anything missing keeps its default. */
    public static ReplacementOptions parse(String encoded) {
        ReplacementOptions options = defaults();
        if (encoded == null || encoded.isBlank()) {
            return options;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : encoded.split(";", -1)) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                fields.put(
                        part.substring(0, equals).strip().toLowerCase(Locale.ROOT),
                        part.substring(equals + 1).strip());
            }
        }
        for (Map.Entry<String, String> field : fields.entrySet()) {
            options = with(options, field.getKey(), field.getValue());
        }
        return options;
    }

    /**
     * One option by the name a {@code WITH (...)} list uses, so the SQL surface and the journal read
     * the same words.
     *
     * @throws PravahaException {@code PRV-4018} for an option this engine does not build, named
     *     rather than ignored -- an ignored {@code backfill.window} is a backfill that read three
     *     years when it was told to read a week
     */
    public static ReplacementOptions with(ReplacementOptions options, String key, String value) {
        return switch (key) {
            case "backfill" ->
                options.withBackfill(
                        switch (value.toLowerCase(Locale.ROOT)) {
                            case "history", "true", "yes" -> Backfill.HISTORY;
                            case "none", "false", "no" -> Backfill.NONE;
                            default ->
                                throw new PravahaException(
                                        BackfillErrors.SOURCE_UNSUPPORTED,
                                        "backfill = '" + value
                                                + "' is not a backfill this engine runs. It is 'history' "
                                                + "-- replay the source and splice onto the live stream -- or 'none', "
                                                + "which starts the new version where the running one is, with empty "
                                                + "state.");
                        });
            case "backfill.rate.limit" -> options.withRateLimit(positive(key, value));
            case "cutover" ->
                options.withCutover(
                        switch (value.toLowerCase(Locale.ROOT)) {
                            case "manual" -> Cutover.MANUAL;
                            case "auto", "automatic" -> Cutover.AUTO;
                            default ->
                                throw new PravahaException(
                                        BackfillErrors.SOURCE_UNSUPPORTED,
                                        "cutover = '" + value + "' is neither 'manual' nor 'auto'.");
                        });
            case "rollback.retention" -> options.withRollbackRetention(duration(key, value));
            case "lane" ->
                options.withLane(
                        switch (value.strip().toLowerCase(Locale.ROOT)) {
                            case "dedicated" -> Lane.DEDICATED;
                            case "shared" -> Lane.SHARED;
                            case "own" -> Lane.OWN;
                            default ->
                                throw new PravahaException(
                                        BackfillErrors.SOURCE_UNSUPPORTED,
                                        "lane = '" + value + "' is not 'dedicated' (a lane of its own, kept), "
                                                + "'own' (a lane of its own for now, as a rebalance moves a query) "
                                                + "or 'shared' (the node's lane-sharing mode decides).");
                        });
            default ->
                throw new PravahaException(
                        BackfillErrors.SOURCE_UNSUPPORTED,
                        "'" + key + "' is not an option this engine builds, and it is refused rather than "
                                + "ignored. A replacement takes backfill (history | none), backfill.rate.limit, "
                                + "cutover (manual | auto), rollback.retention and lane (dedicated | shared). The design's "
                                + "backfill.parallelism, backfill.window and backfill.adaptive are not built: "
                                + "a backfill reads each partition once, from the beginning, at the rate you "
                                + "set.");
        };
    }

    private static long positive(String key, String value) {
        try {
            long parsed = Long.parseLong(value.strip());
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new PravahaException(
                    BackfillErrors.SOURCE_UNSUPPORTED,
                    "'" + key + " = " + value + "' is not a number of records a second; zero means no limit.");
        }
    }

    private static Duration duration(String key, String value) {
        try {
            return Duration.parse(value.strip().toUpperCase(Locale.ROOT));
        } catch (java.time.format.DateTimeParseException e) {
            throw new PravahaException(
                    BackfillErrors.SOURCE_UNSUPPORTED,
                    "'" + key + " = " + value + "' is not an ISO-8601 duration, such as PT1H or PT30M.");
        }
    }
}
