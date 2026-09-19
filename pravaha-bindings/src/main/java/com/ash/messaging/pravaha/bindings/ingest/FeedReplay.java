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
package com.ash.messaging.pravaha.bindings.ingest;

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.state.StateErrors;

/**
 * Choosing which pump a dead letter goes back through, for both kinds of feed.
 *
 * <p>The decision is the same whether the reader is this query's own ({@link PumpingFeed}) or one
 * it shares ({@link SharedFeed}), and it is the kind of decision that is written twice and then
 * diverges: a query reading two streams must not have its {@code orders} record fed into
 * {@code payments} because one of the two implementations picked the first pump it had.
 *
 * <p><strong>A shared reader is still safe to replay through.</strong> A record goes into one
 * pump's sink, and a pump writes into one query's lane -- so replaying on a shared reader delivers
 * the row to the query that asked and to nothing else, which is not true of asking the reader to
 * poll again. The pump's own ingest lock serialises it against the group's thread.
 */
final class FeedReplay {

    private FeedReplay() {}

    /** Replays through the pump reading {@code stream}, or refuses saying which streams there are. */
    static DeadLetterEntry.Replay through(
            List<IngestPump> pumps, String stream, byte[] raw, String sourceOffset, String schema, String id) {
        if (pumps.isEmpty()) {
            throw new PravahaException(
                    StateErrors.DLQ_REPLAY_REFUSED,
                    "this query has no source reader of its own to put the record back through.");
        }
        String wanted = stream == null ? "" : stream.strip();
        if (wanted.isEmpty()) {
            // An entry written before the stream was recorded. A query reading one stream has an
            // unambiguous answer; one reading two does not, and guessing would feed a payments
            // record into an orders stream. Refused rather than guessed.
            if (distinctStreams(pumps).size() == 1) {
                return pumps.get(0).replay(raw, sourceOffset, schema, id);
            }
            throw new PravahaException(
                    StateErrors.DLQ_REPLAY_REFUSED,
                    "this dead letter does not record which stream it arrived on -- it was written before "
                            + "that was kept -- and this query reads " + distinctStreams(pumps)
                            + ". Feeding it into the wrong one would put a row into the view that never "
                            + "existed. Correct the record at the source instead.");
        }
        for (IngestPump pump : pumps) {
            if (wanted.equals(pump.streamName())) {
                return pump.replay(raw, sourceOffset, schema, id);
            }
        }
        throw new PravahaException(
                StateErrors.DLQ_REPLAY_REFUSED,
                "this dead letter came from stream '" + wanted + "' and this query now reads "
                        + distinctStreams(pumps) + ". The binding has changed since the record was rejected, so "
                        + "there is no reader that could decode it as it was decoded then.");
    }

    private static List<String> distinctStreams(List<IngestPump> pumps) {
        return pumps.stream().map(IngestPump::streamName).distinct().sorted().toList();
    }
}
