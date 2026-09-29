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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static final Principal DANA = new Principal("dana", "public", java.util.Set.of("analyst"), Map.of());

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
            // comparison below is against this build on this machine. Measured again after the
            // second query is dropped, and the larger of the two taken: the scan rate follows how
            // much CPU the pump gets, and a single baseline taken before a load spike failed a gate
            // at load 61 (330 scans against a bound of 322) while the test passed three times alone.
            // Bracketing the measurement puts the baseline on both sides of whatever the machine
            // did meanwhile.
            long oneQuery = scansOver(WINDOW);

            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);

            // The late joiner gets the history it was not there for. The shared reader had already
            // read past these three records, so they reach this query through the catch-up read --
            // which is the half of SRC-3 that is not a counter but an answer being right.
            awaitRows(second, 3);

            long twoQueries = scansOver(WINDOW);
            assertThat(CountingScanPlugin.OPEN.get())
                    .as("two different questions about one binding must be one reader of it. Before SRC-3 they "
                            + "were two: two plans, two fingerprints, two plugin instances, two readers and two "
                            + "scans of the same set")
                    .isEqualTo(1);
            assertThat(first.rowsIn()).isGreaterThanOrEqualTo(3);
            assertThat(second.rowsIn()).isGreaterThanOrEqualTo(3);

            registry.drop("asks_another");
            long oneQueryAgain = scansOver(WINDOW);
            long baseline = Math.max(oneQuery, oneQueryAgain);

            // Printed as well as asserted. The assertion says the ratio is not two; the numbers say
            // what it is on this machine, which is what a later reader needs to know whether the
            // threshold below has any headroom left.
            System.out.printf(
                    "%nSRC-3 scans of one binding per %d ms: 1 query = %d, 2 queries (different SQL) = %d, "
                            + "1 query again = %d, load %.1f%n",
                    WINDOW.toMillis(),
                    oneQuery,
                    twoQueries,
                    oneQueryAgain,
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                            .getSystemLoadAverage());

            // The rate first, because it is the number the store feels and the one the finding is
            // measured in. The reader count below is the structural claim behind it.
            assertThat(twoQueries)
                    .as(
                            "one query scanned %d and then %d times per window, and two scanned %d. Scans must "
                                    + "follow the number of bindings, not the number of registrations -- a thousand "
                                    + "queries over one set is what ADR-036 section 3 is about, and the load lands "
                                    + "on the store",
                            oneQuery, oneQueryAgain, twoQueries)
                    .isLessThan(baseline * 3 / 2);
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

    /**
     * CDCREPL-2, for any single-consumer source (postgres-cdc's slot, mysql-cdc's replica id): a
     * second, different query over the binding is refused at registration, naming the holder, and the
     * binding is free again once the holder is dropped.
     */
    @Test
    void aSingleConsumerBindingIsRefusedToASecondQueryUntilTheFirstIsDropped() throws Exception {
        CountingScanPlugin.append(1, 100);

        ViewCatalog views = new ViewCatalog();
        PluginSourceFeeds feeds = feeds(Map.of("guarantee", "EXACTLY_ONCE", "sole", "true"));
        try (QueryRegistry registry = new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds)) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            awaitRows(first, 1);

            assertThatThrownBy(() -> registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA))
                    .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                    .hasMessageContaining("PRV-8028")
                    .hasMessageContaining("'asks_another' cannot read stream 'shared'")
                    .hasMessageContaining("query 'asks_one' is already reading it")
                    .hasMessageContaining("has one consumer at a time");
            assertThat(registry.names()).containsExactly("asks_one");
            assertThat(CountingScanPlugin.CREATED.get())
                    .as("refused before the second query's reader was created")
                    .isEqualTo(1);

            // A second name for the same computation opens no reader, so it is not refused.
            registry.register("asks_one_too", ASKS_ONE, List.of(0), DANA);

            registry.drop("asks_one");
            registry.drop("asks_one_too");
            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
            awaitRows(second, 1);
        }
    }

    @Test
    void theLastQueryOutClosesTheSharedReader() throws Exception {
        CountingScanPlugin.append(1, 100);

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds(Map.of()))) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
            awaitRows(first, 1);

            // SRC-10. A query joining behind the shared reader's position is attached to the live
            // fan-out first and given a private catch-up reader for the gap (see
            // SharedPartitionFeed#join) -- a real second reader for as long as it takes to deliver
            // the history and find its next poll empty, which is two rounds of the shared feed's
            // own thread rather than zero. This used to assert the open-reader count immediately
            // after registering, with no wait for either the second query's own delivery or that
            // catch-up settling -- racing a legitimate transient state rather than a defect. See
            // src10_aCatchUpReaderIsATransientNotALeak for the deterministic reproduction of
            // exactly this window. Waiting for both here is the fix; what is asserted is unchanged.
            awaitRows(second, 1);
            assertThat(awaitOpen(1, Duration.ofSeconds(5)))
                    .as("the second query's catch-up reader must close on its own once it has delivered the "
                            + "history and polled again to find nothing new")
                    .isTrue();

            // A leave narrows the shared reader's request, and the feed thread applies it at the
            // reader's next idle poll by opening the narrower reader and then closing the old one
            // (SharedPartitionFeed#replaceReader) -- two open for an instant, which a count read
            // straight after the drop can land on. Wait for it to settle; the assertion is unchanged.
            registry.drop("asks_one");
            assertThat(awaitOpen(1, Duration.ofSeconds(5)))
                    .as("dropping one of two queries must leave the reader open for the other")
                    .isTrue();

            registry.drop("asks_another");
            assertThat(CountingScanPlugin.OPEN.get())
                    .as("a reader nobody is left reading through must be closed, or a dropped query still costs "
                            + "a connection and a scan")
                    .isZero();
        }
    }

    /**
     * SRC-10, diagnosed rather than assumed. {@code theLastQueryOutClosesTheSharedReader} failed
     * intermittently under full-reactor load with "expected: 1 but was: 2" right after dropping
     * one of two queries. {@link SharedSourceGroup#holders} and {@link SharedPartitionFeed}'s own
     * membership are both mutated only under a lock at every call site in the production code (one
     * monitor per {@link PluginSourceFeeds}, one {@link java.util.concurrent.locks.ReentrantLock}
     * per {@link SharedPartitionFeed}, checked by reading both classes rather than assumed) -- there
     * is nowhere in either for a stale read of a reference count to come from.
     *
     * <p>What is real is a second reader, on purpose. A query that joins a group <em>behind</em> the
     * shared reader's position is attached to the live fan-out first and given a private catch-up
     * reader for what it missed -- correct, and documented as the reason sharing is offered only to
     * at-least-once sources. That catch-up reader does not close the instant it is created: it
     * closes once it has polled at least one record and then polled again and found nothing new
     * (see {@code SharedPartitionFeed#pollCatchUps}), which takes two rounds of the shared feed's
     * own background thread. For the span between those two rounds, {@code CountingScanPlugin.OPEN}
     * genuinely reads one higher than the group's own reader count -- and that is correct, not a
     * leak.
     *
     * <p>The flaky test registered its second query and asserted the reader count immediately, with
     * no wait for either query's delivery or that catch-up's own two rounds -- exactly the shape the
     * finding was framed around: an assertion placed before the thing it asserted had a chance to
     * happen. This test reproduces that window <em>deterministically</em> rather than by getting
     * lucky on scheduling: {@link CountingScanPlugin} can hold one specific reader's closing
     * (empty) poll open on a gate a test controls, so the transient state can be observed on
     * purpose instead of raced, and then released to confirm it resolves on its own.
     */
    @Test
    void src10_aCatchUpReaderIsATransientNotALeak() throws Exception {
        CountingScanPlugin.append(1, 100);

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds(Map.of()))) {
            RegisteredQuery first = registry.register("asks_one", ASKS_ONE, List.of(0), DANA);
            awaitRows(first, 1);
            // Forces the shared reader's position past BEGINNING before the second query joins, so
            // that join is guaranteed to be "behind" and need a catch-up -- the exact ordering that
            // made the original assertion flaky rather than reliably wrong.
            awaitScans(1);

            // Reader #1 is asks_one's original. ASKS_ONE and ASKS_ANOTHER push down different
            // filters, so joining also rebuilds the group's reader to push the OR of the two (reader
            // #2, see SharedPartitionFeed's javadoc); reader #3 is the catch-up this
            // join creates for the row asks_another joined too late to have seen from #2. Armed
            // before registering, so there is no window in which the catch-up could run unobserved.
            CountingScanPlugin.holdEmptyPollForReaderNumber = 3;
            java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
            CountingScanPlugin.heldPollGate = gate;
            try {
                RegisteredQuery second = registry.register("asks_another", ASKS_ANOTHER, List.of(0), DANA);
                awaitRows(second, 1);

                // Held here: the catch-up delivered asks_another's row and is one empty poll away
                // from closing itself, and that poll is parked on the gate. This is precisely the
                // state the flaky test could observe and mistake for a leak.
                assertThat(awaitOpen(2, Duration.ofSeconds(5)))
                        .as("a catch-up reader really does make this 2 for a moment -- that is the state "
                                + "the flake caught, not a defect")
                        .isTrue();
            } finally {
                gate.countDown();
            }

            // Released: the held poll returns empty, pollCatchUps sees it already delivered once,
            // and closes it. No test code drives that close -- it is the shared feed's own thread,
            // on its own schedule, which is why asserting ahead of it was the actual bug.
            assertThat(awaitOpen(1, Duration.ofSeconds(5)))
                    .as("and it closes itself, unassisted, once its next poll comes back empty")
                    .isTrue();
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

            // The escape hatch, and what it is for: a shared reader pushes the OR of its members'
            // WHERE clauses, wider than any one of them, so a deployment running one query against a
            // set it cares about may want its own narrower filter more than it wants the sharing.
            assertThat(CountingScanPlugin.OPEN.get()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------ ADR-039 item 6

    private static final String BIG = "SELECT id, amount FROM shared WHERE amount > 100";

    private static final String SMALL = "SELECT id, amount FROM shared WHERE amount < 10";

    /**
     * ADR-039 item 6, the shared-reader half. Two queries with different WHERE clauses used to share
     * a reader that pushed nothing, so the store sent every record to both. It now pushes the OR of
     * the two, the store sends only what one of them wants, and each keeps its own filter in the
     * engine -- so the answers are unchanged and the rows read are not.
     *
     * <p>Counts are asserted exactly, not as lower bounds, because they are the proof that the
     * reader was replaced without repeating or losing a row: the first query's reader is replaced
     * while it is still handing over a scan -- one record a poll, slowly -- which is the moment a
     * replacement made at the reader's reported position would hand every row of that scan over
     * again.
     */
    @Test
    void differentFiltersShareOneReaderThatPushesTheirOrAndReadsOnlyWhatEitherWants() throws Exception {
        for (int i = 0; i < 300; i++) {
            CountingScanPlugin.append(i, 101 + i); // all wanted by BIG
            if (i % 15 == 0) {
                CountingScanPlugin.append(10_000 + i, i % 10); // 20 wanted by SMALL
            }
        }
        CountingScanPlugin.maxPerPoll = 1;
        CountingScanPlugin.pollDelayMillis = 3;

        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds(Map.of()))) {
            RegisteredQuery big = registry.register("big", BIG, List.of(0), DANA);
            awaitRows(big, 20);
            assertThat(big.rowsIn())
                    .as("the join below must land while the first scan is still being handed over, or this "
                            + "test proves nothing about replacing a reader mid-scan")
                    .isLessThan(300);
            RegisteredQuery small = registry.register("small", SMALL, List.of(0), DANA);

            awaitRows(big, 300);
            awaitRows(small, 20);
            // The catch-up is done with, so what is written next reaches small once, live.
            assertThat(awaitOpen(1, Duration.ofSeconds(5))).isTrue();
            CountingScanPlugin.maxPerPoll = 0;
            CountingScanPlugin.pollDelayMillis = 0;

            // Written after the join: 100 for each query and 100 for neither.
            for (int i = 0; i < 100; i++) {
                CountingScanPlugin.append(20_000 + i, 500 + i);
                CountingScanPlugin.append(30_000 + i, i % 10);
                CountingScanPlugin.append(40_000 + i, 20 + (i % 70));
            }
            awaitRows(big, 500);
            awaitRows(small, 220);
            Thread.sleep(200);

            assertThat(CountingScanPlugin.REQUESTS)
                    .as("the shared reader must be rebuilt to push the OR of both filters, not nothing")
                    .anySatisfy(request -> {
                        assertThat(request.filters()).isEmpty();
                        assertThat(request.alternatives())
                                .containsExactlyInAnyOrder(
                                        List.of(new com.ash.messaging.pravaha.api.plugin.ReadRequest.Filter(
                                                "amount",
                                                com.ash.messaging.pravaha.api.plugin.ReadRequest.Comparison.GT,
                                                100L)),
                                        List.of(new com.ash.messaging.pravaha.api.plugin.ReadRequest.Filter(
                                                "amount",
                                                com.ash.messaging.pravaha.api.plugin.ReadRequest.Comparison.LT,
                                                10L)));
                    });
            // Each query receives what the OR lets through, and exactly once: big had 300 of its
            // own before the join and 200 after (its 100 and small's 100); small had 20 through its
            // catch-up and the same 200 after.
            assertThat(big.rowsIn())
                    .as("a reader replaced mid-scan must not hand the members that scan a second time")
                    .isEqualTo(500);
            assertThat(small.rowsIn()).isEqualTo(220);
            // 300 by big's own reader, 20 by small's catch-up, 200 by the shared one. Pushing
            // nothing would have sent all 300 records written after the join as well.
            assertThat(CountingScanPlugin.ROWS_READ.get())
                    .as("the store must send only rows one of the two queries wants")
                    .isEqualTo(520);

            // And the answers are each query's own, not the union's.
            assertThat(awaitViewSize(big, 400)).isEqualTo(400);
            assertThat(awaitViewSize(small, 120)).isEqualTo(120);
            assertThat(big.view().scan())
                    .allSatisfy(row -> assertThat((Long) row[1]).isGreaterThan(100L));
            assertThat(small.view().scan())
                    .allSatisfy(row -> assertThat((Long) row[1]).isLessThan(10L));
        }
    }

    /**
     * The other direction: when a query leaves, the reader narrows back to what the rest want, at
     * its next idle moment, and the one that stayed loses and repeats nothing across the switch.
     */
    @Test
    void whenAQueryLeavesTheSharedReaderNarrowsToWhatTheRestWant() throws Exception {
        for (int i = 0; i < 50; i++) {
            CountingScanPlugin.append(i, 200 + i);
            CountingScanPlugin.append(1_000 + i, i % 10);
        }
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry =
                new QueryRegistry(views, CountingScanPlugin.SCHEMA).feedingFrom(feeds(Map.of()))) {
            RegisteredQuery big = registry.register("big", BIG, List.of(0), DANA);
            awaitRows(big, 50);
            RegisteredQuery small = registry.register("small", SMALL, List.of(0), DANA);
            awaitRows(small, 50);

            registry.drop("small");
            com.ash.messaging.pravaha.api.plugin.ReadRequest bigAlone = CountingScanPlugin.REQUESTS.get(0);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline
                    && !CountingScanPlugin.REQUESTS
                            .get(CountingScanPlugin.REQUESTS.size() - 1)
                            .equals(bigAlone)) {
                Thread.sleep(5);
            }
            assertThat(CountingScanPlugin.REQUESTS.get(CountingScanPlugin.REQUESTS.size() - 1))
                    .as("with only big left, the reader must go back to pushing big's filter alone")
                    .isEqualTo(bigAlone);
            long readBefore = CountingScanPlugin.ROWS_READ.get();
            long bigBefore = big.rowsIn();

            for (int i = 0; i < 40; i++) {
                CountingScanPlugin.append(2_000 + i, 300 + i);
                CountingScanPlugin.append(3_000 + i, i % 10);
            }
            awaitRows(big, bigBefore + 40);
            Thread.sleep(200);

            assertThat(big.rowsIn() - bigBefore).isEqualTo(40);
            assertThat(CountingScanPlugin.ROWS_READ.get() - readBefore)
                    .as("rows only the dropped query wanted must no longer be read")
                    .isEqualTo(40);
        }
    }

    private static int awaitViewSize(RegisteredQuery query, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && query.view().size() != expected) {
            query.commit();
            Thread.sleep(10);
        }
        return query.view().size();
    }

    /** Waits until the shared reader has scanned at least this many times. See SRC-10. */
    private static void awaitScans(long atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (CountingScanPlugin.SCANS.get() >= atLeast) {
                return;
            }
            Thread.sleep(5);
        }
        assertThat(CountingScanPlugin.SCANS.get())
                .as(
                        "the shared reader scanned %d times in ten seconds; %d were expected",
                        CountingScanPlugin.SCANS.get(), atLeast)
                .isGreaterThanOrEqualTo(atLeast);
    }

    /**
     * Waits for {@code CountingScanPlugin.OPEN} to settle at {@code expected}, rather than reading
     * it once. See SRC-10: a catch-up reader closing itself is real work on another thread, not an
     * instantaneous side effect of the call that created it.
     */
    private static boolean awaitOpen(int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (CountingScanPlugin.OPEN.get() == expected) {
                return true;
            }
            Thread.sleep(5);
        }
        return CountingScanPlugin.OPEN.get() == expected;
    }

    /** Long enough that one scheduling hiccup is a small part of what is counted. */
    private static final Duration WINDOW = Duration.ofMillis(1000);

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
