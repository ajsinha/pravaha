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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.WindowAssignOperator;
import com.ash.messaging.pravaha.runtime.window.SlicedWindows;

/**
 * Adds {@code window_start} and {@code window_end} to each row.
 *
 * <p>Stateless, and that is the point: a row is placed by arithmetic on its event time and passed
 * straight on, so all the state windowing needs lives in the aggregate above. A record belongs to
 * one slice however many windows overlap it (design section 15.3), and the slice is what the
 * boundaries here describe -- the aggregate combines slices into windows when it fires.
 */
final class WindowAssign implements RowProcessor {

    private final WindowAssignOperator operator;
    private final SlicedWindows windows;
    private final RowArena arena;
    private final RowProcessor downstream;
    private final RowLayout layout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;
    private final int inputColumns;

    WindowAssign(WindowAssignOperator operator, RowArena arena, RowProcessor downstream) {
        this.operator = operator;
        this.windows = new SlicedWindows(operator.spec());
        this.arena = arena;
        this.downstream = downstream;
        this.layout = RowLayout.of(operator.outputSchema());
        this.writer = new BinaryRowWriter(layout);
        this.view = new BinaryRowView(layout);
        // The two boundary columns are appended, so everything before them is passed through.
        this.inputColumns = operator.outputSchema().fieldCount() - 2;
    }

    @Override
    public void process(RowView row) {
        long eventTime = row.getLong(operator.eventTimeOrdinal());
        long sliceStart = windows.sliceStartFor(eventTime);

        long handle = arena.allocate(layout.rowSize(1024));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(
                    RuntimeErrors.ARENA_EXHAUSTED,
                    "the window assigner's arena is full; raise pravaha.lane.arena.slab-bytes");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        for (int i = 0; i < inputColumns; i++) {
            RowStages.copyField(row, i, writer, i, operator.outputSchema());
        }
        writer.setLong(inputColumns, sliceStart);
        writer.setLong(inputColumns + 1, sliceStart + windows.sliceSizeNanos());
        writer.weight(row.weight())
                .eventTimestampNanos(row.eventTimestampNanos())
                .sequence(row.sequence())
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }
}
