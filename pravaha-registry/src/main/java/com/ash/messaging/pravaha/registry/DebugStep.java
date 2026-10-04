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

import java.util.List;
import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.serving.ViewChange;

/**
 * What one step of a debug session did (ADR-048).
 *
 * <p>Everything a person needs to say "and that is where it went wrong", in one answer: the rows
 * that went in, what every operator did with them, what came out of the view and with what weight,
 * and where event time now stands. A screen that had to ask four times would be showing four
 * moments.
 *
 * <p>The report is the unit of determinism: two sessions forked from the same checkpoint and given
 * the same steps produce equal reports, field for field. {@code DebugDeterminismTest} is that
 * sentence as a test.
 */
public record DebugStep(
        String sessionId,
        long sequence,
        Kind kind,
        List<InputRow> rowsIn,
        List<Operator> operators,
        List<ViewChange> viewChanges,
        OptionalLong watermarkNanos,
        long rowsConsumed,
        int viewSize,
        boolean exhausted,
        String stopped) {

    public DebugStep {
        rowsIn = List.copyOf(rowsIn);
        operators = List.copyOf(operators);
        viewChanges = List.copyOf(viewChanges);
    }

    /** How a step was asked to advance. */
    public enum Kind {
        /** One input row, whichever partition offers it next. */
        ROW,
        /** Up to {@code count} input rows. */
        ROWS,
        /** Rows until the view's commit actually changes something. */
        COMMIT,
        /** No rows: event time advances to a watermark and any window it closes fires. */
        WATERMARK,
        /** Rows until a predicate over the view holds, or the ceiling is reached. */
        UNTIL
    }

    /** One row that entered the query at this step, as it was read. */
    public record InputRow(
            String stream, int partition, String offset, long weight, long eventTimeNanos, List<String> values) {

        public InputRow {
            values = List.copyOf(values);
        }
    }

    /** What one operator of the plan did during this step. */
    public record Operator(String id, String kind, String label, long rowsIn, long rowsOut) {}

    /**
     * What a caller asks for. Built through the factories, so an unreadable request is refused
     * where it is made rather than half-way through a step.
     */
    public record Request(
            Kind kind,
            long count,
            long watermarkNanos,
            @Nullable String column,
            @Nullable String comparison,
            @Nullable String value) {

        /** One input row. */
        public static Request row() {
            return new Request(Kind.ROW, 1, 0, null, null, null);
        }

        /** Up to {@code count} input rows. */
        public static Request rows(long count) {
            if (count < 1) {
                throw new PravahaException(
                        DebugErrors.BAD_STEP, "a step of " + count + " rows is not a step; ask for at least one");
            }
            return new Request(Kind.ROWS, count, 0, null, null, null);
        }

        /** Rows until the view commits a change. */
        public static Request toCommit() {
            return new Request(Kind.COMMIT, 0, 0, null, null, null);
        }

        /** Advance event time, firing whatever windows that closes. No rows are consumed. */
        public static Request toWatermark(long nanos) {
            return new Request(Kind.WATERMARK, 0, nanos, null, null, null);
        }

        /** Rows until one of the view's rows satisfies {@code column comparison value}. */
        public static Request until(String column, String comparison, String value) {
            return new Request(Kind.UNTIL, 0, 0, column, comparison, value);
        }

        /**
         * Reads a request off a wire, where everything is text.
         *
         * <p>{@code row}, {@code rows:N}, {@code commit}, {@code watermark:NANOS}, {@code
         * until:column:op:value}. One field rather than six, because every surface this travels
         * over -- a Flight action's fields, a CLI flag, a JSON body -- would otherwise have to
         * agree on the same six and none of them wants five nulls.
         */
        public static Request parse(@Nullable String text) {
            String request = text == null ? "" : text.strip();
            if (request.isEmpty() || request.equalsIgnoreCase("row")) {
                return row();
            }
            String[] parts = request.split(":", 4);
            String verb = parts[0].strip().toLowerCase(java.util.Locale.ROOT);
            return switch (verb) {
                case "row" -> row();
                case "rows" -> rows(number(parts, 1, request));
                case "commit" -> toCommit();
                case "watermark" -> toWatermark(number(parts, 1, request));
                case "until" -> {
                    if (parts.length < 4) {
                        throw new PravahaException(
                                DebugErrors.BAD_STEP,
                                "'" + request + "' is not a predicate step. Write until:<column>:<op>:<value>, "
                                        + "such as until:total:<:0 -- one column of the view, one comparison, "
                                        + "one value.");
                    }
                    yield until(parts[1].strip(), parts[2].strip(), parts[3]);
                }
                default ->
                    throw new PravahaException(
                            DebugErrors.BAD_STEP,
                            "'" + request + "' is not a step. A session steps by row, rows:N, commit, "
                                    + "watermark:<nanos> or until:<column>:<op>:<value>.");
            };
        }

        private static long number(String[] parts, int index, String request) {
            if (parts.length <= index) {
                throw new PravahaException(
                        DebugErrors.BAD_STEP, "'" + request + "' needs a number after the colon, as in rows:10");
            }
            try {
                return Long.parseLong(parts[index].strip());
            } catch (NumberFormatException e) {
                throw new PravahaException(
                        DebugErrors.BAD_STEP, "'" + parts[index].strip() + "' is not a number, in '" + request + "'");
            }
        }

        @Override
        public String toString() {
            return switch (kind) {
                case ROW -> "row";
                case ROWS -> "rows:" + count;
                case COMMIT -> "commit";
                case WATERMARK -> "watermark:" + watermarkNanos;
                case UNTIL -> "until:" + column + ":" + comparison + ":" + value;
            };
        }
    }
}
