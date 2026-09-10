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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.LookupJoinOperator;

/**
 * Enriches each record from a dimension table.
 *
 * <p><strong>A lookup is almost entirely waiting, so the waits overlap.</strong> Done one at a time
 * on the lane thread, a millisecond round trip caps a lane at a thousand records a second -- three
 * orders of magnitude below the rest of the engine. Each miss is therefore handed to a virtual
 * thread and the lane carries on; the record waits, its thread waits, and the lane does not. This is
 * what virtual threads are for and the one place in the engine where blocking is the right shape:
 * platform threads would need a pool sized for the store's latency rather than for the machine.
 *
 * <p><strong>Output stays in arrival order.</strong> Lookups complete out of order -- a cached key
 * returns instantly, a cold one takes a round trip -- and emitting as they finish would reorder the
 * stream. Sequence numbers would go backwards, the deduplicating sink would drop live rows, and a
 * downstream window would see time move backwards. So results queue and the queue drains from the
 * front: a record's output waits for every earlier record's.
 *
 * <p>In-flight lookups are bounded by what the source says it will take at once. Past that the lane
 * waits for the oldest to finish, which is backpressure in the only form available here -- the
 * alternative is an unbounded queue of parked records, which is the same memory growth by a longer
 * route.
 *
 * <p>The cache's lifetime is the source's decision, not the engine's. Only the source knows how
 * stale its rows may safely be: a currency table refreshed hourly tolerates minutes, an account
 * balance tolerates nothing. A source that says {@code ZERO} is asked every time.
 *
 * <p>Cached entries hold the looked-up rows as decoded values rather than as the store's bytes. That
 * costs an object per column and is the right trade here: a cache entry outlives many arenas, and
 * keeping flyweights over memory that is about to be rewound is the bug this codebase has already
 * found twice.
 */
final class LookupJoin implements RowProcessor {

    private final LookupJoinOperator plan;
    private final LookupSourcePlugin source;
    private final RowArena arena;
    private final RowProcessor downstream;
    private final int[] keyOrdinals;
    private final StreamSchema inputSchema;
    private final StreamSchema lookupSchema;
    private final RowLayout outputLayout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;
    private final Map<KeyValues, CacheEntry> cache;
    private final long cacheNanos;
    private final int maxCacheEntries;

    private final java.util.ArrayDeque<Pending> pending = new java.util.ArrayDeque<>();

    /**
     * Lookups already running, by key.
     *
     * <p>Touched only from the lane thread, so a plain map: the futures complete elsewhere, but
     * nothing else ever reads or writes this.
     */
    private final Map<KeyValues, java.util.concurrent.CompletableFuture<List<Object[]>>> inFlight =
            new java.util.HashMap<>();

    /**
     * Where parked records' bytes live while their lookups run.
     *
     * <p>Not the lane's arena, which rewinds at the end of every batch. Not the heap either: a
     * parked record is a row like any other, and decoding it to objects and back would cost more
     * than the copy it replaces.
     */
    private final com.ash.messaging.pravaha.state.RowStore pendingRows;

    private final BinaryRowView pendingRow;
    private final java.util.concurrent.ExecutorService lookupThreads;
    private final int maxInFlight;

    private long lookups;
    private long cacheHits;
    private long unmatched;
    private long maxObservedInFlight;
    private long coalesced;

    LookupJoin(
            LookupJoinOperator plan,
            LookupSourcePlugin source,
            RowArena arena,
            RowProcessor downstream,
            int maxCacheEntries) {
        this.plan = plan;
        this.source = source;
        this.arena = arena;
        this.downstream = downstream;
        this.inputSchema = plan.input().outputSchema();
        this.lookupSchema = plan.lookupSchema();
        this.keyOrdinals =
                plan.streamKeys().stream().mapToInt(Integer::intValue).toArray();
        this.outputLayout = RowLayout.of(plan.outputSchema());
        this.writer = new BinaryRowWriter(outputLayout);
        this.view = new BinaryRowView(outputLayout);
        this.cacheNanos = source.cacheFor().toNanos();
        this.maxCacheEntries = maxCacheEntries;
        this.maxInFlight = Math.max(1, source.maxConcurrency());
        // One virtual thread per lookup, created on demand. They cost hundreds of bytes and park
        // without holding a carrier, so there is no pool to size and no queue to tune -- which is
        // the entire reason this operator can overlap waits at all.
        this.lookupThreads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        this.pendingRows = new com.ash.messaging.pravaha.state.RowStore(
                com.ash.messaging.pravaha.common.memory.MemoryAccess.best(), 1 << 16, 64);
        this.pendingRow = new BinaryRowView(RowLayout.of(plan.input().outputSchema()));
        // Access-ordered and bounded: a lookup join's key distribution is usually heavily skewed,
        // and an unbounded cache over an unbounded key space is the memory leak this operator was
        // supposed to avoid.
        this.cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<KeyValues, CacheEntry> eldest) {
                return size() > LookupJoin.this.maxCacheEntries;
            }
        };
    }

    @Override
    public void process(RowView row) {
        // Anything already finished goes out first, so a cache hit behind a slow lookup does not
        // overtake it and the queue does not grow while there is work it could shed.
        drainCompleted();

        KeyValues key = keyOf(row);
        List<Object[]> cached = fromCache(key);
        if (cached != null) {
            cacheHits++;
            if (pending.isEmpty()) {
                // Nothing is waiting, so nothing can be overtaken: emit straight through and keep
                // the common case free of queueing and of copying.
                deliver(row, cached);
            } else {
                pending.add(new Pending(copyOf(row), null, null, cached));
            }
            return;
        }

        if (pending.size() >= maxInFlight) {
            // Backpressure. The alternative is an unbounded queue of parked records, which is the
            // same memory growth by a longer route.
            awaitHead();
            drainCompleted();
        }

        // A key already being looked up is not looked up again -- but only where the source allows
        // caching at all. Overlapping waits would otherwise multiply the requests for a hot key by
        // however many records are in flight, which is exactly the traffic a cache removes, arriving
        // in a burst because the cache is not populated until the first answer returns.
        //
        // A source that permits no caching is saying its rows may change between one record and the
        // next, and sharing one answer between two records is a cache with a lifetime of "however
        // long that lookup took". Honouring cacheFor() == 0 literally is the only reading that does
        // not quietly reintroduce what the source refused.
        java.util.concurrent.CompletableFuture<List<Object[]>> future = cacheNanos > 0 ? inFlight.get(key) : null;
        if (future == null) {
            lookups++;
            Object[] keyValues = key.values();
            future = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> {
                        List<Object[]> rows = new ArrayList<>(1);
                        source.lookup(keyValues, new CollectingSink(rows));
                        return rows;
                    },
                    lookupThreads);
            if (cacheNanos > 0) {
                inFlight.put(key, future);
            }
        } else {
            coalesced++;
        }
        pending.add(new Pending(copyOf(row), key, future, null));
        maxObservedInFlight = Math.max(maxObservedInFlight, pending.size());
    }

    /**
     * Emits every result at the front of the queue that is ready.
     *
     * <p>From the front only. A result behind an unfinished one waits, however long it has been
     * ready, because emitting it would reorder the stream -- and a stream whose sequence numbers go
     * backwards breaks the deduplicating sink and every window downstream.
     */
    private void drainCompleted() {
        while (!pending.isEmpty() && pending.peek().isDone()) {
            release(pending.poll());
        }
    }

    /** Emits one queued record's results and gives its copied bytes back. */
    private void release(Pending head) {
        List<Object[]> rows = head.result();
        if (head.key() != null) {
            inFlight.remove(head.key());
            if (cacheNanos > 0) {
                cache(head.key(), rows);
            }
        }
        try {
            deliver(pendingRow.wrap(pendingRows.regionOf(head.handle()), pendingRows.offsetOf(head.handle())), rows);
        } finally {
            // Released whatever happened: a record whose block leaks on an exception takes the
            // query's memory with it, and the exception is already being reported.
            pendingRows.release(head.handle());
        }
    }

    /** Waits for the oldest outstanding lookup, which is the only one whose result can be emitted. */
    private void awaitHead() {
        Pending head = pending.peek();
        if (head != null) {
            head.await();
        }
    }

    /**
     * Copies a record so it can outlive the batch it arrived in.
     *
     * <p>A parked record is emitted after its lookup returns, which may be several batches later --
     * and by then the lane has rewound the arena its bytes were in. Keeping the flyweight would read
     * whatever occupies those bytes now, which is the class of bug that produces one query's rows in
     * another's output.
     */
    private long copyOf(RowView row) {
        if (!(row instanceof BinaryRowView binary)) {
            throw new IllegalArgumentException(
                    "a lookup join parks binary rows; got " + row.getClass().getSimpleName());
        }
        long handle = pendingRows.allocate(binary.length());
        pendingRows
                .regionOf(handle)
                .copyFrom(pendingRows.offsetOf(handle), binary.region(), binary.offset(), binary.length());
        return handle;
    }

    /** Flushes everything still outstanding. Called at end of input and when the lane goes idle. */
    void drain() {
        while (!pending.isEmpty()) {
            awaitHead();
            drainCompleted();
        }
    }

    private void cache(KeyValues key, List<Object[]> rows) {
        cache.put(key, new CacheEntry(rows, System.nanoTime()));
    }

    private List<Object[]> fromCache(KeyValues key) {
        if (cacheNanos <= 0) {
            return null;
        }
        CacheEntry cached = cache.get(key);
        return cached != null && System.nanoTime() - cached.storedAtNanos() < cacheNanos ? cached.rows() : null;
    }

    private void deliver(RowView row, List<Object[]> matches) {
        if (matches.isEmpty()) {
            unmatched++;
            if (plan.leftOuter()) {
                // Emitted with nulls and never retracted: the lookup already answered, and no later
                // arrival can turn this miss into a hit for this record.
                emit(row, null);
            }
            return;
        }
        for (Object[] match : matches) {
            emit(row, match);
        }
    }

    /** Writes the record's columns, then the dimension's -- or nulls where there was no match. */
    private void emit(RowView row, Object[] match) {
        long handle = arena.allocate(outputLayout.rowSize(1024));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(
                    RuntimeErrors.ARENA_EXHAUSTED, "the lookup join's arena is full; raise arena.slab.size");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        int width = plan.streamWidth();
        for (int i = 0; i < width; i++) {
            InterpretedPipeline.copyField(row, i, writer, i, plan.outputSchema());
        }
        for (int i = 0; i < lookupSchema.fields().size(); i++) {
            Object value = match == null ? null : match[i];
            if (value == null) {
                writer.setNull(width + i);
            } else {
                write(writer, width + i, value);
            }
        }
        writer.weight(row.weight())
                .eventTimestampNanos(row.eventTimestampNanos())
                .sequence(row.sequence())
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    private static void write(RowWriter writer, int ordinal, Object value) {
        switch (value) {
            case Boolean v -> writer.setBoolean(ordinal, v);
            case Byte v -> writer.setByte(ordinal, v);
            case Short v -> writer.setShort(ordinal, v);
            case Integer v -> writer.setInt(ordinal, v);
            case Long v -> writer.setLong(ordinal, v);
            case Float v -> writer.setFloat(ordinal, v);
            case Double v -> writer.setDouble(ordinal, v);
            case String v -> writer.setString(ordinal, v);
            case byte[] v -> writer.setBytes(ordinal, v);
            default ->
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_JOIN,
                        "a lookup returned a " + value.getClass().getSimpleName()
                                + ", which Pravaha cannot put in a row");
        }
    }

    private KeyValues keyOf(RowView row) {
        Object[] values = new Object[keyOrdinals.length];
        for (int i = 0; i < keyOrdinals.length; i++) {
            values[i] = valueOf(row, keyOrdinals[i]);
        }
        return new KeyValues(values);
    }

    private Object valueOf(RowView row, int ordinal) {
        if (row.isNull(ordinal)) {
            return null;
        }
        return switch (inputSchema.field(ordinal).type().typeName()) {
            case BOOLEAN -> row.getBoolean(ordinal);
            case INT8 -> row.getByte(ordinal);
            case INT16 -> row.getShort(ordinal);
            case INT32, DATE -> row.getInt(ordinal);
            case INT64, TIME, TIMESTAMP_LTZ -> row.getLong(ordinal);
            case FLOAT32 -> row.getFloat(ordinal);
            case FLOAT64 -> row.getDouble(ordinal);
            case STRING -> row.getString(ordinal);
            default ->
                throw new PravahaException(
                        RuntimeErrors.UNSUPPORTED_JOIN,
                        "cannot use a " + inputSchema.field(ordinal).type().typeName() + " column as a lookup key");
        };
    }

    long lookupCount() {
        return lookups;
    }

    long cacheHitCount() {
        return cacheHits;
    }

    long unmatchedCount() {
        return unmatched;
    }

    /** Records that joined a lookup already running for their key rather than starting another. */
    long coalescedCount() {
        return coalesced;
    }

    /** The most lookups that were ever outstanding at once. One means nothing ever overlapped. */
    long peakInFlight() {
        return maxObservedInFlight;
    }

    /** Finishes outstanding lookups and releases the threads. */
    void close() {
        drain();
        lookupThreads.shutdown();
        pendingRows.close();
    }

    /** A key, by value, so it can be a map key. */
    private record KeyValues(Object[] values) {
        @Override
        public boolean equals(Object other) {
            return other instanceof KeyValues that && Arrays.equals(values, that.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }

        @Override
        public String toString() {
            return Arrays.toString(values);
        }
    }

    private record CacheEntry(List<Object[]> rows, long storedAtNanos) {}

    /**
     * A record waiting for its lookup, or holding a result that must wait its turn.
     *
     * @param handle the record's copied bytes, owned by the operator's store
     * @param key null when the result came from the cache and so needs no storing back
     * @param future null when the result was already known
     * @param ready the result, when it was already known
     */
    private record Pending(
            long handle,
            KeyValues key,
            java.util.concurrent.CompletableFuture<List<Object[]>> future,
            List<Object[]> ready) {

        boolean isDone() {
            return future == null || future.isDone();
        }

        void await() {
            if (future != null) {
                result();
            }
        }

        /** The rows, waiting for the lookup if it has not finished. */
        List<Object[]> result() {
            if (future == null) {
                return ready;
            }
            try {
                return future.join();
            } catch (java.util.concurrent.CompletionException e) {
                // Unwrapped, because the useful message is the store's -- "connection refused",
                // "table not found" -- and a CompletionException wrapper buries it one frame deep
                // in every log line that follows.
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw e;
            }
        }
    }

    /**
     * Decodes what the source writes into plain values.
     *
     * <p>Values rather than a row, because a cached entry outlives the arena a row would live in.
     * Holding a flyweight over memory that is about to be rewound is a bug this codebase has found
     * twice already, and a cache is the easiest place in the engine to make it a third time.
     */
    private final class CollectingSink implements PartitionReader.RecordSink {
        private final List<Object[]> into;

        CollectingSink(List<Object[]> into) {
            this.into = into;
        }

        @Override
        public RowWriter beginRow() {
            return new ValueRowWriter(lookupSchema, into::add);
        }
    }
}
