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

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.runtime.lane.Lane;
import com.ash.messaging.pravaha.runtime.lane.LaneMultiplexer;

/**
 * One input of a query hosted on a shared lane, as a reader shared with other queries sees it
 * (LANE-2).
 *
 * <p>A reader that feeds several queries on one lane writes each row into the lane once, stamped
 * with a route of its own, and each of those queries {@linkplain #listen listens} to that route.
 * That is one ingest per stream per lane, where each query used to be handed its own copy -- and,
 * before LANE-1 kept them apart, each query was handed every copy.
 *
 * <p><strong>When a query starts and stops listening is a position in the lane's input, not a
 * moment.</strong> Both are control tasks, which run after every row claimed before they were
 * submitted and before every row claimed after. A caller that submits one while nothing is writing
 * the shared route -- the shared reader's own lock is how {@code SharedPartitionFeed} ensures that
 * -- therefore moves the query on or off the fan-out at an exact row: a query joining is handed
 * nothing the reader wrote before it joined, and a query pausing is handed everything written before
 * it paused and nothing after. A subscription changed immediately would do neither, because rows
 * already in the inbox and not yet dispatched would go to whoever was subscribed when they were.
 */
public final class SharedLaneInput {

    private final Lane lane;
    private final LaneMultiplexer multiplexer;
    private final String queryId;
    private final int input;
    private final StreamSchema layout;

    SharedLaneInput(Lane lane, LaneMultiplexer multiplexer, String queryId, int input, StreamSchema layout) {
        this.lane = lane;
        this.multiplexer = multiplexer;
        this.queryId = queryId;
        this.input = input;
        this.layout = layout;
    }

    /** The shared lane: what several queries' inputs have in common when they can share a copy. */
    public Lane lane() {
        return lane;
    }

    /**
     * A writer of one copy of each row into this lane, stamped with {@code route}.
     *
     * <p>It has no reader of its own and is never polled: a shared reader writes through its {@link
     * IngestPump#sharedSink()} under the same freeze it holds every listening query's own pump in,
     * so a checkpoint of any one of them holds this writer between rows too.
     */
    public IngestPump openRoute(int route, BackpressurePolicy policy) {
        return new IngestPump(NO_READER, lane, 0, layout.withStreamId(route), policy).sharingItsInbox();
    }

    /** Starts handing this query the rows stamped with {@code route}, from the lane's current end. */
    public void listen(int route) {
        lane.submitControlTask(() -> multiplexer.subscribe(queryId, route, input));
    }

    /** Stops handing this query the rows stamped with {@code route}, from the lane's current end. */
    public void stopListening(int route) {
        lane.submitControlTask(() -> multiplexer.unsubscribe(queryId, route));
    }

    /**
     * Copies a row already written elsewhere into this lane, stamped with {@code route}.
     *
     * <p>The move {@code accept} makes for a hosted query: the row's own header names its stream,
     * and on a shared lane that would hand it to every query reading the stream, so the copy is
     * restamped with the query's own route before the lane can see it.
     *
     * @return false when the inbox is full, which is backpressure
     */
    static boolean offerStamped(Lane lane, MemoryRegion source, int offset, int length, int route) {
        if (length > lane.inboxCellBytes()) {
            throw new IllegalArgumentException("row of " + length + " bytes exceeds the cell size of "
                    + lane.inboxCellBytes() + "; raise pravaha.lane.inbox.cell-bytes for this node");
        }
        long sequence = lane.claim(0);
        if (sequence == com.ash.messaging.pravaha.common.queue.RowInbox.NO_SPACE) {
            return false;
        }
        MemoryRegion cells = lane.inboxRegion(0);
        int at = lane.cellOffset(0, sequence);
        cells.copyFrom(at, source, offset, length);
        cells.putInt(at + RowLayout.OFFSET_SCHEMA_ID, route);
        lane.publish(0, sequence);
        return true;
    }

    /** What a route writer's pump holds in place of a reader: nothing polls it. */
    private static final PartitionReader NO_READER = new PartitionReader() {
        @Override
        public int poll(RecordSink sink, int maxRecords) {
            return 0;
        }

        @Override
        public SourceOffset position() {
            return SourceOffset.BEGINNING;
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    };

    @Override
    public String toString() {
        return "SharedLaneInput[" + queryId + " input " + input + " on lane " + lane.laneId() + "]";
    }
}
