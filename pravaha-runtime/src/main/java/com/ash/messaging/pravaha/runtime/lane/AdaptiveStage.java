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
package com.ash.messaging.pravaha.runtime.lane;

import java.util.concurrent.atomic.AtomicLong;

import com.ash.messaging.pravaha.common.memory.MemoryRegion;

/**
 * Runs interpreted now, generated as soon as generated exists.
 *
 * <p>Registering ten thousand queries means ten thousand Janino compilations. Done before any of
 * them produces a row, that is minutes of a node being up and useless -- at node restart, which is
 * exactly the moment when being useless is most expensive. So a query starts on the interpreted
 * path, which is correct and already built, and is swapped to its generated stage when a bounded
 * compile pool reaches it.
 *
 * <p>This makes the interpreted path load-bearing twice over. It was the correctness fallback for
 * plans the generator cannot cover (design section 12.4); it is now also the admission strategy,
 * which is a second and independent reason it can never be deleted.
 *
 * <p><strong>The swap cannot lose a row.</strong> Both sides are complete implementations of the
 * same stage and the swap is a single volatile reference assignment, so every batch is processed by
 * whichever one it reaches -- there is no window in which a row is handed to neither, and none in
 * which it is handed to both.
 *
 * <p>An earlier version of this note credited that safety to reading the reference once per batch.
 * It is not the reason: row safety comes from the assignment being atomic. Reading twice would only
 * mis-attribute one batch in the counters, once, in a window a few nanoseconds wide at the moment of
 * the upgrade. That was established by seeding the double read, which passed the row-total test and
 * then passed a purpose-built attribution test as well -- so the single read here is discipline
 * rather than something this suite enforces, and pretending otherwise would put false confidence in
 * a test that cannot deliver it.
 *
 * <p><strong>Stateful stages are not upgraded, and that is a real limit rather than an omission.</strong>
 * A filter or a projection carries nothing between rows, so swapping it is invisible. An aggregate
 * carries its accumulators, and a generated replacement would start empty -- silently resetting the
 * query's answer to whatever arrived after the swap. Transferring state into generated code needs a
 * defined handover both implementations agree on, which does not exist yet, so a stateful pipeline
 * declares itself and stays where it started.
 */
public final class AdaptiveStage implements LaneProcessor {

    private final String queryId;
    private final boolean upgradable;
    private final LaneProcessor interpreted;

    private volatile LaneProcessor active;
    private volatile boolean generated;
    private final AtomicLong rowsInterpreted = new AtomicLong();
    private final AtomicLong rowsGenerated = new AtomicLong();

    /**
     * @param upgradable whether this pipeline may be swapped. False for anything carrying state
     *     between rows: the generated replacement would start empty and quietly reset the answer.
     */
    public AdaptiveStage(String queryId, LaneProcessor interpreted, boolean upgradable) {
        this.queryId = queryId;
        this.interpreted = interpreted;
        this.active = interpreted;
        this.upgradable = upgradable;
    }

    /** A stateless stage, which is the case that can be upgraded. */
    public static AdaptiveStage stateless(String queryId, LaneProcessor interpreted) {
        return new AdaptiveStage(queryId, interpreted, true);
    }

    /** A stage carrying state between rows, which stays on the path it started on. */
    public static AdaptiveStage stateful(String queryId, LaneProcessor interpreted) {
        return new AdaptiveStage(queryId, interpreted, false);
    }

    @Override
    public int onBatch(MemoryRegion region, long[] rowOffsets, int count) {
        // Read once so the batch is attributed to the processor that actually ran it. Reading the
        // field again below would count a batch as generated that ran interpreted, which is a lie in
        // the only metric that shows the upgrade happened.
        LaneProcessor processor = active;
        int emitted = processor.onBatch(region, rowOffsets, count);
        if (processor == interpreted) {
            rowsInterpreted.addAndGet(count);
        } else {
            rowsGenerated.addAndGet(count);
        }
        return emitted;
    }

    /**
     * Swaps in the generated stage.
     *
     * @return {@code false} if this stage may not be upgraded, which the caller should log rather
     *     than retry -- it will never become true
     */
    public boolean upgradeTo(LaneProcessor generatedStage) {
        if (!upgradable) {
            return false;
        }
        this.active = generatedStage;
        this.generated = true;
        return true;
    }

    public boolean isGenerated() {
        return generated;
    }

    public boolean isUpgradable() {
        return upgradable;
    }

    public String queryId() {
        return queryId;
    }

    /** Rows processed before the upgrade. Zero once a query has been running generated from the start. */
    public long rowsInterpreted() {
        return rowsInterpreted.get();
    }

    /** Rows processed after it. The two together are every row the query has seen, with none counted twice. */
    public long rowsGenerated() {
        return rowsGenerated.get();
    }

    @Override
    public void close() {
        LaneProcessor current = active;
        try {
            current.close();
        } finally {
            if (current != interpreted) {
                // The interpreted stage is still holding whatever it acquired: it was never closed
                // when it was swapped out, because a swap is not a shutdown.
                interpreted.close();
            }
        }
    }

    @Override
    public String toString() {
        return "AdaptiveStage[" + queryId + ", " + (generated ? "generated" : "interpreted")
                + (upgradable ? "" : ", not upgradable") + "]";
    }
}
