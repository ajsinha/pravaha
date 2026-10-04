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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.TopNOperator;
import com.ash.messaging.pravaha.state.StateErrors;

/**
 * {@link TopNOperator}, executed: a sorted multiset of rows per partition, and the difference
 * between its first N before and after each change, emitted as a Z-set.
 *
 * <p>Each input row changes one partition. The first N entries -- a row held twice occupies two
 * numbers -- are listed before the change and after it, and compared number by number. Where the
 * row at number {@code k} differs, the old one is retracted at {@code k} and the new one emitted at
 * {@code k}. That is the whole algorithm, and it is exact by construction: the output after every
 * change is the first N of the multiset, which is what a computation from scratch over the net
 * input produces. It costs O(N) per change, which is the price of emitting the row number; a query
 * that projects the number away still pays it, and the retraction and emission of an unchanged row
 * at a new number cancel downstream.
 */
final class TopNRanking implements RowProcessor, HeldRows {

    private final TopNOperator plan;
    private final StreamSchema inputSchema;
    private final StreamSchema outputSchema;
    private final RowArena arena;
    private final RowProcessor downstream;
    private final RowLayout layout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;
    private final Comparator<Object[]> order;
    private final Map<List<Object>, TreeMap<Object[], long[]>> partitions = new HashMap<>();
    private long rowsHeld;

    TopNRanking(TopNOperator plan, RowArena arena, RowProcessor downstream) {
        this.plan = plan;
        this.inputSchema = plan.input().outputSchema();
        this.outputSchema = plan.outputSchema();
        this.arena = arena;
        this.downstream = downstream;
        this.layout = RowLayout.of(outputSchema);
        this.writer = new BinaryRowWriter(layout);
        this.view = new BinaryRowView(layout);
        this.order = orderOf(plan.sortKeys()).thenComparing(MaterializedRows.TOTAL_ORDER);
    }

    private static Comparator<Object[]> orderOf(List<TopNOperator.SortKey> keys) {
        return (left, right) -> {
            for (TopNOperator.SortKey key : keys) {
                Object l = left[key.ordinal()];
                Object r = right[key.ordinal()];
                int c;
                if (l == null || r == null) {
                    // Nulls sort where the key says, whatever the direction.
                    c = l == r ? 0 : (l == null) == key.nullsFirst() ? -1 : 1;
                } else {
                    c = MaterializedRows.compareValues(l, r);
                    c = key.descending() ? -c : c;
                }
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
    }

    @Override
    public void process(RowView row) {
        long weight = row.weight();
        if (weight == 0) {
            return;
        }
        Object[] values = MaterializedRows.read(row, inputSchema);
        List<Object> key = MaterializedRows.key(values, plan.partitionOrdinals());
        TreeMap<Object[], long[]> partition = partitions.computeIfAbsent(key, k -> new TreeMap<>(order));

        List<Object[]> before = top(partition);
        long[] count = partition.get(values);
        long now = (count == null ? 0 : count[0]) + weight;
        if (now < 0) {
            throw new PravahaException(
                    RuntimeErrors.RETRACTED_UNHELD_ROW,
                    plan.label() + " was asked to retract a row it holds " + (now - weight)
                            + " time(s) with weight " + weight + ". A retraction of a row that was never "
                            + "inserted has no answer, and a top-N cannot number a row that is held fewer "
                            + "than zero times.");
        }
        if (now == 0) {
            partition.remove(values);
            rowsHeld--;
        } else if (count == null) {
            partition.put(values, new long[] {now});
            rowsHeld++;
        } else {
            count[0] = now;
        }
        if (rowsHeld > plan.maxRows()) {
            throw new PravahaException(
                    StateErrors.STATE_TOO_LARGE,
                    plan.label() + " holds " + rowsHeld + " distinct rows, past its ceiling of " + plan.maxRows()
                            + ". A top-N keeps every row of every partition, because retracting a top row "
                            + "promotes the next and that row must still be held. Bound it with a window in "
                            + "the PARTITION BY, or read it from a maintained view, where the input is finite.");
        }
        List<Object[]> after = top(partition);
        if (partition.isEmpty()) {
            partitions.remove(key);
        }

        int longest = Math.max(before.size(), after.size());
        for (int i = 0; i < longest; i++) {
            Object[] was = i < before.size() ? before.get(i) : null;
            Object[] is = i < after.size() ? after.get(i) : null;
            if (was != null && is != null && order.compare(was, is) == 0) {
                continue;
            }
            if (was != null) {
                emit(was, i + 1, -1, row);
            }
            if (is != null) {
                emit(is, i + 1, 1, row);
            }
        }
    }

    /** The first N rows of a partition, a row held twice appearing twice. */
    private List<Object[]> top(NavigableMap<Object[], long[]> partition) {
        List<Object[]> first = new ArrayList<>();
        for (Map.Entry<Object[], long[]> entry : partition.entrySet()) {
            for (long c = 0; c < entry.getValue()[0] && first.size() < plan.limit(); c++) {
                first.add(entry.getKey());
            }
            if (first.size() >= plan.limit()) {
                break;
            }
        }
        return first;
    }

    private void emit(Object[] values, long number, long weight, RowView cause) {
        long handle = arena.allocate(layout.rowSize(MaterializedRows.payloadBytes(values)));
        if (handle == ArenaHandle.NULL) {
            throw new PravahaException(RuntimeErrors.ARENA_EXHAUSTED, "no room to emit " + plan.label() + "'s row");
        }
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        MaterializedRows.write(writer, inputSchema, values);
        writer.setLong(values.length, number);
        writer.weight(weight)
                .eventTimestampNanos(cause.eventTimestampNanos())
                .sequence(cause.sequence())
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    /** Every held row and its multiplicity, for a checkpoint. */
    @Override
    public void writeTo(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeLong(rowsHeld);
        for (TreeMap<Object[], long[]> partition : partitions.values()) {
            for (Map.Entry<Object[], long[]> entry : partition.entrySet()) {
                MaterializedRows.writeTo(out, inputSchema, entry.getKey());
                out.writeLong(entry.getValue()[0]);
            }
        }
    }

    /** Restores what {@link #writeTo} wrote, replacing whatever is held. */
    @Override
    public void readFrom(java.io.DataInputStream in) throws java.io.IOException {
        partitions.clear();
        rowsHeld = in.readLong();
        for (long i = 0; i < rowsHeld; i++) {
            Object[] values = MaterializedRows.readFrom(in, inputSchema);
            partitions
                    .computeIfAbsent(MaterializedRows.key(values, plan.partitionOrdinals()), k -> new TreeMap<>(order))
                    .put(values, new long[] {in.readLong()});
        }
    }

    /** Distinct rows held across every partition. */
    @Override
    public long rowsHeld() {
        return rowsHeld;
    }
}
