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
package com.ash.messaging.pravaha.flight;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.backfill.BackfillPlan;
import com.ash.messaging.pravaha.backfill.OffsetSplicedReader;
import com.ash.messaging.pravaha.registry.SourceFeed;
import com.ash.messaging.pravaha.registry.SourceFeedFactory;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;

/**
 * A source with no records at all, whose history is therefore behind it at the first poll.
 *
 * <p>For the fixtures that test the <em>wire</em> of a blue/green replacement -- the actions, their
 * fields, their refusals, and a client in another language driving them. The engine's own tests
 * replace queries over real sources with real history; what those cannot exercise is a Python
 * client calling {@code cut_over}, and what this cannot exercise is anything about the data.
 *
 * <p>Rows still arrive by being pushed through {@code accept}, exactly as they did before this
 * existed: an ordinary feed here is {@link SourceFeed#NONE}.
 */
final class QuietBackfillSource implements SourceFeedFactory {

    @Override
    public SourceFeed open(
            String queryName,
            QueryExecution execution,
            List<String> sourceStreams,
            Runnable afterDelivery,
            Map<String, String> resumeFrom) {
        // A query named stalled_* gets a feed that has stopped (FEED-1), so a client in another
        // language can read a stopped source's state; everything else is fed by being pushed.
        return TestFlightServerMain.stalledFeeds(queryName, execution, sourceStreams, afterDelivery, resumeFrom);
    }

    @Override
    public SourceFeed openBackfill(
            String queryName,
            QueryExecution execution,
            List<String> sourceStreams,
            Runnable afterDelivery,
            Map<String, String> resumeFrom,
            BackfillPlan plan) {
        OffsetSplicedReader reader =
                new OffsetSplicedReader(at -> new Empty(), SourceOffset.BEGINNING, null, plan.job(), false);
        // Polled until the empty history is behind it. One poll is usually enough -- a history
        // with nothing in it is over at the first poll that finds nothing -- but a throttled
        // backfill has no budget in the instant it is created, and a real feed would simply poll
        // again a millisecond later.
        for (int attempt = 0;
                attempt < 400 && reader.phase() != com.ash.messaging.pravaha.backfill.BackfillPhase.LIVE;
                attempt++) {
            reader.poll(
                    () -> {
                        throw new IllegalStateException("an empty partition writes no rows");
                    },
                    64);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return SourceFeed.NONE;
    }

    @Override
    public Optional<String> backfillRefusal(String stream) {
        return Optional.empty();
    }

    /** A partition with nothing in it, and a position that says so. */
    private static final class Empty implements PartitionReader {
        @Override
        public int poll(RecordSink sink, int maxRecords) {
            return 0;
        }

        @Override
        public SourceOffset position() {
            return SourceOffset.BEGINNING;
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }
}
