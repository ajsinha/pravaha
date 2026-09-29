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
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TRANSOVF-1: a {@code SUM} is netted over a batch in 128 bits, so a total that passes {@code 2^63}
 * and comes back inside one batch is answered, and a net total outside the 64-bit range is still
 * PRV-3025 naming the aggregate -- in the unkeyed and the keyed aggregate, rows and partials alike.
 */
class TransientSumOverflowTest {

    private static final StreamSchema IN = StreamSchema.builder("txn")
            .field("k", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final List<AggregateOperator.AggregateCall> CALLS = List.of(
            new AggregateOperator.AggregateCall(AggregateOperator.AggregateCall.Kind.SUM, 1, "total"),
            new AggregateOperator.AggregateCall(AggregateOperator.AggregateCall.Kind.AVG, 1, "mean"));

    private static final StreamSchema GLOBAL_OUT = StreamSchema.builder("out")
            .field("total", Types.int64())
            .field("mean", Types.int64())
            .build();

    private static final StreamSchema KEYED_OUT = StreamSchema.builder("out")
            .field("k", Types.string())
            .field("total", Types.int64())
            .field("mean", Types.int64())
            .build();

    @Test
    void aGlobalSumThatNetsInsideTheRangeIsAnswered() {
        List<List<Object>> out = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8)) {
            GlobalAggregate aggregate = new GlobalAggregate(
                    new AggregateOperator(ScanOperator.of("txn", IN), GLOBAL_OUT, List.of(), CALLS),
                    arena,
                    row -> out.add(List.of(row.getLong(0), row.getLong(1))));
            feed(arena, aggregate, 3, Long.MAX_VALUE, Long.MAX_VALUE, -Long.MAX_VALUE, -Long.MAX_VALUE);
            aggregate.settle();
            aggregate.emit();
        }
        assertThat(out).containsExactly(List.of(3L, 0L));
    }

    @Test
    void aGlobalSumWhoseNetLeavesTheRangeIsRefused() {
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8)) {
            GlobalAggregate aggregate = new GlobalAggregate(
                    new AggregateOperator(ScanOperator.of("txn", IN), GLOBAL_OUT, List.of(), CALLS), arena, row -> {});
            feed(arena, aggregate, Long.MAX_VALUE, Long.MAX_VALUE, -Long.MAX_VALUE, 1);
            assertThatThrownBy(aggregate::settle)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-3025")
                    .hasMessageContaining("SUM(amount)");
        }
    }

    @Test
    void aGlobalTotalIsNeverEmittedUnsettled() {
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8)) {
            GlobalAggregate aggregate = new GlobalAggregate(
                    new AggregateOperator(ScanOperator.of("txn", IN), GLOBAL_OUT, List.of(), CALLS), arena, row -> {});
            feed(arena, aggregate, Long.MAX_VALUE, 1);
            assertThatThrownBy(aggregate::emit).hasMessageContaining("PRV-3025");
        }
    }

    @Test
    void aKeyedSumThatNetsInsideTheRangeIsAnsweredAndOneThatDoesNotIsRefused() {
        List<List<Object>> out = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8)) {
            KeyedAggregate aggregate = new KeyedAggregate(
                    new AggregateOperator(ScanOperator.of("txn", IN), KEYED_OUT, List.of(0), CALLS),
                    IN,
                    arena,
                    row -> out.add(List.of(row.getString(0), row.getLong(1))),
                    100);
            feedKeyed(arena, aggregate, "a", Long.MAX_VALUE);
            feedKeyed(arena, aggregate, "a", Long.MAX_VALUE);
            feedKeyed(arena, aggregate, "a", -Long.MAX_VALUE);
            feedKeyed(arena, aggregate, "b", 7);
            feedKeyed(arena, aggregate, "a", -Long.MAX_VALUE);
            aggregate.settle();
            aggregate.emit();
            assertThat(out).containsExactly(List.of("a", 0L), List.of("b", 7L));

            feedKeyed(arena, aggregate, "b", Long.MAX_VALUE);
            assertThatThrownBy(aggregate::settle)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-3025")
                    .hasMessageContaining("SUM(amount)");
        }
    }

    private static void feed(RowArena arena, GlobalAggregate aggregate, long... amounts) {
        RowLayout layout = RowLayout.of(IN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        for (long amount : amounts) {
            long handle = arena.allocate(layout.rowSize(16));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            writer.setString(0, "x").setLong(1, amount);
            writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
            aggregate.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }
    }

    private static void feedKeyed(RowArena arena, KeyedAggregate aggregate, String key, long amount) {
        RowLayout layout = RowLayout.of(IN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(16));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, key).setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        aggregate.process(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }
}
