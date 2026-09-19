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

    /**
     * Begins the transaction that the writes after it go into. No-op unless transactional.
     *
     * <p><strong>The protocol, for a sink declaring {@link SinkCapabilities#transactional()}.</strong>
     * The engine makes these calls one at a time, never concurrently with {@link #write}.
     *
     * <ol>
     *   <li>{@code beginTransaction(n)}, then any number of {@link #write} and {@link #flush} calls.
     *   <li>At a checkpoint's cut, {@link #prepare prepare(id)}: make everything written since the
     *       begin durable <em>without making it visible</em>, and return a handle naming it. The
     *       engine stores the handle in the checkpoint and begins the next transaction at once.
     *   <li>Once that checkpoint is durable, {@link #commit commit(handle)}.
     *   <li>After a restart, the engine restores the newest checkpoint, calls {@code commit} for every
     *       handle it recorded -- the process may have died after the checkpoint was stored and
     *       before the commit was sent -- and then {@link #abortAfter abortAfter(id)} for everything
     *       else, which the replay is about to write again.
     * </ol>
     *
     * <p>A query that takes no checkpoints has nothing to tie a transaction to, and the engine
     * prepares and commits one per view commit instead: atomic per commit, at least once across a
     * restart.
     *
     * @param checkpointId a label: one more than the newest checkpoint cut before this transaction
     *     began. A checkpoint that fails does not end a transaction, so the one that finally prepares
     *     it may carry a larger id. Labels only increase, across restarts too
     */
    default void beginTransaction(long checkpointId) {}

    /**
     * Makes the open transaction durable and not yet visible, and names it.
     *
     * @return a handle the engine stores in the checkpoint and later passes to {@link #commit},
     *     possibly from another process after a restart -- so it must name the transaction, not an
     *     object in this one's memory
     */
    default String prepare(long checkpointId) {
        return "";
    }

    /**
     * Commits a prepared transaction once the checkpoint recording it is durable.
     *
     * <p><strong>Must be idempotent.</strong> A process that dies after sending this and before a
     * newer checkpoint exists is restored from the checkpoint that recorded the handle, and the engine
     * commits it again, because it cannot know the first commit arrived.
     */
    default void commit(String handle) {}

    /** Abandons a prepared transaction. */
    default void abort(String handle) {}

    /**
     * Abandons every uncommitted transaction begun with a label greater than {@code checkpointId},
     * open or prepared.
     *
     * <p>Called once after a restore, when the handles the restored checkpoint recorded have been
     * committed. Anything this sink holds beyond them was written after that checkpoint's cut and is
     * about to be written again by the replay, so committing it later would be a duplicate. The
     * engine cannot name these transactions itself: the handles of any prepared at a checkpoint that
     * never became durable died with the process that prepared them.
     *
     * <p>No-op by default, which is right for a sink whose uncommitted transactions die with the
     * connection that opened them. A sink whose prepared transactions outlive its process -- XA, a
     * database's {@code PREPARE TRANSACTION} -- must implement it, or they hold their locks for ever.
     * They are never committed either way; this is about the locks, not the data.
     */
    default void abortAfter(long checkpointId) {}
}
