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
package com.ash.messaging.pravaha.bindings.egress;

import java.util.Map;

/**
 * Where one sink's rows go.
 *
 * <p>The mirror of {@code SourceBinding} (see {@code com.ash.messaging.pravaha.bindings.ingest}),
 * deliberately: a sink is named, configured and discovered the same way a source is, and giving it a
 * second shape would be a second thing to learn for no reason. The difference between the two is not
 * in this record -- it is that nothing yet resolves a {@code sinkName} against a registered query's
 * output (W8-13; see ADR-039 item 5). This binding is the half that makes a sink nameable at all.
 *
 * @param sinkName the name a query (eventually {@code INSERT INTO <sinkName>}, or an equivalent
 *     registration option) would use to address this sink
 * @param plugin the sink plugin's {@code name()}, as it reports it
 * @param options the plugin's own configuration, passed through untouched
 */
public record SinkBinding(String sinkName, String plugin, Map<String, String> options) {

    public SinkBinding {
        if (sinkName == null || sinkName.isBlank()) {
            throw new IllegalArgumentException("a sink binding needs the name it is addressed by");
        }
        if (plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException(
                    "a sink binding for '" + sinkName + "' needs a plugin name; the plugins on the classpath "
                            + "report their own names and one of those is what goes here");
        }
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    @Override
    public String toString() {
        return sinkName + " -> " + plugin + options.keySet();
    }
}
