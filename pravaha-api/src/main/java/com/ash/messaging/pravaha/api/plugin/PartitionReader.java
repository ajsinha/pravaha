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

import com.ash.messaging.pravaha.api.data.RowWriter;

/**
 * Reads one partition.
 *
 * <p>{@link #poll} is <strong>non-blocking</strong>: it returns zero when nothing is available and
 * lets the engine's wait strategy decide what to do. A reader that blocks internally takes that
 * decision away from the engine and makes the CPU-for-latency dial (design section 13.3) meaningless.
 */
public interface PartitionReader extends AutoCloseable {

    /**
     * Decodes up to {@code maxRecords} into {@code sink}.
     *
     * @return how many were written; zero when nothing is available
     */
    int poll(RecordSink sink, int maxRecords);

    /** The offset of the last record handed to {@link #poll}. Must be durably restartable. */
    SourceOffset position();

    /**
     * Stops fetching.
     *
     * <p>The engine calls this when a downstream is backpressured. A reader that ignores it turns
     * flow control into an out-of-memory error somewhere further along (design section 13.5).
     */
    void pause();

    void resume();

    @Override
    void close();

    /** Where a reader writes decoded records: straight into the lane's arena, with no intermediate object. */
    interface RecordSink {
        /** Begins a row. The caller must {@code commit()} or {@code abort()} it. */
        RowWriter beginRow();

        /**
         * Offers a record the reader could not turn into a row.
         *
         * <p>Every decoder in this project used to have the same shape at its failure point: abort
         * the row, throw, and carry a comment saying the engine's dead-letter queue would take it
         * from here. Nothing did. The throw left the reader, ended the poll, and stopped that
         * source's ingest -- for the life of the process on a server, and for the whole run on the
         * command line -- because one line of one file had a letter where a number should be.
         *
         * <p><strong>Returning {@code false} means there is nowhere to put it</strong>, and the
         * reader must then fail as it always did. That is the default, so a deployment that has not
         * asked for a dead-letter queue keeps exactly the behaviour it has: a bad record is still
         * refused loudly rather than quietly tolerated. Silently swallowing records by default would
         * be the worse half of the two rules in {@code DeadLetterQueue} -- a query producing
         * slightly wrong answers because some input was discarded, which nobody investigates because
         * nobody notices.
         *
         * @param raw the bytes as received, never re-encoded; a record that failed to decode cannot
         *     be described any other way
         * @param sourceOffset where it came from, in the source's own terms -- a line number, a
         *     Kafka offset -- because the first question asked of a dead letter is "can I replay it?"
         * @param reason what was wrong, in a sentence rather than a class name
         * @return {@code true} if the record was accepted for dead-lettering, {@code false} if the
         *     caller must fail instead
         */
        default boolean reject(byte[] raw, String sourceOffset, String reason) {
            return false;
        }
    }
}
