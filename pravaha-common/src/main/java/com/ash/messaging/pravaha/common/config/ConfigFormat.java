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

import java.nio.file.Path;
import java.util.Map;

/**
 * Parses a configuration file into a flat map of dotted keys.
 *
 * <p>Flattening at the parser boundary is what lets precedence, interpolation and typed access be
 * written once regardless of file format: a YAML document and a properties file produce the same
 * namespace, so nothing downstream needs to know which it came from.
 */
public interface ConfigFormat {

    /** File extensions this format handles, lower-case and without the dot. */
    java.util.Set<String> extensions();

    /**
     * Parses {@code file} into flat key-value pairs, preserving declaration order.
     *
     * @throws com.ash.messaging.pravaha.api.ConfigurationException if the file cannot be read or is
     *     malformed. A half-loaded configuration is never returned -- starting with one is worse
     *     than not starting.
     */
    Map<String, String> parse(Path file);

    /** The format registered for a file's extension, or empty if none is. */
    static java.util.Optional<ConfigFormat> forFile(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return java.util.Optional.empty();
        }
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return ConfigFormats.byExtension(ext);
    }
}
