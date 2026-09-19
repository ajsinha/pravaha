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
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Admission control for shared lanes: which lane a registration lands on, and what happens when
 * none will take it (W9-8).
 *
 * <p>Four streams, because a lane carries at most one pipeline per stream and placement across
 * lanes is only observable with queries that are allowed to share.
 */
@Timeout(120)
class SharedLanePlacementTest {

    private static final List<String> STREAMS = List.of("txn", "orders", "clicks", "trades");

    private ViewCatalog views;
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void setUp() {
        views = new ViewCatalog();
        StreamSchema[] schemas = STREAMS.stream()
                .map(name -> StreamSchema.builder(name)
                        .field("user_id", Types.string())
                        .field("amount", Types.int64())
                        .build())
                .toArray(StreamSchema[]::new);
        registry = new QueryRegistry(views, schemas);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void tearDown() {
        registry.close();
        arena.close();
    }

    private RegisteredQuery projectionOver(String name, String stream) {
        return registry.register(name, "SELECT user_id, amount FROM " + stream, List.of(0), Principal.ANONYMOUS);
    }

    /** One row per count: a query that sees a row twice answers two. */
    private RegisteredQuery countOver(String name, String stream) {
        return countOver(name, stream, "");
    }

    /** With a filter, so two counts over one stream are two computations rather than one shared. */
    private RegisteredQuery countOver(String name, String stream, String where) {
        return registry.register(
                name,
                "SELECT COUNT(*) AS n, SUM(amount) AS total FROM " + stream + where,
                List.of(0),
                Principal.ANONYMOUS);
    }

    private void feed(RegisteredQuery query, String stream, String user, long amount) {
        StreamSchema schema = List.of(registry.streams()).stream()
                .filter(s -> s.name().equals(stream))
                .findFirst()
                .orElseThrow();
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user);
        writer.setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
        query.awaitApplied(Duration.ofSeconds(10));
        query.commit();
    }

    private Object[] only(String sql) {
        List<Object[]> rows = new ViewQuery(views).execute(sql).rows();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    @Test
    void eachRegistrationLandsOnTheLeastLoadedLane() {
        registry.multiplexingLanes(3, 10);

        projectionOver("q_txn", "txn");
        projectionOver("q_orders", "orders");
        projectionOver("q_clicks", "clicks");
        projectionOver("q_trades", "trades");

        // Spread before stacking: three empty lanes take one each, and the fourth goes to the lowest
        // index among the three now tied at one. A registry that always chose lane zero -- which is
        // what one shared lane amounted to -- would read [4, 0, 0].
        assertThat(registry.sharedLaneOf("q_txn")).contains(0);
        assertThat(registry.sharedLaneOf("q_orders")).contains(1);
        assertThat(registry.sharedLaneOf("q_clicks")).contains(2);
        assertThat(registry.sharedLaneOf("q_trades")).contains(0);
        assertThat(registry.pipelinesPerSharedLane()).containsExactly(2, 1, 1);
        assertThat(registry.queriesOnOwnLanes()).isZero();
    }

    @Test
    void aRegistrationNoLaneWillTakeGetsALaneOfItsOwnAndADropFreesTheRoom() {
        registry.multiplexingLanes(1, 2);

        projectionOver("q_txn", "txn");
        projectionOver("q_orders", "orders");
        RegisteredQuery third = projectionOver("q_clicks", "clicks");

        // The ceiling holds, and the query over it is still registered and running -- on a lane of
        // its own, as it would be with multiplexing off -- rather than refused.
        assertThat(registry.pipelinesPerSharedLane()).containsExactly(2);
        assertThat(registry.sharedLaneOf("q_clicks")).isEmpty();
        assertThat(registry.queriesOnOwnLanes()).isEqualTo(1);
        assertThat(third.state()).isEqualTo(QueryState.RUNNING);
        assertThat(registry.maxQueriesPerSharedLane()).isEqualTo(2);

        registry.drop("q_orders");
        assertThat(registry.pipelinesPerSharedLane()).containsExactly(1);

        projectionOver("q_trades", "trades");
        assertThat(registry.sharedLaneOf("q_trades"))
                .as("a dropped query's place on the lane is given to the next registration")
                .contains(0);
        assertThat(registry.pipelinesPerSharedLane()).containsExactly(2);
    }

    @Test
    void twoQueriesOverOneStreamNeverShareALaneBecauseEachWouldCountTheOthersRows() {
        registry.multiplexingLanes(1, 10);

        RegisteredQuery first = countOver("first_count", "txn");
        RegisteredQuery other = countOver("other_count", "txn", " WHERE amount >= 0");
        assertThat(other).as("two computations, not one shared").isNotSameAs(first);

        assertThat(registry.pipelinesPerSharedLane()).containsExactly(1);
        assertThat(registry.sharedLaneOf("first_count")).contains(0);
        assertThat(registry.sharedLaneOf("other_count")).isEmpty();

        // Each query is handed one row by its own caller, the way each registration's feed hands it
        // its own copy of the stream. On one lane, dispatch by stream gave each pipeline both copies
        // and each count read two: measured before this rule existed.
        feed(first, "txn", "ann", 100);
        feed(other, "txn", "bob", 250);

        assertThat(only("SELECT n, total FROM first_count")).containsExactly(1L, 100L);
        assertThat(only("SELECT n, total FROM other_count")).containsExactly(1L, 250L);
    }

    @Test
    void queriesOverDifferentStreamsShareALaneAndKeepTheirOwnAnswers() {
        registry.multiplexingLanes(1, 10);

        RegisteredQuery onTxn = countOver("txn_count", "txn");
        RegisteredQuery onOrders = countOver("orders_count", "orders");

        assertThat(registry.pipelinesPerSharedLane()).containsExactly(2);
        assertThat(registry.sharedLaneOf("txn_count")).contains(0);
        assertThat(registry.sharedLaneOf("orders_count")).contains(0);

        feed(onTxn, "txn", "ann", 100);
        feed(onOrders, "orders", "bob", 250);
        feed(onOrders, "orders", "cat", 50);

        assertThat(only("SELECT n, total FROM txn_count")).containsExactly(1L, 100L);
        assertThat(only("SELECT n, total FROM orders_count")).containsExactly(2L, 300L);
    }

    @Test
    void withMultiplexingOffEveryQueryHasALaneOfItsOwn() {
        projectionOver("q_txn", "txn");
        projectionOver("q_orders", "orders");

        assertThat(registry.pipelinesPerSharedLane()).isEmpty();
        assertThat(registry.sharedLaneOf("q_txn")).isEmpty();
        assertThat(registry.queriesOnOwnLanes()).isEqualTo(2);
        assertThat(registry.maxQueriesPerSharedLane()).isZero();
    }

    @Test
    void theEmbeddersSwitchIsOneLaneWithNoCeiling() {
        registry.multiplexingLanes(true);

        projectionOver("q_txn", "txn");
        projectionOver("q_orders", "orders");
        projectionOver("q_clicks", "clicks");

        assertThat(registry.pipelinesPerSharedLane()).containsExactly(3);
    }

    @Test
    void multiplexingIsSettledBeforeTheFirstRegistration() {
        projectionOver("q_txn", "txn");

        assertThatThrownBy(() -> registry.multiplexingLanes(2, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before the first registration");
        assertThatThrownBy(() -> new QueryRegistry(new ViewCatalog()).multiplexingLanes(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
