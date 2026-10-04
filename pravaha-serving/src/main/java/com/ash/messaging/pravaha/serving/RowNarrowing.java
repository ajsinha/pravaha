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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.sql.plan.NarrowingPlan;

/**
 * A principal's narrowing of one view, applied to rows as they leave it (ADR-059 §4): the rows the row
 * filter keeps, with each masked column replaced -- for a read's scan, for a subscription's snapshot and
 * every commit after it, and for what an alert sees.
 *
 * <p>The filter and the masks are the same operators a registration puts above its scan ({@link
 * NarrowingPlan}), run by the same interpreted pipeline as every other read, so a mask means one thing
 * everywhere. Rows are fed one at a time and each output is matched to the row that produced it, which
 * is what lets a change keep its weight: a retraction stays a retraction of the masked row.
 *
 * <p>Compiled once per view shape and narrowing, and kept: the same principal reading the same view in a
 * loop plans nothing after the first read.
 */
public final class RowNarrowing {

    /** Nothing narrowed: rows pass as they are. */
    public static final RowNarrowing NONE = new RowNarrowing(null, NarrowingPlan.NONE, null);

    private static final int CACHED = 256;
    private static final Map<String, RowNarrowing> COMPILED = new LinkedHashMap<>(CACHED, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RowNarrowing> eldest) {
            return size() > CACHED;
        }
    };

    private final @Nullable StreamSchema schema;
    private final NarrowingPlan plan;
    private final @Nullable PhysicalOperator operator;

    private RowNarrowing(@Nullable StreamSchema schema, NarrowingPlan plan, @Nullable PhysicalOperator operator) {
        this.schema = schema;
        this.plan = plan;
        this.operator = operator;
    }

    /**
     * {@code narrowing} compiled for rows of {@code schema}.
     *
     * @throws com.ash.messaging.pravaha.api.PravahaException {@code PRV-7003} for a filter that cannot be
     *     enforced on these columns, {@code PRV-7038} for a mask that cannot
     */
    public static RowNarrowing of(StreamSchema schema, Narrowing narrowing) {
        if (narrowing == null || narrowing.isNone()) {
            return NONE;
        }
        String key = schema + "\u0000" + schema.fields() + "\u0000" + narrowing.fingerprint();
        synchronized (COMPILED) {
            RowNarrowing cached = COMPILED.get(key);
            if (cached != null) {
                return cached;
            }
        }
        NarrowingPlan plan = NarrowingPlan.compile(schema, narrowing);
        RowNarrowing compiled = plan.isNone()
                ? NONE
                : new RowNarrowing(schema, plan, plan.over(ScanOperator.of(schema.name(), schema)));
        synchronized (COMPILED) {
            COMPILED.put(key, compiled);
        }
        return compiled;
    }

    public boolean isNone() {
        return plan.isNone();
    }

    /** The columns a mask replaces. */
    public Set<String> maskedColumns() {
        return plan.maskedColumns();
    }

    /** The rows the filter keeps, masked. */
    public List<Object[]> apply(List<Object[]> rows) {
        if (isNone() || rows.isEmpty()) {
            return rows;
        }
        List<Object[]> kept = new ArrayList<>(rows.size());
        run(rows, (index, row) -> kept.add(row));
        return kept;
    }

    /** The changes whose rows the filter keeps, masked, each with its own weight. */
    public List<ViewChange> applyChanges(List<ViewChange> changes) {
        if (isNone() || changes.isEmpty()) {
            return changes;
        }
        List<Object[]> rows = new ArrayList<>(changes.size());
        changes.forEach(change -> rows.add(change.values()));
        List<ViewChange> kept = new ArrayList<>(changes.size());
        run(
                rows,
                (index, row) -> kept.add(new ViewChange(row, changes.get(index).weight())));
        return kept;
    }

    @FunctionalInterface
    private interface Kept {
        void kept(int index, Object[] row);
    }

    private void run(List<Object[]> rows, Kept into) {
        List<Object[]> out = new ArrayList<>(1);
        RowLayout layout = RowLayout.of(java.util.Objects.requireNonNull(schema));
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 64);
                InterpretedPipeline pipeline =
                        InterpretedPipeline.compile(java.util.Objects.requireNonNull(operator), (RowOutput)
                                () -> new ValueCollectingWriter(java.util.Objects.requireNonNull(schema), out::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView cursor = new BinaryRowView(layout);
            for (int index = 0; index < rows.size(); index++) {
                long handle = arena.allocate(layout.rowSize(1024));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                ViewQuery.write(writer, java.util.Objects.requireNonNull(schema), rows.get(index));
                writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
                arena.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(cursor.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
                // A filter and a computation hold nothing: a row that passes is out before accept
                // returns, so what came out belongs to the row that went in.
                for (Object[] row : out) {
                    into.kept(index, row);
                }
                out.clear();
                arena.reset();
            }
        }
    }
}
