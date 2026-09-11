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
package com.ash.messaging.pravaha.runtime.exec;

import java.util.HashMap;
import java.util.Map;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.state.RowStore;

/**
 * One side of a join: {@code I(A)}, the integral of everything that arrived, indexed by join key.
 *
 * <p>This is the state a stream-to-stream join is made of. The bilinear rule needs each side's
 * accumulated rows to match the other side's new ones against, and this holds them: rows copied
 * off-heap into a {@link RowStore}, chained per key-hash bucket, each with the Z-set weight of that
 * row.
 *
 * <p><strong>Rows are stored as Z-set elements, not as events.</strong> Two arrivals with identical
 * field values are one element with weight 2, and a retraction is weight -1 that cancels against
 * them. That is what makes an update work without a line of update-specific code: the old row's -1
 * meets its +1 and the entry disappears, and the block goes back to the store rather than being held
 * for the life of the query. A join that appended events instead would keep the retracted row
 * forever and emit it again on the next probe from the other side.
 *
 * <p>An entry lives in a {@link RowStore} block laid out as: next handle, weight, row length, then
 * the row's own bytes. The chain is intrusive because the alternative -- a list object per key --
 * is an allocation per key per query, and there are meant to be ten thousand queries.
 */
final class JoinSide implements AutoCloseable {

    private static final int OFFSET_NEXT = 0;
    private static final int OFFSET_WEIGHT = 8;
    private static final int OFFSET_ROW_LENGTH = 16;
    private static final int OFFSET_ROW = 24;

    private final RowStore store;
    private final StreamSchema schema;
    private final RowLayout layout;
    private final int[] keyOrdinals;
    private final BinaryRowView cursor;

    /**
     * Bucket heads, keyed by the key columns' hash.
     *
     * <p>On the heap, and the one place in this class that is. The rows themselves -- the part that
     * grows with data -- are off-heap; this map holds one long per <em>distinct key</em>, which is
     * the smaller number by orders of magnitude in every join worth running. Moving it off-heap is
     * a later change with a measurement behind it, not a guess.
     */
    private final Map<Long, Long> buckets = new HashMap<>();

    private long rows;
    private long distinctRows;

    /**
     * Somewhere to land a row read back from a checkpoint before it is indexed.
     *
     * <p>Only ever touched by {@link #readFrom}, which runs while the query is stopped. Reusing it
     * on the row path would be a bug of exactly the kind this codebase keeps finding: a view handed
     * downstream while the buffer behind it is about to be overwritten.
     *
     * <p>It has its own view for the same reason, learned the hard way. Handing the restored row in
     * on the shared cursor meant {@code add} re-pointed that cursor at each chain entry it compared
     * against, so the row being inserted turned into the row it was being compared with, and the
     * whole side restored as empty.
     */
    private MemoryRegion scratch;

    private final BinaryRowView restoreView;

    private final com.ash.messaging.pravaha.common.memory.MemoryAccess access;

    JoinSide(
            RowStore store,
            com.ash.messaging.pravaha.common.memory.MemoryAccess access,
            StreamSchema schema,
            int[] keyOrdinals) {
        this.store = store;
        this.access = access;
        this.schema = schema;
        this.layout = RowLayout.of(schema);
        this.keyOrdinals = keyOrdinals.clone();
        this.cursor = new BinaryRowView(layout);
        this.restoreView = new BinaryRowView(layout);
    }

    /**
     * Adds {@code weight} of a row to this side.
     *
     * <p>Returns without storing anything when the weight cancels to zero, and releases the block
     * when an existing entry cancels. Rows whose key contains a null are not stored at all: they can
     * never match, so holding them is a leak with no possible benefit.
     */
    void add(RowView row, long weight) {
        if (weight == 0 || !JoinKeys.isMatchable(row, keyOrdinals)) {
            return;
        }
        long hash = JoinKeys.hash(row, keyOrdinals, schema);
        long head = buckets.getOrDefault(hash, ArenaHandle.NULL);

        long previous = ArenaHandle.NULL;
        for (long entry = head; entry != ArenaHandle.NULL; entry = nextOf(entry)) {
            if (sameRow(entry, row)) {
                long updated = weightOf(entry) + weight;
                if (updated == 0) {
                    unlink(hash, previous, entry);
                } else {
                    store.regionOf(entry).putLong(store.offsetOf(entry) + OFFSET_WEIGHT, updated);
                }
                rows += weight;
                return;
            }
            previous = entry;
        }

        buckets.put(hash, insert(row, weight, head));
        rows += weight;
        distinctRows++;
    }

    /**
     * Forgets rows whose event time is before {@code horizon}, returning how many went.
     *
     * <p>This is what makes a stream-to-stream join survivable, and it is only correct because the
     * horizon is derived from the join's <em>declared</em> match window. A row older than
     * "watermark minus the match window" can no longer be part of any match this join promises to
     * find: any partner still to arrive is, by the watermark's definition, later than that. So this
     * is not discarding data that might have matched -- it is discarding data that is outside what
     * the query asked for.
     *
     * <p>That distinction is the whole argument. Evicting on a size ceiling would silently lose
     * matches the query did ask for, which is why the ceiling fails loudly instead.
     */
    long evictOlderThan(long horizon) {
        if (horizon == Long.MIN_VALUE || buckets.isEmpty()) {
            return 0;
        }
        long removed = 0;
        java.util.Iterator<Map.Entry<Long, Long>> heads = buckets.entrySet().iterator();
        while (heads.hasNext()) {
            Map.Entry<Long, Long> bucket = heads.next();
            long previous = ArenaHandle.NULL;
            long entry = bucket.getValue();
            while (entry != ArenaHandle.NULL) {
                long next = nextOf(entry);
                if (eventTimeOf(entry) < horizon) {
                    long weight = weightOf(entry);
                    if (previous == ArenaHandle.NULL) {
                        bucket.setValue(next);
                    } else {
                        store.regionOf(previous).putLong(store.offsetOf(previous) + OFFSET_NEXT, next);
                    }
                    store.release(entry);
                    rows -= weight;
                    distinctRows--;
                    removed++;
                } else {
                    previous = entry;
                }
                entry = next;
            }
            if (bucket.getValue() == ArenaHandle.NULL) {
                heads.remove();
            }
        }
        return removed;
    }

    /** The event time of a stored row, read back out of the row itself. */
    private long eventTimeOf(long entry) {
        return cursor.wrap(store.regionOf(entry), store.offsetOf(entry) + OFFSET_ROW)
                .eventTimestampNanos();
    }

    /** Calls back for every stored row whose key equals the probe's, with that row's weight. */
    void forEachMatch(RowView probe, int[] probeKeyOrdinals, StreamSchema probeSchema, MatchVisitor visitor) {
        if (!JoinKeys.isMatchable(probe, probeKeyOrdinals)) {
            return;
        }
        long hash = JoinKeys.hash(probe, probeKeyOrdinals, probeSchema);
        for (long entry = buckets.getOrDefault(hash, ArenaHandle.NULL);
                entry != ArenaHandle.NULL;
                entry = nextOf(entry)) {
            RowView stored = wrap(entry);
            // The hash brought candidates; only equal key values are matches.
            //
            // Not reachable today, and kept anyway. Buckets are keyed by the full 64-bit hash, so
            // two distinct keys share a chain only on a full 64-bit collision -- which no test can
            // construct and no query will hit. It becomes load-bearing the moment the index moves
            // off-heap to a masked table, where the bucket is a handful of low bits and collisions
            // are ordinary. Removing it now would mean rediscovering it then, as pairs that never
            // matched appearing in output. JoinKeysTest covers the comparison itself.
            if (JoinKeys.equal(stored, keyOrdinals, schema, probe, probeKeyOrdinals)) {
                visitor.matched(stored, weightOf(entry));
            }
        }
    }

    /** Net weight held: the sum over stored rows, which retractions bring back down. */
    long rowCount() {
        return rows;
    }

    /** Distinct rows currently stored, which is what the store's block count should track. */
    long distinctRows() {
        return distinctRows;
    }

    int distinctKeys() {
        return buckets.size();
    }

    /**
     * Writes every stored row and its weight.
     *
     * <p>Rows go out as their own bytes, unchanged. Re-encoding them through the writer would mean a
     * second encoder that has to agree with the first for the checkpoint to be readable, and the
     * disagreement would only show up as a corrupt restore.
     */
    void writeTo(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt((int) distinctRows);
        byte[] scratch = new byte[0];
        for (long head : buckets.values()) {
            for (long entry = head; entry != ArenaHandle.NULL; entry = nextOf(entry)) {
                int length = store.regionOf(entry).getInt(store.offsetOf(entry) + OFFSET_ROW_LENGTH);
                if (scratch.length < length) {
                    scratch = new byte[length];
                }
                store.regionOf(entry).getBytes(store.offsetOf(entry) + OFFSET_ROW, scratch, 0, length);
                out.writeLong(weightOf(entry));
                out.writeInt(length);
                out.write(scratch, 0, length);
            }
        }
    }

    /**
     * Reads rows back in.
     *
     * <p>Through {@link #add}, not by rebuilding the index directly, so a restored side is indexed
     * by the same code that indexed it originally. A separate restore path is a second
     * implementation of the bucket layout, and the two drift.
     */
    void readFrom(java.io.DataInputStream in) throws java.io.IOException {
        int count = in.readInt();
        byte[] bytes = new byte[0];
        for (int i = 0; i < count; i++) {
            long weight = in.readLong();
            int length = in.readInt();
            if (bytes.length < length) {
                bytes = new byte[length];
            }
            in.readFully(bytes, 0, length);
            ensureScratch(length);
            scratch.putBytes(0, bytes, 0, length);
            add(restoreView.wrap(scratch, 0), weight);
        }
    }

    private void ensureScratch(int length) {
        if (scratch == null || scratch.capacity() < length) {
            if (scratch != null) {
                scratch.close();
            }
            scratch = access.allocate(Math.max(length, 1024));
        }
    }

    private long insert(RowView row, long weight, long nextHandle) {
        int length = asBinary(row).length();
        long entry = store.allocate(OFFSET_ROW + length);
        MemoryRegion region = store.regionOf(entry);
        int base = store.offsetOf(entry);
        region.putLong(base + OFFSET_NEXT, nextHandle);
        region.putLong(base + OFFSET_WEIGHT, weight);
        region.putInt(base + OFFSET_ROW_LENGTH, length);
        copyRow(row, region, base + OFFSET_ROW, length);
        return entry;
    }

    private void unlink(long hash, long previous, long entry) {
        long next = nextOf(entry);
        if (previous == ArenaHandle.NULL) {
            if (next == ArenaHandle.NULL) {
                // Last row for this key. Dropping the bucket rather than leaving an empty chain is
                // what keeps a long-running join's index flat instead of accumulating one dead entry
                // per key it has ever seen.
                buckets.remove(hash);
            } else {
                buckets.put(hash, next);
            }
        } else {
            store.regionOf(previous).putLong(store.offsetOf(previous) + OFFSET_NEXT, next);
        }
        store.release(entry);
        distinctRows--;
    }

    private long nextOf(long entry) {
        return store.regionOf(entry).getLong(store.offsetOf(entry) + OFFSET_NEXT);
    }

    private long weightOf(long entry) {
        return store.regionOf(entry).getLong(store.offsetOf(entry) + OFFSET_WEIGHT);
    }

    private RowView wrap(long entry) {
        return cursor.wrap(store.regionOf(entry), store.offsetOf(entry) + OFFSET_ROW);
    }

    /**
     * Whether a stored entry holds the same Z-set element as {@code row}.
     *
     * <p>Field by field, not byte by byte. The row's header carries a weight, an event time and a
     * sequence number that differ between two arrivals of the same values, so comparing bytes would
     * treat them as different elements and never cancel a retraction against its insert.
     */
    private boolean sameRow(long entry, RowView row) {
        return RowValues.sameFields(wrap(entry), row, schema);
    }

    /**
     * Copies a row into a block, verbatim.
     *
     * <p>Binary rows only. Everything on the row path is one -- the join reads what a lane's arena
     * holds -- and a generic path would mean re-encoding through the writer, which is both slower
     * and a second encoder to keep in agreement with the first.
     */
    private void copyRow(RowView row, MemoryRegion into, int offset, int length) {
        BinaryRowView binary = asBinary(row);
        into.copyFrom(offset, binary.region(), binary.offset(), length);
    }

    private static BinaryRowView asBinary(RowView row) {
        if (row instanceof BinaryRowView binary) {
            return binary;
        }
        throw new IllegalArgumentException(
                "join state stores binary rows; got " + row.getClass().getSimpleName());
    }

    @Override
    public void close() {
        buckets.clear();
        if (scratch != null) {
            scratch.close();
            scratch = null;
        }
    }

    /** Called for each matching stored row. The row is only valid during the call. */
    interface MatchVisitor {
        void matched(RowView row, long weight);
    }
}
