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
package com.ash.messaging.pravaha.runtime.plan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A filter on a narrow column reads that column and nothing beside it.
 *
 * <p>The row layout packs each fixed-width field at its own width, so a {@code TINYINT} is one byte, a
 * {@code SMALLINT} two and a {@code REAL} four, with the next field right behind. The predicate the
 * planner builds for all three used to read too wide: {@code CompareInt} took four bytes for a
 * {@code TINYINT} or {@code SMALLINT} and {@code CompareDouble} eight for a {@code REAL}, so the value
 * compared was the column's own bytes mixed with its neighbour's. {@code WHERE small = 5} then kept or
 * dropped a row depending on the column after {@code small}: a silent wrong answer, on the path every
 * query runs unless the code generator takes it, and the generator refuses exactly these shapes.
 *
 * <p>Each case puts a non-zero neighbour straight after the narrow column, because a zero neighbour is
 * exactly what hid it: most test rows had zeros there.
 */
class NarrowColumnComparisonTest {

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 4);

    @AfterEach
    void close() {
        arena.close();
    }

    private RowView row(StreamSchema schema, java.util.function.Consumer<BinaryRowWriter> fill) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        fill.accept(writer);
        writer.weight(1).eventTimestampNanos(0).sequence(0).commit();
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    @Test
    void aSmallintFilterReadsTwoBytesAndNotItsNeighbour() {
        StreamSchema schema = StreamSchema.builder("s")
                .field("small", Types.int16())
                .field("next", Types.int16())
                .build();
        RowView row = row(schema, w -> {
            w.setShort(0, (short) 5);
            w.setShort(1, (short) 7);
        });

        assertThat(new Predicate.CompareInt(0, "small", Predicate.Op.EQ, 5, TypeName.INT16).test(row))
                .as("small is 5; its neighbour is 7 and must not be part of the comparison")
                .isTrue();
        assertThat(new Predicate.CompareInt(0, "small", Predicate.Op.LT, 6, TypeName.INT16).test(row))
                .isTrue();
    }

    @Test
    void aNegativeSmallintComparesAsItsOwnValue() {
        StreamSchema schema = StreamSchema.builder("s")
                .field("small", Types.int16())
                .field("next", Types.int16())
                .build();
        RowView row = row(schema, w -> {
            w.setShort(0, (short) -3);
            w.setShort(1, (short) 0);
        });

        assertThat(new Predicate.CompareInt(0, "small", Predicate.Op.EQ, -3, TypeName.INT16).test(row))
                .as("a negative two-byte value read as four bytes loses its sign")
                .isTrue();
    }

    @Test
    void aTinyintFilterReadsOneByte() {
        StreamSchema schema = StreamSchema.builder("s")
                .field("tiny", Types.int8())
                .field("next", Types.int8())
                .field("more", Types.int16())
                .build();
        RowView row = row(schema, w -> {
            w.setByte(0, (byte) 9);
            w.setByte(1, (byte) 4);
            w.setShort(2, (short) 1000);
        });

        assertThat(new Predicate.CompareInt(0, "tiny", Predicate.Op.EQ, 9, TypeName.INT8).test(row))
                .isTrue();
    }

    @Test
    void aRealFilterReadsFourBytes() {
        StreamSchema schema = StreamSchema.builder("s")
                .field("ratio", Types.float32())
                .field("next", Types.float32())
                .build();
        RowView row = row(schema, w -> {
            w.setFloat(0, 0.5f);
            w.setFloat(1, 123.25f);
        });

        assertThat(new Predicate.CompareDouble(0, "ratio", Predicate.Op.EQ, 0.5d, TypeName.FLOAT32).test(row))
                .as("a REAL read as eight bytes is its own bits and its neighbour's, as one double")
                .isTrue();
        assertThat(new Predicate.CompareDouble(0, "ratio", Predicate.Op.GT, 0.25d, TypeName.FLOAT32).test(row))
                .isTrue();
    }
}
