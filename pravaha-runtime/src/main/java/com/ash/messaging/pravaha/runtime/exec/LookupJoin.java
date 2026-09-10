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
 * <p><strong>The lookup happens on the lane thread, and that is the operator's defining cost.</strong>
 * A millisecond round trip caps a lane at a thousand records a second, three orders of magnitude
 * below what the rest of the engine does, so the cache below is not an optimisation but the thing
 * that makes the operator usable at all. Overlapping lookups on virtual threads is the real answer
 * and is the next piece of work; until it exists this is stated rather than hidden, because a
 * feature whose throughput ceiling is undocumented gets discovered in production.
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

    private long lookups;
    private long cacheHits;
    private long unmatched;

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
        KeyValues key = keyOf(row);
        List<Object[]> matches = lookup(key);

        if (matches.isEmpty()) {
            unmatched++;
            if (plan.leftOuter()) {
                // Emitted now, with nulls, and never retracted: the lookup already answered, and no
                // later arrival can turn this miss into a hit for this record.
                emit(row, null);
            }
            return;
        }
        for (Object[] match : matches) {
            emit(row, match);
        }
    }

    private List<Object[]> lookup(KeyValues key) {
        long now = System.nanoTime();
        CacheEntry cached = cacheNanos > 0 ? cache.get(key) : null;
        if (cached != null && now - cached.storedAtNanos() < cacheNanos) {
            cacheHits++;
            return cached.rows();
        }

        List<Object[]> rows = new ArrayList<>(1);
        lookups++;
        source.lookup(key.values(), new CollectingSink(rows));
        if (cacheNanos > 0) {
            cache.put(key, new CacheEntry(rows, now));
        }
        return rows;
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
