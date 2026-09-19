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
package com.ash.messaging.pravaha.registry;

/**
 * Data arriving at a registered query's inputs.
 *
 * <p>The piece that was missing. Registration built a {@code QueryExecution} with lanes, arenas and
 * watermarks, and then nothing ever handed it a row: the only code in the engine that drove a pump
 * was the CLI's one-shot {@code run}. A query registered on a server reached {@code RUNNING} and sat
 * at zero rows for as long as it lived.
 *
 * <p>An interface here rather than an implementation, because the registry must not know what a
 * Kafka topic or a Delta table is. It knows that a query has input streams and that something may be
 * attached to them; the deployment knows what. {@code pravaha-server} supplies the implementation
 * that reads bindings from configuration and drives source plugins.
 *
 * <p><strong>Pause has to reach the feed.</strong> {@link RegisteredQuery#accept} drops rows while
 * paused, but a feed writes into the lane's inbox directly and never passes through it -- so a pause
 * that did not reach here would be a pause in name only, with rows still arriving and the view still
 * moving. That is worse than not offering pause at all.
 */
public interface SourceFeed extends AutoCloseable {

    /** A feed attached to nothing: the answer for a query whose streams have no binding. */
    SourceFeed NONE = new SourceFeed() {
        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public long rowsFed() {
            return 0;
        }

        @Override
        public void close() {}

        @Override
        public String describe() {
            return "no source is bound to this query's streams";
        }

        @Override
        public FeedStatus status() {
            return FeedStatus.none(describe());
        }
    };

    /** Stops reading. Rows already in flight are not recalled. */
    void pause();

    /** Starts reading again from where the source left off. */
    void resume();

    /** Rows handed to the execution since the feed opened. */
    long rowsFed();

    /**
     * What this feed is reading, for an operator looking at a query that is not moving.
     *
     * <p>"Zero rows" has two causes that look identical from the outside -- nothing is bound, or
     * something is bound and quiet -- and telling them apart is most of diagnosing a silent query.
     */
    String describe();

    /**
     * Whether each source is still reading, and why not when one has stopped (FEED-1).
     *
     * <p>What every operator-facing surface reads, rather than parsing {@link #describe()}. The
     * default -- running, no sources listed -- is what a feed that predates this reports: it never
     * said it had stopped, and this does not invent that it has.
     */
    default FeedStatus status() {
        return FeedStatus.of(describe(), java.util.List.of());
    }

    @Override
    void close();
}
