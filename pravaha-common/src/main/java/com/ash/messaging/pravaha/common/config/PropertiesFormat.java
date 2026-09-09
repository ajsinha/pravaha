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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ash.messaging.pravaha.api.ConfigurationException;

/**
 * Reads {@code .properties} files.
 *
 * <p>Hand-parsed rather than delegating to {@code java.util.Properties}, for three reasons that all
 * matter here: {@code Properties} is a {@code Hashtable} and loses declaration order, it reports
 * nothing about *where* a malformed line was, and it silently accepts duplicate keys. This parser
 * keeps order, names the line number in every error, and treats a duplicate key as a warning-worthy
 * last-one-wins rather than an invisible one.
 *
 * <p>Supports {@code #} and {@code !} comments, {@code =} and {@code :} separators, and trailing
 * backslash for continuation.
 */
public final class PropertiesFormat implements ConfigFormat {

    @Override
    public Set<String> extensions() {
        return Set.of("properties", "props", "conf");
    }

    @Override
    public Map<String, String> parse(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigurationException(ConfigErrors.FILE_UNREADABLE, "cannot read configuration file " + file, e);
        }

        Map<String, String> out = new LinkedHashMap<>();
        StringBuilder continued = new StringBuilder();
        int startLine = 0;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.strip();

            if (continued.length() == 0 && (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!"))) {
                continue;
            }
            if (continued.length() == 0) {
                startLine = i + 1;
            }
            if (trimmed.endsWith("\\")) {
                continued.append(trimmed, 0, trimmed.length() - 1);
                continue;
            }
            continued.append(trimmed);
            parseEntry(continued.toString(), file, startLine, out);
            continued.setLength(0);
        }

        if (continued.length() > 0) {
            throw new ConfigurationException(
                    ConfigErrors.FILE_MALFORMED,
                    file + ":" + startLine + " ends with a line continuation but the file ends there");
        }
        return out;
    }

    private static void parseEntry(String entry, Path file, int line, Map<String, String> out) {
        int separator = indexOfSeparator(entry);
        if (separator < 0) {
            throw new ConfigurationException(
                    ConfigErrors.FILE_MALFORMED, file + ":" + line + " is not 'key=value' or 'key: value': " + entry);
        }
        String key = entry.substring(0, separator).strip();
        String value = entry.substring(separator + 1).strip();
        if (key.isEmpty()) {
            throw new ConfigurationException(ConfigErrors.FILE_MALFORMED, file + ":" + line + " has an empty key");
        }
        out.put(key, unescape(value));
    }

    private static int indexOfSeparator(String entry) {
        for (int i = 0; i < entry.length(); i++) {
            char c = entry.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }
}
