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
package com.ash.messaging.pravaha.registry;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.serving.AnswerListener;
import com.ash.messaging.pravaha.serving.ServedView;

/**
 * Reads another query's answer as a source: its snapshot, then every change to it (ADR-056).
 *
 * <p>Two halves meet here. The upstream's view calls {@link #onSnapshot} and {@link #onAnswer}
 * inside its own commits, and those only queue. The downstream's pump calls {@link #poll}, which
 * turns what is queued into rows with weights -- a row leaving the answer at {@code -1}, a row
 * entering it at {@code +1} -- and writes them into the downstream's inbox.
 *
 * <p><strong>The image.</strong> Every row this reader has handed over, net of what it has
 * retracted, keyed by the upstream's key: the upstream's answer as the downstream has consumed it.
 * It is what every change is applied against, so a row is retracted with exactly the values it was
 * inserted with; it is what a snapshot is compared with, so a snapshot after a restart, a restore or
 * an overflow feeds only the difference; and it is what a checkpoint records as this input's
 * position, because a position in the upstream's commits does not survive the upstream replaying.
 * The rows are the upstream's own arrays, never copies.
 *
 * <p><strong>The cut.</strong> A checkpoint reads {@link #position} under the ingest freeze, when
 * the lane has been handed exactly the rows written so far, and later asks for {@link #cut} on the
 * lane at the marker. Between the two this reader may hand over more, so from the freeze it records
 * what each key held before its first change, and the cut writes the image with those undone.
 */
final class UpstreamReader implements PartitionReader, AnswerListener {

    /** How many changed rows may wait before the reader stops queueing and re-snapshots instead. */
    static final int QUEUE_LIMIT = 65_536;

    private static final int IMAGE_MAGIC = 0x50525549; // "PRUI"
    private static final int IMAGE_VERSION = 1;
    private static final Object ABSENT = new Object();

    private final String upstream;
    private final ServedView view;
    private final StreamSchema schema;
    private final int[] keyOrdinals;
    private final Object lock = new Object();

    private final ArrayDeque<Object> queue = new ArrayDeque<>();
    private int queuedRows;
    private boolean resync;
    private final ArrayDeque<Pending> ready = new ArrayDeque<>();
    private final Map<RowKey, Object[]> image = new HashMap<>();
    private Map<RowKey, Object> undo;
    private long frontier = Long.MIN_VALUE;
    private long frontierAtFreeze = Long.MIN_VALUE;
    private long sequence;
    private volatile Runnable wake = () -> {};

    private record Snapshot(List<Object[]> rows, long frontier) {}

    private record Change(List<Object[]> leaving, List<Object[]> entering, long frontier) {}

    @SuppressWarnings("ArrayRecordComponent") // carries the array; nothing compares or hashes one
    private record Pending(Object[] row, long weight, long frontier) {}

    UpstreamReader(String upstream, ServedView view, StreamSchema schema, byte[] restoredImage) {
        this.upstream = upstream;
        this.view = view;
        this.schema = schema;
        this.keyOrdinals =
                view.keyOrdinals().stream().mapToInt(Integer::intValue).toArray();
        if (restoredImage != null) {
            readImage(restoredImage);
        }
    }

    /** Starts following: the snapshot arrives at once, under the upstream's monitor. */
    void follow(Runnable onChange) {
        this.wake = onChange;
        view.followAnswer(this);
    }

    /** The upstream's answer as this reader has consumed it: one entry per row. */
    int imageSize() {
        synchronized (lock) {
            return image.size();
        }
    }

    // ------------------------------------------------------------------ the upstream's side

    @Override
    public void onSnapshot(List<Object[]> rows, long at) {
        synchronized (lock) {
            // Everything queued before it is in it.
            queue.clear();
            queuedRows = 0;
            resync = false;
            queue.add(new Snapshot(rows, at));
        }
        wake.run();
    }

    @Override
    public void onAnswer(List<Object[]> leaving, List<Object[]> entering, long at) {
        synchronized (lock) {
            if (resync) {
                return;
            }
            int rows = leaving.size() + entering.size();
            if (queuedRows + rows > QUEUE_LIMIT) {
                // Conflated, not lost: the image makes a fresh snapshot's difference exact.
                queue.clear();
                queuedRows = 0;
                // The feed asks for the answer again (needsSnapshot) -- not from here, which is
                // inside the upstream's commit, under its monitor.
                resync = true;
            } else {
                queue.add(new Change(leaving, entering, at));
                queuedRows += rows;
            }
        }
        wake.run();
    }

    /** Whether the queue overflowed and the feed must ask for the answer again. */
    boolean needsSnapshot() {
        synchronized (lock) {
            return resync && queue.isEmpty() && ready.isEmpty();
        }
    }

    /** Asks the upstream for its answer again; called on the feed thread, never inside a commit. */
    void resnapshot() {
        view.resnapshotAnswer(this);
    }

    // ------------------------------------------------------------------ the downstream's side

    @Override
    public int poll(RecordSink sink, int maxRecords) {
        synchronized (lock) {
            int written = 0;
            while (written < maxRecords) {
                if (ready.isEmpty()) {
                    // An item may expand to nothing -- an empty snapshot, a change the image
                    // already holds -- so look again rather than write what is not there.
                    if (!expandNext()) {
                        break;
                    }
                    continue;
                }
                Pending next = ready.poll();
                write(sink, next);
                written++;
            }
            return written;
        }
    }

    /** Turns the next queued item into rows, against the image as it stands. False when none. */
    private boolean expandNext() {
        Object next = queue.poll();
        if (next == null) {
            return false;
        }
        switch (next) {
            case Snapshot snapshot -> {
                Map<RowKey, Object[]> after = new LinkedHashMap<>();
                for (Object[] row : snapshot.rows()) {
                    after.put(keyOf(row), row);
                }
                for (Map.Entry<RowKey, Object[]> held : image.entrySet()) {
                    if (!after.containsKey(held.getKey())) {
                        ready.add(new Pending(held.getValue(), -1L, snapshot.frontier()));
                    }
                }
                after.forEach((key, row) -> difference(key, row, snapshot.frontier()));
            }
            case Change change -> {
                queuedRows -= change.leaving().size() + change.entering().size();
                Map<RowKey, Object[]> after = new LinkedHashMap<>();
                for (Object[] row : change.leaving()) {
                    after.put(keyOf(row), null);
                }
                for (Object[] row : change.entering()) {
                    after.put(keyOf(row), row);
                }
                after.forEach((key, row) -> difference(key, row, change.frontier()));
            }
            default -> throw new IllegalStateException("unknown queued item " + next);
        }
        return true;
    }

    /** Queues what takes the image's row under {@code key} to {@code after}: nothing when equal. */
    private void difference(RowKey key, Object[] after, long at) {
        Object[] before = image.get(key);
        if (Arrays.deepEquals(before, after)) {
            return;
        }
        if (before != null) {
            ready.add(new Pending(before, -1L, at));
        }
        if (after != null) {
            ready.add(new Pending(after, 1L, at));
        }
    }

    private void write(RecordSink sink, Pending pending) {
        RowWriter writer = sink.beginRow();
        Object[] row = pending.row();
        for (int ordinal = 0; ordinal < row.length; ordinal++) {
            ViewRowValues.write(writer, schema, ordinal, row[ordinal]);
        }
        // The upstream's committed frontier is this row's event time: the downstream's view
        // frontier follows the upstream's, and an AtLeast read of it waits for the upstream.
        long at = pending.frontier() == Long.MIN_VALUE ? 0L : pending.frontier();
        writer.weight(pending.weight())
                .eventTimestampNanos(at)
                .sequence(++sequence)
                .commit();
        RowKey key = keyOf(row);
        Object previous = pending.weight() < 0 ? image.remove(key) : image.put(key, row);
        if (undo != null && !undo.containsKey(key)) {
            undo.put(key, previous == null ? ABSENT : previous);
        }
        frontier = Math.max(frontier, pending.frontier());
    }

    /**
     * Where this input stands, read under the checkpoint's freeze: the upstream and the frontier
     * consumed, for an operator to read. The exact position is the image, carried by {@link #cut}.
     */
    @Override
    public SourceOffset position() {
        synchronized (lock) {
            undo = new HashMap<>();
            frontierAtFreeze = frontier;
            return new SourceOffset("view:" + upstream + "@" + frontier);
        }
    }

    /** The image as it stood at the last {@link #position}, for the checkpoint being cut. */
    @SuppressWarnings("ReferenceEquality") // identity is the question here: a sentinel, a thread or the very object
    byte[] cut() {
        synchronized (lock) {
            Map<RowKey, Object> undone = undo == null ? Map.of() : undo;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
                out.writeInt(IMAGE_MAGIC);
                out.writeInt(IMAGE_VERSION);
                out.writeUTF(upstream);
                out.writeInt(schema.fieldCount());
                out.writeLong(frontierAtFreeze);
                java.util.List<Object[]> rows = new java.util.ArrayList<>(image.size());
                for (Map.Entry<RowKey, Object[]> entry : image.entrySet()) {
                    Object before = undone.get(entry.getKey());
                    if (before == null) {
                        rows.add(entry.getValue());
                    } else if (before != ABSENT) {
                        rows.add((Object[]) before);
                    }
                }
                for (Map.Entry<RowKey, Object> entry : undone.entrySet()) {
                    if (entry.getValue() != ABSENT && !image.containsKey(entry.getKey())) {
                        rows.add((Object[]) entry.getValue());
                    }
                }
                out.writeInt(rows.size());
                for (Object[] row : rows) {
                    ViewRowValues.writeRow(out, row);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot write the consumed answer of '" + upstream + "': " + e, e);
            }
            undo = null;
            return bytes.toByteArray();
        }
    }

    private void readImage(byte[] bytes) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (in.readInt() != IMAGE_MAGIC || in.readInt() != IMAGE_VERSION) {
                throw refusedImage("it is not an image this engine writes");
            }
            String recorded = in.readUTF();
            int columns = in.readInt();
            if (!recorded.equals(upstream) || columns != schema.fieldCount()) {
                throw refusedImage("it was taken over '" + recorded + "' with " + columns + " columns, and this query "
                        + "reads '" + upstream + "' with " + schema.fieldCount());
            }
            frontier = in.readLong();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                Object[] row = ViewRowValues.readRow(in, columns);
                image.put(keyOf(row), row);
            }
        } catch (java.io.IOException e) {
            throw refusedImage("it could not be read: " + e.getMessage());
        }
    }

    private PravahaException refusedImage(String why) {
        return new PravahaException(
                RegistryErrors.CHAIN_UNSUPPORTED,
                "the checkpoint's record of what this query had consumed from '" + upstream + "' cannot be used: "
                        + why + ". Following the upstream without it would feed its whole answer again on top of "
                        + "what the restored state already counted, so the restore is refused (ADR-056).");
    }

    private RowKey keyOf(Object[] row) {
        Object[] key = new Object[keyOrdinals.length];
        for (int i = 0; i < key.length; i++) {
            key[i] = row[keyOrdinals[i]];
        }
        return new RowKey(key);
    }

    /** A view key, compared by content -- including a {@code BYTES} column's. */
    @SuppressWarnings("ArrayRecordComponent") // equals and hashCode compare the array's contents
    private record RowKey(Object[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof RowKey key && Arrays.deepEquals(values, key.values);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(values);
        }
    }

    @Override
    public void pause() {
        // Nothing to stop: the upstream queues, and an overflow re-snapshots. See QUEUE_LIMIT.
    }

    @Override
    public void resume() {}

    @Override
    public void close() {
        view.unfollowAnswer(this);
    }
}
