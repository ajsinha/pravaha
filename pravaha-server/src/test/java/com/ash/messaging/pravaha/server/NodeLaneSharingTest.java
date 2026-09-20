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
package com.ash.messaging.pravaha.server;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.ingest.LaneProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code pravaha.lane.multiplex.*} reaches the registry a node builds (W9-8).
 *
 * <p>The registry has been able to host queries on shared lanes since W9-9 and W9-10, and no node
 * ever asked it to: {@code PravahaNode} never called {@code multiplexingLanes}, so every server ran a
 * lane per query whatever its configuration said. These tests go through the node's builder, so
 * they fail if the setting stops reaching the registry -- which a registry-level test cannot see.
 */
@Timeout(120)
class NodeLaneSharingTest {

    private static StreamCatalog catalog() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build());
        catalog.register(StreamSchema.builder("orders")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build());
        return catalog;
    }

    private static PravahaNode node(boolean multiplex) {
        LaneProperties lanes = new LaneProperties();
        lanes.getMultiplex().setEnabled(multiplex);
        // One shared lane, so two queries that may share one have to.
        lanes.getMultiplex().setLanes(1);
        lanes.getMultiplex().setMaxQueriesPerLane(10);
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        return PravahaNode.builder()
                .withCatalog(catalog())
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("lane-sharing-node")
                .withLanes(lanes)
                .build();
    }

    private static RegisteredQuery countOver(QueryRegistry registry, String name, String stream) {
        return registry.register(
                name, "SELECT COUNT(*) AS n, SUM(amount) AS total FROM " + stream, List.of(0), Principal.ANONYMOUS);
    }

    private static void feed(QueryRegistry registry, RegisteredQuery query, String stream, long amount) {
        StreamSchema schema = List.of(registry.streams()).stream()
                .filter(s -> s.name().equals(stream))
                .findFirst()
                .orElseThrow();
        RowLayout layout = RowLayout.of(schema);
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 1)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            long handle = arena.allocate(layout.rowSize(64));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            writer.setString(0, "u-" + amount);
            writer.setLong(1, amount);
            writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
            arena.trimTo(handle, writer.sizeSoFar());
            assertThat(query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                    .isTrue();
            query.awaitApplied(Duration.ofSeconds(10));
        }
        query.commit();
    }

    /** The one row a count's view holds: its count and its total. */
    private static List<Object> answer(RegisteredQuery query) {
        List<Object[]> rows = query.view().scan();
        assertThat(rows).hasSize(1);
        return List.of(rows.get(0));
    }

    @Test
    void withTheSettingOnTwoRegistrationsShareALaneAndBothAnswersAreRight() {
        PravahaNode node = node(true);
        node.start();
        try {
            QueryRegistry registry = node.registry().orElseThrow();
            RegisteredQuery onTxn = countOver(registry, "txn_totals", "txn");
            RegisteredQuery onOrders = countOver(registry, "order_totals", "orders");

            assertThat(registry.pipelinesPerSharedLane())
                    .as("both registrations on the node's one shared lane")
                    .containsExactly(2);
            assertThat(registry.sharedLaneOf("txn_totals")).contains(0);
            assertThat(registry.sharedLaneOf("order_totals")).contains(0);
            assertThat(registry.queriesOnOwnLanes()).isZero();

            feed(registry, onTxn, "txn", 100);
            feed(registry, onOrders, "orders", 250);
            feed(registry, onOrders, "orders", 50);

            // One inbox, one arena, two streams: each count sees its own stream's rows and only those.
            assertThat(answer(registry.require("txn_totals"))).containsExactly(1L, 100L);
            assertThat(answer(registry.require("order_totals"))).containsExactly(2L, 300L);

            assertThat(node.describe())
                    .as("what an operator reads to see the setting doing something")
                    .contains("lanes: shared, queries per lane [2] of at most 10; 0 on lanes of their own");
        } finally {
            node.stop();
        }
    }

    @Test
    void theNodePublishesHowFullEachSharedLaneIs() {
        PravahaNode node = node(true);
        node.start();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PravahaMetrics metrics = new PravahaMetrics(meters, node);
        try {
            QueryRegistry registry = node.registry().orElseThrow();
            countOver(registry, "txn_totals", "txn");
            countOver(registry, "order_totals", "orders");
            registry.register(
                    "txn_big",
                    "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE amount > 10",
                    List.of(0),
                    Principal.ANONYMOUS);

            metrics.sync();

            assertThat(meters.find("pravaha.lane.shared.queries")
                            .tag("lane", "0")
                            .gauge()
                            .value())
                    .as("all three on the one shared lane, two of them over txn (LANE-2)")
                    .isEqualTo(3);
            assertThat(meters.find("pravaha.lane.own.queries").gauge().value()).isZero();
            assertThat(meters.find("pravaha.lane.shared.bytes").gauge().value())
                    .as("the one shared lane's inbox and arena, held once for three queries")
                    .isEqualTo((double) registry.sharedLaneBytes())
                    .isPositive();

            // B6. The lane's backpressure is read through a query hosted on it, because a hosted
            // query's execution runs on that lane's group -- so the gauge and the query must agree
            // exactly, and this is the assertion that catches the representative being picked
            // wrong or going stale.
            double laneBlocked = meters.find("pravaha.lane.blocked.fraction")
                    .tag("lane", "0")
                    .gauge()
                    .value();
            assertThat(laneBlocked)
                    .as("a lane nothing is queued behind is not blocked, and says 0 rather than nothing")
                    .isZero();
            assertThat(metrics.laneRepresentatives())
                    .as("lane 0 is read through one of the queries hosted on it -- an empty entry "
                            + "and an idle lane both read 0, so the numbers agreeing proves nothing on its own")
                    .containsKey(0);
            assertThat(metrics.laneRepresentatives().get(0)).isIn("txn_totals", "order_totals", "txn_big");
            assertThat(laneBlocked)
                    .isEqualTo(registry.find(metrics.laneRepresentatives().get(0))
                            .orElseThrow()
                            .backpressure()
                            .blockedFraction());
            assertThat(meters.find("pravaha.lane.inbox.depth")
                            .tag("lane", "0")
                            .gauge()
                            .value())
                    .as("and its inbox is empty, because all three queries are keeping up")
                    .isZero();
            assertThat(meters.find("pravaha.metrics.operators.enabled").gauge().value())
                    .as("per-operator counters are off unless pravaha.metrics.operators says otherwise")
                    .isZero();
        } finally {
            metrics.close();
            meters.close();
            node.stop();
        }
    }

    @Test
    void withTheSettingOnAQueryOverAStreamAlreadyOnTheLaneSharesItAndCountsOnlyItsOwnRows() {
        PravahaNode node = node(true);
        node.start();
        try {
            QueryRegistry registry = node.registry().orElseThrow();
            RegisteredQuery first = countOver(registry, "txn_totals", "txn");
            RegisteredQuery second = registry.register(
                    "txn_big",
                    "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE amount > 10",
                    List.of(0),
                    Principal.ANONYMOUS);

            assertThat(registry.pipelinesPerSharedLane()).containsExactly(2);
            assertThat(registry.sharedLaneOf("txn_big")).contains(0);

            feed(registry, first, "txn", 100);
            feed(registry, second, "txn", 250);

            assertThat(answer(registry.require("txn_totals"))).containsExactly(1L, 100L);
            assertThat(answer(registry.require("txn_big"))).containsExactly(1L, 250L);
            assertThat(node.describe())
                    .contains("lanes: shared, queries per lane [2] of at most 10; 0 on lanes of their own");
        } finally {
            node.stop();
        }
    }

    @Test
    void withTheSettingOffEachRegistrationHasALaneOfItsOwn() {
        PravahaNode node = node(false);
        node.start();
        try {
            QueryRegistry registry = node.registry().orElseThrow();
            RegisteredQuery onTxn = countOver(registry, "txn_totals", "txn");
            RegisteredQuery onOrders = countOver(registry, "order_totals", "orders");

            assertThat(registry.pipelinesPerSharedLane()).isEmpty();
            assertThat(registry.sharedLaneOf("txn_totals")).isEmpty();
            assertThat(registry.sharedLaneOf("order_totals")).isEmpty();
            assertThat(registry.queriesOnOwnLanes()).isEqualTo(2);

            feed(registry, onTxn, "txn", 100);
            feed(registry, onOrders, "orders", 250);

            assertThat(answer(registry.require("txn_totals"))).containsExactly(1L, 100L);
            assertThat(answer(registry.require("order_totals"))).containsExactly(1L, 250L);
            assertThat(node.describe()).contains("lanes: one per query (pravaha.lane.multiplex.enabled is false)");
        } finally {
            node.stop();
        }
    }
}
