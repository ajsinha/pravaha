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

import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.lane.LaneProcessor;

/**
 * Adapts a lane's batch of row offsets to the pipeline's row-at-a-time interface.
 *
 * <p>A nested record of {@link QueryExecution} until that class reached the size at which this
 * project extracts rather than grows. Nothing about it changed in the move: it is still
 * package-private, still built only by {@code QueryExecution}, and still runs on the lane thread
 * and nowhere else.
 */
record LanePipeline(InterpretedPipeline pipeline, BinaryRowView[] views, BinaryRowView[] partials, List<String> streams)
        implements LaneProcessor {

    @Override
    public int onBatch(MemoryRegion region, long[] offsets, int count) {
        return onBatch(0, region, offsets, count);
    }

    @Override
    public int onBatch(int input, MemoryRegion region, long[] offsets, int count) {
        BinaryRowView view = views[input];
        BinaryRowView partial = partials == null ? null : partials[input];
        String stream = streams.get(input);
        for (int i = 0; i < count; i++) {
            int at = (int) offsets[i];
            if (partial != null
                    && region.getInt(at + RowLayout.OFFSET_SCHEMA_ID) == QueryExecution.PARTIAL_AGGREGATE_ROW_ID) {
                // A source pre-combined these rows (ADR-039 item 6): fold the partial straight
                // into the aggregate, past the filter it was already computed under.
                partial.wrap(region, at);
                pipeline.acceptPartialAggregate(stream, partial, partial.weight());
                continue;
            }
            // A flyweight over the lane's own inbox cell: the row is read in place and never
            // copied, which is the entire reason the inbox holds bytes rather than objects.
            pipeline.accept(stream, view.wrap(region, at));
        }
        // The batch is whole: what it wrote may now be seen, all of it at once (VIEW-1). A
        // checkpoint's marker cuts the batch before this runs, so the output its cut commits
        // ends exactly at the marker.
        pipeline.endOfBatch();
        // The batch is done and everything it produced has been pushed downstream, so the rows
        // this pipeline allocated are unreachable. Without this the arena only ever grew.
        pipeline.resetArena();
        return count;
    }

    @Override
    public void onIdle() {
        // Nothing arriving means nothing will push a parked record out, so anything waiting on
        // a lookup is finished here instead of waiting for the stream to resume.
        pipeline.drainPending();
    }

    @Override
    public void close() {
        // On the lane thread, at shutdown: stateful operators emit what they were holding.
        pipeline.finish();
        pipeline.close();
    }
}
