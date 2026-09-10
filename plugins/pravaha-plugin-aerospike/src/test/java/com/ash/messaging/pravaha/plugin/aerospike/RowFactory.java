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
package com.ash.messaging.pravaha.plugin.aerospike;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

/** Builds real binary rows, because the sink reads them the way the engine produces them. */
final class RowFactory implements AutoCloseable {

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
    private final RowLayout layout;

    RowFactory(StreamSchema schema) {
        this.layout = RowLayout.of(schema);
    }

    /** {@code order_id, status, amount} with a Z-set weight. */
    RowView row(long id, String status, long amount, long weight) {
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id)
                .setString(1, status)
                .setLong(2, amount)
                .weight(weight)
                .eventTimestampNanos(id)
                .sequence(id)
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    @Override
    public void close() {
        arena.close();
    }
}
