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
package com.ash.messaging.pravaha.runtime.dlq;

import java.util.Base64;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The one place that knows what a line of a {@code .dlq} file looks like.
 *
 * <p>It was a private method on the writer for as long as nothing read the file back. Now that an
 * API, a CLI and a console all render dead letters, the format has two ends, and a format whose
 * reader and writer are written separately is a format that drifts by one field at a time.
 *
 * <p><strong>Hand-written, not a JSON library.</strong> The runtime module carries no JSON
 * dependency and is not going to acquire one to read six strings and two numbers -- the same
 * reasoning that keeps {@code ControlWire} hand-framed. The parser is deliberately narrow: it reads
 * the objects this writer produces, and anything else is not a dead letter and says so by returning
 * empty rather than by guessing.
 *
 * <p><strong>Every field is optional on the way in.</strong> A file written before B5 has no
 * {@code code}, {@code stream}, {@code schema} or {@code wall}, and it must still list, show and --
 * where the refusals allow -- replay. A missing field reads as empty, which is what every surface
 * renders as "not recorded".
 */
final class DeadLetterJson {

    private DeadLetterJson() {}

    /** One entry as a line, with no trailing newline. */
    static String write(DeadLetter letter) {
        StringBuilder out = new StringBuilder(160 + letter.size() * 2);
        out.append("{\"timestamp\":").append(letter.timestampNanos());
        if (letter.wallMillis() > 0) {
            out.append(",\"wall\":").append(letter.wallMillis());
        }
        out.append(",\"query\":\"").append(escape(letter.queryId())).append('"');
        out.append(",\"correlationId\":\"")
                .append(escape(letter.correlationId()))
                .append('"');
        if (!letter.stream().isEmpty()) {
            out.append(",\"stream\":\"").append(escape(letter.stream())).append('"');
        }
        out.append(",\"offset\":\"").append(escape(letter.sourceOffset())).append('"');
        if (!letter.code().isEmpty()) {
            out.append(",\"code\":\"").append(escape(letter.code())).append('"');
        }
        out.append(",\"reason\":\"").append(escape(letter.reason())).append('"');
        if (!letter.schema().isEmpty()) {
            out.append(",\"schema\":\"").append(escape(letter.schema())).append('"');
        }
        out.append(",\"raw\":\"")
                .append(Base64.getEncoder().encodeToString(letter.raw()))
                .append("\"}");
        return out.toString();
    }

    /** One line back, or empty when it is not one of ours -- a blank line, a truncated tail, a marker. */
    static Optional<DeadLetter> read(String line) {
        if (line == null || line.isBlank() || line.charAt(0) != '{') {
            return Optional.empty();
        }
        String query = string(line, "query");
        if (query == null) {
            return Optional.empty();
        }
        byte[] raw;
        try {
            String encoded = string(line, "raw");
            raw = encoded == null ? new byte[0] : Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException notBase64) {
            // A half-written last line, most often: the queue flushes per entry, but a machine can
            // still lose power inside one. Skipped rather than failing the whole listing, because a
            // torn tail must not hide the thousand entries in front of it.
            return Optional.empty();
        }
        String reason = string(line, "reason");
        return Optional.of(new DeadLetter(
                query,
                reason == null ? "" : reason,
                orEmpty(string(line, "code")),
                orEmpty(string(line, "stream")),
                orEmpty(string(line, "schema")),
                orEmpty(string(line, "offset")),
                raw,
                orEmpty(string(line, "correlationId")),
                number(line, "timestamp"),
                number(line, "wall")));
    }

    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    /** The string value of {@code "key":"..."}, unescaped, or null when the key is absent. */
    static @Nullable String string(String line, String key) {
        int at = keyAt(line, key);
        if (at < 0) {
            return null;
        }
        int open = line.indexOf('"', at);
        if (open < 0) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        for (int i = open + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                return value.toString();
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (++i >= line.length()) {
                return value.toString();
            }
            char escaped = line.charAt(i);
            switch (escaped) {
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (i + 4 < line.length()) {
                        value.append((char) Integer.parseInt(line.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> value.append(escaped);
            }
        }
        return value.toString();
    }

    /** The numeric value of {@code "key":123}, or zero when the key is absent or unreadable. */
    static long number(String line, String key) {
        int at = keyAt(line, key);
        if (at < 0) {
            return 0;
        }
        int end = at;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
            end++;
        }
        try {
            return at == end ? 0 : Long.parseLong(line.substring(at, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Where the value of {@code key} starts, or -1.
     *
     * <p>Matched as the quoted key followed by a colon, so a key name appearing inside another
     * field's <em>value</em> is not mistaken for the field: a reason that says {@code "code":} is a
     * reason, and the writer escapes its quotes, so the literal {@code "code":} only occurs where
     * this writer put it.
     */
    private static int keyAt(String line, String key) {
        int found = line.indexOf("\"" + key + "\":");
        return found < 0 ? -1 : found + key.length() + 3;
    }

    /** Escapes the characters that would otherwise make the line unparseable. */
    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
