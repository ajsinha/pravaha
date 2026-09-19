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

import java.util.List;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Where computed results go.
 *
 * <p>Writes are batched because the cost of a sink is almost always a network round trip, and
 * amortising that over a batch is the difference between a sink that keeps up and one that becomes
 * the bottleneck for the whole query.
 */
public interface StreamSinkPlugin extends PravahaPlugin {

    SinkCapabilities capabilities();

    /**
     * The row shape this sink was configured to write, when it was given one.
     *
     * <p>A sink reads each row by ordinal and type through its <em>own</em> declared schema, while
     * the engine hands it rows laid out as the <em>query</em> produced them. When the two disagree --
     * a column added, two swapped -- every value is read from the wrong place and nothing fails. So a
     * sink that has a schema says so here, answerable after {@link #configure} and without {@link
     * #open}, and a registration whose output does not match it is refused before a row is written.
     *
     * <p>Empty, the default, means the sink takes whatever shape it is given.
     */
    default java.util.Optional<StreamSchema> schema() {
        return java.util.Optional.empty();
    }

    /**
     * The columns that identify a record at this sink, for a sink that upserts and deletes by key.
     *
     * <p>They must be the query's own key, as a set. A sink keyed on fewer columns than the view
     * collapses distinct rows onto one record, and a retraction of one of them deletes the other's;
     * a sink keyed on more leaves the old record behind whenever a row changes. Both are silent, so
     * a registration whose view key differs is refused. Empty, the default, means an append-only
     * sink with no key.
     */
    default java.util.List<String> keyColumns() {
        return java.util.List.of();
    }

    /**
     * Writes a batch.
     *
     * <p>Must be idempotent when {@link SinkCapabilities#idempotentUpsert()} is declared: on
     * recovery the engine replays from the last checkpoint, and a non-idempotent sink that claims
     * otherwise produces duplicates that nobody notices until a reconciliation fails.
     *
     * @return how many rows were written
     */
    int write(List<RowView> batch);

    /** Ensures everything written so far is durable at the sink. */
    void flush();

    /** Begins a transaction for a checkpoint. No-op unless transactional. */
    default void beginTransaction(long checkpointId) {}

    /** Prepares to commit. Returns a handle the engine stores in the checkpoint. */
    default String prepare(long checkpointId) {
        return "";
    }

    /** Commits a prepared transaction after the checkpoint is durable. */
    default void commit(String handle) {}

    /** Abandons a prepared transaction. */
    default void abort(String handle) {}
}
