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

    /**
     * Bound on nesting depth: a backstop against the stack, not a cycle detector (E-8).
     *
     * <p>It was 32 and it shared {@link ConfigErrors#CIRCULAR_REFERENCE} with the real cycle
     * detector below -- the {@code visiting} set, which works correctly on an actual loop. So a
     * genuine 34-deep acyclic chain ({@code k1=${k0}}, {@code k2=${k1}}, … ) was refused as a
     * "circular reference" that did not exist, and the operator went looking for a loop. Measured
     * exactly: 33 resolved, 34 did not. ERRC-004's own vacuity control -- a 100-deep terminating
     * chain, written to prove the cycle check was not firing on everything -- tripped it.
     *
     * <p>Now 256, and it refuses under a code of its own. The number is chosen against the stack
     * rather than against any configuration: resolution recurses about three frames per level, so
     * 256 is well under a default thread's stack and far past anything a person writes. A chain
     * that reaches it is a generated file with a loop in the generator, and the refusal says
     * depth rather than implying a cycle.
     */
    private static final int MAX_DEPTH = 256;

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
                    ConfigErrors.REFERENCE_TOO_DEEP,
                    "reference nesting deeper than " + MAX_DEPTH + " while resolving '" + owner.key() + "' ("
                            + owner.source().display() + ": " + owner.origin()
                            + "). This is depth, not a cycle -- an actual loop is detected separately and "
                            + "refused as PRV-1011 naming the keys in it. A chain this long is almost always "
                            + "generated; flatten it, or resolve it where it is generated.");
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
