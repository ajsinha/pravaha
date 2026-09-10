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

import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.SpscRowRing;

/**
 * Rows moving between lanes, one dedicated ring per ordered pair.
 *
 * <p>A repartitioning query needs rows to reach the lane that owns their key: a {@code GROUP BY} on
 * a column the source was not partitioned by, or a join whose two sides arrive keyed differently.
 * The exchange is what moves them, and its cost is the entire reason the scaling gate exists -- if
 * moving a row between lanes is expensive, adding lanes stops helping and the single-writer model
 * buys nothing.
 *
 * <p><strong>N(N-1) rings rather than one queue per consumer.</strong> Eight lanes is fifty-six
 * rings, which sounds extravagant and is the cheap option: a shared inbound queue per lane would
 * put every producer back on one cache line and reintroduce precisely the contention the lane model
 * exists to remove. Each ring is single-producer, single-consumer, so a handoff is a copy and two
 * cursor writes with no atomic operation at all (design section 13.3).
 *
 * <p><strong>Single hop, and that is a real constraint.</strong> A sender may block waiting for room
 * in a ring, which is safe only because a receiving lane never has to send in order to receive: the
 * exchange is one hop, so the dependency graph between lanes is acyclic and somebody is always
 * draining. A multi-stage shuffle -- repartition, aggregate, repartition again on a different key --
 * makes that graph cyclic and can deadlock, and the fix is credit-based flow control rather than a
 * bigger ring. It is not built, so multi-hop exchange is not offered.
 */
public final class LaneExchange implements AutoCloseable {

    /** What a lane uses to send. Its own row of the matrix, so a lane cannot send as another lane. */
    public interface Sender {

        /**
         * Sends one row to another lane.
         *
         * @return {@code false} if that lane's ring is full. Backpressure, and the caller's cue to
         *     stop pulling new input rather than to spin -- a lane spinning on a full ring is not
         *     doing the work that would drain it.
         */
        boolean send(
                int targetLane, com.ash.messaging.pravaha.common.memory.MemoryRegion source, int offset, int length);

        /** How many lanes this exchange spans. */
        int laneCount();

        /** The lane sending. */
        int laneId();
    }

    private final SpscRowRing[][] rings;
    private final int laneCount;

    /**
     * @param cells cells per ring per direction; the whole exchange is
     *     {@code laneCount * (laneCount - 1) * cells * cellBytes} of off-heap memory, which is worth
     *     computing before setting either number generously
     */
    public LaneExchange(int laneCount, MemoryAccess access, int cells, int cellBytes) {
        if (laneCount < 2) {
            throw new IllegalArgumentException("an exchange needs at least two lanes, got " + laneCount);
        }
        this.laneCount = laneCount;
        this.rings = new SpscRowRing[laneCount][laneCount];
        for (int from = 0; from < laneCount; from++) {
            for (int to = 0; to < laneCount; to++) {
                if (from != to) {
                    // No ring on the diagonal: a lane routing a row to itself hands it to its own
                    // processor directly, and a ring there would be a copy and a round trip for
                    // nothing. That case is common -- with eight lanes, one row in eight.
                    rings[from][to] = new SpscRowRing(access, cells, cellBytes);
                }
            }
        }
    }

    public int laneCount() {
        return laneCount;
    }

    /** The ring carrying rows from one lane to another. */
    SpscRowRing ring(int from, int to) {
        return rings[from][to];
    }

    /** A sending view for one lane. */
    public Sender senderFor(int laneId) {
        return new LaneSender(laneId);
    }

    private final class LaneSender implements Sender {
        private final int laneId;

        LaneSender(int laneId) {
            this.laneId = laneId;
        }

        @Override
        public boolean send(
                int targetLane, com.ash.messaging.pravaha.common.memory.MemoryRegion source, int offset, int length) {
            if (targetLane == laneId) {
                throw new IllegalArgumentException(
                        "lane " + laneId + " routed a row to itself through the exchange; a lane's own rows go "
                                + "straight to its processor, and this call would cost a copy for nothing");
            }
            return rings[laneId][targetLane].offer(source, offset, length);
        }

        @Override
        public int laneCount() {
            return laneCount;
        }

        @Override
        public int laneId() {
            return laneId;
        }
    }

    /** Total rows sitting in the exchange, for the backpressure signal. */
    public int inFlight() {
        int total = 0;
        for (int from = 0; from < laneCount; from++) {
            for (int to = 0; to < laneCount; to++) {
                if (rings[from][to] != null) {
                    total += rings[from][to].size();
                }
            }
        }
        return total;
    }

    @Override
    public void close() {
        for (int from = 0; from < laneCount; from++) {
            for (int to = 0; to < laneCount; to++) {
                if (rings[from][to] != null) {
                    rings[from][to].close();
                    rings[from][to] = null;
                }
            }
        }
    }

    @Override
    public String toString() {
        return "LaneExchange[" + laneCount + " lanes, " + (laneCount * (laneCount - 1)) + " rings, " + inFlight()
                + " rows in flight]";
    }
}
