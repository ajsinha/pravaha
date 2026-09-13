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
package com.ash.messaging.pravaha.plugin.filesystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.data.RowKind;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Reads one file, line by line.
 *
 * <p>The offset is a line number rather than a byte offset. Both resume correctly; a line number is
 * legible in a checkpoint and in a log, and for a plugin whose main job is to make other things
 * testable, being readable when something goes wrong is worth more than the byte offset's
 * efficiency.
 */
final class FilesystemPartitionReader implements PartitionReader {

    private final DelimitedCodec codec;
    private final BufferedReader reader;
    private long lineNumber;
    private boolean paused;
    private boolean exhausted;

    private final java.util.Set<String> deleteMarkers;

    FilesystemPartitionReader(
            Path path,
            DelimitedCodec codec,
            boolean skipHeader,
            SourceOffset resumeFrom,
            java.util.Set<String> deleteMarkers) {
        this.deleteMarkers = deleteMarkers == null ? java.util.Set.of() : deleteMarkers;
        this.codec = codec;
        try {
            this.reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "cannot open " + path, e);
        }
        try {
            if (skipHeader) {
                reader.readLine();
                lineNumber++;
            }
            if (resumeFrom != null && !resumeFrom.isBeginning()) {
                long target = Long.parseLong(resumeFrom.token());
                while (lineNumber < target && reader.readLine() != null) {
                    lineNumber++;
                }
            }
        } catch (IOException e) {
            throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "cannot seek in " + path, e);
        }
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused || exhausted) {
            return 0;
        }
        int produced = 0;
        try {
            while (produced < maxRecords) {
                String line = reader.readLine();
                if (line == null) {
                    exhausted = true;
                    break;
                }
                lineNumber++;
                if (line.isEmpty()) {
                    continue;
                }
                RowWriter writer = sink.beginRow();
                try {
                    codec.decode(line, lineNumber, writer);
                    // The row's event time comes from the column the schema marked, when it marked
                    // one. Without this every row carries zero and no watermark can reach a window
                    // in the present -- so a windowed query ingests everything and emits nothing.
                    long eventTime = codec.lastEventTimeNanos();
                    if (eventTime != Long.MIN_VALUE) {
                        writer.eventTimestampNanos(eventTime);
                    }
                    // A file is an append-only log of insertions *unless* it names an operation
                    // column. With one, a row can retract what an earlier row inserted, which is
                    // what makes the engine's Z-set model reachable from a configured source at all.
                    String operation = codec.lastOperation();
                    boolean retraction = operation != null && deleteMarkers.contains(operation.strip());
                    writer.rowKind(retraction ? RowKind.DELETE : RowKind.INSERT)
                            .weight(retraction ? -1L : 1L)
                            .sequence(lineNumber)
                            .commit();
                    produced++;
                } catch (RuntimeException e) {
                    // One malformed line must not cost the batch. The engine's DLQ handles the
                    // record; the reader's job is to keep going.
                    writer.abort();
                    throw e;
                }
            }
        } catch (IOException e) {
            throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "read failed at line " + lineNumber, e);
        }
        return produced;
    }

    @Override
    public SourceOffset position() {
        return lineNumber == 0 ? SourceOffset.BEGINNING : new SourceOffset(Long.toString(lineNumber));
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        paused = false;
    }

    boolean isExhausted() {
        return exhausted;
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException e) {
            // Closing a read-only file handle: nothing actionable, and throwing here would mask
            // whatever caused the close.
        }
    }
}
