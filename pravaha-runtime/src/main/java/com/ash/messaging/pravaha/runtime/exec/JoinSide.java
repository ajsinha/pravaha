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

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.state.VariableKeyStateMap;
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

    /**
     * Whether this row has ever taken part in a match.
     *
     * <p>Bytes 20 to 23 were already padding between the four-byte length and the eight-byte-aligned
     * row, so this costs nothing per row. An outer join needs it: a row that is evicted having never
     * matched is precisely the row that has to be emitted null-padded, and by eviction time there is
     * nothing else left to ask.
     */
    private static final int OFFSET_MATCHED = 20;

    private static final int OFFSET_ROW = 24;

    /** Size classes for the bucket index's own key/value store: eight-byte values need very little. */
    private static final int BUCKET_STORE_SLAB_BYTES = 1 << 20;

    /** 4096 slabs of a MiB each is a four-GiB ceiling on the bucket index -- tens of millions of
     * distinct keys before it is reached, which is the point: a ceiling this join never used to have
     * must not become the first thing a legitimate large join hits. */
    private static final int BUCKET_STORE_MAX_SLABS = 4096;

    private final RowStore store;
    private final StreamSchema schema;
    private final RowLayout layout;
    private final int[] keyOrdinals;
    private final BinaryRowView cursor;

    /**
     * Bucket heads, keyed by the key columns' hash -- off-heap (ADR-039 item 4, W8-12).
     *
     * <p>This was the one place in this class still on the heap, and its own comment used to record
     * why: "moving it off-heap is a later change with a measurement behind it, not a guess." {@link
     * VariableKeyStateMap} is that change. The key stored here is the eight-byte hash {@link
     * JoinKeys#hash} already computes -- this class does not need an arbitrary-width key, because it
     * reduced to one a long time ago -- so this usage does not exercise the map's variable-width
     * case; {@code VariableKeyStateMapTest} does that, over string keys, which is the case {@link
     * com.ash.messaging.pravaha.runtime.window.SlicedAggregateState}'s {@code GROUP BY} state would
     * need and this one does not.
     */
    private final VariableKeyStateMap buckets;

    /** Encodes a hash as the eight bytes {@link #buckets} is keyed by. Reused across every call. */
    private final MemoryRegion hashScratch;

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
        this(store, access, schema, keyOrdinals, BUCKET_STORE_MAX_SLABS, null, 0);
    }

    /**
     * With the key index given the overflow tier too (ADR-044).
     *
     * <p>The rows spilled and the index over them did not: ADR-044's measurement found a spilled join
     * still carrying an index of about 100 bytes a key in RAM, bounded by nothing but a four-gigabyte
     * backstop. With a tier, the index's own store gets the same RAM
     * ceiling as the rows and spills past it, and so does its slot table -- sixteen bytes a slot, the
     * part a probe walks: up to that ceiling in RAM, the rest in mapped segments.
     *
     * @param indexRamSlabs the RAM ceiling of the index's store, in {@link #BUCKET_STORE_SLAB_BYTES}
     *     slabs
     * @param overflowAccess the tier, or {@code null} for none -- the index then keeps its four-gigabyte
     *     RAM backstop, exactly as before
     */
    JoinSide(
            RowStore store,
            com.ash.messaging.pravaha.common.memory.MemoryAccess access,
            StreamSchema schema,
            int[] keyOrdinals,
            int indexRamSlabs,
            com.ash.messaging.pravaha.common.memory.MemoryAccess overflowAccess,
            int maxOverflowSlabs) {
        this.store = store;
        this.access = access;
        this.schema = schema;
        this.layout = RowLayout.of(schema);
        this.keyOrdinals = keyOrdinals.clone();
        this.cursor = new BinaryRowView(layout);
        this.restoreView = new BinaryRowView(layout);
        this.buckets = overflowAccess == null
                ? new VariableKeyStateMap(access, 64, BUCKET_STORE_SLAB_BYTES, BUCKET_STORE_MAX_SLABS)
                : new VariableKeyStateMap(
                        access, 64, BUCKET_STORE_SLAB_BYTES, indexRamSlabs, overflowAccess, maxOverflowSlabs);
        this.hashScratch = access.allocate(Long.BYTES);
    }

    /** Compacts the key index's own overflow slabs (ADR-044); the rows are the join's to compact. */
    int compactIndexIfFragmented(double threshold) {
        return buckets.compactIfFragmented(threshold);
    }

    /** The overflow tier's numbers for this side's key index. */
    com.ash.messaging.pravaha.state.SpillStatistics indexSpillStatistics() {
        return buckets.spillStatistics();
    }

    boolean indexHasSpilled() {
        return buckets.hasSpilled();
    }

    /** Bytes of this side's key-index slot table held in the overflow tier. */
    long indexTableBytesMapped() {
        return buckets.indexBytesMapped();
    }

    /** The chain head for a hash, or {@link ArenaHandle#NULL} if no bucket exists for it yet. */
    private long headFor(long hash) {
        hashScratch.putLong(0, hash);
        long bucketHandle = buckets.find(hashScratch, 0, Long.BYTES);
        return bucketHandle == ArenaHandle.NULL
                ? ArenaHandle.NULL
                : buckets.valueRegionOf(bucketHandle).getLong(buckets.valueOffsetOf(bucketHandle));
    }

    /** Sets a hash's chain head, creating the bucket if this is its first entry. */
    private void setHead(long hash, long head) {
        hashScratch.putLong(0, hash);
        long bucketHandle = buckets.getOrCreate(hashScratch, 0, Long.BYTES, Long.BYTES);
        buckets.valueRegionOf(bucketHandle).putLong(buckets.valueOffsetOf(bucketHandle), head);
    }

    /**
     * Adds {@code weight} of a row to this side.
     *
     * <p>Returns without storing anything when the weight cancels to zero, and releases the block
     * when an existing entry cancels. Rows whose key contains a null are not stored at all: they can
     * never match, so holding them is a leak with no possible benefit.
     */
    long add(RowView row, long weight) {
        if (weight == 0 || !JoinKeys.isMatchable(row, keyOrdinals)) {
            return ArenaHandle.NULL;
        }
        long hash = JoinKeys.hash(row, keyOrdinals, schema);
        long head = headFor(hash);

        long previous = ArenaHandle.NULL;
        for (long entry = head; entry != ArenaHandle.NULL; entry = nextOf(entry)) {
            if (sameRow(entry, row)) {
                long updated = weightOf(entry) + weight;
                if (updated == 0) {
                    unlink(hash, previous, entry);
                    rows += weight;
                    return ArenaHandle.NULL;
                }
                store.regionOf(entry).putLong(store.offsetOf(entry) + OFFSET_WEIGHT, updated);
                rows += weight;
                return entry;
            }
            previous = entry;
        }

        long entry = insert(row, weight, head);
        setHead(hash, entry);
        rows += weight;
        distinctRows++;
        return entry;
    }

    /** Marks a row this side holds as having matched. Safe to call with {@link ArenaHandle#NULL}. */
    void markMatched(RowView row) {
        if (!JoinKeys.isMatchable(row, keyOrdinals)) {
            return;
        }
        long hash = JoinKeys.hash(row, keyOrdinals, schema);
        for (long entry = headFor(hash); entry != ArenaHandle.NULL; entry = nextOf(entry)) {
            if (sameRow(entry, row)) {
                markMatched(entry);
                return;
            }
        }
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
        return evictOlderThan(horizon, null);
    }

    /**
     * Evicts, telling {@code unmatched} about every row that is leaving having never matched.
     *
     * <p>That callback is how an outer join emits its null-padded rows. Eviction is the right moment
     * and the only one: before it, a match could still arrive; after it, the row is gone. The
     * callback sees the row while it is still readable, because a moment later the block is
     * released and reused.
     */
    long evictOlderThan(long horizon, java.util.function.Consumer<RowView> unmatched) {
        if (horizon == Long.MIN_VALUE || buckets.size() == 0) {
            return 0;
        }
        long removed = 0;
        // Snapshot the bucket handles before mutating: forEach walks the slot table by index, and
        // removing the *current* bucket mid-walk is safe (its slot is simply tombstoned, and we
        // already hold the handle we need), but taking the list up front makes that safety explicit
        // rather than relying on it.
        List<Long> bucketHandles = new ArrayList<>(buckets.size());
        buckets.forEach(bucketHandles::add);
        for (long bucketHandle : bucketHandles) {
            long hash = buckets.keyRegionOf(bucketHandle).getLong(buckets.keyOffsetOf(bucketHandle));
            long previous = ArenaHandle.NULL;
            long head = buckets.valueRegionOf(bucketHandle).getLong(buckets.valueOffsetOf(bucketHandle));
            long entry = head;
            while (entry != ArenaHandle.NULL) {
                long next = nextOf(entry);
                if (eventTimeOf(entry) < horizon) {
                    long weight = weightOf(entry);
                    if (unmatched != null && weight > 0 && !matchedOf(entry)) {
                        unmatched.accept(cursor.wrap(store.regionOf(entry), store.offsetOf(entry) + OFFSET_ROW));
                    }
                    if (previous == ArenaHandle.NULL) {
                        head = next;
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
            if (head == ArenaHandle.NULL) {
                hashScratch.putLong(0, hash);
                buckets.remove(hashScratch, 0, Long.BYTES);
            } else {
                buckets.valueRegionOf(bucketHandle).putLong(buckets.valueOffsetOf(bucketHandle), head);
            }
        }
        return removed;
    }

    /**
     * Presents every row handle this side holds to a compaction of the shared row store (ADR-044),
     * writing back the ones that moved.
     *
     * <p>A row's handle lives in exactly one place: the bucket's head if it is first in its chain,
     * otherwise the {@code next} link of the entry before it. So each chain is walked from its head,
     * relocating as it goes, and the link that pointed at a moved row is rewritten -- in the entry
     * before it, which has itself already been relocated, so the write lands in the live copy. Nothing
     * else in this class keeps a handle between calls.
     */
    void relocateRows(RowStore.Relocation relocation) {
        buckets.forEach(bucketHandle -> {
            MemoryRegion bucket = buckets.valueRegionOf(bucketHandle);
            int bucketOffset = buckets.valueOffsetOf(bucketHandle);
            long head = bucket.getLong(bucketOffset);
            long previous = relocation.relocate(head);
            if (previous != head) {
                bucket.putLong(bucketOffset, previous);
            }
            long next = nextOf(previous);
            while (next != ArenaHandle.NULL) {
                long moved = relocation.relocate(next);
                if (moved != next) {
                    store.regionOf(previous).putLong(store.offsetOf(previous) + OFFSET_NEXT, moved);
                }
                previous = moved;
                next = nextOf(moved);
            }
        });
    }

    /** Records that a stored row has matched, so eviction knows not to emit it null-padded. */
    private void markMatched(long entry) {
        store.regionOf(entry).putInt(store.offsetOf(entry) + OFFSET_MATCHED, 1);
    }

    private boolean matchedOf(long entry) {
        return store.regionOf(entry).getInt(store.offsetOf(entry) + OFFSET_MATCHED) != 0;
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
        for (long entry = headFor(hash); entry != ArenaHandle.NULL; entry = nextOf(entry)) {
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

    /**
     * Every row this side holds, keyed by its join key, as text (ADR-047).
     *
     * <p>Read-only. It walks the buckets exactly as {@link #writeTo} does and rewrites nothing:
     * inspecting a join's index must not evict from it, and eviction is what every other walk here
     * exists to do.
     *
     * <p>The rows are rendered while the cursor still points at them, because the flyweight moves
     * on to the next entry immediately afterwards -- the same reason {@code evictOlderThan} hands
     * an outer join's unmatched row to its callback rather than collecting the rows and returning
     * them.
     */
    void describeHeld(HeldVisitor visitor) {
        List<Long> bucketHandles = new ArrayList<>(buckets.size());
        buckets.forEach(bucketHandles::add);
        for (long bucketHandle : bucketHandles) {
            long head = buckets.valueRegionOf(bucketHandle).getLong(buckets.valueOffsetOf(bucketHandle));
            for (long entry = head; entry != ArenaHandle.NULL; entry = nextOf(entry)) {
                visitor.held(wrap(entry), weightOf(entry), matchedOf(entry));
            }
        }
    }

    /** What {@link #describeHeld} reports for one stored row. */
    interface HeldVisitor {
        void held(RowView row, long weight, boolean matched);
    }

    /** The schema the rows on this side are encoded with, for rendering them. */
    StreamSchema schema() {
        return schema;
    }

    /** The ordinals this side is keyed on, for rendering a stored row's key. */
    int[] keyOrdinals() {
        return keyOrdinals.clone();
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

    /** RAM this side's key index holds: its slot table and whatever of its store has not spilled. */
    long indexRamBytes() {
        return buckets.bytesAllocated() - buckets.spillStatistics().overflowBytesReserved();
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
        List<Long> bucketHandles = new ArrayList<>(buckets.size());
        buckets.forEach(bucketHandles::add);
        for (long bucketHandle : bucketHandles) {
            long head = buckets.valueRegionOf(bucketHandle).getLong(buckets.valueOffsetOf(bucketHandle));
            for (long entry = head; entry != ArenaHandle.NULL; entry = nextOf(entry)) {
                int length = store.regionOf(entry).getInt(store.offsetOf(entry) + OFFSET_ROW_LENGTH);
                if (scratch.length < length) {
                    scratch = new byte[length];
                }
                store.regionOf(entry).getBytes(store.offsetOf(entry) + OFFSET_ROW, scratch, 0, length);
                out.writeLong(weightOf(entry));
                out.writeBoolean(matchedOf(entry));
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
            boolean matched = in.readBoolean();
            int length = in.readInt();
            if (bytes.length < length) {
                bytes = new byte[length];
            }
            in.readFully(bytes, 0, length);
            ensureScratch(length);
            scratch.putBytes(0, bytes, 0, length);
            long entry = add(restoreView.wrap(scratch, 0), weight);
            if (matched && entry != ArenaHandle.NULL) {
                // Carried through the checkpoint, or a left row that had already matched and been
                // emitted would be emitted a second time, null-padded, when it aged out after the
                // restore.
                markMatched(entry);
            }
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
        // Explicit rather than relying on fresh memory being zero: a released entry is reused, and
        // an inherited flag would suppress an outer join's null-padded row for a row that never
        // matched.
        region.putInt(base + OFFSET_MATCHED, 0);
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
                hashScratch.putLong(0, hash);
                buckets.remove(hashScratch, 0, Long.BYTES);
            } else {
                setHead(hash, next);
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
        buckets.close();
        hashScratch.close();
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
