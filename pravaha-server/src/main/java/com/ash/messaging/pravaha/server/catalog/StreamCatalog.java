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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.sql.SqlErrors;

/**
 * The streams this node knows about.
 *
 * <p>In-memory for now; the Raft-backed catalog arrives with the cluster in Wave 8. What matters
 * already is that a registered schema is <strong>immutable</strong> and versioned: a running query
 * keeps the version it was planned against, so registering a new version cannot change the meaning
 * of a query already in flight (design section 11.4).
 */
@Component
public class StreamCatalog {

    private final Map<String, StreamSchema> streams = new LinkedHashMap<>();

    private final Map<String, StreamSchema> lookups = new LinkedHashMap<>();

    public synchronized StreamSchema register(StreamSchema schema) {
        StreamSchema existing = streams.get(schema.name());
        if (existing != null && existing.version() == schema.version()) {
            // DOCX-19: PRV-1014, not PRV-2002. This is a declaration conflicting with what the
            // catalog already holds -- from pravaha.streams or from POST /api/v1/streams -- and
            // not a SQL statement failing validation. Both codes are 400 over HTTP, so the status
            // a client sees is unchanged; what changes is which range the number sends a reader to.
            throw new PravahaException(
                    ConfigErrors.STREAM_VERSION_IN_USE,
                    "stream '" + schema.name() + "' version " + schema.version()
                            + " is already registered. Schema versions are immutable; register a new "
                            + "version rather than replacing one a query may be planned against.");
        }
        streams.put(schema.name(), schema);
        return schema;
    }

    /**
     * Records a dimension table, so SQL that joins one can be validated and explained.
     *
     * <p>Kept apart from the streams: the planner treats the two differently, and a dimension
     * registered as a stream plans a lookup join as a stream-to-stream join that waits forever. The
     * REST surface used to have no way to say which a schema was, so `POST /queries/validate` on a
     * lookup query said the table did not exist -- for a query the registry would accept.
     */
    public synchronized StreamSchema registerLookup(StreamSchema schema) {
        lookups.put(schema.name(), schema);
        return schema;
    }

    /** The dimension tables, which are not streams and must not be planned as ones. */
    public synchronized Collection<StreamSchema> lookups() {
        return List.copyOf(lookups.values());
    }

    public synchronized Optional<StreamSchema> find(String name) {
        return Optional.ofNullable(streams.get(name));
    }

    public synchronized StreamSchema require(String name) {
        return find(name)
                .orElseThrow(() -> new PravahaException(
                        SqlErrors.UNKNOWN_STREAM,
                        // SX-5. This appended every declared stream's name, and GET /api/v1/streams/{name}
                        // reaches it for any name the caller's policy allows -- so a principal allowed an
                        // invented name was handed the node's whole inventory, including the streams the
                        // listing hides from them. The listing is the filtered way to find names.
                        "no stream named '" + name + "' is declared on this node. The declared streams are "
                                + "not listed here, because that would tell a caller who may not read them "
                                + "that they exist; GET /api/v1/streams lists the ones you may read."));
    }

    /**
     * A schema with its event-time column marked, and the stream's own out-of-orderness.
     *
     * <p>Rebuilt rather than mutated, because a schema is immutable: a running query keeps the version
     * it was planned against. Shared by the configuration path ({@code pravaha.streams.<n>}) and
     * {@code POST /api/v1/streams}, so the two declare event time the same way.
     *
     * @param column the event-time column, or null/blank for a stream with none
     * @param outOfOrderness how late rows may be, or null for the engine default; meaningless, and
     *     refused, without an event-time column
     */
    public static StreamSchema withEventTime(StreamSchema parsed, String column, java.time.Duration outOfOrderness) {
        return withEventTime(parsed, column, outOfOrderness, null);
    }

    /**
     * As {@link #withEventTime(StreamSchema, String, java.time.Duration)}, with the stream's allowed
     * lateness too (HLP-7).
     *
     * @param allowedLateness how long after a window closes a late row may still correct it, or null
     *     for none; refused without an event-time column, and refused negative
     */
    public static StreamSchema withEventTime(
            StreamSchema parsed, String column, java.time.Duration outOfOrderness, java.time.Duration allowedLateness) {
        if (allowedLateness != null && allowedLateness.isNegative()) {
            throw refused(
                    parsed.name(),
                    "allowed-lateness",
                    "gives a negative allowed lateness, " + allowedLateness
                            + ". It is how long a closed window stays open to a late correction; zero means "
                            + "none.");
        }
        if (outOfOrderness != null && outOfOrderness.isNegative()) {
            // Here rather than only in StreamSchema.Builder.outOfOrderness, which raises a bare
            // IllegalArgumentException carrying neither the stream nor the key (TIME-9).
            throw refused(
                    parsed.name(),
                    "out-of-orderness",
                    "gives a negative out-of-orderness, " + outOfOrderness
                            + ". It is how late a row's event time may be before the engine stops waiting; "
                            + "zero claims the source is strictly ordered, which the engine will hold you to.");
        }
        if (column == null || column.isBlank()) {
            if (allowedLateness != null) {
                throw refused(
                        parsed.name(),
                        "allowed-lateness",
                        "gives an allowed lateness and no event-time column. Allowed lateness is how long a "
                                + "window "
                                + "stays open to a late row after it closes, so it needs an event time to be "
                                + "about.");
            }
            if (outOfOrderness != null) {
                throw refused(
                        parsed.name(),
                        "out-of-orderness",
                        "gives an out-of-orderness and no event-time column. Out-of-orderness is how late a "
                                + "row's "
                                + "event time may be, so it needs an event time to be about.");
            }
            return parsed;
        }
        String eventTime = column.strip();
        if (!parsed.hasField(eventTime)) {
            // DOCX-19: PRV-1013, the code for a stream declaration's time settings. See refused().
            throw new PravahaException(
                    ConfigErrors.STREAM_EVENT_TIME_INVALID,
                    "stream '" + parsed.name() + "' declares '" + eventTime + "' as its event time and has no such "
                            + "column. Its columns are "
                            + parsed.fields().stream()
                                    .map(com.ash.messaging.pravaha.api.data.Field::name)
                                    .toList()
                            + ".");
        }
        StreamSchema.Builder builder = StreamSchema.builder(parsed.name());
        parsed.fields().forEach(field -> builder.field(field.name(), field.type()));
        builder.eventTime(eventTime);
        if (outOfOrderness != null) {
            builder.outOfOrderness(outOfOrderness);
        }
        if (allowedLateness != null) {
            builder.allowedLateness(allowedLateness);
        }
        try {
            return builder.build();
        } catch (IllegalArgumentException e) {
            // TIME-9. The builder's remaining refusal -- an event-time column that is not a
            // TIMESTAMP -- was a bare IllegalArgumentException with no code, no stream name and no
            // configuration key, whose primary output was a Spring ApplicationContextException
            // stack trace. One line away in this same method, a *misspelt* column already got
            // PRV-2002 naming the stream and listing its columns, so the bar was demonstrably met
            // for one of two mistakes an operator makes in the same YAML block. Wrapped here
            // rather than fixed in StreamSchema, which is in pravaha-api and cannot know either
            // the key or which surface the value came through.
            throw refused(parsed.name(), "event-time", "cannot declare its event time: " + e.getMessage() + ".");
        }
    }

    /**
     * One shape for every refusal of a stream's event-time declaration (TIME-9).
     *
     * <p>Code, stream, configuration key, the rejected value and what it would do -- the shape
     * {@code pravaha.watermark.idle-after}'s four refusals already had and these did not. Both
     * surfaces are named because this is the one implementation behind both: the key for a node
     * described by a file, and the field for {@code POST /api/v1/streams}.
     */
    private static PravahaException refused(String stream, String setting, String what) {
        return new PravahaException(
                // DOCX-19: PRV-1013, not PRV-2002. Every refusal that comes through here names a
                // configuration key -- pravaha.streams.<n>.event-time, .out-of-orderness,
                // .allowed-lateness -- or the REST field that spells the same setting. A node that
                // will not start over one of those is not a SQL problem, and the ranges table puts
                // 2xxx under SQL, so the number was sending the reader to their query.
                ConfigErrors.STREAM_EVENT_TIME_INVALID,
                "stream '" + stream + "' " + what + " Set by pravaha.streams." + stream + "." + setting + ", or by '"
                        + camel(setting) + "' on POST /api/v1/streams.");
    }

    /** {@code out-of-orderness} as the REST body spells it, so the message names both surfaces. */
    private static String camel(String setting) {
        StringBuilder text = new StringBuilder();
        boolean up = false;
        for (char c : setting.toCharArray()) {
            if (c == '-') {
                up = true;
            } else {
                text.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return text.toString();
    }

    public synchronized Collection<StreamSchema> all() {
        return java.util.List.copyOf(streams.values());
    }

    public synchronized boolean contains(String name) {
        return streams.containsKey(name);
    }

    public synchronized int size() {
        return streams.size();
    }
}
