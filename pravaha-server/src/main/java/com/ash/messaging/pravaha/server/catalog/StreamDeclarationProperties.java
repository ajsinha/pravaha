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

        private String schema;
        private String eventTime;
        private java.time.Duration outOfOrderness;
        private java.time.Duration allowedLateness;

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
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
        public String getEventTime() {
            return eventTime;
        }

        public void setEventTime(String eventTime) {
            this.eventTime = eventTime;
        }

        /** How late this stream's rows may be. Overrides the engine default for this stream only. */
        public java.time.Duration getOutOfOrderness() {
            return outOfOrderness;
        }

        public void setOutOfOrderness(java.time.Duration outOfOrderness) {
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
         */
        public java.time.Duration getAllowedLateness() {
            return allowedLateness;
        }

        public void setAllowedLateness(java.time.Duration allowedLateness) {
            this.allowedLateness = allowedLateness;
        }
    }
}
