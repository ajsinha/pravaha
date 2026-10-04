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
import java.util.NavigableMap;
import java.util.TreeMap;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KeyedAggregate#processPartial}, proven the same way {@code
 * PartialAggregatePushdownEquivalenceTest} proves {@link GlobalAggregate#processPartial}: the
 * grouped case is the other half of what {@code SourcePushdown.partialAggregateFor} (pravaha-sql)
 * already decides is safe to push -- a global aggregate is not the only shape it offers a source a
 * partial for -- so it needs the same proof, against the operator directly rather than through SQL,
 * since a bounded {@code GROUP BY} needs a served-view read to reach {@code KeyedAggregate} through
 * a real plan and that machinery adds nothing this property needs.
 */
class KeyedAggregatePartialEquivalenceTest {

    private static StreamSchema inputSchema() {
        return StreamSchema.builder("txn")
                .field("status", Types.string())
                .field("amount", Types.int64())
                .build();
    }

    private static StreamSchema outputSchema() {
        return StreamSchema.builder("out")
                .field("status", Types.string())
                .field("n", Types.int64())
                .field("total", Types.int64())
                .build();
    }

    private static AggregateOperator operator() {
        return new AggregateOperator(
                ScanOperator.of("txn", inputSchema()),
                outputSchema(),
                List.of(0),
                List.of(
                        new AggregateOperator.AggregateCall(AggregateOperator.AggregateCall.Kind.COUNT, -1, "n"),
                        new AggregateOperator.AggregateCall(AggregateOperator.AggregateCall.Kind.SUM, 1, "total")));
    }

    private record Row(String status, long amount) {}

    private static List<Row> data(int count) {
        List<Row> rows = new ArrayList<>();
        String[] statuses = {"A", "B", "C"};
        for (int i = 0; i < count; i++) {
            rows.add(new Row(statuses[i % statuses.length], (long) (i * 7 % 200)));
        }
        return rows;
    }

    /**
     * Every combination of row count and batch count from 1 to a modest bound, rather than a
     * jqwik property: pravaha-runtime's test scope does not carry jqwik (it lives with pravaha-it
     * and pravaha-algebra), and an exhaustive small grid over the two dimensions this property
     * actually varies -- how many rows, and how many partials they are split into -- covers the
     * same ground a randomised property would for a space this size.
     */
    @Test
    void aSourceThatComputesPerGroupPartialsCorrectlyProducesTheSameAnswer() {
        for (int rowCount = 1; rowCount <= 60; rowCount++) {
            for (int batches = 1; batches <= 4; batches++) {
                List<Row> rows = data(rowCount);
                assertThat(runPushedDown(rows, batches))
                        .as(
                                "pushing per-group partials for %d rows split into %d batches changed the answer",
                                rowCount, batches)
                        .isEqualTo(runOrdinary(rows));
            }
        }
    }

    /** Feeds every row through the ordinary row-at-a-time path. */
    private static NavigableMap<String, List<Long>> runOrdinary(List<Row> rows) {
        AggregateOperator operator = operator();
        RowLayout inputLayout = RowLayout.of(inputSchema());
        List<Object[]> captured = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8)) {
            KeyedAggregate aggregate = new KeyedAggregate(operator, inputSchema(), arena, collector(captured), 1_000);
            BinaryRowWriter writer = new BinaryRowWriter(inputLayout);
            BinaryRowView view = new BinaryRowView(inputLayout);
            for (Row row : rows) {
                long handle = arena.allocate(inputLayout.rowSize(64));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                writer.setString(0, row.status()).setLong(1, row.amount());
                writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
                arena.trimTo(handle, writer.sizeSoFar());
                aggregate.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            aggregate.emit();
        }
        return toMap(captured);
    }

    /** Feeds no rows: only correctly-computed per-group partials, {@code batches} of them. */
    private static NavigableMap<String, List<Long>> runPushedDown(List<Row> rows, int batches) {
        AggregateOperator operator = operator();
        RowLayout outputLayout = RowLayout.of(outputSchema());
        List<Object[]> captured = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8)) {
            KeyedAggregate aggregate = new KeyedAggregate(operator, inputSchema(), arena, collector(captured), 1_000);
            int batchSize = Math.max(1, (rows.size() + batches - 1) / batches);
            for (int start = 0; start < rows.size(); start += batchSize) {
                List<Row> batch = rows.subList(start, Math.min(rows.size(), start + batchSize));
                java.util.Map<String, long[]> perGroup = new java.util.LinkedHashMap<>();
                for (Row row : batch) {
                    long[] counters = perGroup.computeIfAbsent(row.status(), k -> new long[2]);
                    counters[0]++;
                    counters[1] += row.amount();
                }
                BinaryRowWriter writer = new BinaryRowWriter(outputLayout);
                BinaryRowView view = new BinaryRowView(outputLayout);
                for (var entry : perGroup.entrySet()) {
                    long handle = arena.allocate(outputLayout.rowSize(64));
                    writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                    writer.setString(0, entry.getKey())
                            .setLong(1, entry.getValue()[0])
                            .setLong(2, entry.getValue()[1]);
                    writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
                    arena.trimTo(handle, writer.sizeSoFar());
                    aggregate.processPartial(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)), 1L);
                }
            }
            aggregate.emit();
        }
        return toMap(captured);
    }

    private static RowProcessor collector(List<Object[]> captured) {
        return row -> captured.add(new Object[] {row.getString(0), row.getLong(1), row.getLong(2)});
    }

    /**
     * {@code List<Long>} rather than {@code long[]}: a {@link TreeMap}'s own {@code equals}
     * delegates to the values' {@code equals}, and two arrays with identical content are never
     * equal to each other -- {@code assertThat(...).isEqualTo(...)} on a map of arrays would fail
     * every call regardless of whether the aggregates agreed, which is exactly the kind of test
     * that has not been seen to fail for the right reason.
     */
    private static NavigableMap<String, List<Long>> toMap(List<Object[]> captured) {
        TreeMap<String, List<Long>> map = new TreeMap<>();
        for (Object[] row : captured) {
            map.put((String) row[0], List.of((Long) row[1], (Long) row[2]));
        }
        return map;
    }
}
