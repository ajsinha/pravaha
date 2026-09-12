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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code pravaha.sources.*}: what each stream reads from.
 *
 * <pre>
 * pravaha:
 *   sources:
 *     txn:
 *       plugin: filesystem
 *       options:
 *         path: /var/lib/pravaha/incoming
 *         schema: "id:INT64,user:STRING,amount:INT64"
 * </pre>
 *
 * <p>Separate from {@code pravaha.streams}, which is the schema. A stream can be defined and planned
 * against with nothing attached to it -- that is what a query written ahead of its source needs --
 * so binding is its own decision and its own block.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.sources")
public class SourceBindingProperties {

    private final Map<String, Spec> bindings = new LinkedHashMap<>();

    /** Spring binds into this map; the key is the stream name. */
    public Map<String, Spec> getBindings() {
        return bindings;
    }

    /** The configured bindings, validated. */
    public List<SourceBinding> toBindings() {
        List<SourceBinding> configured = new ArrayList<>();
        bindings.forEach((stream, spec) -> configured.add(new SourceBinding(stream, spec.plugin, spec.options)));
        return configured;
    }

    /** One stream's binding, as written in configuration. */
    public static class Spec {

        private String plugin;
        private Map<String, String> options = new LinkedHashMap<>();

        public String getPlugin() {
            return plugin;
        }

        public void setPlugin(String plugin) {
            this.plugin = plugin;
        }

        public Map<String, String> getOptions() {
            return options;
        }

        public void setOptions(Map<String, String> options) {
            this.options = options == null ? new LinkedHashMap<>() : options;
        }
    }
}
