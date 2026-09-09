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
package com.ash.messaging.pravaha.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry of known {@link ConfigFormat}s.
 *
 * <p>{@code .properties} is built in because it needs no dependency. YAML is registered by the
 * module that brings a YAML parser with it, rather than forcing that dependency on every embedder
 * -- {@code pravaha-common} sits underneath the whole engine, and what it drags in, everything
 * drags in.
 */
public final class ConfigFormats {

    private static final List<ConfigFormat> REGISTERED = new CopyOnWriteArrayList<>(List.of(new PropertiesFormat()));

    private ConfigFormats() {}

    /** Registers a format. Later registrations win for an extension both claim. */
    public static void register(ConfigFormat format) {
        REGISTERED.add(0, format);
    }

    static Optional<ConfigFormat> byExtension(String extension) {
        String ext = extension.toLowerCase(Locale.ROOT);
        for (ConfigFormat f : REGISTERED) {
            if (f.extensions().contains(ext)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /** Every extension currently handled, for diagnostics. */
    public static List<String> supportedExtensions() {
        List<String> all = new ArrayList<>();
        REGISTERED.forEach(f -> all.addAll(f.extensions()));
        return List.copyOf(all);
    }
}
