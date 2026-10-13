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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;

/**
 * A stream declaration turned into the schema the catalogue holds, and its source binding into the one
 * the plugin is given.
 *
 * <p>Moved out of {@code PravahaNode} so a stream declared under {@code pravaha.streams} at start and one
 * declared by a file in {@code pravaha.config.streams-dir} while the node runs (ADR-064) go through one
 * implementation: the rules -- schema required, event-time and lateness together, a unitless lateness in
 * seconds -- cannot drift between the two.
 */
public final class StreamDeclarations {

    private StreamDeclarations() {}

    /**
     * The schema a declaration describes, validated.
     *
     * @param where where the declaration was written, for the refusal: {@code pravaha.streams} or a file
     */
    public static StreamSchema schemaOf(
            String name,
            StreamDeclarationProperties.Declaration declaration,
            Duration defaultOutOfOrderness,
            String where) {
        if (declaration.getSchema() == null || declaration.getSchema().isBlank()) {
            // DOCX-19: PRV-1020, not PRV-2002. A key that is missing from the configuration
            // file is not a SQL statement failing validation, and 2xxx is the SQL range.
            throw new PravahaException(
                    ConfigErrors.MISSING_REQUIRED,
                    "stream '" + name + "' is declared under " + where + " with no schema. A stream "
                            + "is a name and a shape; the name alone cannot be planned against.");
        }
        StreamSchema parsed = FilesystemSourcePlugin.parseSchema(name, declaration.getSchema());
        return withEventTime(parsed, declaration, defaultOutOfOrderness);
    }

    /**
     * TIME-6. One line per stream saying what its event time is and what lateness is in force, because
     * four of the six ways to arrive at "RUNNING, ingesting, serving nothing" are settings that nothing on
     * the node ever mentioned. The effective values, from the built schema rather than from the
     * declaration, so a default shows as the default rather than as blank.
     */
    public static void describe(Logger log, StreamSchema declared) {
        log.info(
                "stream {}: event-time={}, out-of-orderness={}, allowed-lateness={}",
                declared.name(),
                declared.eventTimeOrdinal().isPresent()
                        ? declared.field(declared.eventTimeOrdinal().getAsInt()).name()
                        : "none -- no window over this stream can ever close",
                declared.eventTimeOrdinal().isPresent() ? declared.outOfOrderness() : "n/a",
                declared.eventTimeOrdinal().isPresent() ? declared.allowedLateness() : "n/a");
    }

    /**
     * Marks the event-time column, and the stream's own lateness, if the declaration named them.
     *
     * <p>Rebuilt rather than mutated because a schema is immutable: a running query keeps the
     * version it was planned against, which is what stops a re-declaration changing the meaning of a
     * query already in flight.
     */
    private static StreamSchema withEventTime(
            StreamSchema parsed, StreamDeclarationProperties.Declaration declaration, Duration defaultOutOfOrderness) {
        boolean namesAnEventTime = declaration.getEventTime() != null
                && !declaration.getEventTime().isBlank();
        // T-6. `getOutOfOrderness() != null` is the whole of the second half of that finding:
        // StreamCatalog.withEventTime refuses an out-of-orderness declared without an event-time
        // column, and an early return here once meant such a declaration never reached it.
        if (!namesAnEventTime && declaration.getAllowedLateness() == null && declaration.getOutOfOrderness() == null) {
            return parsed;
        }
        // DOCX-6. The node-level default applies to a stream that declares an event time and no
        // lateness of its own. Not to one that declares no event time: there is nothing for the
        // lateness to be about.
        Duration lateness = declaration.getOutOfOrderness() != null
                ? declaration.getOutOfOrderness()
                : (namesAnEventTime ? defaultOutOfOrderness : null);
        // One implementation, shared with POST /api/v1/streams, so a stream declared by file and one
        // declared over HTTP cannot disagree about what an event-time declaration means.
        return StreamCatalog.withEventTime(
                parsed, declaration.getEventTime(), lateness, declaration.getAllowedLateness());
    }

    /**
     * Passes the stream's declared event-time column down to its source.
     *
     * <p>So it is declared once. The engine's schema and the plugin's are built from different
     * places -- the declaration's {@code schema} and the binding's own {@code schema} option -- and only
     * the first carried the event-time marker. The plugin then decoded rows with a schema that had none
     * and stamped them all with event time zero, which no watermark can advance past.
     */
    public static SourceBinding withDeclaredEventTime(
            SourceBinding binding, StreamDeclarationProperties.@Nullable Declaration declaration) {
        if (declaration == null
                || declaration.getEventTime() == null
                || declaration.getEventTime().isBlank()
                || binding.options().containsKey("event.time")) {
            return binding;
        }
        Map<String, String> options = new LinkedHashMap<>(binding.options());
        options.put("event.time", declaration.getEventTime().strip());
        return new SourceBinding(binding.streamName(), binding.plugin(), options);
    }
}
