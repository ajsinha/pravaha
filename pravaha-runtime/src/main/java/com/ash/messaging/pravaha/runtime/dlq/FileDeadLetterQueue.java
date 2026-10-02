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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

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
 *
 * <p><strong>Bounded, by evicting the oldest.</strong> Unbounded, this file was a way for one
 * renamed column to fill the disk the node's checkpoints are on. The bound is
 * {@link DeadLetterRetention}, and when it is passed the oldest entries go and the loss is written
 * down beside the file, warned about in the log, and counted on every surface -- see that class for
 * why eviction and not refusal.
 */
public final class FileDeadLetterQueue implements DeadLetterQueue {

    private static final System.Logger LOG = System.getLogger(FileDeadLetterQueue.class.getName());

    private final Path file;
    private final Path evictionRecord;
    private final DeadLetterRetention retention;
    private BufferedWriter writer;
    private long count;
    private long failures;

    /** What is in the file now, kept as it is written so that the bound costs no stat per record. */
    private long bytesInFile;

    private long entriesInFile;

    private long evictedEntries;
    private long evictedBytes;

    public FileDeadLetterQueue(Path file) throws IOException {
        this(file, DeadLetterRetention.defaults());
    }

    public FileDeadLetterQueue(Path file, DeadLetterRetention retention) throws IOException {
        this.file = file;
        this.retention = retention == null ? DeadLetterRetention.unbounded() : retention;
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.evictionRecord = evictionRecordFor(file);
        // Every rejected record's raw bytes land here: a copy of production data, created at
        // the process umask -- and unlike the journal this file carried no warning at all
        // that it is data-classified.
        SensitiveFiles.createOwnerOnly(file);
        this.bytesInFile = Files.exists(file) ? Files.size(file) : 0;
        this.entriesInFile = countEntries(file);
        this.writer = open(file);
    }

    /**
     * Synchronized: a query's pumps and, since DLQPROJ-1, its lane (a row whose evaluation failed)
     * write the one file, each from its own thread.
     */
    @Override
    public synchronized void accept(DeadLetter letter) {
        try {
            String line = DeadLetterJson.write(letter);
            writer.write(line);
            writer.newLine();
            // Flushed per entry. A DLQ whose last few entries are lost in a buffer when the process
            // dies loses them at exactly the moment they matter most -- a crash is when the
            // interesting records arrive.
            writer.flush();
            count++;
            entriesInFile++;
            bytesInFile += line.getBytes(StandardCharsets.UTF_8).length + 1L;
            enforceRetention();
        } catch (IOException e) {
            failures++;
        }
    }

    /**
     * Drops the oldest entries when the file has passed its bound, and writes down what went.
     *
     * <p>Only when the bound is passed, and then down to four fifths of it, so that a queue sitting
     * at its ceiling does not rewrite the whole file once per rejected record. The rewrite is to a
     * temporary file and an atomic move, so a machine that loses power mid-compaction comes back to
     * the old file entire rather than to half of a new one -- losing evidence to the mechanism that
     * exists to bound its loss would be the worst possible bug here.
     */
    private void enforceRetention() {
        if (!retention.bounded()) {
            return;
        }
        boolean ageBound = !retention.maxAge().isZero();
        if (!retention.exceeded(bytesInFile, entriesInFile) && !ageBound) {
            return;
        }
        try {
            compact();
        } catch (IOException e) {
            // The bound could not be applied. Counted like any other write failure, because a file
            // that is over its bound is a problem an operator has to see, and throwing here would
            // stop the pipeline over a housekeeping failure.
            failures++;
            LOG.log(System.Logger.Level.WARNING, "could not apply the dead-letter bound to " + file + ": " + e);
        }
    }

    private void compact() throws IOException {
        long now = System.currentTimeMillis();
        // Two passes, both forwards. The first decides how many of the oldest have to go; the
        // second copies what is left. Forwards because the lines are not fixed width, and holding
        // the whole file in memory to reverse it is exactly the cost the bound exists to avoid --
        // so the tail that fits is sized with a deque of line lengths and nothing else.
        Deque<Long> lengths = new ArrayDeque<>();
        long live = 0;
        long liveBytes = 0;
        long expiredCount = 0;
        long totalBytes = 0;
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                long length = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                totalBytes += length;
                if (expired(line, now)) {
                    // An expired entry is never kept, however much room there is: an age bound is a
                    // promise about how long data is held, not a hint about file size.
                    expiredCount++;
                    continue;
                }
                lengths.addLast(length);
                live++;
                liveBytes += length;
            }
        }
        // Drop from the front until what remains is comfortably inside the bound.
        long keep = live;
        long keepBytes = liveBytes;
        while (!lengths.isEmpty() && (keep > retention.targetEntries() || keepBytes > retention.targetBytes())) {
            keepBytes -= lengths.removeFirst();
            keep--;
        }
        long dropLive = live - keep;
        long went = dropLive + expiredCount;
        if (went <= 0) {
            return;
        }
        Path replacement = file.resolveSibling(file.getFileName() + ".compacting");
        SensitiveFiles.createOwnerOnly(replacement);
        long skipped = 0;
        long droppedBytes = 0;
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                BufferedWriter out = Files.newBufferedWriter(
                        replacement,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            String line;
            while ((line = lines.readLine()) != null) {
                long length = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                if (expired(line, now)) {
                    droppedBytes += length;
                    continue;
                }
                if (skipped < dropLive) {
                    skipped++;
                    droppedBytes += length;
                    continue;
                }
                out.write(line);
                out.newLine();
            }
        }
        writer.close();
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        SensitiveFiles.createOwnerOnly(file);
        this.writer = open(file);
        this.bytesInFile = Files.size(file);
        this.entriesInFile = countEntries(file);
        this.evictedEntries += went;
        this.evictedBytes += droppedBytes;
        recordEviction(now, went, droppedBytes, totalBytes);
        LOG.log(
                System.Logger.Level.WARNING,
                "the dead-letter queue " + file + " reached its bound (" + retention.describe() + "); " + went
                        + " of its oldest entries (" + droppedBytes + " bytes) were evicted and are gone. "
                        + "Raise pravaha.dlq.max-bytes, or drain the queue more often.");
    }

    /**
     * Writes the loss down where it outlives the process.
     *
     * <p>A counter in memory says nothing after a restart, and a log line says nothing to a
     * deployment whose logs roll hourly. This file is what makes "how much of this queue's history
     * is missing" answerable a week later, which is the question somebody asks when the counts on a
     * dashboard do not add up.
     */
    private void recordEviction(long now, long entries, long bytes, long wasBytes) {
        String line = "{\"wall\":" + now + ",\"entries\":" + entries + ",\"bytes\":" + bytes + ",\"was\":" + wasBytes
                + ",\"bound\":\"" + DeadLetterJson.escape(retention.describe()) + "\"}" + System.lineSeparator();
        try {
            SensitiveFiles.createOwnerOnly(evictionRecord);
            Files.writeString(
                    evictionRecord, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            failures++;
        }
    }

    private static Path evictionRecordFor(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        String name = file.getFileName().toString();
        String query = DeadLetterFiles.queryOf(file);
        // A queue opened on an arbitrary path -- `pravaha run --dlq rejects.jsonl` -- keeps its
        // eviction record beside it under the same name, rather than being renamed into the
        // directory convention a server uses.
        return parent == null
                ? Path.of(name + ".evicted")
                : (query == null ? parent.resolve(name + ".evicted") : DeadLetterFiles.evicted(parent, query));
    }

    private static BufferedWriter open(Path file) throws IOException {
        return Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static long countEntries(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        long entries = 0;
        try (BufferedReader lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (lines.readLine() != null) {
                entries++;
            }
        }
        return entries;
    }

    /** Whether this line's entry is past the age bound. A line that is not an entry never is. */
    private boolean expired(String line, long now) {
        Optional<DeadLetter> letter = DeadLetterJson.read(line);
        return letter.isPresent() && retention.tooOld(letter.get().wallMillis(), now);
    }

    public Path file() {
        return file;
    }

    /** The bound this queue applies. */
    public DeadLetterRetention retention() {
        return retention;
    }

    /** Entries retention has evicted since this queue was opened. */
    public long evicted() {
        return evictedEntries;
    }

    /** Bytes those entries were. */
    public long evictedBytes() {
        return evictedBytes;
    }

    /** How large the file is, tracked as it is written so a gauge costs no stat. */
    public long bytesInFile() {
        return bytesInFile;
    }

    /** How many entries the file holds right now, after any eviction. */
    @Override
    public long depth() {
        return entriesInFile;
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
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            failures++;
        }
    }
}
