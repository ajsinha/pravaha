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
package com.ash.messaging.pravaha.bindings.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.connect.PluginDiscovery;

/**
 * Finds and opens the dimension tables a node's configuration names.
 *
 * <p>The mirror of {@link PluginSourceFeeds}, for the other half of the plugin SPI. Lookup joins
 * were implemented, optimised, tested and documented, and could not be reached: nothing in shipped
 * code ever discovered a {@code LookupSourcePlugin}, so {@code QueryRegistry} had no dimension table
 * to give an execution and every registration planned its dimensions as consumed streams.
 *
 * <p>One instance per configured table, opened once and shared by every query that joins it. Shared
 * deliberately: a dimension table is a client to something outside the process, and one per query
 * would multiply its connections by the number of queries for no benefit. The SPI requires
 * thread-safety for exactly this reason.
 */
public final class PluginLookupSources implements AutoCloseable {

    private final Map<String, SourceBinding> bindings = new LinkedHashMap<>();
    private final List<LookupSourcePlugin> opened = new ArrayList<>();

    public PluginLookupSources bind(SourceBinding binding) {
        bindings.put(binding.streamName(), binding);
        return this;
    }

    public Map<String, SourceBinding> bindings() {
        return Map.copyOf(bindings);
    }

    /**
     * Opens every configured table.
     *
     * <p>Eagerly, at start-up, rather than on the first query that joins one. A dimension table that
     * cannot be reached is a node that cannot answer, and finding that out when the first record
     * arrives means finding it out in production; the SPI already refuses a plan naming a table it
     * was not given, for the same reason.
     */
    public List<LookupSourcePlugin> open() {
        List<LookupSourcePlugin> plugins = new ArrayList<>();
        try {
            for (SourceBinding binding : bindings.values()) {
                LookupSourcePlugin plugin = discover(binding);
                plugin.configure(new PluginSourceFeeds.BindingContext(binding));
                plugin.open();
                plugins.add(plugin);
            }
        } catch (RuntimeException e) {
            closeQuietly(plugins);
            throw e;
        }
        opened.addAll(plugins);
        return plugins;
    }

    private LookupSourcePlugin discover(SourceBinding binding) {
        // PKG-3: each provider is taken on its own, so one that cannot be loaded no longer ends
        // discovery for every lookup plugin; it is refused by name, with its cause, only when it
        // could be the one asked for.
        PluginDiscovery.Found<LookupSourcePlugin> found =
                PluginDiscovery.find(LookupSourcePlugin.class, binding.plugin());
        if (found.plugin() != null) {
            return found.plugin();
        }
        if (!found.failures().isEmpty()) {
            throw PluginDiscovery.loadFailed("lookup", binding.plugin(), found);
        }
        List<String> available = found.available();
        // CFG-4, the lookup half. The source side's message was rewritten to say what "available"
        // means, because a one-entry list read beside a document naming seven plugins looks like a
        // contradiction rather than an answer. This one was left as the bare list, and it has a
        // second trap of its own: both shipped lookup plugins report a name ending in `-lookup`,
        // while docs/guides/CONNECTORS.md's table called them "aerospike, jdbc" -- so the likeliest way
        // to arrive here is to have copied the name out of the documentation.
        throw new PravahaException(
                IngestErrors.NO_SUCH_PLUGIN,
                "no lookup plugin named '" + binding.plugin() + "' is on the classpath, so dimension table '"
                        + binding.streamName() + "' cannot be opened. Available: "
                        + (available.isEmpty() ? "none -- no lookup plugin jar is on the classpath" : available)
                        + ". A plugin answers to the name it reports for itself and is found by "
                        + "ServiceLoader, so it resolves only when its jar is on THIS process's classpath: the "
                        + "server jar carries no lookup plugin at all, and the two that ship -- "
                        + "'aerospike-lookup' in pravaha-plugin-aerospike and 'jdbc-lookup' in "
                        + "pravaha-plugin-jdbc -- are separate modules that have to be added to it. Both names "
                        + "end in '-lookup': 'aerospike' and 'jdbc' without the suffix are the source plugins, "
                        + "and naming one of those here finds nothing. docs/guides/CONNECTORS.md says which module "
                        + "ships which name.");
    }

    @Override
    public void close() {
        closeQuietly(opened);
        opened.clear();
    }

    private static void closeQuietly(List<? extends AutoCloseable> plugins) {
        for (AutoCloseable plugin : plugins) {
            try {
                plugin.close();
            } catch (Exception e) {
                // Already unwinding, or shutting down. A failure closing one table must not stop
                // the others being closed.
            }
        }
    }
}
