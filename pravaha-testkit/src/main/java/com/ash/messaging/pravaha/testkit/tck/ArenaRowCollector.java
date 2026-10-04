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
package com.ash.messaging.pravaha.testkit.tck;

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

/**
 * Collects a reader's rows into an arena, the way a lane does: the TCK's default collector, and one a
 * plugin's own tests can use (TCKCOLLECT-1).
 *
 * <p>Every source plugin's TCK test used to carry its own copy of these 175 lines. Into an arena rather
 * than into objects on purpose: a collector that materialised each row as a map would not exercise the
 * writer the engine actually uses, and the row layout is where several of this project's real defects
 * have lived. Rows are recorded on {@code commit}, never on {@code beginRow}: a collector that counted
 * begun rows would hide a reader that starts rows it does not finish, and the tests would then encode
 * that behaviour as correct. An aborted row is not recorded.
 *
 * <p>The rows are views into the arena, valid until {@link #close()}.
 */
public final class ArenaRowCollector implements SourcePluginTck.RowCollector {

    /** Variable-length bytes reserved per row unless a test says otherwise. */
    public static final int DEFAULT_VARIABLE_BYTES = 512;

    private final RowLayout layout;
    private final int variableBytes;
    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 64);
    private final BinaryRowWriter writer;
    private final List<RowView> rows = new ArrayList<>();

    public ArenaRowCollector(StreamSchema schema) {
        this(schema, DEFAULT_VARIABLE_BYTES);
    }

    /** @param variableBytes room for strings and byte arrays in one row; a wider row is refused */
    public ArenaRowCollector(StreamSchema schema, int variableBytes) {
        this.layout = RowLayout.of(schema);
        this.variableBytes = variableBytes;
        this.writer = new BinaryRowWriter(layout);
    }

    @Override
    public RowWriter beginRow() {
        long handle = arena.allocate(layout.rowSize(variableBytes));
        if (handle == ArenaHandle.NULL) {
            throw new IllegalStateException("the collector's arena is exhausted; a TCK fixture should be small");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        return new CommitRecording(
                writer, () -> rows.add(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))));
    }

    /** The committed rows, in the order they were committed. */
    @Override
    public List<RowView> rows() {
        return rows;
    }

    @Override
    public void close() {
        arena.close();
    }

    /** Delegates everything, and records the row when it is committed. */
    private record CommitRecording(BinaryRowWriter delegate, Runnable onCommit) implements RowWriter {

        @Override
        public StreamSchema schema() {
            return delegate.schema();
        }

        @Override
        public RowWriter setNull(int ordinal) {
            delegate.setNull(ordinal);
            return this;
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            delegate.setBoolean(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            delegate.setByte(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            delegate.setShort(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            delegate.setInt(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            delegate.setLong(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            delegate.setFloat(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            delegate.setDouble(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            delegate.setDecimal(ordinal, high, low);
            return this;
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            delegate.setBytes(ordinal, value);
            return this;
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            delegate.setString(ordinal, value);
            return this;
        }

        @Override
        public RowWriter weight(long weight) {
            delegate.weight(weight);
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long nanos) {
            delegate.eventTimestampNanos(nanos);
            return this;
        }

        @Override
        public RowWriter sequence(long sequence) {
            delegate.sequence(sequence);
            return this;
        }

        @Override
        public int commit() {
            int size = delegate.commit();
            onCommit.run();
            return size;
        }

        @Override
        public void abort() {
            delegate.abort();
        }
    }
}
