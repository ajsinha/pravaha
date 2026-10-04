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
package com.ash.messaging.pravaha.server.api;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * What the dead-letter endpoints answer with.
 *
 * <p>The shape is decided by one question: <strong>the bytes are data, and the metadata is
 * not</strong>. A dead letter's raw record is a row of the source -- an account number, a
 * customer id -- and a record that failed to decode cannot have a row filter applied to it,
 * because there is no row. So the two travel in one object with the bytes as a field that may be
 * absent and a sentence beside it saying why, rather than as two endpoints or a silently trimmed
 * response: an operator who may see that a queue is 412 deep and may not see what is in it should
 * be told which of those they are looking at.
 */
public final class DeadLetterDtos {

    private DeadLetterDtos() {}

    /**
     * One entry, as a listing or a fetch returns it.
     *
     * @param id what addresses it -- the correlation id, the same string the node's log lines
     *     carry, so the person searching the log and the person calling this hold one name
     * @param sequence its position in the file, oldest first; it shifts when retention evicts,
     *     which is why {@code id} and not this is the handle
     * @param query the query whose feed rejected it
     * @param stream which of that query's streams it arrived on, empty when the entry predates
     *     that being recorded
     * @param offset where it came from, in the source's own terms: {@code line 812},
     *     {@code orders/3@1041}
     * @param code the {@code PRV-} code of the decode failure, empty when the source named none
     * @param reason the decoder's own sentence, or empty when it is withheld
     * @param at when it was rejected, on the wall clock; null for an entry written before that
     *     was recorded
     * @param size how many bytes the record was, which is disclosed even when the bytes are not:
     *     a length is not a row
     * @param raw the record itself, Base64, or null when it is withheld
     * @param withheld why {@code raw} and {@code reason} are absent, or null when they are not
     * @param replay {@code NEW}, {@code REPLAYED} or {@code FAILED_AGAIN}
     * @param replayedAt when it was replayed, or null
     */
    @Schema(name = "DeadLetter", description = "One record a query's feed could not decode")
    public record DeadLetter(
            String id,
            long sequence,
            String query,
            String stream,
            String offset,
            String code,
            String reason,
            @Nullable Instant at,
            int size,
            @Nullable String raw,
            @Nullable String withheld,
            String replay,
            @Nullable Instant replayedAt) {}

    /**
     * A page of a query's dead letters, newest first, with the counts beside it.
     *
     * @param evicted how many entries retention has removed and are gone; never omitted, because a
     *     depth without it cannot be read -- a queue steady at two thousand is either one bad
     *     afternoon or a bound throwing two thousand a minute away
     * @param retention the bound in force, so the page says what it is measured against
     */
    @Schema(name = "DeadLetterPage", description = "A query's dead letters, newest first")
    public record Page(
            String query,
            List<DeadLetter> entries,
            int offset,
            int limit,
            long total,
            boolean more,
            long bytes,
            long evicted,
            long evictedBytes,
            long replayed,
            long failedAgain,
            @Nullable Instant oldest,
            @Nullable Instant newest,
            String retention,
            boolean configured) {}

    /**
     * How deep a query's queue is, without any of it.
     *
     * <p>Its own endpoint because it is the one a dashboard polls, and a dashboard that had to
     * fetch a page of records with their bytes to learn a number would be reading production data
     * every fifteen seconds to draw a line.
     */
    @Schema(name = "DeadLetterCount", description = "How many dead letters a query has, and what retention took")
    public record Count(
            String query,
            long total,
            long bytes,
            long evicted,
            long evictedBytes,
            long replayed,
            long failedAgain,
            @Nullable Instant oldest,
            @Nullable Instant newest,
            String retention,
            boolean configured) {}

    /** Which entries to replay. */
    @Schema(name = "DeadLetterReplayRequest", description = "The ids to feed back through the query")
    public record ReplayRequest(List<String> ids) {}

    /**
     * What one replay did.
     *
     * @param outcome {@code REPLAYED} when the record decoded and is a row in the view now, or
     *     {@code FAILED_AGAIN} when it did not and has gone back on the queue as a new entry
     * @param newId the id of that new entry, when there is one
     */
    @Schema(name = "DeadLetterReplayed", description = "The result of replaying one dead letter")
    public record Replayed(String id, String outcome, String detail, String newId) {}

    /** What a replay of several did, in the order they were asked for. */
    @Schema(name = "DeadLetterReplayResult", description = "The result of replaying a selection")
    public record ReplayResult(String query, List<Replayed> results, int replayed, int failedAgain) {}
}
