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

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.runtime.window.SlicedAggregateState;
import com.ash.messaging.pravaha.runtime.window.SlicedWindows;

/**
 * The interpreted windowed aggregate.
 *
 * <p>Folds each row into its slice and fires whole windows when the watermark passes their end
 * (design section 15.3). The state lives in {@link SlicedAggregateState}, which is where the
 * arithmetic and the bounds are; this class is the part that knows about rows.
 *
 * <p><strong>Grouping is on a single composite key.</strong> The group columns are hashed into one
 * {@code long}, which is what the slice state is keyed by. That is a deliberate simplification with
 * a real consequence: two different key combinations that hash the same would be merged, silently.
 * The hash is a 64-bit mix, so at a million keys the chance of any collision is around
 * 3 x 10^-8 -- small, but not zero, and the honest fix is to carry the key bytes rather than a hash.
 * That belongs with the keyed state store in Wave 4's second half, and until then this is recorded
 * here rather than left for somebody to discover.
 *
 * <p>Firing is driven by {@link #advanceWatermark}, and end of input fires everything still open.
 * A bounded source -- a file, a backfill -- would otherwise leave its last windows unemitted, which
 * looks exactly like the query being wrong about its final period.
 */
final class WindowedAggregate implements RowProcessor {

    private final WindowedAggregateOperator operator;
    private final SlicedWindows windows;
    private final SlicedAggregateState state;
    private final RowArena arena;
    private final RowProcessor downstream;
    private final RowLayout layout;
    private final BinaryRowWriter writer;
    private final BinaryRowView view;
    private final long[] scratch;
    private final List<Integer> valueOrdinals;
    private final List<com.ash.messaging.pravaha.api.data.TypeName> groupTypes;

    private long watermark = Long.MIN_VALUE;
    private long lastFiredWatermark = Long.MIN_VALUE;
    private long highestEventTime = Long.MIN_VALUE;

    WindowedAggregate(WindowedAggregateOperator operator, RowArena arena, RowProcessor downstream) {
        this.operator = operator;
        this.windows = new SlicedWindows(operator.spec());
        this.arena = arena;
        this.downstream = downstream;
        this.layout = RowLayout.of(operator.outputSchema());
        this.writer = new BinaryRowWriter(layout);
        this.view = new BinaryRowView(layout);

        SlicedAggregateState.Kind[] kinds =
                new SlicedAggregateState.Kind[operator.aggregates().size()];
        this.valueOrdinals = operator.aggregates().stream()
                .map(AggregateOperator.AggregateCall::argumentOrdinal)
                .toList();
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = switch (operator.aggregates().get(i).kind()) {
                case COUNT -> SlicedAggregateState.Kind.COUNT;
                case SUM, AVG -> SlicedAggregateState.Kind.SUM;
                case MIN -> SlicedAggregateState.Kind.MIN;
                case MAX -> SlicedAggregateState.Kind.MAX;
            };
        }
        this.state = new SlicedAggregateState(windows, kinds, operator.maxSlices());
        this.scratch = new long[kinds.length];
        // Resolved once, at construction: the key hash must not look a column's type up per row.
        this.groupTypes = operator.groupKeys().stream()
                .map(ordinal ->
                        operator.input().outputSchema().field(ordinal).type().typeName())
                .toList();
    }

    @Override
    public void process(RowView row) {
        long windowStart = row.getLong(operator.windowStartOrdinal());
        long key = compositeKey(row);
        for (int i = 0; i < scratch.length; i++) {
            int ordinal = valueOrdinals.get(i);
            scratch[i] = ordinal < 0 || row.isNull(ordinal) ? 0 : row.getLong(ordinal);
        }
        // The window start is the event time as far as slicing is concerned: the assigner has
        // already placed the row, and using it here keeps the two from disagreeing about a boundary.
        state.update(key, windowStart, scratch, row.weight());
        highestEventTime = Math.max(highestEventTime, row.eventTimestampNanos());
    }

    /**
     * Fires every window that has completed since the last call.
     *
     * <p>Windows fire in order and each fires once, because a consumer applying two results for one
     * window in arrival order keeps whichever arrived last.
     */
    void advanceWatermark(long watermarkNanos) {
        if (watermarkNanos <= watermark) {
            return;
        }
        watermark = watermarkNanos;
        long from = lastFiredWatermark == Long.MIN_VALUE ? firstWindowStart() : lastFiredWatermark;
        for (long windowEnd : windows.windowsCompletedBetween(from, watermark)) {
            emitWindow(windowEnd);
        }
        lastFiredWatermark = watermark;
        // Release what no window can need again. Allowed lateness is not wired to the query yet, so
        // this releases at the watermark; when lateness arrives it is one argument.
        state.discardSlicesEndingBefore(watermark, 0);
    }

    private long firstWindowStart() {
        // Start firing from the earliest window that could exist, not from Long.MIN_VALUE, or the
        // first advance would walk every window since the epoch.
        return highestEventTime == Long.MIN_VALUE
                ? 0
                : windows.sliceStartFor(highestEventTime) - operator.spec().sizeNanos();
    }

    /** Called when the input ends: a bounded source must not leave its last windows unemitted. */
    @Override
    public void finish() {
        if (highestEventTime != Long.MIN_VALUE) {
            // Past the end of the last window that can contain the highest event time seen.
            advanceWatermark(highestEventTime
                    + operator.spec().sizeNanos()
                    + operator.spec().slideNanos());
        }
    }

    private void emitWindow(long windowEnd) {
        for (SlicedAggregateState.WindowResult result : state.fire(windowEnd)) {
            long handle = arena.allocate(layout.rowSize(256));
            if (handle == ArenaHandle.NULL) {
                throw new PravahaException(
                        RuntimeErrors.ARENA_EXHAUSTED, "no room to emit a window result for key " + result.key());
            }
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            int column = 0;
            writer.setLong(column++, result.windowStartNanos());
            writer.setLong(column++, result.windowEndNanos());
            for (int i = 0; i < result.values().length; i++) {
                writer.setLong(column++, result.values()[i]);
            }
            writer.weight(1L)
                    .eventTimestampNanos(result.windowEndNanos())
                    .sequence(result.windowEndNanos())
                    .commit();
            arena.trimTo(handle, writer.sizeSoFar());
            downstream.process(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }
    }

    /**
     * Hashes the grouping columns into one long.
     *
     * <p>Window boundaries are part of the key, so two windows for the same user are different
     * groups without the state needing to know what a window is.
     */
    private long compositeKey(RowView row) {
        long hash = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < groupTypes.size(); i++) {
            int ordinal = operator.groupKeys().get(i);
            long value;
            if (row.isNull(ordinal)) {
                // A distinct constant rather than zero: NULL and 0 are different groups, and SQL is
                // emphatic that they are.
                value = 0xD1B54A32D192ED03L;
            } else {
                value = switch (groupTypes.get(i)) {
                    case STRING -> row.getString(ordinal).hashCode();
                    case BOOLEAN -> row.getBoolean(ordinal) ? 1 : 0;
                    case INT8 -> row.getByte(ordinal);
                    case INT16 -> row.getShort(ordinal);
                    case INT32, DATE -> row.getInt(ordinal);
                    default -> row.getLong(ordinal);
                };
            }
            hash = mix(hash ^ value);
        }
        return hash;
    }

    private static long mix(long z) {
        long h = z;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
