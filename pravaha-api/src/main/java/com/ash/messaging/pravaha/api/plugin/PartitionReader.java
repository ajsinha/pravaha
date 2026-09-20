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

    /**
     * Whether every row this reader writes is a pre-combined partial aggregate rather than a row of
     * the stream.
     *
     * <p>{@code false} by default, and for every reader created without a {@link
     * ReadRequest.PartialAggregate} in its request. A reader that was asked for one and chose to
     * honour it answers {@code true} for its whole life -- the engine asks once, when it wires the
     * reader in, and writes its rows in the aggregate's output layout from then on. A reader asked
     * for one that declined, because some filter could not be expressed, answers {@code false} and
     * returns rows, which is always correct. See {@link ReadRequest.PartialAggregate} for what a
     * partial must contain.
     */
    default boolean deliversPartialAggregate() {
        return false;
    }

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

    /**
     * Tells the reader that a checkpoint recording {@code offset} for this partition is durable.
     *
     * <p>For a source that holds something on the store's side until it is told it may let go -- a
     * PostgreSQL replication slot retains write-ahead log until its client confirms a position --
     * this is the only safe moment to confirm. Confirming at delivery instead lets the store discard
     * changes a restore from the last checkpoint would need to read again; never confirming makes
     * the store keep everything for ever. Between the two is this call: a restart can only ever
     * resume from a durable checkpoint, so nothing before the newest one will be asked for again.
     *
     * <p>The offset is one this reader returned from {@link #position()}, possibly some time ago:
     * the reader may have moved on since. Called from the checkpointing thread, not the thread that
     * polls, so an implementation must be safe to call concurrently with {@link #poll}. It must not
     * block for long and must not throw for a store that is momentarily unreachable -- the next
     * checkpoint will say the same thing again.
     *
     * <p>A no-op by default, which is right for every source whose position lives only in the
     * checkpoint. A reader shared by several queries is not told: the queries checkpoint at
     * different positions, and confirming any one of them could release what another still needs.
     */
    default void checkpointed(SourceOffset offset) {}

    /**
     * Decodes one record's bytes out of band, without reading from the source.
     *
     * <p>What makes a dead letter replayable. A record in the queue is exactly the bytes this
     * reader could not turn into a row, and the only thing in the system that knows how to try
     * again is the decoder that failed -- so replay asks the reader rather than reimplementing a
     * CSV parser somewhere the operator can reach. The record goes into {@code sink} exactly as a
     * polled one would, which is what makes a replayed row a row like any other: it lands at the
     * frontier the query has reached now, and if it fails again the sink's own
     * {@link RecordSink#reject} puts it back on the queue.
     *
     * <p><strong>The reader's position does not move.</strong> This is not a seek and not a
     * re-read; nothing about where the source is reading changes, and a checkpoint taken after a
     * replay records the same offset it would have without one.
     *
     * <p>{@code false} by default, and that is a refusal rather than a failure: a source that
     * cannot decode a record outside its own read loop -- one whose decode depends on the block, the
     * transaction or the schema message it arrived with -- says so, and replay is refused by name
     * instead of producing a row from bytes nobody can vouch for.
     *
     * @param raw the bytes as the queue recorded them
     * @param sourceOffset where they came from, for a reader that puts the offset in the row
     * @return true if the bytes were decoded or rejected; false if this reader does not do this
     */
    default boolean decodeOne(byte[] raw, String sourceOffset, RecordSink sink) {
        return false;
    }

    /**
     * Whether this reader has already read past {@code sourceOffset}, so the source will not
     * deliver that record again by itself.
     *
     * <p>The question that decides whether replaying a dead letter can double-count. A source that
     * promises {@code EXACTLY_ONCE} promises it by having replayable offsets, so if it is still
     * positioned at or before the record, the record is coming again on its own -- and feeding it
     * in now would put it in twice. Once the reader is past it, the source will never deliver it
     * again and a replay is the only way it can get in at all.
     *
     * <p>{@code false} by default, which means "I cannot say" and not "no". A reader that does not
     * answer makes an exactly-once replay refusable rather than guessable: the alternative is
     * duplicating a record in a pipeline that promised not to, which is the failure nobody
     * discovers until a total is wrong.
     *
     * @param sourceOffset an offset in this source's own terms, as it was recorded on the entry
     */
    default boolean hasReadPast(String sourceOffset) {
        return false;
    }

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

        /**
         * The same, with the {@code PRV-} code of the decode failure.
         *
         * <p>Every other failure in this engine is addressable by a number that goes in a runbook,
         * a log filter and a help page; a dead letter's was a sentence and nothing else, so a
         * thousand rejections could not be grouped by what went wrong or linked to the page that
         * explains it. A reader that has a code passes it; one that does not keeps calling
         * {@link #reject(byte[], String, String)} and its entries say the code was not recorded,
         * which is true and is better than a guessed one.
         *
         * @param code the failure's code, as {@code PRV-5040}
         */
        default boolean reject(byte[] raw, String sourceOffset, String reason, String code) {
            return reject(raw, sourceOffset, reason);
        }
    }
}
