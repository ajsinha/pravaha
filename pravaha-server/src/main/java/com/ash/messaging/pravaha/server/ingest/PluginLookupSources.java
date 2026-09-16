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
import java.util.ServiceLoader;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.connect.PluginErrors;

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
        List<String> available = new ArrayList<>();
        try {
            for (LookupSourcePlugin candidate : ServiceLoader.load(LookupSourcePlugin.class)) {
                if (candidate.name().equalsIgnoreCase(binding.plugin())) {
                    return candidate;
                }
                available.add(candidate.name());
                closeQuietly(List.of(candidate));
            }
        } catch (java.util.ServiceConfigurationError e) {
            // ERRC-059's mirror for lookup plugins: ServiceLoader raises this from inside the
            // iteration, not as a RuntimeException, so it would otherwise leave every
            // PravahaException handler on the way out as a bare, uncoded Error instead of a
            // diagnosable, documented failure.
            throw new PravahaException(
                    PluginErrors.LOAD_FAILED,
                    "a lookup plugin on the classpath could not be loaded while looking for '" + binding.plugin()
                            + "': " + e.getMessage(),
                    e);
        }
        throw new PravahaException(
                IngestErrors.NO_SUCH_PLUGIN,
                "no lookup plugin named '" + binding.plugin() + "' is on the classpath, so dimension table '"
                        + binding.streamName() + "' cannot be opened. Available: "
                        + (available.isEmpty() ? "none -- no lookup plugin jar is on the classpath" : available));
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
