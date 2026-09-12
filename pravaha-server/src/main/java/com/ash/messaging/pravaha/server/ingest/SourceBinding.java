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
package com.ash.messaging.pravaha.server.ingest;

import java.util.Map;

/**
 * Where one stream's rows come from.
 *
 * <p>The stream's <em>schema</em> lives in the catalog and says what a row looks like. This says
 * where rows arrive from, and the two are deliberately separate: a stream can be defined, validated
 * and planned against long before anything is attached to it, which is exactly what a query written
 * against tomorrow's topic needs.
 *
 * @param streamName the stream this binds, as a query would name it in a FROM clause
 * @param plugin the source plugin's {@code name()}, as it reports it
 * @param options the plugin's own configuration, passed through untouched
 */
public record SourceBinding(String streamName, String plugin, Map<String, String> options) {

    public SourceBinding {
        if (streamName == null || streamName.isBlank()) {
            throw new IllegalArgumentException("a source binding needs the stream it binds");
        }
        if (plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException(
                    "a source binding for '" + streamName + "' needs a plugin name; the plugins on the "
                            + "classpath report their own names and one of those is what goes here");
        }
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    @Override
    public String toString() {
        return streamName + " <- " + plugin + options.keySet();
    }
}
