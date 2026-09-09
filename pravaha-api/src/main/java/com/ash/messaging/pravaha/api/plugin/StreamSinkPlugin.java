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
