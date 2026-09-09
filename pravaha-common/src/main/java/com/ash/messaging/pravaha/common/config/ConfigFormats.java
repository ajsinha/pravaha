/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
