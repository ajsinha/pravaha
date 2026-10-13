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

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;

/**
 * {@code pravaha.sources.*}: what each stream reads from.
 *
 * <pre>
 * pravaha:
 *   sources:
 *     txn:
 *       plugin: filesystem
 *       options:
 *         path: /opt/pravaha/data/incoming
 *         schema: "id:INT64,user:STRING,amount:INT64"
 * </pre>
 *
 * <p>Separate from {@code pravaha.streams}, which is the schema. A stream can be defined and planned
 * against with nothing attached to it -- that is what a query written ahead of its source needs --
 * so binding is its own decision and its own block.
 */
@Component
@ConfigurationProperties(prefix = "pravaha")
public class SourceBindingProperties {

    private final Map<String, Spec> sources =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>()); // ADR-064: changed live

    private final Map<String, Spec> lookups =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>()); // ADR-064: changed live

    /**
     * Spring binds into this map; the key is the stream name.
     *
     * <p>The prefix is {@code pravaha} and the property is {@code sources} rather than the other way
     * round. With {@code prefix = "pravaha.sources"} Spring binds {@code pravaha.sources.<field>},
     * so a map called {@code bindings} would be filled from {@code pravaha.sources.bindings.txn} --
     * and the configuration documented everywhere, {@code pravaha.sources.txn}, would bind nothing
     * at all and start a server that silently read no rows. Found by running one.
     */
    public Map<String, Spec> getSources() {
        return sources;
    }

    /**
     * {@code pravaha.lookups.*}: the dimension tables a query may join against.
     *
     * <pre>
     * pravaha:
     *   lookups:
     *     users:
     *       plugin: aerospike-lookup
     *       options:
     *         hosts: "as-1:3000"
     *         set: users
     *         key.bin: user_id
     * </pre>
     *
     * <p>Its own block rather than a flag on a source, because the two are different things: a
     * source is consumed and advances event time, a dimension table is asked. A node with no
     * lookups block can still plan and run everything it could before.
     */
    public Map<String, Spec> getLookups() {
        return lookups;
    }

    /** The configured lookup bindings, keyed by the name a query joins against. */
    public List<SourceBinding> toLookupBindings() {
        List<SourceBinding> configured = new ArrayList<>();
        lookups.forEach((table, spec) -> configured.add(new SourceBinding(
                table, java.util.Objects.requireNonNullElse(spec.effectivePlugin(), ""), spec.effectiveOptions())));
        return configured;
    }

    /** The configured bindings, validated. */
    public List<SourceBinding> toBindings() {
        List<SourceBinding> configured = new ArrayList<>();
        sources.forEach((stream, spec) -> configured.add(new SourceBinding(
                stream, java.util.Objects.requireNonNullElse(spec.effectivePlugin(), ""), spec.effectiveOptions())));
        return configured;
    }

    /** One stream's binding, as written in configuration. */
    public static class Spec {

        private @Nullable String plugin;
        private Map<String, String> options = new LinkedHashMap<>();
        private @Nullable String connector;
        private @Nullable String resolvedPlugin;
        private @Nullable Map<String, String> resolvedOptions;

        public @Nullable String getPlugin() {
            return plugin;
        }

        public void setPlugin(@Nullable String plugin) {
            this.plugin = plugin;
        }

        public Map<String, String> getOptions() {
            return options;
        }

        public void setOptions(Map<String, String> options) {
            this.options = options == null ? new LinkedHashMap<>() : options;
        }

        /**
         * A named connection this binding takes its plugin and options from (ADR-064): a file in
         * {@code pravaha.config.connectors-dir}. The binding's own options are laid over the connection's.
         */
        public @Nullable String getConnector() {
            return connector;
        }

        public void setConnector(@Nullable String connector) {
            this.connector = connector == null || connector.isBlank() ? null : connector.strip();
        }

        /** Records what the named connection resolved to; not a setting (no bean accessor names it). */
        public void resolvedTo(String plugin, Map<String, String> options) {
            this.resolvedPlugin = plugin;
            this.resolvedOptions = Map.copyOf(options);
        }

        /** The plugin the binding uses: its own, or its connection's. */
        public @Nullable String effectivePlugin() {
            return resolvedPlugin != null ? resolvedPlugin : plugin;
        }

        /** The options the binding uses: its own, laid over its connection's. */
        public Map<String, String> effectiveOptions() {
            return resolvedOptions != null ? resolvedOptions : options;
        }
    }
}
