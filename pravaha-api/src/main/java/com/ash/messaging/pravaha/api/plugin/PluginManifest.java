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
package com.ash.messaging.pravaha.api.plugin;

import java.util.Map;
import java.util.Objects;

/**
 * What a plugin jar declares about itself.
 *
 * <p>Read before any of the plugin's classes are loaded, so an incompatible plugin is rejected
 * without running its code. That ordering matters: a plugin built against a different API version
 * would otherwise fail with a {@code NoSuchMethodError} from somewhere inside its own
 * initialisation, which tells an operator nothing useful.
 *
 * @param name the identifier used in configuration
 * @param version the plugin's own version
 * @param requiredApiVersion the API version it was built against
 * @param mainClass the {@link PravahaPlugin} implementation to instantiate
 * @param configSchema setting name to human description, used to generate the console's config form
 */
public record PluginManifest(
        String name, Version version, Version requiredApiVersion, String mainClass, Map<String, String> configSchema) {

    public PluginManifest {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(requiredApiVersion, "requiredApiVersion");
        Objects.requireNonNull(mainClass, "mainClass");
        if (name.isBlank()) {
            throw new IllegalArgumentException("plugin name must not be blank");
        }
        configSchema = configSchema == null ? Map.of() : Map.copyOf(configSchema);
    }

    /** Whether this plugin can run against the engine's current API. */
    public boolean isCompatibleWith(Version apiVersion) {
        return apiVersion.isCompatibleWith(requiredApiVersion);
    }
}
