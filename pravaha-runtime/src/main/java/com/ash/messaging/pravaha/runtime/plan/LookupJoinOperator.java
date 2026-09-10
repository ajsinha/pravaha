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
package com.ash.messaging.pravaha.runtime.plan;

import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Enrichment: each record joined against the current version of a dimension table.
 *
 * <p>The other kind of join, and the one most streaming pipelines actually need. A stream-to-stream
 * join keeps both sides in state and grows with both; this keeps nothing. Each record asks the store
 * for its key and the answer is used immediately, so the memory is a cache the operator chooses to
 * hold rather than state it is obliged to.
 *
 * <p>{@code LEFT} is supported here and refused for stream-to-stream joins, which looks
 * inconsistent and is not. A lookup answers definitively at the moment it is asked, so an unmatched
 * record is emitted with nulls and never has to be retracted; nothing arriving later can turn that
 * miss into a hit. On two streams the same syntax means holding every unmatched row for as long as
 * a match could still arrive, which without a time bound is forever.
 *
 * <p>The limitation to state plainly: the lookup is as of <em>now</em>. Replaying last month's
 * stream joins it against today's dimension table, so a backfill produces today's answers for old
 * records. That is what a processing-time lookup join means everywhere it exists, and it is the
 * reason the temporal syntax names an event-time column it does not honour.
 *
 * @param streamKeys ordinals in the input, positionally paired with the source's key columns
 * @param lookupStream the registered name of the dimension table, resolved to a plugin at start-up
 * @param leftOuter whether an unmatched record is emitted with nulls or dropped
 */
public record LookupJoinOperator(
        PhysicalOperator input,
        String lookupStream,
        List<Integer> streamKeys,
        StreamSchema lookupSchema,
        StreamSchema outputSchema,
        boolean leftOuter)
        implements PhysicalOperator {

    public LookupJoinOperator {
        streamKeys = List.copyOf(streamKeys);
        if (streamKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "a lookup join needs at least one key column; without one every record would ask the store "
                            + "for its whole contents");
        }
    }

    @Override
    public List<PhysicalOperator> inputs() {
        return List.of(input);
    }

    /**
     * Not stateful.
     *
     * <p>A cache is not state in the sense that matters here: losing it costs latency, not
     * correctness, so there is nothing to checkpoint and nothing to bound. That is the whole
     * advantage of a lookup join over keeping the dimension in a stream-to-stream join's state.
     */
    @Override
    public boolean isStateful() {
        return false;
    }

    @Override
    public String label() {
        return (leftOuter ? "LookupLeftJoin[" : "LookupJoin[") + lookupStream + " on "
                + streamKeys.stream()
                        .map(ordinal -> input.outputSchema().field(ordinal).name())
                        .toList() + "]";
    }

    /** Where the dimension's columns begin in the output. */
    public int streamWidth() {
        return input.outputSchema().fields().size();
    }
}
