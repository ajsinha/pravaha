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
package com.ash.messaging.pravaha.plugin.kafka;

import java.nio.charset.StandardCharsets;
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
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

/**
 * What a source reader handed over, collected into an arena the way a lane's inbox holds it.
 *
 * <p>A row counts on {@code commit}, never on {@code beginRow}, so a reader that begins rows it does
 * not finish is not hidden. Rejected records are kept too, and accepted only when the test says a
 * dead-letter queue is there to take them.
 */
final class Collected implements SourcePluginTck.RowCollector {

    /** A record offered to the dead-letter queue. */
    record Rejection(String raw, String at, String reason) {}

    private final StreamSchema schema;
    private final RowLayout layout;
    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 22, 64);
    private final BinaryRowWriter writer;
    private final List<RowView> rows = new ArrayList<>();
    final List<Rejection> rejections = new ArrayList<>();
    private final boolean deadLetters;

    Collected(StreamSchema schema) {
        this(schema, false);
    }

    Collected(StreamSchema schema, boolean deadLetters) {
        this.schema = schema;
        this.layout = RowLayout.of(schema);
        this.writer = new BinaryRowWriter(layout);
        this.deadLetters = deadLetters;
    }

    @Override
    public RowWriter beginRow() {
        long handle = arena.allocate(layout.rowSize(512));
        if (handle == ArenaHandle.NULL) {
            throw new IllegalStateException("collector arena exhausted");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        BinaryRowView view = new BinaryRowView(layout);
        return new Committing(writer, () -> rows.add(view.wrap(arena.regionOf(handle), arena.offsetOf(handle))));
    }

    @Override
    public boolean reject(byte[] raw, String sourceOffset, String reason) {
        rejections.add(new Rejection(new String(raw, StandardCharsets.UTF_8), sourceOffset, reason));
        return deadLetters;
    }

    @Override
    public List<RowView> rows() {
        return rows;
    }

    /** Each row as its column values, then {@code @weight}: {@code [u1, 300, @1]}. */
    List<String> described() {
        List<String> described = new ArrayList<>();
        for (RowView row : rows) {
            List<String> values = new ArrayList<>();
            for (int ordinal = 0; ordinal < schema.fieldCount(); ordinal++) {
                values.add(row.isNull(ordinal) ? "null" : text(row, ordinal));
            }
            values.add("@" + row.weight());
            described.add(values.toString());
        }
        return described;
    }

    private String text(RowView row, int ordinal) {
        return switch (schema.field(ordinal).type().typeName()) {
            case BOOLEAN -> Boolean.toString(row.getBoolean(ordinal));
            case INT8 -> Byte.toString(row.getByte(ordinal));
            case INT16 -> Short.toString(row.getShort(ordinal));
            case INT32, DATE -> Integer.toString(row.getInt(ordinal));
            case INT64, TIME, TIMESTAMP_LTZ -> Long.toString(row.getLong(ordinal));
            case FLOAT32 -> Float.toString(row.getFloat(ordinal));
            case FLOAT64 -> Double.toString(row.getDouble(ordinal));
            case DECIMAL ->
                com.ash.messaging.pravaha.common.row.Decimals.toBigDecimal(
                                row.getDecimalHigh(ordinal),
                                row.getDecimalLow(ordinal),
                                ((com.ash.messaging.pravaha.api.data.DecimalType)
                                                schema.field(ordinal).type())
                                        .scale())
                        .toPlainString();
            default -> row.getString(ordinal);
        };
    }

    @Override
    public void close() {
        arena.close();
    }

    private record Committing(BinaryRowWriter delegate, Runnable onCommit) implements RowWriter {

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
