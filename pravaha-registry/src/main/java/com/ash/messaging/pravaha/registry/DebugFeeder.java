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
package com.ash.messaging.pravaha.registry;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.serving.ViewSink;

/**
 * Hands replayed rows to an execution, one at a time (ADR-048).
 *
 * <p>Shared by the two things that replay a script: the debug session itself, and the rehearsal
 * that works out what an exported fixture should expect. They have to encode a row identically or
 * the fixture would assert an answer the session never saw, so there is one encoder rather than
 * two.
 */
final class DebugFeeder implements AutoCloseable {

    /** How long a settle waits for the lane. Generous: nothing here is on a hot path. */
    static final Duration SETTLE = Duration.ofSeconds(30);

    private final QueryExecution execution;
    private final RowArena arena;
    private final Map<String, RowLayout> layouts = new LinkedHashMap<>();

    DebugFeeder(QueryExecution execution) {
        this.execution = execution;
        this.arena = new RowArena(MemoryAccess.best(), 1 << 20, 64);
        for (String stream : execution.streams()) {
            layouts.put(stream, RowLayout.of(execution.pipeline(0).inputSchema(stream)));
        }
    }

    /** The schema each stream's rows are encoded with, which a fixture has to declare. */
    Map<String, StreamSchema> schemas() {
        Map<String, StreamSchema> schemas = new LinkedHashMap<>();
        layouts.forEach((stream, layout) -> schemas.put(stream, layout.schema()));
        return schemas;
    }

    /** Encodes one replayed row and offers it to the execution's lane. */
    void feed(ReplaySource.ReplayRow row) {
        RowLayout layout = layouts.get(row.stream());
        if (layout == null) {
            throw new PravahaException(
                    DebugErrors.BAD_STEP,
                    "the replay delivered a row from '" + row.stream() + "', which this query does not read ("
                            + execution.streams() + ")");
        }
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView reader = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(1024));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        Object[] values = row.values();
        for (int ordinal = 0; ordinal < values.length; ordinal++) {
            DebugRowValues.write(writer, layout.schema(), ordinal, values[ordinal]);
        }
        writer.weight(row.weight())
                .eventTimestampNanos(row.eventTimeNanos())
                .sequence(row.sequence())
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        execution.accept(row.stream(), reader.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    /**
     * Waits for the lane and publishes, so what is read next is a whole answer.
     *
     * <p>The same three steps a registered query's commit takes, in the same order and for the
     * same reasons: drain first, because the view is applied on the lane's thread; publish
     * unwindowed aggregates, because nothing else will; then commit, which is what moves the
     * frontier a reader sees.
     */
    static void settle(QueryExecution execution, ViewSink sink) {
        execution.awaitQuiescent(SETTLE);
        execution.publishContinuousAggregates();
        execution.awaitQuiescent(SETTLE);
        sink.commitApplied();
    }

    @Override
    public void close() {
        arena.close();
    }
}
