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
package com.ash.messaging.pravaha.sdk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Turns a plain string map into the one thing every config-driven setting in this SDK reads:
 * dotted keys such as {@code tls.ca-certificate}, plus the two standard overrides that let an
 * operator change a setting without touching the file that named it.
 *
 * <p>This is not {@code pravaha-common}'s {@code ConfigurationBuilder} -- that class lives in a
 * module this artifact's own {@code enforce-thin-client} rule bans as a dependency, on purpose:
 * the client is embedded in someone else's application and must not drag the engine's
 * configuration stack in with it. What is reproduced here is only the one behavior that matters
 * for a client -- a file (or whatever the embedding application already loaded into a map) can be
 * overridden by a system property, and by an environment variable over that -- not the reference
 * resolution, format registry, or command-line layer the engine itself needs.
 *
 * <p>Precedence, low to high: {@code base}, then matching system properties, then matching
 * environment variables. Programmatic configuration -- calling a builder method directly --
 * always outranks all three, because {@link TlsOptions.Builder#applyConfig(Map)} and
 * {@link ClientOptions.Builder#applyConfig(Map)} are ordinary builder calls: anything set before
 * or after them by a direct method call wins in the usual last-call-wins way.
 */
public final class SdkConfig {

    private SdkConfig() {}

    /**
     * Merges {@code base} with system properties and environment variables whose keys, once
     * translated, begin with {@code prefix} (e.g. {@code "pravaha."}) -- with the prefix stripped
     * back off, so the merged map stays in the same unprefixed convention every {@code
     * applyConfig} method reads ({@code tls.enabled}, not {@code pravaha.tls.enabled}). The prefix
     * exists only so a client sharing a process with other {@code pravaha.*}-prefixed settings does
     * not have every one of them picked up as if it were a client option.
     *
     * <p>A system property is matched by its literal dotted name, prefix included ({@code
     * -Dpravaha.tls.enabled=false} overrides a base-map {@code tls.enabled}). An environment
     * variable is matched upper-cased with dots and hyphens turned into underscores ({@code
     * PRAVAHA_TLS_ENABLED=false}), the same translation {@code pravaha-common}'s {@code
     * ConfigurationBuilder.addEnvironment} uses for its own (differently-prefixed) keys, so an
     * operator who already knows that convention from the server does not have to learn a second
     * one for the client. Environment outranks system property, matching that same class's
     * precedence order.
     */
    public static Map<String, String> layered(Map<String, String> base, String prefix) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(prefix, "prefix");
        Map<String, String> merged = new LinkedHashMap<>(base);
        String envPrefix = prefix.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        Properties props = System.getProperties();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith(prefix)) {
                merged.put(name.substring(prefix.length()), props.getProperty(name));
            }
        }
        System.getenv().forEach((name, value) -> {
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.startsWith(envPrefix)) {
                String key = upper.substring(envPrefix.length())
                        .toLowerCase(Locale.ROOT)
                        .replace('_', '.');
                merged.put(key, value);
            }
        });
        return merged;
    }

    /** {@link Properties} loaded from a file, as the plain string map every {@code applyConfig} reads. */
    public static Map<String, String> fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        Map<String, String> map = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            map.put(name, properties.getProperty(name));
        }
        return map;
    }

    static boolean truthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    static boolean falsy(String value) {
        return "false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value);
    }
}
