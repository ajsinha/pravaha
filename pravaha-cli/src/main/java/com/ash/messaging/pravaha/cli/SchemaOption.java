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
package com.ash.messaging.pravaha.cli;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;

/**
 * The one place {@code --schema} and {@code --event-time} become a stream.
 *
 * <p>{@code name:TYPE,…} says a column is a {@code TIMESTAMP}; it has no syntax for saying which
 * timestamp is the stream's <em>own</em> time, and nothing infers it. A windowed query over a
 * stream that declares none is refused at plan time (TIME-6) exactly as a node refuses it, which
 * is right and would otherwise leave {@code validate}, {@code explain} and {@code run} unable to
 * plan the one kind of query most worth checking before deploying it.
 *
 * <p>So the three commands take the same option, spelled as the node's configuration spells it
 * ({@code pravaha.streams.<name>.event-time}), and they build the schema the same way — because
 * three copies of this is how {@code validate} starts saying a query is fine that a node refuses.
 */
final class SchemaOption {

    private SchemaOption() {}

    /**
     * @param streamName the stream the SQL names
     * @param schemaSpec the {@code --schema} value, {@code name:TYPE,…}
     * @param eventTimeColumn the {@code --event-time} value; blank or null declares none
     */
    static StreamSchema parse(String streamName, String schemaSpec, String eventTimeColumn) {
        StreamSchema parsed = FilesystemSourcePlugin.parseSchema(streamName, schemaSpec);
        if (eventTimeColumn == null || eventTimeColumn.isBlank()) {
            return parsed;
        }
        StreamSchema.Builder builder = StreamSchema.builder(parsed.name());
        parsed.fields().forEach(field -> builder.field(field.name(), field.type()));
        return builder.eventTime(eventTimeColumn.strip()).build();
    }
}
