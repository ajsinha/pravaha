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
package com.ash.messaging.pravaha.api.plugin;

import java.time.Duration;
import java.util.List;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * A dimension table: something to look rows up in, rather than consume.
 *
 * <p>The distinction from {@link StreamSourcePlugin} is about memory, not taste. A stream is read
 * from end to end and a join keeps its rows in state; a lookup table is asked one key at a time and
 * keeps nothing. Registering a hundred-million-row customer table as a stream is how a query runs
 * out of memory, and which one a table is has to be declared rather than guessed from the query.
 *
 * <p>A lookup answers as of <em>now</em>, which is what {@code JOIN ... FOR SYSTEM_TIME AS OF}
 * expresses and also its main limitation: replaying yesterday's stream against today's dimension
 * table gives today's answers. That is the accepted meaning of a processing-time lookup join
 * everywhere it exists, and it is worth knowing before using one to rebuild history.
 *
 * <p>Because the answer is definitive at the moment it is given, a {@code LEFT JOIN} against a
 * lookup table is safe where the same syntax against a stream is not: an unmatched row is emitted
 * with nulls immediately and never has to be retracted, since no later arrival can turn a miss into
 * a hit for that row.
 */
public interface LookupSourcePlugin extends PravahaPlugin {

    /** The shape of the rows a lookup returns. */
    StreamSchema schema();

    /**
     * The columns a key is given in, by name, in order.
     *
     * <p>Declared rather than inferred from the query, because a store can only answer efficiently
     * on the keys it is indexed for. A query joining on anything else is refused at registration
     * with this list in the message, rather than running a full scan per record and being blamed on
     * the network.
     */
    List<String> keyColumns();

    /**
     * Looks up every row matching {@code key}, writing them through {@code sink}.
     *
     * <p><strong>Called concurrently, from up to {@link #maxConcurrency()} threads at once.</strong>
     * That is how the engine hides a network round trip: a lookup is nearly all waiting, and waiting
     * one record at a time caps a lane at the inverse of the store's latency. An implementation
     * must therefore be thread-safe, which for most clients means a pool rather than one connection
     * -- a JDBC {@code Connection} shared between threads is the classic version of this mistake,
     * and it corrupts results rather than failing cleanly.
     *
     * <p>Each call gets its own {@code sink}, so nothing about the writing needs coordinating; it is
     * the client underneath that does.
     *
     * @param key one value per {@link #keyColumns()} entry, in that order
     * @return how many rows were written
     */
    int lookup(Object[] key, PartitionReader.RecordSink sink);

    /**
     * How many lookups this source will take at once.
     *
     * <p>The source's number, not the engine's, because the limit is the store's: a connection pool
     * of ten, a client that serialises internally, a rate limit somebody else depends on. Returning
     * one means "call me one at a time", which is always safe and gives up the overlap.
     *
     * <p>The engine will not exceed it, so a source can size its own pool to this number and be
     * certain it is enough.
     */
    default int maxConcurrency() {
        return 8;
    }

    /**
     * How long a lookup usually takes.
     *
     * <p>Not decoration. It is what the planner uses to decide whether a lookup join is viable at
     * the query's rate at all -- a millisecond per record on the lane thread caps a lane at a
     * thousand records a second, which is three orders of magnitude off the engine's other numbers.
     * A source that reports honestly gets a warning at registration; one that reports nothing gets
     * the benefit of the doubt and a surprise.
     */
    default Duration typicalLatency() {
        return Duration.ofMillis(1);
    }

    /**
     * How long a looked-up row may be cached, or {@link Duration#ZERO} for not at all.
     *
     * <p>The source decides, because only the source knows how stale its data may safely be. A
     * currency table refreshed hourly can be cached for minutes; an account balance cannot be
     * cached at all. The engine will not choose a number on a plugin's behalf.
     */
    default Duration cacheFor() {
        return Duration.ZERO;
    }
}
