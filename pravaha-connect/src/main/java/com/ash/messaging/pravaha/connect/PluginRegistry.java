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
package com.ash.messaging.pravaha.connect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.PluginManifest;
import com.ash.messaging.pravaha.api.plugin.PravahaPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * The plugins an engine knows about.
 *
 * <p>One registry per engine, not a static singleton -- embedded mode runs several engines in one
 * JVM and each may be configured with a different set of plugins. A process-wide registry would
 * make that impossible and make tests order-dependent, which is the same reasoning that applies to
 * {@code Configuration}.
 *
 * <p>Compatibility is checked against the manifest <strong>before</strong> the plugin's classes are
 * loaded. A plugin built against a different API version otherwise fails with a
 * {@code NoSuchMethodError} from inside its own initialisation, which tells an operator nothing.
 */
public final class PluginRegistry implements AutoCloseable {

    private final Map<String, Registration> byName = new LinkedHashMap<>();
    private final Version apiVersion;

    public PluginRegistry() {
        this(Version.apiVersion());
    }

    PluginRegistry(Version apiVersion) {
        this.apiVersion = apiVersion;
    }

    /** A registered plugin and the manifest it declared. */
    public record Registration(PluginManifest manifest, PravahaPlugin plugin) {}

    /**
     * Registers a plugin instance.
     *
     * @throws PravahaException if the name is taken or the API version is incompatible
     */
    public PluginRegistry register(PluginManifest manifest, PravahaPlugin plugin) {
        if (!manifest.isCompatibleWith(apiVersion)) {
            throw new PravahaException(
                    PluginErrors.INCOMPATIBLE_API,
                    "plugin '" + manifest.name() + "' " + manifest.version() + " was built against API "
                            + manifest.requiredApiVersion() + ", which this engine (API " + apiVersion
                            + ") cannot host. Rebuild the plugin against API " + apiVersion + ".");
        }
        Registration existing = byName.get(manifest.name());
        if (existing != null) {
            throw new PravahaException(
                    PluginErrors.DUPLICATE_NAME,
                    "two plugins both call themselves '" + manifest.name() + "': "
                            + existing.manifest().version() + " and " + manifest.version()
                            + ". Names are how configuration refers to a plugin, so they must be unique.");
        }
        byName.put(manifest.name(), new Registration(manifest, plugin));
        return this;
    }

    public Optional<PravahaPlugin> find(String name) {
        return Optional.ofNullable(byName.get(name)).map(Registration::plugin);
    }

    /**
     * Looks up a plugin, failing with the available names.
     *
     * <p>Listing what *is* registered turns "no such plugin" from a dead end into a diagnosis --
     * the answer is usually a typo or a jar that did not get deployed.
     */
    public PravahaPlugin require(String name) {
        return find(name)
                .orElseThrow(() -> new PravahaException(
                        PluginErrors.NOT_FOUND, "no plugin named '" + name + "'. Registered: " + byName.keySet()));
    }

    /** Looks up a plugin and checks its type. */
    public <T extends PravahaPlugin> T require(String name, Class<T> type) {
        PravahaPlugin plugin = require(name);
        if (!type.isInstance(plugin)) {
            throw new PravahaException(
                    PluginErrors.CAPABILITY_MISMATCH,
                    "plugin '" + name + "' is a " + plugin.getClass().getSimpleName() + ", not a "
                            + type.getSimpleName() + ". Check whether it is configured as a source where a "
                            + "sink was meant, or the reverse.");
        }
        return type.cast(plugin);
    }

    public Collection<Registration> registrations() {
        return List.copyOf(byName.values());
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    public int size() {
        return byName.size();
    }

    /**
     * Closes every plugin.
     *
     * <p>One plugin failing to close must not prevent the others from closing: leaking a connection
     * pool because an unrelated plugin threw on shutdown is how a restart loop turns into resource
     * exhaustion. Failures are collected and reported together.
     */
    @Override
    public void close() {
        List<Exception> failures = new ArrayList<>();
        for (Registration registration : byName.values()) {
            try {
                registration.plugin().close();
            } catch (Exception e) {
                failures.add(new IllegalStateException(
                        "plugin '" + registration.manifest().name() + "' failed to close", e));
            }
        }
        byName.clear();
        if (!failures.isEmpty()) {
            IllegalStateException combined =
                    new IllegalStateException(failures.size() + " plugin(s) failed to close cleanly");
            failures.forEach(combined::addSuppressed);
            throw combined;
        }
    }
}
