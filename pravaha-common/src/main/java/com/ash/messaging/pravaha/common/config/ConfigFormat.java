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
