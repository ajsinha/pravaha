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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.runtime.window.WindowSpec;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EMITROOM-1: an aggregate emits more groups than its arena holds at once. Each emitted row is given
 * back once downstream has copied it, so a window of a million groups no longer stopped the query
 * with {@code PRV-3001 no room to emit} at ~836 k, below the view's own ceiling.
 */
class EmissionRoomTest {

    /** Far more groups than the 128 KiB arena below could hold as rows at once. */
    private static final int GROUPS = 20_000;

    private static final StreamSchema KEYED_IN = StreamSchema.builder("w")
            .field("k", Types.string())
            .field("v", Types.int64())
            .build();

    private static final StreamSchema WINDOWED_IN = StreamSchema.builder("w")
            .field("k", Types.string())
            .field("window_start", Types.int64())
            .field("window_end", Types.int64())
            .build();

    private static final List<AggregateOperator.AggregateCall> COUNT =
            List.of(new AggregateOperator.AggregateCall(AggregateOperator.AggregateCall.Kind.COUNT, -1, "c"));

    @Test
    void aWindowOfMoreGroupsThanTheArenaHoldsIsEmittedWhole() {
        StreamSchema out = StreamSchema.builder("out")
                .field("window_start", Types.int64())
                .field("window_end", Types.int64())
                .field("k", Types.string())
                .field("c", Types.int64())
                .build();
        long size = 10_000_000_000L;
        Set<String> seen = new HashSet<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 2);
                WindowedAggregate aggregate = new WindowedAggregate(
                        new WindowedAggregateOperator(
                                ScanOperator.of("w", WINDOWED_IN),
                                out,
                                WindowSpec.tumbling(size),
                                List.of(1, 2, 0),
                                COUNT,
                                1,
                                2,
                                GROUPS * 2,
                                0),
                        arena,
                        row -> seen.add(row.getString(2) + "=" + row.getLong(3)))) {
            feed(
                    arena,
                    WINDOWED_IN,
                    (writer, i) -> writer.setString(0, "k" + i).setLong(1, 0L).setLong(2, size),
                    aggregate::process);
            aggregate.finish();
        }
        assertThat(seen).hasSize(GROUPS).contains("k0=1", "k" + (GROUPS - 1) + "=1");
    }

    @Test
    void aGroupedAggregateEmitsMoreGroupsThanTheArenaHolds() {
        StreamSchema out = StreamSchema.builder("out")
                .field("k", Types.string())
                .field("c", Types.int64())
                .build();
        Set<String> seen = new HashSet<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 2)) {
            KeyedAggregate aggregate = new KeyedAggregate(
                    new AggregateOperator(ScanOperator.of("w", KEYED_IN), out, List.of(0), COUNT),
                    KEYED_IN,
                    arena,
                    row -> seen.add(row.getString(0) + "=" + row.getLong(1)),
                    GROUPS * 2);
            feed(arena, KEYED_IN, (writer, i) -> writer.setString(0, "k" + i).setLong(1, i), aggregate::process);
            aggregate.settle();
            aggregate.emit();
        }
        assertThat(seen).hasSize(GROUPS).contains("k0=1", "k" + (GROUPS - 1) + "=1");
    }

    private interface RowFiller {
        void fill(BinaryRowWriter writer, int i);
    }

    /** One row per group, each given back to the arena once processed, as a lane's batch is. */
    private static void feed(
            RowArena arena,
            StreamSchema schema,
            RowFiller filler,
            Consumer<com.ash.messaging.pravaha.api.data.RowView> into) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        for (int i = 0; i < GROUPS; i++) {
            long mark = arena.mark();
            long handle = arena.allocate(layout.rowSize(16));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            java.util.Objects.requireNonNull(filler).fill(writer, i);
            writer.weight(1L).eventTimestampNanos(1L).sequence(i).commit();
            into.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            arena.resetTo(mark);
        }
    }
}
