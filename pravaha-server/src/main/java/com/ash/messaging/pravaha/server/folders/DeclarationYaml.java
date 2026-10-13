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
package com.ash.messaging.pravaha.server.folders;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The YAML a write puts in a declaration file: what a person would have typed (ADR-064).
 *
 * <p>Written by hand rather than by a YAML library's dumper, so the file reads like the examples in the
 * guides -- block style, the settings in the order a person reads them, a value quoted only when YAML
 * would read it as something else -- and so {@code enabled:} can be switched in a file a person wrote
 * without losing their comments.
 */
public final class DeclarationYaml {

    /** A value that YAML reads as the same string unquoted. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_/.][A-Za-z0-9_./-]*|[0-9]+(ms|s|m|h|d)");

    /** A key that needs no quotes: option keys such as {@code bootstrap.servers} included. */
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");

    /** Plain scalars YAML 1.1 reads as booleans or null, which must be quoted to stay strings. */
    private static final Set<String> RESERVED =
            Set.of("true", "false", "yes", "no", "on", "off", "y", "n", "null", "~");

    /** A top-level {@code enabled:} line, as a person or this class wrote it. */
    private static final Pattern ENABLED_LINE = Pattern.compile("(?m)^enabled[ \\t]*:.*(\\r?\\n|\\z)");

    private DeclarationYaml() {}

    /** A binding: a plugin or a connection, and options laid over the connection's. */
    public record Binding(@Nullable String plugin, @Nullable String connector, Map<String, String> options) {

        public Binding {
            options = options == null ? Map.of() : Map.copyOf(options);
        }
    }

    /**
     * A stream file.
     *
     * @param who who wrote it, for the first line
     */
    public static String stream(
            String name,
            String schema,
            @Nullable String eventTime,
            @Nullable String outOfOrderness,
            @Nullable String allowedLateness,
            boolean enabled,
            @Nullable Binding source,
            String who) {
        StringBuilder out = header("Stream", name, who);
        line(out, "", "schema", schema, true);
        line(out, "", "event-time", eventTime, false);
        line(out, "", "out-of-orderness", outOfOrderness, false);
        line(out, "", "allowed-lateness", allowedLateness, false);
        if (!enabled) {
            out.append("enabled: false\n");
        }
        if (source != null) {
            out.append("source:\n");
            binding(out, "  ", source);
        }
        return out.toString();
    }

    /** A connection, lookup or sink file: a plugin (or, for a lookup or sink, a connection) and its options. */
    public static String binding(DeclarationKind kind, String name, Binding binding, boolean enabled, String who) {
        StringBuilder out = header(
                switch (kind) {
                    case CONNECTOR -> "Connection";
                    case LOOKUP -> "Lookup";
                    case SINK -> "Sink";
                    case STREAM -> "Stream";
                },
                name,
                who);
        if (!enabled) {
            out.append("enabled: false\n");
        }
        binding(out, "", binding);
        return out.toString();
    }

    /**
     * {@code text} with its top-level {@code enabled:} switched -- the line removed for on, which is the
     * default, and written for off -- and every other line, comments included, as it was.
     */
    public static String withEnabled(String text, boolean enabled) {
        String without = ENABLED_LINE.matcher(text).replaceAll("");
        if (enabled) {
            return without;
        }
        String body = without.isEmpty() || without.endsWith("\n") ? without : without + "\n";
        return body + "enabled: false\n";
    }

    private static StringBuilder header(String what, String name, String who) {
        return new StringBuilder("# ")
                .append(what)
                .append(' ')
                .append(name)
                .append(", written through the Pravaha API by ")
                .append(who.replaceAll("[\\r\\n]", " "))
                .append(".\n# This file is the declaration: edit it here or from the console, the CLI or the API.\n");
    }

    private static void binding(StringBuilder out, String indent, Binding binding) {
        line(out, indent, "connector", binding.connector(), false);
        line(out, indent, "plugin", binding.plugin(), false);
        if (!binding.options().isEmpty()) {
            out.append(indent).append("options:\n");
            new TreeMap<>(binding.options()).forEach((key, value) -> line(out, indent + "  ", key, value, false));
        }
    }

    private static void line(StringBuilder out, String indent, String key, @Nullable String value, boolean quote) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.append(indent)
                .append(key(key))
                .append(": ")
                .append(quote ? quoted(value) : scalar(value))
                .append('\n');
    }

    private static String key(String key) {
        return PLAIN_KEY.matcher(key).matches() && !RESERVED.contains(key.toLowerCase(Locale.ROOT)) ? key : quoted(key);
    }

    /** {@code value} as YAML reads it back unchanged: plain when it can be, double-quoted when not. */
    static String scalar(String value) {
        return PLAIN.matcher(value).matches() && !RESERVED.contains(value.toLowerCase(Locale.ROOT))
                ? value
                : quoted(value);
    }

    private static String quoted(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\x%02x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
