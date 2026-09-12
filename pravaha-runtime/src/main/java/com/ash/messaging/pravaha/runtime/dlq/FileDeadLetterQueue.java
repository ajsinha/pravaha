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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

import com.ash.messaging.pravaha.common.io.SensitiveFiles;

/**
 * The default dead-letter queue: one JSON object per line, appended to a file.
 *
 * <p>A file because it is the one sink that is always available. The design's default (section 15.6)
 * is deliberate: a DLQ that depends on Kafka is unavailable exactly when Kafka is the thing that
 * broke, and a DLQ that is unavailable during an incident is a DLQ that does not exist.
 *
 * <p>JSON lines because the file is read by a person under time pressure, usually with {@code grep}
 * and {@code jq}, and a format that needs a tool to open is a format that gets ignored. Raw bytes
 * are Base64 -- they are arbitrary and frequently not text, and embedding them raw would produce a
 * file that breaks the line-oriented reading it exists for.
 *
 * <p><strong>Writing never throws.</strong> The caller is already handling a failure and cannot
 * handle a second one; an exception here would turn a bad record into a stopped pipeline, which is
 * precisely what a DLQ exists to prevent. Failures are counted instead, and a non-zero count is the
 * signal that the DLQ itself needs attention.
 */
public final class FileDeadLetterQueue implements DeadLetterQueue {

    private final Path file;
    private final BufferedWriter writer;
    private long count;
    private long failures;

    public FileDeadLetterQueue(Path file) throws IOException {
        this.file = file;
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Every rejected record's raw bytes land here: a copy of production data, created at
        // the process umask -- and unlike the journal this file carried no warning at all
        // that it is data-classified.
        SensitiveFiles.createOwnerOnly(file);
        this.writer = Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public void accept(DeadLetter letter) {
        try {
            writer.write(toJson(letter));
            writer.newLine();
            // Flushed per entry. A DLQ whose last few entries are lost in a buffer when the process
            // dies loses them at exactly the moment they matter most -- a crash is when the
            // interesting records arrive.
            writer.flush();
            count++;
        } catch (IOException e) {
            failures++;
        }
    }

    private static String toJson(DeadLetter letter) {
        return "{\"timestamp\":" + letter.timestampNanos()
                + ",\"query\":\"" + escape(letter.queryId())
                + "\",\"correlationId\":\"" + escape(letter.correlationId())
                + "\",\"offset\":\"" + escape(letter.sourceOffset())
                + "\",\"reason\":\"" + escape(letter.reason())
                + "\",\"raw\":\"" + Base64.getEncoder().encodeToString(letter.raw())
                + "\"}";
    }

    /** Escapes the characters that would otherwise make the line unparseable. */
    private static String escape(String value) {
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

    public Path file() {
        return file;
    }

    @Override
    public long count() {
        return count;
    }

    @Override
    public long failures() {
        return failures;
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            failures++;
        }
    }
}
