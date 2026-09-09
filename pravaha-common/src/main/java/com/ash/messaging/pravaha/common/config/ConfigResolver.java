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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.ash.messaging.pravaha.api.ConfigurationException;

/**
 * Expands {@code ${key}} and {@code ${key:default}} references.
 *
 * <p>References resolve against the fully-merged configuration, so a value in a file may refer to a
 * key supplied by the environment. Defaults may themselves contain references, and nesting is
 * resolved innermost-first.
 *
 * <p><strong>An unresolved reference is fatal.</strong> Leaving the literal {@code ${DB_PASSWORD}}
 * in place so "the problem is visible" is how a placeholder ends up in a connection string, or in a
 * log, or on the wire. Refusing to start is louder and safer, and the message names the key and
 * where it came from.
 */
final class ConfigResolver {

    /** Bound on nesting depth; deep enough for any real configuration, shallow enough to fail fast. */
    private static final int MAX_DEPTH = 32;

    private ConfigResolver() {}

    /** Resolves every value in {@code entries}, preserving provenance. */
    static Map<String, ConfigValue> resolveAll(Map<String, ConfigValue> entries) {
        Map<String, ConfigValue> resolved = new LinkedHashMap<>(entries.size() * 2);
        for (Map.Entry<String, ConfigValue> e : entries.entrySet()) {
            ConfigValue value = e.getValue();
            String expanded = resolve(value.value(), entries, new LinkedHashSet<>(Set.of(e.getKey())), value, 0);
            resolved.put(e.getKey(), value.withValue(expanded));
        }
        return resolved;
    }

    private static String resolve(
            String text, Map<String, ConfigValue> entries, Set<String> visiting, ConfigValue owner, int depth) {
        if (text.indexOf("${") < 0) {
            return text;
        }
        if (depth > MAX_DEPTH) {
            throw new ConfigurationException(
                    ConfigErrors.CIRCULAR_REFERENCE,
                    "reference nesting deeper than " + MAX_DEPTH + " while resolving '" + owner.key() + "' ("
                            + owner.source().display() + ": " + owner.origin() + ")");
        }

        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("${", i);
            if (start < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, start);
            int end = matchingBrace(text, start, owner);
            String token = text.substring(start + 2, end);
            // Resolve the token itself first, so ${prefix${suffix}} works innermost-out.
            String resolvedToken = resolve(token, entries, visiting, owner, depth + 1);
            out.append(expand(resolvedToken, entries, visiting, owner, depth + 1));
            i = end + 1;
        }
        return out.toString();
    }

    private static int matchingBrace(String text, int start, ConfigValue owner) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return i;
                }
            }
        }
        throw new ConfigurationException(
                ConfigErrors.FILE_MALFORMED,
                "unclosed '${' in '" + owner.key() + "' (" + owner.source().display() + ": " + owner.origin() + "): "
                        + Redaction.mask(owner.key(), text));
    }

    private static String expand(
            String token, Map<String, ConfigValue> entries, Set<String> visiting, ConfigValue owner, int depth) {
        int colon = token.indexOf(':');
        String key = (colon < 0 ? token : token.substring(0, colon)).trim();
        String fallback = colon < 0 ? null : token.substring(colon + 1);

        if (!visiting.add(key)) {
            throw new ConfigurationException(
                    ConfigErrors.CIRCULAR_REFERENCE,
                    "circular reference: " + String.join(" -> ", visiting) + " -> " + key);
        }
        try {
            ConfigValue referenced = entries.get(key);
            if (referenced != null) {
                return resolve(referenced.value(), entries, visiting, referenced, depth);
            }
            if (fallback != null) {
                return resolve(fallback, entries, visiting, owner, depth);
            }
            throw new ConfigurationException(
                    ConfigErrors.UNRESOLVED_REFERENCE,
                    "'" + owner.key() + "' (" + owner.source().display() + ": " + owner.origin()
                            + ") refers to '${" + key + "}', which is not set anywhere and has no default. "
                            + "Set it, or write ${" + key + ":some-default}.");
        } finally {
            visiting.remove(key);
        }
    }
}
