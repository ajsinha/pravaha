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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

/**
 * What a continuous unwindowed aggregate says on the way out (CKPT-3).
 *
 * <p>Closing a query runs the lanes' finishers and commits what they emit, so the last thing a
 * stateful operator holds reaches the view (W-2). For an unwindowed aggregate that rule needed a
 * qualification it did not have: the aggregate has been publishing its answer on every tick, so
 * the view already holds it, and the finisher re-inserted it with weight {@code +1} and no
 * retraction. The row's weight in the view doubled a moment before the view was discarded, and
 * every subscriber was handed an insert of an answer it already had.
 *
 * <p>The two cases below are the whole of it: nothing has changed since the last published
 * answer, so the close says nothing; something has, so the close says exactly that, as a
 * retraction of the old answer and an insert of the new one.
 */
class ClosedAggregateTest {

    private static final String TOTALS = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry(new ViewCatalog(), TXN);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    @Test
    void aClosedAggregateRepublishesNothingWhenItsAnswerHasNotChanged() {
        RegisteredQuery query = registry.register("spend_totals", TOTALS, List.of(0), DANA);
        txn(query, "u1", 100);
        txn(query, "u2", 250);
        query.commit();
        assertThat(query.view().size()).isEqualTo(1);

        List<ViewChange> onTheWayOut = new ArrayList<>();
        try (Subscription subscription = query.subscribe(onTheWayOut::addAll)) {
            query.close();
            assertThat(onTheWayOut)
                    .as("[2, 350] was published by the commit above and nothing has happened since, so "
                            + "closing the query has nothing to say; it used to say [2, 350] again, with "
                            + "weight +1 and no retraction")
                    .isEmpty();
            assertThat(subscription.delivered()).isZero();
        }
    }

    @Test
    void aClosedAggregatePublishesTheChangeItHasNotPublishedYet() {
        RegisteredQuery query = registry.register("spend_totals", TOTALS, List.of(0), DANA);
        txn(query, "u1", 100);
        txn(query, "u2", 250);
        query.commit();

        List<ViewChange> onTheWayOut = new ArrayList<>();
        try (Subscription subscription = query.subscribe(onTheWayOut::addAll)) {
            // Applied but never published: the tick that would have published it does not come,
            // because the query is closed first. W-2's rule is what carries it to the view.
            txn(query, "u3", 75);
            query.close();

            assertThat(onTheWayOut)
                    .as("the answer really did change, so the close retracts the published one and "
                            + "inserts the new one -- the silence above must be silence about an "
                            + "unchanged answer, not a finisher that stopped emitting")
                    .containsExactly(
                            new ViewChange(new Object[] {2L, 350L}, -1L), new ViewChange(new Object[] {3L, 425L}, 1L));
            assertThat(subscription.delivered()).isEqualTo(2);
        }
    }

    private void txn(RegisteredQuery query, String user, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(Duration.ofSeconds(10));
    }
}
