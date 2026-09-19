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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.io.SensitiveFiles;

/**
 * Every row a delete-detecting reader has emitted and not retracted, and the files that give them
 * back exactly after a restart.
 *
 * <p><strong>The invariant.</strong> The rows held here are, as a Z-set, exactly the sum of every
 * row this reader has written into the engine: an emitted {@code +1} adds one, an emitted {@code -1}
 * removes the row it cancels. A pass compares the store against <em>these</em> rows -- not against
 * the previous pass as read -- so whatever happened in between, the next pass emits exactly the
 * difference between what the engine was given and what the store holds.
 *
 * <p><strong>Restore.</strong> Each emitted row is appended to a log and counted; {@link #token()}
 * forces the log to disk and returns {@code <reader>/<count>}. A reader created from that token loads
 * the newest snapshot at or below the count and replays the log up to it, which is the emitted rows
 * as they stood when the token was taken -- the same rows the engine's own checkpoint, taken at the
 * same frozen moment, holds in its operator state. The two agree, so the first pass after a restore
 * retracts only rows the restored view holds and inserts only rows it lacks. Log entries after the
 * count are ignored: they were emitted after the checkpoint, into state the restore threw away.
 *
 * <p><strong>Files.</strong> Under {@code <state dir>/<partition>/<reader>/}: {@code snap-<n>.bin}
 * (every row after {@code n} emitted, checksummed, written to a temporary name and renamed),
 * {@code log-<n>.bin} (each row emitted after snapshot {@code n}, each entry checksummed), {@code
 * parent} (the reader this one was restored from, if any) and {@code confirmed} (present once a
 * checkpoint naming this reader is durable). They hold the source's rows, so each is created
 * owner-only before its first byte, as checkpoints are. A restored reader writes its own snapshot at once and
 * never writes into its parent's directory; the parent's is deleted once a checkpoint naming the new
 * reader is durable, since nothing will resume from the older one again.
 *
 * <p>Identical, apart from its package, in the Aerospike and the Cassandra plugin; see {@link RowRecorder}.
 *
 * @param <K> the store's identity for a row: Aerospike's record digest, Cassandra's partition token
 */
final class EmittedRows<K> {

    /** One emitted row. Rows sharing a key -- one Cassandra partition's rows -- chain through {@code next}. */
    static final class Entry {
        final byte[] row;
        final long eventTimeNanos;
        Entry next;

        /** The pass that last saw this row in the store; a scan-order-free source marks and sweeps with it. */
        int seenPass;

        Entry(byte[] row, long eventTimeNanos) {
            this.row = row;
            this.eventTimeNanos = eventTimeNanos;
        }
    }

    /** How a key is written to and read back from the state files. */
    interface KeyCodec<K> {
        void write(DataOutput out, K key) throws IOException;

        K read(DataInput in) throws IOException;
    }

    /** The two codes a plugin reports these failures under. */
    record Codes(ErrorCode full, ErrorCode failed) {}

    private static final int MAGIC = 0x50564453; // "PVDS"
    private static final int VERSION = 1;
    private static final int MAX_TOKENS_REMEMBERED = 16;

    private final Map<K, Entry> rows;
    private final KeyCodec<K> codec;
    private final long maxRows;
    private final String fingerprint;
    private final Codes codes;
    private final String what;
    private final Path partitionDir;
    private final Path dir;
    private final String readerId;
    private final String parentId;
    private long minSnapshotEntries = 1024;

    private long size;
    private long emitted;
    private long segmentStart;
    private FileOutputStream logFile;
    private DataOutputStream log;
    private boolean dirty;
    private final TreeSet<Long> snapshots = new TreeSet<>();
    private final TreeSet<Long> handedOut = new TreeSet<>();
    private long confirmed = -1;
    private boolean released;
    private boolean closed;

    /**
     * Opens this reader's state: empty for a fresh reader, or loaded from the reader a token names.
     *
     * @param rows an empty map of the kind the reader needs -- hashed, or sorted by token
     * @param partitionDir where every reader of this partition keeps its directory
     * @param resumeToken {@code <reader>/<count>} from {@link #token()}, or null for a fresh reader
     * @param fingerprint the stream's shape; state written for another shape is refused, not replayed
     * @param what the reader in words, for messages
     */
    static <K> EmittedRows<K> open(
            Map<K, Entry> rows,
            KeyCodec<K> codec,
            Path partitionDir,
            String resumeToken,
            long maxRows,
            String fingerprint,
            Codes codes,
            String what) {
        return new EmittedRows<>(rows, codec, partitionDir, resumeToken, maxRows, fingerprint, codes, what);
    }

    private EmittedRows(
            Map<K, Entry> rows,
            KeyCodec<K> codec,
            Path partitionDir,
            String resumeToken,
            long maxRows,
            String fingerprint,
            Codes codes,
            String what) {
        this.rows = rows;
        this.codec = codec;
        this.maxRows = maxRows;
        this.fingerprint = fingerprint;
        this.codes = codes;
        this.what = what;
        this.partitionDir = partitionDir;
        this.readerId = UUID.randomUUID().toString().replace("-", "");
        this.dir = partitionDir.resolve(readerId);
        String parent = null;
        if (resumeToken != null) {
            int slash = resumeToken.indexOf('/');
            if (slash <= 0) {
                throw new PravahaException(
                        codes.failed(),
                        what + ": the checkpoint names delete-detection state '" + resumeToken
                                + "', which is not <reader>/<count>");
            }
            parent = resumeToken.substring(0, slash);
            long count = parseCount(resumeToken.substring(slash + 1), resumeToken);
            load(partitionDir.resolve(parent), count);
        }
        this.parentId = parent;
        try {
            SensitiveFiles.createOwnerOnly(dir.resolve("parent"));
            Files.writeString(dir.resolve("parent"), parent == null ? "" : parent);
            writeSnapshot(0);
            openSegment(0);
        } catch (IOException e) {
            throw failed("cannot create its delete-detection state under " + dir, e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The rows.

    /** How many rows are held -- the number {@code deletes.max.keys} bounds. */
    synchronized long size() {
        return size;
    }

    /** The rows of one key, or null. The chain must not be modified. */
    synchronized Entry get(K key) {
        return rows.get(key);
    }

    /** Every key and its chain, for a sweep. Called on the polling thread only. */
    void forEach(BiConsumer<K, Entry> action) {
        synchronized (this) {
            rows.forEach(action);
        }
    }

    /** The map itself, for a reader that walks it in key order. Polling thread only. */
    Map<K, Entry> view() {
        return rows;
    }

    /**
     * Refuses a pass that would hold more rows than the ceiling, before any of it is emitted.
     *
     * <p>Refused rather than degraded: the alternative is to stop remembering some rows, and a row
     * not remembered is a delete that can never be detected -- a view keeping a dead row for ever,
     * which is the very failure this state exists to prevent.
     */
    void refuseBeyondCeiling(long wouldHold) {
        if (wouldHold > maxRows) {
            throw new PravahaException(
                    codes.full(),
                    what + " would hold " + wouldHold + " rows to detect deletes, more than deletes.max.keys ("
                            + maxRows + "). Every row the source has emitted is remembered so that its "
                            + "disappearance can be retracted; raise deletes.max.keys (and the heap to match), "
                            + "split the source into more partitions, or set deletes: ignore.");
        }
    }

    /**
     * Records one emitted row: applies it to the held rows and appends it to the log.
     *
     * <p>Call only after the row was committed to the engine, on the polling thread. A retraction
     * must name a row that is held; anything else is a bug in the reader, and is thrown as one.
     */
    synchronized void emitted(long weight, K key, byte[] row, long eventTimeNanos) {
        apply(weight, key, row, eventTimeNanos);
        try {
            writeEntry(log, weight, key, eventTimeNanos, row);
        } catch (IOException e) {
            throw failed("cannot append to its delete-detection log in " + dir, e);
        }
        emitted++;
        dirty = true;
    }

    private void apply(long weight, K key, byte[] row, long eventTimeNanos) {
        if (weight > 0) {
            Entry entry = new Entry(row, eventTimeNanos);
            entry.next = rows.get(key);
            rows.put(key, entry);
            size++;
            return;
        }
        Entry head = rows.get(key);
        Entry previous = null;
        for (Entry at = head; at != null; previous = at, at = at.next) {
            if (Arrays.equals(at.row, row)) {
                if (previous == null) {
                    if (at.next == null) {
                        rows.remove(key);
                    } else {
                        rows.put(key, at.next);
                    }
                } else {
                    previous.next = at.next;
                }
                size--;
                return;
            }
        }
        throw new IllegalStateException(what + ": a retraction names a row that was never emitted, for key " + key);
    }

    // ------------------------------------------------------------------------------------------
    // Positions.

    /**
     * Where this reader is, as {@code <reader>/<count>}, with every row up to it forced to disk.
     *
     * <p>Forced here because this is the only moment before a checkpoint can become durable: a token
     * in a durable checkpoint that names rows still in a page cache is a restore that fails, or --
     * worse, if the tail is torn -- one that silently replays too few.
     */
    synchronized String token() {
        if (closed) {
            return readerId + "/" + emitted;
        }
        if (dirty) {
            try {
                log.flush();
                logFile.getChannel().force(false);
            } catch (IOException e) {
                throw failed("cannot force its delete-detection log to disk in " + dir, e);
            }
            dirty = false;
        }
        handedOut.add(emitted);
        while (handedOut.size() > MAX_TOKENS_REMEMBERED) {
            handedOut.pollFirst();
        }
        return readerId + "/" + emitted;
    }

    /**
     * A pass has ended with everything it found emitted: a moment to snapshot, when the log has grown
     * past a quarter of the rows (at least {@link #minSnapshotEntries}). So a restore replays at most
     * that much, and a pass that changed nothing writes nothing at all.
     */
    synchronized void passEnded() {
        long since = emitted - segmentStart;
        if (since == 0 || since < Math.max(minSnapshotEntries, size / 4)) {
            return;
        }
        try {
            writeSnapshot(emitted);
            closeSegment();
            openSegment(emitted);
        } catch (IOException e) {
            throw failed("cannot write its delete-detection snapshot in " + dir, e);
        }
        collectGarbage();
    }

    /**
     * A checkpoint naming {@code token} is durable, so nothing older will be resumed from again: the
     * snapshots and logs it does not need can go, and so can the directory this reader was restored
     * from, and any other reader restored from that same parent whose own checkpoint never became
     * durable -- an attempt that failed before this one.
     */
    synchronized void checkpointed(String token) {
        int slash = token.indexOf('/');
        if (slash <= 0 || !token.substring(0, slash).equals(readerId)) {
            return;
        }
        long count;
        try {
            count = Long.parseLong(token.substring(slash + 1));
        } catch (NumberFormatException e) {
            return;
        }
        confirmed = Math.max(confirmed, count);
        handedOut.headSet(confirmed).clear();
        if (!released) {
            released = true;
            try {
                SensitiveFiles.createOwnerOnly(dir.resolve("confirmed"));
                Files.writeString(dir.resolve("confirmed"), token);
                if (parentId != null) {
                    deleteTree(partitionDir.resolve(parentId));
                    try (var siblings = Files.list(partitionDir)) {
                        for (Path sibling : siblings.toList()) {
                            if (sibling.equals(dir) || !Files.isDirectory(sibling)) {
                                continue;
                            }
                            Path parentFile = sibling.resolve("parent");
                            if (Files.exists(parentFile)
                                    && Files.readString(parentFile).equals(parentId)
                                    && !Files.exists(sibling.resolve("confirmed"))) {
                                deleteTree(sibling);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // Garbage left behind is disk, not correctness; the next confirmation tries again.
                released = false;
            }
        }
        collectGarbage();
    }

    /** Keeps the snapshot (and its log) under every count that may still be resumed from, and nothing else. */
    private void collectGarbage() {
        TreeSet<Long> needed = new TreeSet<>();
        needed.add(snapshots.floor(emitted));
        for (long count : handedOut) {
            Long floor = snapshots.floor(count);
            if (floor != null) {
                needed.add(floor);
            }
        }
        if (confirmed >= 0) {
            Long floor = snapshots.floor(confirmed);
            if (floor != null) {
                needed.add(floor);
            }
        }
        for (Long point : new ArrayList<>(snapshots)) {
            if (!needed.contains(point)) {
                try {
                    Files.deleteIfExists(dir.resolve("snap-" + point + ".bin"));
                    Files.deleteIfExists(dir.resolve("log-" + point + ".bin"));
                    snapshots.remove(point);
                } catch (IOException e) {
                    // Left for the next collection.
                }
            }
        }
    }

    /** Closes the log. Deletes nothing: a checkpoint may still name this reader. */
    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            closeSegment();
        } catch (IOException e) {
            // Nothing a checkpoint relies on is lost: token() forced what it handed out.
        }
    }

    /** Test hook: how many log entries make a snapshot worth writing, at minimum. */
    void snapshotAtLeastEvery(long entries) {
        this.minSnapshotEntries = entries;
    }

    /** This reader's directory, for tests and messages. */
    Path directory() {
        return dir;
    }

    // ------------------------------------------------------------------------------------------
    // Files.

    private void openSegment(long start) throws IOException {
        Path file = dir.resolve("log-" + start + ".bin");
        SensitiveFiles.createOwnerOnly(file);
        this.logFile = new FileOutputStream(file.toFile());
        this.log = new DataOutputStream(new BufferedOutputStream(logFile, 1 << 16));
        this.segmentStart = start;
        this.dirty = false;
    }

    private void closeSegment() throws IOException {
        if (log != null) {
            log.flush();
            logFile.getChannel().force(false);
            log.close();
            log = null;
        }
    }

    private void writeSnapshot(long count) throws IOException {
        Path temporary = dir.resolve("snap-" + count + ".tmp");
        SensitiveFiles.createOwnerOnly(temporary);
        CRC32 crc = new CRC32();
        try (FileOutputStream file = new FileOutputStream(temporary.toFile())) {
            DataOutputStream out =
                    new DataOutputStream(new BufferedOutputStream(new CheckedOutputStream(file, crc), 1 << 16));
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(fingerprint);
            out.writeLong(count);
            out.writeLong(size);
            for (Map.Entry<K, Entry> each : rows.entrySet()) {
                for (Entry at = each.getValue(); at != null; at = at.next) {
                    codec.write(out, each.getKey());
                    out.writeLong(at.eventTimeNanos);
                    out.writeInt(at.row.length);
                    out.write(at.row);
                }
            }
            out.flush();
            long sum = crc.getValue();
            new DataOutputStream(file).writeLong(sum);
            file.getChannel().force(true);
        }
        Files.move(temporary, dir.resolve("snap-" + count + ".bin"), StandardCopyOption.ATOMIC_MOVE);
        SensitiveFiles.syncDirectory(dir);
        snapshots.add(count);
    }

    private void writeEntry(DataOutputStream out, long weight, K key, long eventTimeNanos, byte[] row)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(row.length + 48);
        DataOutputStream entry = new DataOutputStream(bytes);
        entry.writeByte(weight > 0 ? 1 : -1);
        codec.write(entry, key);
        entry.writeLong(eventTimeNanos);
        entry.writeInt(row.length);
        entry.write(row);
        entry.flush();
        byte[] encoded = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(encoded);
        out.write(encoded);
        out.writeInt((int) crc.getValue());
    }

    /** Loads another reader's rows as they stood after {@code count} were emitted. */
    private void load(Path from, long count) {
        if (!Files.isDirectory(from)) {
            throw failed(
                    "the checkpoint names delete-detection state in " + from + ", which is gone. Without it the rows "
                            + "the restored view holds are unknown, and the next pass would retract nothing it should "
                            + "and double everything else. Restore that directory, or drop and re-register the query "
                            + "so it starts afresh",
                    null);
        }
        long snapshot = -1;
        try (var files = Files.list(from)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("snap-") && name.endsWith(".bin")) {
                    long point = parseCount(name.substring(5, name.length() - 4), name);
                    if (point <= count && point > snapshot) {
                        snapshot = point;
                    }
                }
            }
        } catch (IOException e) {
            throw failed("cannot list its delete-detection state in " + from, e);
        }
        if (snapshot < 0) {
            throw failed("no snapshot at or below " + count + " remains in " + from, null);
        }
        readSnapshot(from.resolve("snap-" + snapshot + ".bin"), snapshot);
        replayLog(from.resolve("log-" + snapshot + ".bin"), count - snapshot);
    }

    private void readSnapshot(Path file, long expectedCount) {
        CRC32 crc = new CRC32();
        try (InputStream raw = Files.newInputStream(file)) {
            long payload = Files.size(file) - Long.BYTES;
            DataInputStream in = new DataInputStream(
                    new CheckedInputStream(new BufferedInputStream(new LimitedStream(raw, payload), 1 << 16), crc));
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw failed(file + " is not a delete-detection snapshot this version wrote", null);
            }
            String shape = in.readUTF();
            if (!shape.equals(fingerprint)) {
                throw failed(
                        file + " was written for a stream shaped " + shape + ", and this one is " + fingerprint
                                + ". Rows of another shape cannot be retracted from this one; drop and re-register "
                                + "the query so it starts afresh",
                        null);
            }
            if (in.readLong() != expectedCount) {
                throw failed(file + " does not hold the count its name says", null);
            }
            long held = in.readLong();
            refuseBeyondCeiling(held);
            for (long index = 0; index < held; index++) {
                K key = codec.read(in);
                long eventTime = in.readLong();
                byte[] row = new byte[in.readInt()];
                in.readFully(row);
                apply(1, key, row, eventTime);
            }
            long expected = crc.getValue();
            long stored = new DataInputStream(raw).readLong();
            if (stored != expected) {
                throw failed(file + " fails its checksum; it was damaged after it was written", null);
            }
        } catch (NoSuchFileException e) {
            throw failed("the snapshot " + file + " is gone", e);
        } catch (IOException e) {
            throw failed("cannot read the snapshot " + file, e);
        }
    }

    private void replayLog(Path file, long entries) {
        if (entries == 0) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            for (long index = 0; index < entries; index++) {
                byte weight = in.readByte();
                K key = codec.read(in);
                long eventTime = in.readLong();
                byte[] row = new byte[in.readInt()];
                in.readFully(row);
                int stored = in.readInt();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(row.length + 48);
                DataOutputStream entry = new DataOutputStream(bytes);
                entry.writeByte(weight);
                codec.write(entry, key);
                entry.writeLong(eventTime);
                entry.writeInt(row.length);
                entry.write(row);
                CRC32 crc = new CRC32();
                crc.update(bytes.toByteArray());
                if ((int) crc.getValue() != stored) {
                    throw failed(file + " entry " + index + " fails its checksum", null);
                }
                apply(weight, key, row, eventTime);
                if (weight > 0) {
                    refuseBeyondCeiling(size);
                }
            }
        } catch (EOFException e) {
            throw failed(file + " ends before the " + entries + " entries the checkpoint names", e);
        } catch (IOException e) {
            throw failed("cannot read the log " + file, e);
        }
    }

    private PravahaException failed(String detail, Throwable cause) {
        return new PravahaException(codes.failed(), what + ": " + detail, cause);
    }

    private long parseCount(String text, String whole) {
        try {
            long value = Long.parseLong(text);
            if (value < 0) {
                throw new NumberFormatException(text);
            }
            return value;
        } catch (NumberFormatException e) {
            throw failed("'" + whole + "' does not hold a count", e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> all;
        try (var walk = Files.walk(root)) {
            all = walk.sorted(java.util.Comparator.reverseOrder()).toList();
        }
        for (Path path : all) {
            Files.deleteIfExists(path);
        }
    }

    /** Reads at most {@code limit} bytes of a stream, so the trailing checksum is not read as payload. */
    private static final class LimitedStream extends InputStream {
        private final InputStream in;
        private long left;

        LimitedStream(InputStream in, long limit) {
            this.in = in;
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int value = in.read();
            if (value >= 0) {
                left--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int read = in.read(buffer, offset, (int) Math.min(length, left));
            if (read > 0) {
                left -= read;
            }
            return read;
        }
    }
}
