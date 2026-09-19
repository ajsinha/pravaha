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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a registered query's feed costs the node in platform threads.
 *
 * <p>A feed is a thread per computation, and that is deliberate: a pump holds a reader and a staging
 * buffer only one thread may touch, and its backpressure hysteresis is edge-triggered and assumes it
 * sees every poll. What the reasoning defends is <em>confinement</em>, which a virtual thread
 * satisfies exactly -- it is still one thread of execution owning the pump. It differs only in not
 * holding a carrier while parked, and parked is what this loop is almost all of the time.
 *
 * <p>{@code QueryRegistry}'s own note says a thread per query is "fine at tens". The feed was one of
 * three platform threads each registration cost. This is the one removed.
 */
@Timeout(60)
final class FeedThreadCostTest {

    private static final int FEEDS = 30;

    @Test
    void aFeedCostsNoPlatformThread() throws Exception {
        long before = feedThreads();
        List<PumpingFeed> feeds = new ArrayList<>();
        try {
            for (int i = 0; i < FEEDS; i++) {
                // No pumps: the loop naps rather than polling, which is precisely the state a quiet
                // query's feed sits in and the state whose cost this is about.
                PumpingFeed feed = new PumpingFeed("q-" + i, List.of(), List.of(), List.of(), "no sources", () -> {});
                feed.start();
                feeds.add(feed);
            }

            // Give them time to be scheduled and reach their nap, so a count of zero cannot be
            // "the threads had not started yet". A platform implementation registers its thread at
            // start(), before the body runs, so this wait can only make the old behaviour more
            // visible -- never less.
            Thread.sleep(Duration.ofSeconds(2).toMillis());

            assertThat(feedThreads() - before)
                    .as(
                            "%d feeds added %d platform threads. Thread.getAllStackTraces() reports platform "
                                    + "threads only, so a virtual feed contributes nothing to it -- which is the "
                                    + "whole change: a quiet query's feed should not hold a megabyte of stack to nap",
                            FEEDS, feedThreads() - before)
                    .isZero();
        } finally {
            for (PumpingFeed feed : feeds) {
                feed.close();
            }
        }
    }

    /** Feed threads visible as platform threads. A virtual one is not, and that is the assertion. */
    private static long feedThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("pravaha-feed-"))
                .count();
    }
}
