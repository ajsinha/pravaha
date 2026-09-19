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
 *
 * <p><strong>Following</strong> ({@code follow: true}) is {@code tail -f}: end of file stops being
 * end of stream. Without it a continuous query over a file is a batch query -- the reader latched
 * exhausted at the first null read and never looked again, so the query kept a view it would never
 * update, and reported RUNNING while doing it.
 *
 * <p>Following also handles the file being replaced under it, which is what log rotation and an
 * atomic rewrite both look like. A shrunk file, or one whose identity changed, is a new file: the
 * reader reopens from the start rather than sitting on a handle to something nobody can see any
 * more. A file that merely grew is read on from where it was.
 */
final class FilesystemPartitionReader implements PartitionReader {

    private final DelimitedCodec codec;
    private BufferedReader reader;
    private long lineNumber;
    private boolean paused;
    private boolean exhausted;

    private final java.util.Set<String> deleteMarkers;

    /** Whether end of file means end of stream. */
    private final boolean follow;

    private final Path path;
    private final boolean skipHeader;

    /**
     * The size and identity of the file as of the last read, so a replacement can be recognised.
     *
     * <p>Size alone is not enough: a rotated file is often replaced by one of a similar size, and a
     * reader comparing only length would read the new file's bytes as a continuation of the old
     * one's -- silently interleaving two files' rows into one stream.
     */
    private long lastSize;

    private Object lastIdentity;

    FilesystemPartitionReader(
            Path path,
            DelimitedCodec codec,
            boolean skipHeader,
            SourceOffset resumeFrom,
            java.util.Set<String> deleteMarkers) {
        this(path, codec, skipHeader, resumeFrom, deleteMarkers, false);
    }

    FilesystemPartitionReader(
            Path path,
            DelimitedCodec codec,
            boolean skipHeader,
            SourceOffset resumeFrom,
            java.util.Set<String> deleteMarkers,
            boolean follow) {
        this.deleteMarkers = deleteMarkers == null ? java.util.Set.of() : deleteMarkers;
        this.codec = codec;
        this.follow = follow;
        this.path = path;
        this.skipHeader = skipHeader;
        try {
            this.reader = lenientUtf8Reader(path);
        } catch (IOException e) {
            throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "cannot open " + path + ": " + why(e), e);
        }
        rememberFile();
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
            throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "cannot seek in " + path + ": " + why(e), e);
        }
    }

    /**
     * What the operating system said, and what it means when it said the one thing a node running
     * many sources hits first.
     *
     * <p>Every failure to open a file here was reported as {@code "cannot open <path>"} and nothing
     * else: the {@code IOException}'s own message -- which is the only part naming the <em>reason</em>
     * -- went into the cause and out of the sentence anybody reads. For most failures that costs a
     * click. For one it costs an afternoon.
     *
     * <p>That one is the descriptor ceiling. A node holding a followed file per query holds one
     * descriptor per query (SRC-4), and the query that crosses {@code ulimit -n} fails with
     * {@code FILESYSTEM_DECODE_FAILED} naming a file that is perfectly fine -- so the person reading
     * it goes and inspects that file's permissions, its encoding and its schema, none of which is
     * wrong. Measured at {@code ulimit -n 300}: the 276th bound source failed, and the words "too
     * many open files" appeared nowhere an operator would look.
     *
     * <p>The error <em>code</em> is deliberately left alone. It is wrong -- this is not a decode
     * failure -- but 5040 is a published identifier and changing it is a separate decision from
     * making the message say what happened.
     */
    static String why(IOException failure) {
        String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        if (failure instanceof java.nio.file.AccessDeniedException) {
            // API-F3. AccessDeniedException carries the path as its message and null as its
            // reason, so the one word an operator needs -- permission -- reached neither side:
            // the sentence repeated the path they had just typed. This is the commonest open
            // failure there is and the only one whose remedy is not in the path at all.
            return reason + ": permission denied. A node reads and writes as the user it runs as, "
                    + "so check the file's owner and mode and its directory's";
        }
        if (reason.toLowerCase(java.util.Locale.ROOT).contains("too many open files")) {
            return reason
                    + ". This is the process's file-descriptor limit, not this file: a node holds one "
                    + "descriptor per bound source, so raise `ulimit -n` or bind fewer sources. The file "
                    + "named above is almost certainly fine";
        }
        return reason;
    }

    /** Notes the file's current size and identity, for spotting a replacement later. */
    private void rememberFile() {
        try {
            java.nio.file.attribute.BasicFileAttributes attributes =
                    Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            lastSize = attributes.size();
            lastIdentity = attributes.fileKey() != null ? attributes.fileKey() : attributes.creationTime();
        } catch (IOException e) {
            // Gone, or unreadable. Nothing to remember, and the next poll will find out properly.
            lastSize = -1;
            lastIdentity = null;
        }
    }

    /**
     * Reopens when the file underneath has been replaced. Returns true if it was.
     *
     * <p>Replaced means a different identity, or a smaller size than we have already read past --
     * truncation and rotation both look like that. A file that only grew is the ordinary case and
     * is left alone, because reopening it would replay every row we have already delivered.
     */
    private boolean reopenIfReplaced() {
        java.nio.file.attribute.BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
        } catch (IOException e) {
            // The file is momentarily absent -- mid-rotation, most likely. Not an error: the next
            // poll looks again, and a source that died because a log rotated would be worse than
            // one that waits.
            return false;
        }
        Object identity = attributes.fileKey() != null ? attributes.fileKey() : attributes.creationTime();
        boolean replaced = lastIdentity != null && !lastIdentity.equals(identity);
        boolean truncated = attributes.size() < lastSize;
        if (!replaced && !truncated) {
            lastSize = attributes.size();
            return false;
        }
        try {
            reader.close();
        } catch (IOException e) {
            // Closing the handle to a file that is already gone is not a failure worth reporting.
        }
        try {
            reader = lenientUtf8Reader(path);
        } catch (IOException e) {
            throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "cannot reopen " + path + ": " + why(e), e);
        }
        lineNumber = 0;
        // Whatever was half-read belonged to the file that has just gone.
        partial.setLength(0);
        if (skipHeader) {
            try {
                reader.readLine();
                lineNumber++;
            } catch (IOException e) {
                throw new ConfigurationException(
                        DelimitedCodec.DECODE_FAILED, "cannot read header of " + path + ": " + why(e), e);
            }
        }
        rememberFile();
        rotations++;
        return true;
    }

    private long rotations;

    /**
     * A line the writer has not finished yet.
     *
     * <p>{@code readLine} returns whatever is there at end of file, terminated or not -- so tailing
     * a file being appended to would read half a line as a record, decode it against the schema,
     * and then read the other half as a second record. Both would be wrong and neither would look
     * it. In follow mode a line is a record only once its newline has arrived.
     */
    private final StringBuilder partial = new StringBuilder();

    /**
     * The next complete line, or null when the writer has not finished one.
     *
     * <p>Character at a time, through the buffer the reader already has. Only in follow mode: the
     * bounded read keeps {@code readLine}, where a trailing unterminated line is the last line of a
     * finished file rather than a line still being written.
     */
    private String readCompleteLine() throws IOException {
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                String line = partial.toString();
                partial.setLength(0);
                // A file written on Windows ends its lines \r\n, and the \r is not data.
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            partial.append((char) c);
        }
        return null;
    }

    /** How many times the file was replaced underneath this reader. */
    long rotationCount() {
        return rotations;
    }

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        if (paused || exhausted) {
            return 0;
        }
        if (follow) {
            reopenIfReplaced();
        }
        int produced = 0;
        try {
            while (produced < maxRecords) {
                String line = follow ? readCompleteLine() : reader.readLine();
                if (line == null) {
                    // Following means end of file is not end of stream: the writer may not have
                    // finished the line, or may not have written it yet. Returning what we have and
                    // being polled again is the whole of `tail -f`.
                    if (!follow) {
                        exhausted = true;
                    }
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
                    //
                    // That comment was the plan and `throw e` was the code, for as long as this
                    // reader has existed: one letter where a number should be, in one line of a
                    // twenty-thousand-line file, ended the poll and stopped the source. reject()
                    // is what the comment was describing. It answers false when no dead-letter
                    // queue is attached, and then this fails exactly as it always did -- a record
                    // is not dropped just because nobody arranged somewhere to put it.
                    //
                    // Offered before the row is abandoned, and the abandon only happens if it was
                    // taken. On the unguarded path abort() refuses -- it cannot return a claimed
                    // inbox cell -- and aborting first meant its "report this" message replaced
                    // the decode failure that caused it, so the one thing the person needed (the
                    // line, the column, the value that would not convert) never reached them.
                    if (!sink.reject(line.getBytes(StandardCharsets.UTF_8), "line " + lineNumber, reasonFor(e))) {
                        throw e;
                    }
                    writer.abort();
                }
            }
        } catch (IOException e) {
            throw new ConfigurationException(DelimitedCodec.DECODE_FAILED, "read failed at line " + lineNumber, e);
        }
        return produced;
    }

    /**
     * A reader that replaces undecodable bytes instead of refusing the file.
     *
     * <p>TY-12. {@code Files.newBufferedReader} uses a decoder whose malformed-input action is
     * {@code REPORT}, so one invalid UTF-8 byte anywhere in a file threw from the *reader* rather
     * than from a record — which aborts the whole read, not the row. The reported symptom was
     * {@code read failed at line 0}: not the line with the bad byte, because the failure happens
     * before any line is produced.
     *
     * <p>Replacing is the right behaviour for a byte-oriented column: a {@code BYTES} field holds
     * arbitrary bytes by definition, and refusing an entire feed because one of them is not text is
     * a text assumption imposed on data that never made it. The replacement character is visible in
     * the value, so a row that was mangled says so; a file that was never read says nothing.
     */
    private static BufferedReader lenientUtf8Reader(Path file) throws IOException {
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        return new BufferedReader(new java.io.InputStreamReader(Files.newInputStream(file), decoder));
    }

    /**
     * What was wrong with the record, as a sentence.
     *
     * <p>The decoder's message already names the line, the column, the declared type and the value
     * that would not convert -- which is the whole of what a person needs. A class name is not, so
     * the exception's type is only used when there is no message at all.
     */
    private static String reasonFor(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
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
