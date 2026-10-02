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
package com.ash.messaging.pravaha.serving;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NANGROUP-1: a view keyed by a DOUBLE compares keys as SQL groups them -- either zero is one key, as
 * every NaN already was -- while each row keeps its own value.
 */
class DoubleKeyViewTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("by_d")
            .field("d", Types.float64())
            .field("c", Types.int64())
            .build();

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);

    @AfterEach
    void tearDown() {
        arena.close();
    }

    private RowView row(double d, long c) {
        RowLayout layout = RowLayout.of(SCHEMA);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(16));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setDouble(0, d).setLong(1, c);
        writer.weight(1).eventTimestampNanos(0).sequence(0).commit();
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    @Test
    void eitherZeroFindsTheOneKey() {
        ServedView view = new ServedView("by_d", SCHEMA, List.of(0), 1000);
        view.apply(row(-0.0, 7), 1);
        view.commit(1);
        assertThat(view.get(0.0).found()).isTrue();
        assertThat(view.get(-0.0).found()).isTrue();
        // The row keeps its own value: grouping is canonical, a projected -0.0 is not rewritten.
        assertThat(Double.doubleToRawLongBits((Double) view.get(0.0).values().orElseThrow()[0]))
                .isEqualTo(Double.doubleToRawLongBits(-0.0));
        assertThat(view.get(Double.longBitsToDouble(0x7ff8000000000001L)).found())
                .isFalse();
        view.apply(row(Double.NaN, 1), 2);
        view.commit(2);
        assertThat(view.get(Double.longBitsToDouble(0x7ff8000000000001L)).found())
                .isTrue();
    }
}
