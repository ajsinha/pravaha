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
package com.ash.messaging.pravaha.server.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code pravaha.streams.*}: the streams this node knows about, declared in configuration.
 *
 * <pre>
 * pravaha:
 *   streams:
 *     txn:
 *       schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"
 * </pre>
 *
 * <p>Until now the only way to put a schema in the catalog was {@code POST /api/v1/streams}, so a
 * node could not be described by a file: every restart needed someone to make an HTTP call before
 * any query would plan. A stream that is bound to a source under {@code pravaha.sources} but never
 * declared produced "Object 'txn' not found. Known streams: []" -- accurate, and baffling next to a
 * configuration file that clearly mentions {@code txn}.
 *
 * <p>The spec form is the plugin's own ({@code name:TYPE,...}), the same spelling used by the CLI,
 * the REST API and a filesystem binding's {@code schema} option. One spelling of a schema across
 * every surface is worth more than a tidier one here that has to be learned separately.
 */
@Component
@ConfigurationProperties(prefix = "pravaha")
public class StreamDeclarationProperties {

    private final Map<String, Declaration> streams = new LinkedHashMap<>();

    public Map<String, Declaration> getStreams() {
        return streams;
    }

    /** One stream's schema, as written in configuration. */
    public static class Declaration {

        private @Nullable String schema;
        private @Nullable String eventTime;

        // Seconds, not Spring's default of milliseconds. See getOutOfOrderness (TIME-3). The
        // annotation targets a field rather than a method, and the field is where Spring's
        // JavaBeanBinder reads it from.
        @org.springframework.boot.convert.DurationUnit(java.time.temporal.ChronoUnit.SECONDS)
        private java.time.@Nullable Duration outOfOrderness;

        // And its twin, for the same reason and in the same breath: `allowed-lateness: 30` bound as
        // thirty milliseconds, which is indistinguishable from none.
        @org.springframework.boot.convert.DurationUnit(java.time.temporal.ChronoUnit.SECONDS)
        private java.time.@Nullable Duration allowedLateness;

        public @Nullable String getSchema() {
            return schema;
        }

        public void setSchema(@Nullable String schema) {
            this.schema = schema;
        }

        /**
         * The column carrying this stream's event time.
         *
         * <p>Without it no watermark can advance, and without a watermark no window ever closes: a
         * windowed query plans, registers, reports RUNNING, ingests every row and emits nothing, for
         * ever. The {@code name:TYPE} schema grammar has no syntax for marking a column, and nothing
         * else on the server ever called {@code StreamSchema.Builder.eventTime} -- so windowing, the
         * feature the engine exists for, was unreachable from configuration.
         */
        public @Nullable String getEventTime() {
            return eventTime;
        }

        public void setEventTime(@Nullable String eventTime) {
            this.eventTime = eventTime;
        }

        /**
         * How late this stream's rows may be. Overrides the engine default for this stream only.
         *
         * <p><strong>A unitless number is seconds</strong> (TIME-3). Spring's relaxed binding reads
         * one into a {@code Duration} as <em>milliseconds</em> unless told otherwise, and this
         * carried no annotation: {@code out-of-orderness: 60} meant sixty milliseconds, gave 11
         * windows and a last total of 1045 -- the same two numbers as the ten-second default, so
         * the operator who meant a minute could not see the difference at the only surface that
         * could have shown it. There is no bound that catches it either, because sixty milliseconds
         * is a perfectly legitimate out-of-orderness; the key one line above,
         * {@code pravaha.watermark.idle-after}, is saved by having a one-second minimum, and this
         * has nothing to be saved by. Seconds is the unit lateness is discussed in, so a unitless
         * number now means what somebody writing one meant, and every explicit form
         * ({@code 60s}, {@code PT1M}, {@code 60ms}) is unchanged. The effective value is logged per
         * stream at startup (TIME-6), which is the other half: a setting nothing states cannot be
         * checked.
         */
        public java.time.@Nullable Duration getOutOfOrderness() {
            return outOfOrderness;
        }

        public void setOutOfOrderness(java.time.@Nullable Duration outOfOrderness) {
            this.outOfOrderness = outOfOrderness;
        }

        /**
         * How long after a window closes a late row may still correct it (HLP-7).
         *
         * <p>{@code StreamSchema.allowedLateness}, which the planner hands every windowed aggregate
         * over the stream, and which nothing on a server could set: a late row was always dropped,
         * though the correction path -- a retraction of the published window and the corrected one
         * -- was built. Zero, the default, keeps a window final when it closes. Non-zero makes a
         * windowed query revise its answers, so it can no longer write to an append-only sink
         * ({@code PRV-2041}).
         *
         * <p>A unitless number is <strong>seconds</strong>, as {@link #getOutOfOrderness()}'s is
         * and for the same reason (TIME-3): {@code allowed-lateness: 30} used to bind as thirty
         * milliseconds, which is indistinguishable from the zero default.
         */
        public java.time.@Nullable Duration getAllowedLateness() {
            return allowedLateness;
        }

        public void setAllowedLateness(java.time.@Nullable Duration allowedLateness) {
            this.allowedLateness = allowedLateness;
        }
    }
}
