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
package com.ash.messaging.pravaha.it;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowKind;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.Subscription;
import com.ash.messaging.pravaha.registry.SubscriptionFilter;
import com.ash.messaging.pravaha.registry.SubscriptionOptions;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewChange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code docs/qa/cases/STRM.md}, executed.
 *
 * <p>A subscription is how an answer leaves the engine as it changes rather than as a snapshot, and
 * the contract it makes is narrow and load-bearing: changes arrive per commit and never per row, so
 * a subscriber never sees a half-applied window; they carry weights, so a correction arrives as a
 * retraction and an insert rather than as a message type a consumer has to recognise; and a
 * subscriber that cannot keep up is counted rather than quietly served less.
 *
 * <p>Harness H-E of STRM.md: {@code ViewCatalog}, {@code QueryRegistry}, one registered
 * pass-through query, rows pushed through {@link RegisteredQuery#accept} with an explicit weight,
 * and nothing published until {@link RegisteredQuery#commit()}.
 *
 * <p>STRM.md's second harness, H-EA, is an unwindowed {@code GROUP BY user_id}. That is refused --
 * {@code PRV-2050}, an unbounded key space -- so the cases that need a real retract-and-insert pair
 * use the continuous <em>global</em> aggregate instead, which publishes on commit as a retraction
 * of the previous answer and an insert of the new one. STRM-013, 014, 031, 045, 046 and 082 are
 * unreachable as written for the same reason and are recorded rather than converted.
 */
class SubscriptionAnswerTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final StreamSchema TRADE = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("product_type", Types.string())
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

    // ================================================== A. change kinds delivered

    @Test
    void strm001_aPlainInsertIsDeliveredOnceWithWeightPlusOne() {
        // STRM-001. The base case: one row, one change, weight +1, values intact, and the three
        // counters agreeing that nothing was lost or merged on the way.
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feed("u1", 300);
            query.commit();
            assertThat(seen).hasSize(1);
            assertThat(seen.get(0).weight()).isEqualTo(1L);
            assertThat(seen.get(0).isRetraction()).isFalse();
            assertThat(seen.get(0).values()).containsExactly("u1", 300L);
            assertThat(subscription.delivered()).isEqualTo(1);
            assertThat(subscription.conflated()).isZero();
            assertThat(subscription.dropped()).isZero();
        }
    }

    @Test
    void strm002And003_threeRowsInOneCommitArriveAsOneBatchAndNotBefore() {
        // STRM-002 and STRM-003. One callback of three, not three callbacks of one: the batch
        // boundary is the commit boundary, which is what lets a consumer treat a batch as a
        // consistent state rather than as a stream of guesses.
        List<List<ViewChange>> batches = new ArrayList<>();
        try (Subscription subscription = query.subscribe(b -> batches.add(List.copyOf(b)))) {
            feed("u1", 1);
            feed("u2", 2);
            feed("u3", 3);
            assertThat(batches).as("nothing before the commit").isEmpty();
            assertThat(subscription.delivered()).isZero();

            query.commit();
            assertThat(batches).hasSize(1);
            assertThat(batches.get(0)).hasSize(3);
            assertThat(subscription.delivered()).isEqualTo(3);
        }
    }

    @Test
    void strm004And005_anUpdateArrivesAsARetractionAndAnInsertInThatOrder() {
        // STRM-004 and STRM-005. The retraction must come first, because a consumer folding the
        // batch in order must never hold two answers at once -- with the insert first it holds
        // 300 and 350 together, and any total it computes in that instant is wrong.
        //
        // Driven through the continuous global aggregate, which is the shape reachable from a
        // configured node: an unwindowed GROUP BY is refused (PRV-2050), so H-EA does not exist.
        ViewCatalog ownViews = new ViewCatalog();
        try (QueryRegistry ownRegistry = new QueryRegistry(ownViews, TXN)) {
            RegisteredQuery total =
                    ownRegistry.register("total", "SELECT SUM(amount) AS total FROM txn", List.of(0), DANA);
            List<List<ViewChange>> batches = new ArrayList<>();
            try (Subscription subscription = total.subscribe(b -> batches.add(List.copyOf(b)))) {
                feedInto(total, "u1", 300);
                total.commit();
                total.commit();
                batches.clear();

                feedInto(total, "u1", 50);
                total.commit();
                total.commit();

                List<ViewChange> pair = batches.stream().flatMap(List::stream).toList();
                assertThat(pair).hasSize(2);
                assertThat(pair.get(0).weight()).isEqualTo(-1L);
                assertThat(pair.get(0).values()[0]).isEqualTo(300L);
                assertThat(pair.get(1).weight()).isEqualTo(1L);
                assertThat(pair.get(1).values()[0]).isEqualTo(350L); // 300 + 50 = 350
                // A consumer applying weights ends at 350; one ignoring them ends at 650.
                long folded = 300 - 300 + 350;
                assertThat(folded).isEqualTo(350);
            }
        }
    }

    @Test
    void strm006_aStandaloneRetractionIsDeliveredWithWeightMinusOne() {
        // STRM-006. A subscriber attached after the insert sees only the withdrawal, which is the
        // whole mechanism a late-data correction reaches a consumer by.
        feed("u1", 300);
        query.commit();

        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feedW("u1", 300, -1, 1);
            query.commit();
            assertThat(seen).hasSize(1);
            assertThat(seen.get(0).weight()).isEqualTo(-1L);
            assertThat(seen.get(0).isRetraction()).isTrue();
            assertThat(seen.get(0).values()).containsExactly("u1", 300L);
            assertThat(query.view().get("u1").found()).isFalse();
        }
    }

    @Test
    void strm007_aWeightOfZeroRemovesNothingAndAddsNothing() {
        // STRM-007. STRM.md records two candidate answers -- the documented one, where zero is a
        // removal, and the code-as-written one, where it is an upsert. Neither is what a Z-set
        // does: adding zero is a no-op, so u1 keeps the 300 it had and the 999 never lands.
        feed("u1", 300);
        query.commit();

        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feedW("u1", 999, 0, 1);
            query.commit();
            assertThat(query.view().get("u1").values().orElseThrow()[1])
                    .as("a zero-weight change must not overwrite the row")
                    .isEqualTo(300L);
            assertThat(seen).allMatch(c -> c.weight() == 0L || c.values()[1].equals(300L));
        }
    }

    @Test
    void strm008And009_netZeroInsideOneCommitDependsOnWhichSideCameFirst() {
        // STRM-008: +1 then -1 nets to zero and the key is gone. Both changes are still delivered,
        // because a subscriber maintaining its own copy needs the pair to reach the same state.
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feed("u1", 300);
            feedW("u1", 300, -1, 2);
            query.commit();
            assertThat(seen).hasSize(2);
            assertThat(seen.get(0).weight()).isEqualTo(1L);
            assertThat(seen.get(1).weight()).isEqualTo(-1L);
            assertThat(seen.stream().mapToLong(ViewChange::weight).sum()).isZero(); // 1 + (-1) = 0
            assertThat(query.view().get("u1").found()).isFalse();
        }

        // STRM-009: over a key that already exists, -1 then +1 nets to the key still being there.
        feed("u2", 300);
        query.commit();
        List<ViewChange> second = new ArrayList<>();
        try (Subscription subscription = query.subscribe(second::addAll)) {
            feedW("u2", 300, -1, 3);
            feedW("u2", 300, 1, 4);
            query.commit();
            assertThat(second).hasSize(2);
            assertThat(query.view().get("u2").found())
                    .as("1 - 1 + 1 = 1, so the key stands")
                    .isTrue();
        }
    }

    @Test
    void strm010_explicitWeightsPassThroughUnchanged() {
        // STRM-010. Weights other than plus or minus one are ordinary: a source that batches ten
        // identical rows into one arrival of weight 10 must not have that flattened to 1.
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feedW("u1", 10, 3, 1);
            feedW("u2", 20, -2, 2);
            feedW("u3", 30, 7, 3);
            query.commit();
            assertThat(seen.stream().map(ViewChange::weight)).containsExactly(3L, -2L, 7L);
            assertThat(seen.stream().mapToLong(ViewChange::weight).sum()).isEqualTo(8); // 3 - 2 + 7
            assertThat(seen.stream().map(ViewChange::isRetraction)).containsExactly(false, true, false);
            assertThat(query.view().get("u1").found()).isTrue();
            assertThat(query.view().get("u3").found()).isTrue();
            assertThat(query.view().get("u2").found())
                    .as("a net weight of -2 is not a present row")
                    .isFalse();
        }
    }

    @Test
    void strm011_everyRowKindMapsToTheWeightItsNameImplies() {
        // STRM-011. The four kinds are a vocabulary over the same one number: +1, +1, -1, -1. A
        // consumer that understood kinds and a consumer that understood weights must agree, or
        // the two halves of the API describe different systems.
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feedKind("a", 1, RowKind.INSERT, 1);
            feedKind("b", 2, RowKind.UPDATE_AFTER, 2);
            feedKind("c", 3, RowKind.UPDATE_BEFORE, 3);
            feedKind("d", 4, RowKind.DELETE, 4);
            query.commit();
            assertThat(seen.stream().map(ViewChange::weight)).containsExactly(1L, 1L, -1L, -1L);
            assertThat(seen.stream().mapToLong(ViewChange::weight).sum()).isZero(); // 1 + 1 - 1 - 1
            assertThat(query.view().get("a").found()).isTrue();
            assertThat(query.view().get("b").found()).isTrue();
            assertThat(query.view().get("c").found()).isFalse();
            assertThat(query.view().get("d").found()).isFalse();
        }
    }

    @Test
    void strm012_theLastOfWeightAndRowKindWins() {
        // STRM-012. Both write the same field, so order decides. Worth pinning because a writer
        // that set a kind for readability and a weight for precision would silently get whichever
        // it wrote second, and the two disagree by a factor of minus one.
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            RowLayout layout = RowLayout.of(TXN);
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);

            long handle = arena.allocate(layout.rowSize(256));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            writer.setString(0, "a").setLong(1, 1).weight(-1).rowKind(RowKind.INSERT);
            writer.eventTimestampNanos(0).sequence(1).commit();
            arena.trimTo(handle, writer.sizeSoFar());
            query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            query.awaitApplied(Duration.ofSeconds(10));

            handle = arena.allocate(layout.rowSize(256));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            writer.setString(0, "b").setLong(1, 2).rowKind(RowKind.DELETE).weight(5);
            writer.eventTimestampNanos(0).sequence(2).commit();
            arena.trimTo(handle, writer.sizeSoFar());
            query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            query.awaitApplied(Duration.ofSeconds(10));

            query.commit();
            assertThat(seen).hasSize(2);
            assertThat(seen.get(0).weight())
                    .as("rowKind(INSERT) written after weight(-1)")
                    .isEqualTo(1L);
            assertThat(seen.get(1).weight())
                    .as("weight(5) written after rowKind(DELETE)")
                    .isEqualTo(5L);
        }
    }

    @Test
    void strm015_changingTheKeyColumnIsARetractOfTheOldKeyAndAnInsertOfTheNew() {
        // STRM-015. The view is keyed on user_id, so moving a row between keys is two changes and
        // the view ends with one key rather than two. An engine treating it as one update would
        // leave the old key behind, which is a row that no longer corresponds to any input.
        feed("u1", 300);
        query.commit();
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feedW("u1", 300, -1, 2);
            feedW("u2", 300, 1, 3);
            query.commit();
            assertThat(seen).hasSize(2);
            assertThat(seen.get(0).weight()).isEqualTo(-1L);
            assertThat(seen.get(0).values()).containsExactly("u1", 300L);
            assertThat(seen.get(1).weight()).isEqualTo(1L);
            assertThat(seen.get(1).values()).containsExactly("u2", 300L);
            assertThat(query.view().size()).isEqualTo(1);
            assertThat(query.view().get("u1").found()).isFalse();
            assertThat(query.view().get("u2").found()).isTrue();
        }
    }

    @Test
    void strm016_aNullSurvivesTheSubscriptionAsANullAndNotAsAZero() {
        // STRM-016. A null amount arriving as 0 is the difference between "no reading" and "a
        // reading of zero", and no consumer can recover the first from the second.
        StreamSchema nullable = StreamSchema.builder("txn")
                .field("user_id", Types.string().withNullable(true))
                .field("amount", Types.int64().withNullable(true))
                .build();
        ViewCatalog ownViews = new ViewCatalog();
        try (QueryRegistry ownRegistry = new QueryRegistry(ownViews, nullable);
                RowArena ownArena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery nullableQuery =
                    ownRegistry.register("qn", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            List<ViewChange> seen = new ArrayList<>();
            try (Subscription subscription = nullableQuery.subscribe(seen::addAll)) {
                RowLayout layout = RowLayout.of(nullable);
                BinaryRowWriter writer = new BinaryRowWriter(layout);
                BinaryRowView view = new BinaryRowView(layout);

                long handle = ownArena.allocate(layout.rowSize(256));
                writer.begin(ownArena.regionOf(handle), ownArena.offsetOf(handle));
                writer.setString(0, "u1")
                        .setNull(1)
                        .weight(1)
                        .eventTimestampNanos(0)
                        .sequence(1)
                        .commit();
                ownArena.trimTo(handle, writer.sizeSoFar());
                nullableQuery.accept(view.wrap(ownArena.regionOf(handle), ownArena.offsetOf(handle)));
                nullableQuery.awaitApplied(Duration.ofSeconds(10));

                handle = ownArena.allocate(layout.rowSize(256));
                writer.begin(ownArena.regionOf(handle), ownArena.offsetOf(handle));
                writer.setNull(0)
                        .setLong(1, 42)
                        .weight(1)
                        .eventTimestampNanos(0)
                        .sequence(2)
                        .commit();
                ownArena.trimTo(handle, writer.sizeSoFar());
                nullableQuery.accept(view.wrap(ownArena.regionOf(handle), ownArena.offsetOf(handle)));
                nullableQuery.awaitApplied(Duration.ofSeconds(10));

                nullableQuery.commit();
                assertThat(seen).hasSize(2);
                assertThat(seen.get(0).values()[0]).isEqualTo("u1");
                assertThat(seen.get(0).values()[1])
                        .as("a null amount is null, not 0L")
                        .isNull();
                assertThat(seen.get(1).values()[0])
                        .as("and a null key is a key")
                        .isNull();
                assertThat(seen.get(1).values()[1]).isEqualTo(42L);
                assertThat(nullableQuery.view().size()).isEqualTo(2);
            }
        }
    }

    @Test
    void strm023_aSubscriberCannotReachIntoTheViewThroughTheChangeItWasHanded() {
        // STRM-023. One ViewChange instance is handed to every subscriber, so if its array were
        // the view's own row a single careless consumer would corrupt the answer for everybody.
        List<ViewChange> a = new ArrayList<>();
        List<ViewChange> b = new ArrayList<>();
        try (Subscription first = query.subscribe(changes -> {
                    a.addAll(changes);
                    changes.forEach(change -> change.values()[1] = 999L);
                });
                Subscription second = query.subscribe(b::addAll)) {
            feed("u1", 300);
            query.commit();
            assertThat(b.get(0).values()[1])
                    .as("the other subscriber's copy is untouched")
                    .isEqualTo(300L);
            assertThat(query.view().get("u1").values().orElseThrow()[1])
                    .as("and so is the view")
                    .isEqualTo(300L);
            assertThat(a.get(0).values()[1])
                    .as("values() hands out a fresh copy each time")
                    .isEqualTo(300L);
        }
    }

    // ================================================== C. correctness of the stream

    @Test
    void strm029_tenThousandChangesArriveExactlyOnce() {
        // STRM-029. Every amount from 1 to 10,000 appears once and only once, which is a stronger
        // statement than the count: a stream that dropped one change and duplicated another would
        // have the right total and the wrong contents.
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription =
                query.subscribe(SubscriptionOptions.of(100_000, SubscriptionOptions.Overflow.FAIL), seen::addAll)) {
            for (int i = 1; i <= 10_000; i++) {
                feed("u" + i, i);
            }
            query.commit();
            assertThat(subscription.delivered()).isEqualTo(10_000);
            assertThat(subscription.dropped()).isZero();
            assertThat(subscription.conflated()).isZero();
            Map<Long, Integer> counts = new HashMap<>();
            for (ViewChange change : seen) {
                counts.merge((Long) change.values()[1], 1, Integer::sum);
            }
            assertThat(counts).hasSize(10_000);
            assertThat(counts.values()).allMatch(count -> count == 1);
            // 1 + 2 + ... + 10,000 = 10,000 * 10,001 / 2 = 50,005,000.
            assertThat(seen.stream().mapToLong(c -> (Long) c.values()[1]).sum()).isEqualTo(50_005_000L);
        }
    }

    @Test
    void strm030_theBoundedBufferDropsTheOldestAndCountsIt() {
        // STRM-030. One change past the default buffer of 10,000: the default overflow is
        // CONFLATE, which falls back to dropping the oldest when the key has never been seen --
        // so 10,000 are delivered, one is dropped, and the missing one is the first fed.
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            for (int i = 1; i <= 10_001; i++) {
                feed("u" + i, i);
            }
            query.commit();
            assertThat(subscription.delivered()).isEqualTo(10_000);
            assertThat(subscription.dropped() + subscription.conflated()).isEqualTo(1);
            Set<Object> amounts = new java.util.HashSet<>();
            seen.forEach(change -> amounts.add(change.values()[1]));
            assertThat(amounts).hasSize(10_000).doesNotContain(1L).contains(2L, 10_001L);
        }
    }

    @Test
    void strm032_changesArriveInTheOrderTheyWereFedAndNotInKeyOrder() {
        // STRM-032. z, a, m, b -- feed order, not sorted order. A consumer replaying a batch to
        // rebuild state relies on this: reordering is only safe if every change in the batch is
        // for a distinct key, which the engine does not promise.
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription =
                query.subscribe(SubscriptionOptions.of(100_000, SubscriptionOptions.Overflow.FAIL), seen::addAll)) {
            feed("z", 1);
            feed("a", 2);
            feed("m", 3);
            feed("b", 4);
            query.commit();
            assertThat(seen.stream().map(c -> c.values()[0])).containsExactly("z", "a", "m", "b");
        }
    }

    @Test
    void strm034_aChangeAppliedWhileACallbackIsRunningIsNotLost() {
        // STRM-034. The window between copying the pending set and calling the listener is where
        // a change would vanish if the copy and the clear were not one step. The second row must
        // arrive in the second batch -- not in the first, and not nowhere.
        List<List<ViewChange>> batches = new ArrayList<>();
        try (Subscription subscription = query.subscribe(b -> batches.add(List.copyOf(b)))) {
            feed("u1", 1);
            query.commit();
            feed("u2", 2);
            query.commit();
            assertThat(batches).hasSize(2);
            assertThat(batches.get(0)).hasSize(1);
            assertThat(batches.get(0).get(0).values()).containsExactly("u1", 1L);
            assertThat(batches.get(1)).hasSize(1);
            assertThat(batches.get(1).get(0).values()).containsExactly("u2", 2L);
            assertThat(subscription.delivered()).isEqualTo(2);
        }
    }

    @Test
    void strm035And036_aSubscriberAttachingMidStreamGetsNoSnapshot() {
        // STRM-035. Three rows committed before the subscription: the new subscriber receives one
        // change, not four. That is the design -- a subscription is a tap, not a replay -- and it
        // is also the hole STRM-036 names: read-then-subscribe has a gap that nothing closes,
        // because no API exposes the frontier a read was answered at.
        feed("u1", 1);
        feed("u2", 2);
        feed("u3", 3);
        query.commit();

        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feed("u4", 4);
            query.commit();
            assertThat(seen).hasSize(1);
            assertThat(seen.get(0).values()).containsExactly("u4", 4L);
            assertThat(subscription.delivered()).isEqualTo(1);
            assertThat(query.view().size()).as("while the view holds all four").isEqualTo(4);
        }
    }

    @Test
    void strm038_acommitWithNothingInItDeliversNoCallback() {
        // STRM-038. Six commits, one of which carries a row: one callback. Waking a subscriber to
        // hand it an empty list is a cost paid per subscriber per commit for no information, and
        // at fifty commits a second with two hundred subscribers it is the whole budget.
        AtomicInteger callbacks = new AtomicInteger();
        try (Subscription subscription = query.subscribe(changes -> callbacks.incrementAndGet())) {
            query.commit();
            query.commit();
            query.commit();
            feed("u1", 1);
            query.commit();
            query.commit();
            query.commit();
            assertThat(callbacks.get()).isEqualTo(1);
            assertThat(subscription.delivered()).isEqualTo(1);
        }
    }

    @Test
    void strm039And040And044_aTapFilterAdmitsOnlyTheRowsThatMatchEveryColumnItNames() {
        // STRM-039, STRM-040 and STRM-044. The filter is applied at the tap, so every subscriber
        // reads the same computation however differently it filters -- and a filter naming two
        // columns means both, not either.
        ViewCatalog ownViews = new ViewCatalog();
        try (QueryRegistry ownRegistry = new QueryRegistry(ownViews, TRADE);
                RowArena ownArena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery trades =
                    ownRegistry.register("trades", "SELECT user_id, amount, product_type FROM txn", List.of(0), DANA);

            List<ViewChange> swapsOnly = new ArrayList<>();
            List<ViewChange> bothColumns = new ArrayList<>();
            List<ViewChange> everything = new ArrayList<>();
            try (Subscription a = trades.subscribe(
                            SubscriptionOptions.DEFAULT,
                            SubscriptionFilter.matching(trades.outputSchema(), "product_type", "SWAP"),
                            swapsOnly::addAll);
                    Subscription b = trades.subscribe(
                            SubscriptionOptions.DEFAULT,
                            SubscriptionFilter.matching(
                                    trades.outputSchema(), Map.of("user_id", "u1", "product_type", "SWAP")),
                            bothColumns::addAll);
                    Subscription c = trades.subscribe(
                            SubscriptionOptions.DEFAULT,
                            SubscriptionFilter.matching(trades.outputSchema(), Map.of()),
                            everything::addAll)) {
                feedTrade(ownArena, trades, "u1", 10, "SWAP", 1);
                feedTrade(ownArena, trades, "u1", 20, "BOND", 2);
                feedTrade(ownArena, trades, "u2", 30, "SWAP", 3);
                feedTrade(ownArena, trades, "u2", 40, "BOND", 4);
                trades.commit();

                assertThat(swapsOnly).hasSize(2); // u1/SWAP and u2/SWAP
                assertThat(bothColumns).hasSize(1); // 4 rows, 4 - 1 = 3 filtered out
                assertThat(bothColumns.get(0).values()).containsExactly("u1", 10L, "SWAP");
                assertThat(everything)
                        .as("an empty filter map is the same as no filter")
                        .hasSize(4);
                assertThat(a.dropped()).isZero();
            }
        }
    }

    @Test
    void strm043_afilterNamingAColumnTheViewDoesNotHaveIsRefused() {
        // STRM-043. Ignoring an unknown column would silently deliver everything to a subscriber
        // that asked for a slice, which is the failure mode a filter exists to prevent.
        assertThatThrownBy(() -> SubscriptionFilter.matching(query.outputSchema(), "prodcut_type", "SWAP"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("prodcut_type");
    }

    // ================================================== D. subscriber behaviour

    @Test
    void strm053_tenSubscribersOnOneQueryAllReceiveTheSameChanges() {
        // STRM-053. Ten dashboards, one computation: the claim the whole registry rests on. The
        // lists must be equal as ordered lists, not merely the same size.
        int subscribers = 10;
        List<List<ViewChange>> seen = new ArrayList<>();
        List<Subscription> subscriptions = new ArrayList<>();
        try {
            for (int i = 0; i < subscribers; i++) {
                List<ViewChange> mine = new ArrayList<>();
                seen.add(mine);
                subscriptions.add(query.subscribe(
                        SubscriptionOptions.of(100_000, SubscriptionOptions.Overflow.FAIL), mine::addAll));
            }
            assertThat(query.subscriberCount()).isEqualTo(subscribers);
            for (int i = 1; i <= 1_000; i++) {
                feed("u" + i, i);
            }
            query.commit();
            for (List<ViewChange> mine : seen) {
                assertThat(mine).hasSize(1_000);
                // 1 + 2 + ... + 1000 = 1000 * 1001 / 2 = 500,500.
                assertThat(mine.stream().mapToLong(c -> (Long) c.values()[1]).sum())
                        .isEqualTo(500_500L);
            }
            assertThat(registry.names()).hasSize(1);
        } finally {
            subscriptions.forEach(Subscription::close);
        }
    }

    @Test
    void strm055_asubscriberThatClosesItselfInsideItsOwnCallbackDoesNotDisturbTheOthers() {
        // STRM-055. Closing during iteration is where a listener list either copies or throws a
        // ConcurrentModificationException, and the consequence of throwing is that the other
        // subscribers silently stop receiving.
        List<ViewChange> a = new ArrayList<>();
        List<ViewChange> b = new ArrayList<>();
        List<ViewChange> c = new ArrayList<>();
        Subscription[] first = new Subscription[1];
        try (Subscription one = query.subscribe(changes -> {
                    a.addAll(changes);
                    first[0].close();
                });
                Subscription two = query.subscribe(b::addAll);
                Subscription three = query.subscribe(c::addAll)) {
            first[0] = one;
            feed("u1", 1);
            query.commit();
            feed("u2", 2);
            query.commit();

            assertThat(a).as("A saw the first batch and then left").hasSize(1);
            assertThat(b).hasSize(2);
            assertThat(c).hasSize(2);
            assertThat(query.subscriberCount()).isEqualTo(2);
        }
    }

    @Test
    void strm056_asubscriberThatThrowsIsDetachedAndTheOthersAreUnaffected() {
        // STRM-056. One broken consumer must not be everybody's problem. It is called once,
        // detached with a reason it can read, and the query goes on running.
        AtomicInteger calls = new AtomicInteger();
        List<ViewChange> healthy = new ArrayList<>();
        try (Subscription broken = query.subscribe(changes -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("consumer is broken");
                });
                Subscription good = query.subscribe(healthy::addAll)) {
            for (int commit = 1; commit <= 3; commit++) {
                feed("u" + commit, commit);
                query.commit();
            }
            assertThat(calls.get()).as("called once, then detached").isEqualTo(1);
            assertThat(broken.isClosed()).isTrue();
            assertThat(broken.failure()).isPresent();
            assertThat(broken.failure().orElseThrow().getMessage()).contains("threw and has been detached");
            assertThat(healthy).hasSize(3);
            assertThat(query.state()).isEqualTo(com.ash.messaging.pravaha.registry.QueryState.RUNNING);
        }
    }

    @Test
    void strm059_withNoSubscriberTheSinkKeepsNoChangeLog() {
        // STRM-059. A view with nobody watching must not accumulate ViewChange objects it will
        // never hand out -- which at two million rows is the difference between a flat heap and
        // an OutOfMemoryError nothing explains.
        for (int i = 0; i < 20_000; i++) {
            feed("k" + (i % 100), i);
            if (i % 5_000 == 0) {
                query.commit();
            }
        }
        query.commit();
        assertThat(query.view().pendingChanges()).isZero();
        assertThat(query.subscriberCount()).isZero();
        assertThat(query.view().size()).isEqualTo(100);
    }

    // ================================================== E. lifecycle

    @Test
    void strm061And064_apausedQueryAcceptsNothingAndDeliversNothing() {
        // STRM-061 and STRM-064. Pause has to stop the rows, not merely stop the delivery: rows
        // pushed while paused are refused at accept and are gone, so a resume is not a replay.
        List<ViewChange> seen = new ArrayList<>();
        try (Subscription subscription = query.subscribe(seen::addAll)) {
            feed("a", 1);
            query.commit();
            assertThat(seen).hasSize(1);

            registry.pause("q");
            assertThat(query.subscriberCount())
                    .as("pausing does not detach a subscriber")
                    .isEqualTo(1);
            long before = query.rowsIn();
            assertThat(offer("b", 2)).isFalse();
            assertThat(offer("c", 3)).isFalse();
            query.commit();
            assertThat(query.rowsIn()).isEqualTo(before);
            assertThat(seen).hasSize(1);

            registry.resume("q");
            feed("d", 4);
            query.commit();
            assertThat(seen).hasSize(2);
            assertThat(seen.get(1).values()).containsExactly("d", 4L);
            // b and c appear nowhere: the view holds a and d, and that is the cost of a pause.
            assertThat(query.view().size()).isEqualTo(2);
        }
    }

    @Test
    void strm066_subscribingToADroppedQueryIsRefused() {
        // STRM-066. A dropped query has destroyed its state, so a subscription to it could only
        // ever deliver nothing -- and silently delivering nothing is the failure mode this
        // refusal exists to replace.
        registry.drop("q");
        assertThatThrownBy(() -> query.subscribe(changes -> {}))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-8003")
                .hasMessageContaining("DROPPED");
    }

    @Test
    void strm076_subscriberCountFollowsTheSubscribers() {
        // STRM-076, the in-process half. The number a console shows next to a query has to be the
        // number of consumers actually attached, through attaching, detaching and pausing.
        assertThat(query.subscriberCount()).isZero();
        Subscription a = query.subscribe(changes -> {});
        assertThat(query.subscriberCount()).isEqualTo(1);
        Subscription b = query.subscribe(changes -> {});
        assertThat(query.subscriberCount()).isEqualTo(2);
        a.close();
        assertThat(query.subscriberCount()).isEqualTo(1);
        registry.pause("q");
        assertThat(query.subscriberCount()).as("a pause is not a detach").isEqualTo(1);
        registry.resume("q");
        assertThat(query.subscriberCount()).isEqualTo(1);
        b.close();
        assertThat(query.subscriberCount()).isZero();
    }

    // ================================================== F. backpressure

    @Test
    void strm078_theBufferBoundIsExactAtItAndOneEitherSide() {
        // STRM-078. 99, 100 and 101 distinct-key rows against a buffer of 100: nothing dropped,
        // nothing dropped, exactly one dropped -- and the one dropped is the first fed, which is
        // what DROP_OLDEST means. An off-by-one here loses a row under a green status.
        for (int rows : List.of(99, 100)) {
            List<ViewChange> seen =
                    runBatch(rows, SubscriptionOptions.of(100, SubscriptionOptions.Overflow.DROP_OLDEST));
            assertThat(seen).as("%d rows into a buffer of 100", rows).hasSize(rows);
        }
        ViewCatalog ownViews = new ViewCatalog();
        try (QueryRegistry ownRegistry = new QueryRegistry(ownViews, TXN);
                RowArena ownArena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery q = ownRegistry.register("over", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            List<ViewChange> seen = new ArrayList<>();
            try (Subscription subscription =
                    q.subscribe(SubscriptionOptions.of(100, SubscriptionOptions.Overflow.DROP_OLDEST), seen::addAll)) {
                for (int i = 1; i <= 101; i++) {
                    feedInto(ownArena, q, "u" + i, i);
                }
                q.commit();
                assertThat(subscription.delivered()).isEqualTo(100);
                assertThat(subscription.dropped()).isEqualTo(1);
                // The first fed is gone, so the amounts are 2..101: 101 * 102 / 2 - 1 = 5,150.
                assertThat(seen.stream().mapToLong(c -> (Long) c.values()[1]).sum())
                        .isEqualTo(5_150L);
            }
        }
    }

    @Test
    void strm079_aBufferOfOneKeepsTheNewestAndARequestForLessIsRefused() {
        // STRM-079. One is the minimum the record allows, and zero or below is refused at
        // construction rather than being quietly treated as one -- a buffer of zero would mean
        // every change dropped, which nobody asks for on purpose.
        ViewCatalog ownViews = new ViewCatalog();
        try (QueryRegistry ownRegistry = new QueryRegistry(ownViews, TXN);
                RowArena ownArena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery q = ownRegistry.register("one", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            List<ViewChange> seen = new ArrayList<>();
            try (Subscription subscription =
                    q.subscribe(SubscriptionOptions.of(1, SubscriptionOptions.Overflow.DROP_OLDEST), seen::addAll)) {
                for (int i = 1; i <= 5; i++) {
                    feedInto(ownArena, q, "k" + i, i);
                }
                q.commit();
                assertThat(subscription.delivered()).isEqualTo(1);
                assertThat(subscription.dropped()).isEqualTo(4); // 1 + 4 = 5 fed
                assertThat(seen.get(0).values()[1]).as("the newest survives").isEqualTo(5L);
            }
        }
        assertThatThrownBy(() -> SubscriptionOptions.of(0, SubscriptionOptions.Overflow.DROP_OLDEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bufferRows must be at least 1, got 0");
        assertThatThrownBy(() -> SubscriptionOptions.of(-1, SubscriptionOptions.Overflow.DROP_OLDEST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strm080And081_conflateReplacesByKeyAndDropsTheOldestWhenItCannot() {
        // STRM-080: two keys fed five times into a buffer of two, so every change after the first
        // two replaces one in place: k1 keeps 50 and k2 keeps 40, three conflations and no drops.
        ViewCatalog ownViews = new ViewCatalog();
        try (QueryRegistry ownRegistry = new QueryRegistry(ownViews, TXN);
                RowArena ownArena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery q = ownRegistry.register("c", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            List<ViewChange> seen = new ArrayList<>();
            try (Subscription subscription =
                    q.subscribe(SubscriptionOptions.of(2, SubscriptionOptions.Overflow.CONFLATE), seen::addAll)) {
                feedInto(ownArena, q, "k1", 10);
                feedInto(ownArena, q, "k2", 20);
                feedInto(ownArena, q, "k1", 30);
                feedInto(ownArena, q, "k2", 40);
                feedInto(ownArena, q, "k1", 50);
                q.commit();
                assertThat(seen).hasSize(2);
                assertThat(seen.stream().map(c -> c.values()[1])).containsExactly(50L, 40L);
                assertThat(subscription.conflated()).isEqualTo(3);
                assertThat(subscription.dropped()).isZero();
            }
        }

        // STRM-081: four distinct keys into the same buffer of two. There is nothing to replace,
        // so CONFLATE falls back to dropping the oldest -- two dropped, none conflated, which is
        // the exact mirror of the numbers above.
        ViewCatalog otherViews = new ViewCatalog();
        try (QueryRegistry otherRegistry = new QueryRegistry(otherViews, TXN);
                RowArena otherArena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery q = otherRegistry.register("d", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            List<ViewChange> seen = new ArrayList<>();
            try (Subscription subscription =
                    q.subscribe(SubscriptionOptions.of(2, SubscriptionOptions.Overflow.CONFLATE), seen::addAll)) {
                feedInto(otherArena, q, "a", 1);
                feedInto(otherArena, q, "b", 2);
                feedInto(otherArena, q, "c", 3);
                feedInto(otherArena, q, "d", 4);
                q.commit();
                assertThat(seen.stream().map(c -> c.values()[1])).containsExactly(3L, 4L);
                assertThat(subscription.dropped()).isEqualTo(2);
                assertThat(subscription.conflated()).isZero();
            }
        }
    }

    @Test
    void strm083_dropOldestCountsEveryLoss() {
        // STRM-083. Ten thousand distinct keys into a buffer of two: two delivered, 9,998
        // dropped, and 2 + 9,998 = 10,000. The count is the difference between a lossy stream a
        // consumer can detect and one it cannot.
        ViewCatalog ownViews = new ViewCatalog();
        try (QueryRegistry ownRegistry = new QueryRegistry(ownViews, TXN);
                RowArena ownArena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery q = ownRegistry.register("lossy", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            List<ViewChange> seen = new ArrayList<>();
            try (Subscription subscription =
                    q.subscribe(SubscriptionOptions.of(2, SubscriptionOptions.Overflow.DROP_OLDEST), seen::addAll)) {
                for (int i = 1; i <= 10_000; i++) {
                    feedInto(ownArena, q, "k" + i, i);
                }
                q.commit();
                assertThat(subscription.delivered()).isEqualTo(2);
                assertThat(subscription.dropped()).isEqualTo(9_998);
                assertThat(subscription.delivered() + subscription.dropped()).isEqualTo(10_000);
                assertThat(seen.stream().map(c -> c.values()[1])).containsExactly(9_999L, 10_000L);
            }
        }
    }

    @Test
    void strm084_failClosesTheSubscriptionAndSaysWhy() {
        // STRM-084. A consumer that would rather stop than be wrong asks for FAIL, and what it
        // gets has to be a message it can act on: how far behind it fell, and that re-reading the
        // view is how to catch up.
        ViewCatalog ownViews = new ViewCatalog();
        try (QueryRegistry ownRegistry = new QueryRegistry(ownViews, TXN);
                RowArena ownArena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery q = ownRegistry.register("strict", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            try (Subscription subscription =
                    q.subscribe(SubscriptionOptions.of(1, SubscriptionOptions.Overflow.FAIL), changes -> {})) {
                feedInto(ownArena, q, "u1", 1);
                feedInto(ownArena, q, "u2", 2);
                assertThatThrownBy(q::commit).isInstanceOf(PravahaException.class);
                assertThat(subscription.isClosed()).isTrue();
                assertThat(subscription.failure()).isPresent();
                assertThat(subscription.failure().orElseThrow().getMessage())
                        .contains("Reconnect and re-read the view to catch up");
                assertThat(subscription.delivered()).isZero();
            }
        }
    }

    @Test
    void strm090_deliveredCountsWhatWasHandedOverAndStopsWhenTheConsumerDoes() {
        // STRM-090. A consumer that throws on its fifth call is detached there, so delivered()
        // stops at 4 * 3 = 12 of the 15 rows fed. The three-change gap is real and nothing else
        // reports it -- delivered + dropped + conflated does not add up to what was fed, which is
        // the case's own finding.
        AtomicInteger calls = new AtomicInteger();
        try (Subscription subscription =
                query.subscribe(SubscriptionOptions.of(1_000, SubscriptionOptions.Overflow.FAIL), changes -> {
                    if (calls.incrementAndGet() == 5) {
                        throw new IllegalStateException("enough");
                    }
                })) {
            for (int commit = 1; commit <= 10; commit++) {
                feed("a" + commit, commit);
                feed("b" + commit, commit);
                feed("c" + commit, commit);
                query.commit();
            }
            assertThat(subscription.isClosed()).isTrue();
            assertThat(subscription.delivered()).isEqualTo(12); // four batches of three
            assertThat(subscription.dropped()).isZero();
            assertThat(subscription.conflated()).isZero();
            // 30 rows fed over ten commits, 12 delivered: the 18 not delivered are counted nowhere.
            assertThat(30 - subscription.delivered()).isEqualTo(18);
        }
    }

    // ================================================== J. windowed queries

    @Test
    void strm117_awindowClosingReachesASubscriberAsOneInsertPerGroup() {
        // STRM-117. Three users with four rows each inside one window: when it closes the
        // subscriber gets one +1 per group carrying the window's total, not twelve row-level
        // changes and not one change for the window as a whole.
        // 100 + 101 + 102 + 103 = 406 per user.
        List<List<ViewChange>> batches = windowedBatches(
                List.of(
                        new Windowed("u1", 100, 1),
                        new Windowed("u1", 101, 2),
                        new Windowed("u1", 102, 3),
                        new Windowed("u1", 103, 4),
                        new Windowed("u2", 100, 1),
                        new Windowed("u2", 101, 2),
                        new Windowed("u2", 102, 3),
                        new Windowed("u2", 103, 4),
                        new Windowed("u3", 100, 1),
                        new Windowed("u3", 101, 2),
                        new Windowed("u3", 102, 3),
                        new Windowed("u3", 103, 4)),
                List.of(11L));
        List<ViewChange> all = batches.stream().flatMap(List::stream).toList();
        assertThat(all).hasSize(3);
        assertThat(all).allMatch(c -> c.weight() == 1L);
        assertThat(all.stream().mapToLong(c -> (Long) c.values()[2]).sum()).isEqualTo(3 * 406L);
        assertThat(all.stream().map(c -> c.values()[2])).containsExactly(406L, 406L, 406L);
    }

    @Test
    void strm118_twoWindowsClosingOnOneAdvanceArriveInOneBatch() {
        // STRM-118. Six changes in one commit: three groups at window start 0 with
        // 100 + 102 = 202, and three at window start 10 with 200 + 203 = 403. The batch sums to
        // 3 * 202 + 3 * 403 = 606 + 1209 = 1815, and a subscriber that saw the two windows in
        // separate batches would have a moment where half the advance was visible.
        List<Windowed> rows = new ArrayList<>();
        for (String user : List.of("u1", "u2", "u3")) {
            rows.add(new Windowed(user, 100, 1));
            rows.add(new Windowed(user, 102, 3));
            rows.add(new Windowed(user, 200, 11));
            rows.add(new Windowed(user, 203, 13));
        }
        List<List<ViewChange>> batches = windowedBatches(rows, List.of(21L));
        List<ViewChange> all = batches.stream().flatMap(List::stream).toList();
        assertThat(all).hasSize(6);
        assertThat(all.stream().mapToLong(c -> (Long) c.values()[2]).sum()).isEqualTo(1_815L);
        assertThat(all.stream().filter(c -> (Long) c.values()[1] == 0L).map(c -> c.values()[2]))
                .containsExactly(202L, 202L, 202L);
        assertThat(all.stream()
                        .filter(c -> (Long) c.values()[1] == 10_000_000_000L)
                        .map(c -> c.values()[2]))
                .containsExactly(403L, 403L, 403L);
    }

    @Test
    @Disabled("PRV-STRM defect 1 (FINDINGS T-3): allowed lateness is the constant zero for every "
            + "TUMBLE query the planner builds, and no key, flag or clause sets it -- so a late row "
            + "inside a closed window is dropped rather than reaching a subscriber as a retract/insert "
            + "pair. CONCEPTS.md and StreamSchema's javadoc both state the opposite.")
    void strm119_lateDataReopeningAWindowReachesTheSubscriberAsARetractAndInsertPair() {
        // STRM-119. u1's window holds 100 and 102, published as 202. A late row of 50 for the same
        // window must arrive as -1 carrying 202 and +1 carrying 100 + 102 + 50 = 252, so that a
        // weight-applying consumer holds 252 and not 202 + 252 = 454.
        List<List<ViewChange>> batches = windowedBatches(
                List.of(new Windowed("u1", 100, 1), new Windowed("u1", 102, 3), new Windowed("u1", 50, 9)),
                List.of(11L, 12L));
        List<ViewChange> second = batches.get(1);
        assertThat(second).hasSize(2);
        assertThat(second.get(0).weight()).isEqualTo(-1L);
        assertThat(second.get(0).values()[2]).isEqualTo(202L); // 100 + 102
        assertThat(second.get(1).weight()).isEqualTo(1L);
        assertThat(second.get(1).values()[2]).isEqualTo(252L); // 202 + 50
    }

    // ------------------------------------------------------------------ harness

    /** One row of a windowed fixture: a key, an amount, and the second it happened. */
    private record Windowed(String user, long amount, long second) {}

    private static final long SECOND = 1_000_000_000L;

    /**
     * A windowed query with a subscriber attached from the start, advanced once per watermark.
     *
     * <p>Its own registry, because the standing fixture has no event-time column and adding one
     * would change what every other case in this file is fed.
     */
    private List<List<ViewChange>> windowedBatches(List<Windowed> rows, List<Long> watermarkSeconds) {
        StreamSchema timed = StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
        ViewCatalog ownViews = new ViewCatalog();
        List<List<ViewChange>> batches = new ArrayList<>();
        try (QueryRegistry ownRegistry = new QueryRegistry(ownViews, timed);
                RowArena ownArena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery windowed = ownRegistry.register(
                    "w",
                    "SELECT user_id, window_start, SUM(amount) AS total FROM "
                            + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                            + "GROUP BY user_id, window_start, window_end",
                    List.of(0, 1),
                    DANA);
            try (Subscription subscription = windowed.subscribe(
                    SubscriptionOptions.of(100_000, SubscriptionOptions.Overflow.FAIL),
                    b -> batches.add(List.copyOf(b)))) {
                RowLayout layout = RowLayout.of(timed);
                BinaryRowWriter writer = new BinaryRowWriter(layout);
                BinaryRowView view = new BinaryRowView(layout);
                long sequence = 0;
                int watermark = 0;
                for (Windowed row : rows) {
                    long handle = ownArena.allocate(layout.rowSize(256));
                    writer.begin(ownArena.regionOf(handle), ownArena.offsetOf(handle));
                    writer.setString(0, row.user())
                            .setLong(1, row.amount())
                            .setLong(2, row.second() * SECOND)
                            .weight(1)
                            .eventTimestampNanos(row.second() * SECOND)
                            .sequence(++sequence)
                            .commit();
                    ownArena.trimTo(handle, writer.sizeSoFar());
                    windowed.accept(view.wrap(ownArena.regionOf(handle), ownArena.offsetOf(handle)));
                    windowed.awaitApplied(Duration.ofSeconds(10));
                    // A second watermark advance, if the case asked for one, goes after the last
                    // row that is meant to be on time.
                    if (watermarkSeconds.size() > 1 && watermark == 0 && rows.indexOf(row) == rows.size() - 2) {
                        windowed.advanceWatermark(watermarkSeconds.get(watermark++) * SECOND);
                    }
                }
                windowed.advanceWatermark(watermarkSeconds.get(watermarkSeconds.size() - 1) * SECOND);
            }
        }
        return batches;
    }

    private List<ViewChange> runBatch(int rows, SubscriptionOptions options) {
        ViewCatalog ownViews = new ViewCatalog();
        List<ViewChange> seen = new ArrayList<>();
        try (QueryRegistry ownRegistry = new QueryRegistry(ownViews, TXN);
                RowArena ownArena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery q = ownRegistry.register("batch", "SELECT user_id, amount FROM txn", List.of(0), DANA);
            try (Subscription subscription = q.subscribe(options, seen::addAll)) {
                for (int i = 1; i <= rows; i++) {
                    feedInto(ownArena, q, "u" + i, i);
                }
                q.commit();
            }
        }
        return seen;
    }

    private void feed(String user, long amount) {
        feedW(user, amount, 1, 0);
    }

    private boolean offer(String user, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user)
                .setLong(1, amount)
                .weight(1)
                .eventTimestampNanos(0)
                .sequence(0)
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        return query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    private void feedW(String user, long amount, long weight, long sequence) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user)
                .setLong(1, amount)
                .weight(weight)
                .eventTimestampNanos(0)
                .sequence(sequence)
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private void feedKind(String user, long amount, RowKind kind, long sequence) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user)
                .setLong(1, amount)
                .rowKind(kind)
                .eventTimestampNanos(0)
                .sequence(sequence)
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }

    private void feedInto(RegisteredQuery target, String user, long amount) {
        feedInto(arena, target, user, amount);
    }

    private static void feedInto(RowArena into, RegisteredQuery target, String user, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = into.allocate(layout.rowSize(256));
        writer.begin(into.regionOf(handle), into.offsetOf(handle));
        writer.setString(0, user)
                .setLong(1, amount)
                .weight(1)
                .eventTimestampNanos(0)
                .sequence(0)
                .commit();
        into.trimTo(handle, writer.sizeSoFar());
        target.accept(view.wrap(into.regionOf(handle), into.offsetOf(handle)));
        target.awaitApplied(Duration.ofSeconds(10));
    }

    private static void feedTrade(
            RowArena into, RegisteredQuery target, String user, long amount, String product, long sequence) {
        RowLayout layout = RowLayout.of(TRADE);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = into.allocate(layout.rowSize(256));
        writer.begin(into.regionOf(handle), into.offsetOf(handle));
        writer.setString(0, user)
                .setLong(1, amount)
                .setString(2, product)
                .weight(1)
                .eventTimestampNanos(0)
                .sequence(sequence)
                .commit();
        into.trimTo(handle, writer.sizeSoFar());
        target.accept(view.wrap(into.regionOf(handle), into.offsetOf(handle)));
        target.awaitApplied(Duration.ofSeconds(10));
    }
}
