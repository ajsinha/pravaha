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
package com.ash.messaging.pravaha.server.ingest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SRC-3: different questions about one source are now one reader and one scan rate.
 *
 * <p>The sharing that already existed is {@code QueryFingerprint}'s, and it is real: register the
 * same SQL twice and the second registration is the same computation behind the same feed. It is
 * also the wrong seam for the load this is about. A deployment holding a thousand continuous queries
 * over one Aerospike set holds a thousand <em>different</em> questions, and every one of them used to
 * be its own plugin, its own reader and its own scan of that set -- which is load on somebody's
 * database, measured at 79-131 scans a second for four queries against 43-50 for one
 * ({@code AerospikeSourceScaleIT}).
 *
 * <p>{@link CountingScanPlugin} is that source's shape without the cluster: a store, an offset into
 * it, and a scan counted per poll that goes to it. Two of its numbers matter here and they are
 * different questions -- how many readers are open, which is the structural claim, and how fast the
 * scans come, which is the one the store feels.
 */
@Timeout(120)
class SharedSourceReaderTest {

    private static final Principal DANA = new Principal("dana", "acme", java.util.Set.of("analyst"), Map.of());

    /** Two questions about one set. Different SQL, so a different plan and a different fingerprint. */
    private static final String ASKS_ONE = "SELECT user_id, amount FROM shared WHERE amount > 0";

    private static final String ASKS_ANOTHER = "SELECT user_id, amount FROM shared WHERE amount > 1";

    @BeforeEach
    void reset() {
        CountingScanPlugin.reset();
    }

    private static PluginSourceFeeds feeds(Map<String, String> options) {
        return new PluginSourceFeeds().bind(new SourceBinding("shared", "counting-scan", options));
    }

    @Test
    void differentQueriesOverOneBindingAreOneReaderAndOneScanRate() throws Exception {
        CountingScanPlugin.append(1, 100);
        CountingScanPlugin.append(2, 250);
        CountingScanPlugin.append(3, 50);

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds(Map.of()))) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            awaitRows(first, 3);

            // The baseline: what one query costs the store, measured rather than assumed, so the
            // comparison below is against this build on this machine.
            long oneQuery = scansOver(Duration.ofMillis(400));

            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);

            // The late joiner gets the history it was not there for. The shared reader had already
            // read past these three records, so they reach this query through the catch-up read --
            // which is the half of SRC-3 that is not a counter but an answer being right.
            awaitRows(second, 3);

            long twoQueries = scansOver(Duration.ofMillis(400));

            // Printed as well as asserted. The assertion says the ratio is not two; the numbers say
            // what it is on this machine, which is what a later reader needs to know whether the
            // threshold below has any headroom left.
            System.out.printf(
                    "%nSRC-3 scans of one binding in 400ms: 1 query = %d, 2 queries (different SQL) = %d, "
                            + "readers open = %d%n",
                    oneQuery, twoQueries, CountingScanPlugin.OPEN.get());

            // The rate first, because it is the number the store feels and the one the finding is
            // measured in. The reader count below is the structural claim behind it.
            assertThat(twoQueries)
                    .as(
                            "one query scanned %d times in 400ms and two scanned %d. Scans must follow the "
                                    + "number of bindings, not the number of registrations -- a thousand queries "
                                    + "over one set is what ADR-036 section 3 is about, and the load lands on the "
                                    + "store",
                            oneQuery, twoQueries)
                    .isLessThan(oneQuery * 3 / 2);

            assertThat(CountingScanPlugin.OPEN.get())
                    .as("two different questions about one binding must be one reader of it. Before SRC-3 they "
                            + "were two: two plans, two fingerprints, two plugin instances, two readers and two "
                            + "scans of the same set")
                    .isEqualTo(1);

            // Both queries answer, and they answer differently: the shared reader pushes nothing
            // down once two filters disagree, and each query keeps its own filter above it.
            assertThat(first.rowsIn()).isGreaterThanOrEqualTo(3);
            assertThat(second.rowsIn()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void aQueryJoiningLaterIsFedEverythingWrittenAfterItJoinedToo() throws Exception {
        CountingScanPlugin.append(1, 100);

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds(Map.of()))) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            awaitRows(first, 1);

            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
            awaitRows(second, 1);

            // Written after the join, so it arrives through the fan-out rather than the catch-up.
            // Both paths have to work for a late joiner or it is short of either history or news,
            // and being short of news is the failure that looks fine for the first minute.
            CountingScanPlugin.append(2, 250);
            awaitRows(second, 2);
            awaitRows(first, 2);
        }
    }

    @Test
    void pausingOneQueryLeavesTheOthersReadingAndCatchesItUpOnResume() throws Exception {
        CountingScanPlugin.append(1, 100);

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds(Map.of()))) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
            awaitRows(first, 1);
            awaitRows(second, 1);

            registry.pause("asks_one");
            Thread.sleep(100);
            long pausedAt = first.rowsIn();

            CountingScanPlugin.append(2, 250);
            CountingScanPlugin.append(3, 50);

            // The other query keeps reading. A pause that stalled the shared reader would be one
            // operator pausing one query and stopping a thousand.
            awaitRows(second, 3);
            assertThat(first.rowsIn())
                    .as("a paused query must stop receiving rows even when the reader it shares is still running")
                    .isEqualTo(pausedAt);

            registry.resume("asks_one");
            // And it is made whole: it resumes from where it stopped, not from where the others
            // have got to, which is what the catch-up read is for.
            awaitRows(first, 3);
        }
    }

    @Test
    void aSourcePromisingExactlyOnceKeepsAReaderPerQuery() throws Exception {
        CountingScanPlugin.append(1, 100);

        ViewCatalog views = new ViewCatalog();
        PluginSourceFeeds feeds = feeds(Map.of("guarantee", "EXACTLY_ONCE"));
        try (QueryRegistry registry = new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds)) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
            awaitRows(first, 1);
            awaitRows(second, 1);

            // The limit of the fix, asserted so it is a decision rather than an oversight. Handing a
            // late joiner over from its catch-up read to the shared one duplicates the overlap, and
            // a source that has promised there are no duplicates does not get that. It keeps the
            // reader per query it always had -- and so the scan per query, which is the cost of the
            // guarantee rather than a defect.
            assertThat(CountingScanPlugin.OPEN.get())
                    .as("a source declaring EXACTLY_ONCE must not be shared: the handover between a catch-up "
                            + "reader and a shared one re-delivers the overlap")
                    .isEqualTo(2);
        }
    }

    @Test
    void theLastQueryOutClosesTheSharedReader() throws Exception {
        CountingScanPlugin.append(1, 100);

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds(Map.of()))) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
            awaitRows(first, 1);

            registry.drop("asks_one");
            assertThat(CountingScanPlugin.OPEN.get())
                    .as("dropping one of two queries must leave the reader open for the other")
                    .isEqualTo(1);

            registry.drop("asks_another");
            assertThat(CountingScanPlugin.OPEN.get())
                    .as("a reader nobody is left reading through must be closed, or a dropped query still costs "
                            + "a connection and a scan")
                    .isZero();
        }
    }

    @Test
    void sharingCanBeTurnedOffPerBinding() throws Exception {
        CountingScanPlugin.append(1, 100);

        ViewCatalog views = new ViewCatalog();
        PluginSourceFeeds feeds = feeds(Map.of("share.reader", "false"));
        try (QueryRegistry registry = new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds)) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
            awaitRows(first, 1);
            awaitRows(second, 1);

            // The escape hatch, and what it is for: a shared reader pushes nothing down once two
            // queries disagree about the WHERE clause, so a deployment running one query against a
            // set it cares about may want the server-side filter more than it wants the sharing.
            assertThat(CountingScanPlugin.OPEN.get()).isEqualTo(2);
        }
    }

    private static long scansOver(Duration window) throws InterruptedException {
        long before = CountingScanPlugin.SCANS.get();
        Thread.sleep(window.toMillis());
        return CountingScanPlugin.SCANS.get() - before;
    }

    private static void awaitRows(RegisteredQuery query, long atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (query.rowsIn() >= atLeast) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(query.rowsIn())
                .as("the feed delivered %d rows in ten seconds; %d were expected", query.rowsIn(), atLeast)
                .isGreaterThanOrEqualTo(atLeast);
    }
}
