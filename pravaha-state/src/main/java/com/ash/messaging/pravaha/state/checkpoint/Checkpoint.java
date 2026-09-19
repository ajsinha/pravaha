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
package com.ash.messaging.pravaha.state.checkpoint;

import java.util.Map;

/**
 * One consistent point in a query's life: where every source was, and what every operator held.
 *
 * <p>The pairing is the whole idea. Offsets without state resume the reading and lose the
 * accumulated answers; state without offsets keeps the answers and re-reads records already folded
 * into them. Either alone produces a query that looks like it recovered and is quietly wrong, which
 * is why they are one object with one identity rather than two things written near each other
 * (design section 14.4, ADR-008).
 *
 * <p>Exactly-once is a property of <em>state</em>, not of output. On recovery the engine rewinds its
 * sources and replays; records between the checkpoint and the failure are processed a second time,
 * and the state is correct because it was rewound with them. Output written before the failure was
 * still written -- unless the sink is transactional, in which case what it was written after the
 * cut was never committed: its handle is recorded in {@link #operatorState} beside the view, and
 * only a durable checkpoint commits it (ADR-043 "As built"). So output is exactly once to a
 * transactional sink, effectively once to an idempotent one, and at least once to anything else.
 *
 * @param id monotonic, and the tiebreak when two checkpoints are found
 * @param timestampNanos when it was taken, for humans rather than for logic
 * @param offsets source offsets by partition name, in each source's own terms
 * @param operatorState serialized operator state by operator id
 */
public record Checkpoint(long id, long timestampNanos, Map<String, String> offsets, Map<String, byte[]> operatorState) {

    public Checkpoint {
        offsets = Map.copyOf(offsets);
        operatorState = Map.copyOf(operatorState);
    }

    /** Bytes held, which is what retention policy is expressed in. */
    public long sizeBytes() {
        return operatorState.values().stream().mapToLong(bytes -> bytes.length).sum();
    }

    @Override
    public String toString() {
        return "Checkpoint[" + id + ", " + offsets.size() + " partitions, " + operatorState.size() + " operators, "
                + sizeBytes() + " bytes]";
    }
}
