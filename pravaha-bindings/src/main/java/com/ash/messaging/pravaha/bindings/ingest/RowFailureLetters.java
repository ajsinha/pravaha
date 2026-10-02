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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.registry.FeedStatus;
import com.ash.messaging.pravaha.registry.SourceFeed;
import com.ash.messaging.pravaha.runtime.RuntimeErrors;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetter;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry;
import com.ash.messaging.pravaha.runtime.dlq.DeadLetterQueue;
import com.ash.messaging.pravaha.runtime.exec.RowFailureSink;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;

/**
 * A query's dead-letter queue as the place a row whose evaluation failed goes (DLQPROJ-1).
 *
 * <p>The guide always said a row that divides by zero or overflows "goes to the dead-letter queue";
 * only a record the source could not decode ever did, and every other row stopped the query with
 * the queue configured and empty. With {@code pravaha.dlq.directory} set, the query's lanes now hand
 * such a row here -- when it failed before reaching any state, see {@code exec.RowGuard} -- and it is
 * written to the query's own file beside the decode failures, coded {@code PRV-3027}, its columns as
 * a JSON object, and the query keeps running. Every query gets the queue, pushed-to or source-fed,
 * because a pushed row can fail the same way.
 *
 * <p>Also the feed decorator that closes the queue when the feed it was opened with closes, for the
 * query whose queue was opened here rather than by one of its pumps.
 */
final class RowFailureLetters implements RowFailureSink {

    private final DeadLetterQueue queue;
    private final String queryName;

    RowFailureLetters(DeadLetterQueue queue, String queryName) {
        this.queue = queue;
        this.queryName = queryName;
    }

    @Override
    public void reject(String stream, StreamSchema schema, String row, ArithmeticException failure) {
        queue.accept(new DeadLetter(
                queryName,
                "evaluating the row failed and it was not applied: " + failure.getMessage(),
                RuntimeErrors.ROW_EVALUATION_FAILED.code(),
                stream,
                IngestPump.schemaSignature(schema),
                "",
                row.getBytes(StandardCharsets.UTF_8),
                UUID.randomUUID().toString(),
                System.nanoTime(),
                System.currentTimeMillis()));
    }

    /** {@code feed}, closing {@code owned} after it -- the queue this class opened for the query. */
    static SourceFeed closingWith(SourceFeed feed, List<AutoCloseable> owned) {
        if (owned.isEmpty()) {
            return feed;
        }
        return new SourceFeed() {
            @Override
            public void pause() {
                feed.pause();
            }

            @Override
            public void resume() {
                feed.resume();
            }

            @Override
            public long rowsFed() {
                return feed.rowsFed();
            }

            @Override
            public String describe() {
                return feed.describe();
            }

            @Override
            public FeedStatus status() {
                return feed.status();
            }

            @Override
            public DeadLetterEntry.Replay replay(
                    String stream, byte[] raw, String sourceOffset, String schema, String id) {
                return feed.replay(stream, raw, sourceOffset, schema, id);
            }

            @Override
            public void close() {
                try {
                    feed.close();
                } finally {
                    for (AutoCloseable resource : owned) {
                        try {
                            resource.close();
                        } catch (Exception ignored) {
                            // Closing a queue after its feed: nothing left to report it to.
                        }
                    }
                }
            }
        };
    }
}
