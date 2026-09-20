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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewChange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Attaching consumers to a registered query (ADR-025, ADR-026).
 *
 * <p>Two properties carry the weight here. A subscriber must see whole commits, never half a batch.
 * And a subscriber that cannot keep up must not become everybody else's problem -- the engine is
 * never blocked, and whatever is lost is counted rather than quietly discarded.
 */
class SubscriptionTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private ViewCatalog views;
    private QueryRegistry registry;
    private RowArena arena;
    private RegisteredQuery query;

    @BeforeEach
    void setUp() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, TXN);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        query = registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    private void feed(String user, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user);
        writer.setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        // The engine applies rows on the lane's thread, so a test that fed and then read would be
        // racing it rather than testing it. Live ingest needs none of this; a definite answer at a
        // definite moment does.
        query.awaitApplied(java.time.Duration.ofSeconds(10));
    }

    @Test
    void aSubscriberReceivesChangesOnCommit() {
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feed("u1", 300L);
            feed("u2", 50L);
            query.commit();

            assertThat(seen).hasSize(2);
            assertThat(subscription.delivered()).isEqualTo(2);
        }
    }

    @Test
    void nothingArrivesBeforeTheCommit() {
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feed("u1", 300L);
            feed("u2", 50L);

            // Between commits the view holds a partly applied batch. A subscriber woken per row
            // could act on a total that was still being assembled.
            assertThat(seen).isEmpty();

            query.commit();
            assertThat(seen).hasSize(2);
        }
    }

    @Test
    void eachCommitArrivesAsOneBatch() {
        List<Integer> batchSizes = new ArrayList<>();
        try (Subscription subscription = query.subscribe(batch -> batchSizes.add(batch.size()))) {
            feed("u1", 1L);
            feed("u2", 2L);
            query.commit();
            feed("u3", 3L);
            query.commit();

            assertThat(batchSizes).containsExactly(2, 1);
        }
    }

    @Test
    void changesCarryTheirWeight() {
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feed("u1", 300L);
            query.commit();

            // +1 for a row appearing. A correction would arrive as -1 then +1, which is the same
            // arithmetic as everything else rather than a message type to special-case.
            assertThat(seen).singleElement().satisfies(change -> {
                assertThat(change.weight()).isEqualTo(1L);
                assertThat(change.isRetraction()).isFalse();
                assertThat(change.values()[0]).isEqualTo("u1");
            });
        }
    }

    @Test
    void manySubscribersShareOneComputation() {
        List<ViewChange> first = new ArrayList<>();
        List<ViewChange> second = new ArrayList<>();
        try (Subscription a = query.subscribe(first::addAll);
                Subscription b = query.subscribe(second::addAll)) {
            feed("u1", 300L);
            query.commit();

            assertThat(first).hasSize(1);
            assertThat(second).hasSize(1);
            assertThat(registry.size()).as("still one computation").isEqualTo(1);
        }
    }

    @Test
    void aSubscriberLeavingDoesNotDisturbTheOthers() {
        List<ViewChange> staying = new ArrayList<>();
        Subscription leaving = query.subscribe(changes -> {});
        try (Subscription stays = query.subscribe(staying::addAll)) {
            leaving.close();
            feed("u1", 300L);
            query.commit();

            assertThat(staying).hasSize(1);
            assertThat(query.state()).isEqualTo(QueryState.RUNNING);
        }
    }

    @Test
    void aSlowSubscriberConflatesRatherThanBlockingTheEngine() {
        List<ViewChange> seen = new ArrayList<>();
        // A buffer of one, so the second change in a batch must overflow.
        try (Subscription subscription =
                query.subscribe(SubscriptionOptions.of(1, SubscriptionOptions.Overflow.CONFLATE), seen::addAll)) {
            feed("u1", 10L);
            feed("u1", 20L);
            feed("u1", 30L);
            query.commit();

            // One key, so the newest change for it supersedes the waiting ones. The engine was
            // never blocked, and what was superseded is counted rather than silently gone.
            assertThat(subscription.conflated()).isPositive();
            assertThat(seen).hasSize(1);
        }
    }

    @Test
    void droppingIsCountedRatherThanSilent() {
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription =
                query.subscribe(SubscriptionOptions.of(2, SubscriptionOptions.Overflow.DROP_OLDEST), seen::addAll)) {
            feed("u1", 1L);
            feed("u2", 2L);
            feed("u3", 3L);
            feed("u4", 4L);
            query.commit();

            // A subscriber quietly missing data is the failure this option exists to make visible.
            assertThat(subscription.dropped()).isEqualTo(2);
            assertThat(seen).hasSize(2);
        }
    }

    @Test
    void aSubscriberThatCannotAffordToMissAnythingIsFailedInstead() {
        Subscription subscription =
                query.subscribe(SubscriptionOptions.of(1, SubscriptionOptions.Overflow.FAIL), changes -> {});

        feed("u1", 1L);
        feed("u2", 2L);
        // commit() no longer throws, and that half of the expectation is withdrawn by STRM-2. The
        // caller of commit() is the engine, not the subscriber: the throw escaped ViewSink.commit's
        // listener loop mid-iteration and reached advanceWatermark, which failed the whole query --
        // so one subscriber's buffer policy ended a computation two others were reading, and which
        // of them got their batch depended on attach order. The sibling case immediately below,
        // where a consumer throws, already worked this way.
        query.commit();

        // The subscriber still finds out immediately, which is what FAIL is for. On its own
        // channel, where the news concerns it and nobody else.
        assertThat(subscription.isClosed()).isTrue();
        assertThat(subscription.failure()).isPresent();
        assertThat(subscription.failure().orElseThrow()).hasMessageContaining("fell more than 1 changes behind");
    }

    @Test
    void aSubscriberThatThrowsIsDetachedRatherThanCalledAgain() {
        AtomicInteger calls = new AtomicInteger();
        Subscription subscription = query.subscribe(changes -> {
            calls.incrementAndGet();
            throw new IllegalStateException("consumer is broken");
        });

        feed("u1", 1L);
        query.commit();
        feed("u2", 2L);
        query.commit();

        // Called once. Calling it again every commit turns one broken subscriber into a stream of
        // exceptions on the engine's own thread.
        assertThat(calls.get()).isEqualTo(1);
        assertThat(subscription.isClosed()).isTrue();
        assertThat(subscription.failure()).isPresent();
    }

    @Test
    void aSinkWithNoSubscribersDoesNotAccumulateAChangeLog() {
        // No subscription at all: the common case, and it must not quietly build a list nobody
        // will read. Asserted by running a lot of rows through and expecting no failure.
        for (int i = 0; i < 50_000; i++) {
            feed("u" + (i % 100), i);
        }
        query.commit();

        assertThat(query.rowsIn()).isEqualTo(50_000);
    }

    @Test
    void aSubscriberSeesOnlyTheRowsItAskedFor() {
        List<ViewChange> seen = new ArrayList<>();
        StreamSchema schema = query.outputSchema();
        try (Subscription subscription = query.subscribe(
                SubscriptionOptions.DEFAULT, SubscriptionFilter.matching(schema, "user_id", "u2"), seen::addAll)) {
            feed("u1", 10L);
            feed("u2", 20L);
            feed("u3", 30L);
            query.commit();

            assertThat(seen)
                    .singleElement()
                    .satisfies(change -> assertThat(change.values()[0]).isEqualTo("u2"));
        }
    }

    @Test
    void aFilterOnANumberWrittenAsTextMatchesTheNumber() {
        // HLP-9. A filter arrives over the wire as text -- the Flight ticket carries strings -- and
        // was compared with Objects.equals, so "20" never equalled the view's Long 20 and a filter on
        // any column but a text one silently matched nothing.
        List<ViewChange> seen = new ArrayList<>();
        StreamSchema schema = query.outputSchema();
        try (Subscription subscription = query.subscribe(
                SubscriptionOptions.DEFAULT, SubscriptionFilter.matching(schema, "amount", "20"), seen::addAll)) {
            feed("u1", 10L);
            feed("u2", 20L);
            query.commit();

            assertThat(seen)
                    .singleElement()
                    .satisfies(change -> assertThat(change.values()[0]).isEqualTo("u2"));
        }
    }

    @Test
    void textThatIsNotANumberIsRefusedForANumericColumn() {
        assertThatThrownBy(() -> SubscriptionFilter.matching(query.outputSchema(), "amount", "twenty"))
                .hasMessageContaining("amount")
                .hasMessageContaining("twenty");
    }

    @Test
    void everyScalarColumnTypeCanBeFilteredOnFromText() {
        StreamSchema wide = StreamSchema.builder("wide")
                .field("flag", Types.bool())
                .field("tiny", Types.int8())
                .field("small", Types.int16())
                .field("n", Types.int32())
                .field("big", Types.int64())
                .field("ratio", Types.float64())
                .field("text", Types.string())
                .build();
        SubscriptionFilter filter = SubscriptionFilter.matching(
                wide,
                new java.util.LinkedHashMap<>(Map.of(
                        "flag", "TRUE",
                        "tiny", "1",
                        "small", "2",
                        "n", "3",
                        "big", "4",
                        "ratio", "0.5",
                        "text", "x")));
        Object[] row = {true, (byte) 1, (short) 2, 3, 4L, 0.5d, "x"};

        assertThat(filter.accepts(new ViewChange(row, 1))).isTrue();
        Object[] other = row.clone();
        other[0] = false;
        assertThat(filter.accepts(new ViewChange(other, 1))).isFalse();
        assertThat(SubscriptionFilter.matching(wide, "big", 4L).accepts(new ViewChange(row, 1)))
                .as("a typed value still matches, for an embedder that passes one")
                .isTrue();
        StreamSchema money = StreamSchema.builder("money")
                .field("price", Types.decimal(10, 2))
                .field("weight", Types.float32())
                .build();
        assertThat(SubscriptionFilter.matching(money, Map.of("price", "1.5", "weight", "2.5"))
                        .accepts(new ViewChange(new Object[] {new java.math.BigDecimal("1.50"), 2.5f}, 1)))
                .as("a decimal is read at its column's scale")
                .isTrue();
        assertThatThrownBy(() -> SubscriptionFilter.matching(money, "price", "1.505"))
                .hasMessageContaining("1.505");
        assertThatThrownBy(() -> SubscriptionFilter.matching(wide, "flag", "yes"))
                .hasMessageContaining("BOOLEAN");
    }

    @Test
    void differentlyFilteredSubscribersShareOneComputation() {
        List<ViewChange> first = new ArrayList<>();
        List<ViewChange> second = new ArrayList<>();
        StreamSchema schema = query.outputSchema();
        try (Subscription a = query.subscribe(
                        SubscriptionOptions.DEFAULT,
                        SubscriptionFilter.matching(schema, "user_id", "u1"),
                        first::addAll);
                Subscription b = query.subscribe(
                        SubscriptionOptions.DEFAULT,
                        SubscriptionFilter.matching(schema, "user_id", "u2"),
                        second::addAll)) {
            feed("u1", 10L);
            feed("u2", 20L);
            query.commit();

            // Two subscribers, two different slices, one read of the source and one copy of the
            // state. This is the argument for separating registration from subscription.
            assertThat(first).hasSize(1);
            assertThat(second).hasSize(1);
            assertThat(registry.size()).isEqualTo(1);
        }
    }

    @Test
    void filteredOutRowsDoNotConsumeTheSubscribersBuffer() {
        List<ViewChange> seen = new ArrayList<>();
        StreamSchema schema = query.outputSchema();
        try (Subscription subscription = query.subscribe(
                SubscriptionOptions.of(2, SubscriptionOptions.Overflow.DROP_OLDEST),
                SubscriptionFilter.matching(schema, "user_id", "u1"),
                seen::addAll)) {
            feed("u1", 1L);
            for (int i = 0; i < 50; i++) {
                feed("other-" + i, i);
            }
            query.commit();

            // Filtering happens before buffering. Otherwise a subscriber watching one key would
            // have its own row pushed out by fifty rows it never asked for.
            assertThat(seen).hasSize(1);
            assertThat(subscription.dropped()).isZero();
        }
    }

    @Test
    void aFilterOnAColumnTheViewDoesNotHaveIsRefused() {
        StreamSchema schema = query.outputSchema();

        // Refused rather than ignored: a typo that is quietly dropped leaves a subscriber receiving
        // everything while believing it asked for a slice.
        assertThatThrownBy(() -> SubscriptionFilter.matching(schema, "prodcut_type", "SWAP"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("has no column");
    }

    @Test
    void subscribingToADroppedQueryIsRefused() {
        registry.drop("q");

        assertThatThrownBy(() -> query.subscribe(changes -> {}))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8003");
    }

    @Test
    void strm17TheRefusalNamesTheQueryTheCallerAskedAboutRatherThanAFingerprint() {
        // STRM-17. anyName() fell back to fingerprint.shortForm() once removeName had emptied the
        // name set, and both subscribe overloads build their message from it -- so the one message
        // whose job is to say which query you cannot subscribe to read "cannot subscribe to
        // 'a740dfd20964': it is DROPPED", naming an identifier that appears nowhere in the
        // caller's code.
        registry.drop("q");

        assertThatThrownBy(() -> query.subscribe(changes -> {}))
                .hasMessageContaining("cannot subscribe to 'q'")
                .hasMessageNotContaining(query.fingerprint().shortForm());
    }

    @Test
    void strm12ADropClosesItsSubscriptionsAndSaysWhySoTheCountReturnsToZero() {
        // STRM-12. RegisteredQuery.close() touched no subscription, so after any drop the
        // Subscription objects reported isClosed() == false with an empty failure(), stayed in the
        // sink's listener list, and subscriberCount() -- which OPERATIONS.md offers as the
        // operator's signal that nobody is watching a query -- never returned to zero.
        Subscription one = query.subscribe(changes -> {});
        Subscription two = query.subscribe(changes -> {});
        assertThat(query.subscriberCount()).isEqualTo(2);

        registry.drop("q");

        assertThat(one.isClosed()).isTrue();
        assertThat(two.isClosed()).isTrue();
        assertThat(one.failure().orElseThrow().getMessage())
                .as("a reason the client can act on, not silence")
                .contains("PRV-8018")
                .contains("has been dropped");
        assertThat(query.subscriberCount())
                .as("the count is a signal only if it can go back down")
                .isZero();
    }

    @Test
    void strm12ClosingTheRegistrySaysTheNodeIsStoppingRatherThanThatTheQueryIsOver() {
        // STRM-12's second half. A restart arrived as a clean completion, which reads as "this
        // stream is finished" -- for a query that is journalled, comes back RUNNING and moves on
        // without the client that stopped. The two events call for opposite responses, so they are
        // two codes.
        Subscription live = query.subscribe(changes -> {});

        registry.close();

        assertThat(live.isClosed()).isTrue();
        assertThat(live.failure().orElseThrow().getMessage())
                .contains("PRV-8019")
                .contains("shutting down");
    }

    @Test
    void strm14DroppingOneNameEndsOnlyTheSubscriptionsOpenedUnderIt() {
        // STRM-14. Two registrations over byte-identical SQL are one computation with two names.
        // Dropping one removed the name and the view but left the computation running, so a
        // subscriber attached under the dropped name went on receiving rows -- while a read of
        // that same name at the same instant was refused as a view that does not exist. Two server
        // responses to one name, contradicting each other, and neither explicable as a stale
        // client. The authorization consequence is the reason it is not cosmetic: the re-check
        // loop kept asking the policy about a name it could no longer have an opinion on.
        RegisteredQuery same = registry.register("qb", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        assertThat(same).isSameAs(query);

        List<ViewChange> onDropped = new ArrayList<>();
        List<ViewChange> onSurviving = new ArrayList<>();
        Subscription dropped = query.subscribeAs("q", SubscriptionOptions.DEFAULT, null, onDropped::addAll);
        Subscription surviving = query.subscribeAs("qb", SubscriptionOptions.DEFAULT, null, onSurviving::addAll);

        feed("u1", 10);
        query.commit();
        assertThat(onDropped).hasSize(1);
        assertThat(onSurviving).hasSize(1);

        registry.drop("q");

        assertThat(dropped.isClosed())
                .as("the name it asked for is gone, and so is its view")
                .isTrue();
        assertThat(dropped.failure().orElseThrow().getMessage())
                .contains("PRV-8018")
                .contains("'q'");
        assertThat(surviving.isClosed())
                .as("STRM-067's mirror case was already right and must stay right")
                .isFalse();

        feed("u2", 20);
        query.commit();
        assertThat(onDropped)
                .as("nothing more reaches a subscriber on a name that does not exist")
                .hasSize(1);
        assertThat(onSurviving).hasSize(2);
    }

    @Test
    void aPushedWatermarkIsStillReported() {
        // The property the fix must not cost: an embedder driving watermarks by hand is a real
        // caller, and its number is not the execution's.
        query.advanceWatermark(5_000_000_000L);
        assertThat(query.watermarkNanos()).contains(5_000_000_000L);
    }
}
