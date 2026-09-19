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
package com.ash.messaging.pravaha.sdk.flight;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A snapshot subscription over Flight, through the Java SDK (SUB-1): the view, then every commit.
 */
@Timeout(120)
class JavaSdkSnapshotSubscriptionTest {

    private static final String SQL = "SELECT trade_id, product_type FROM trade";

    private static final StreamSchema TRADE = StreamSchema.builder("trade")
            .field("trade_id", Types.string())
            .field("product_type", Types.string())
            .build();

    private QueryRegistry registry;
    private PravahaFlightServer server;
    private PravahaFlightClient client;
    private RowArena arena;

    @BeforeEach
    void start() {
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TRADE);
        server = new PravahaFlightServer(views).hosting(registry).start("localhost", 0);
        client = PravahaFlightClient.connect("grpc://localhost:" + server.port());
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void stop() {
        client.close();
        server.close();
        registry.close();
        arena.close();
    }

    /** Hands the query a row and waits for it to be applied; commits only when asked. */
    private void apply(RegisteredQuery query, String tradeId, String product, boolean commit) {
        offer(query, tradeId, product);
        assertThat(query.awaitApplied(Duration.ofSeconds(10))).isTrue();
        if (commit) {
            query.commit();
        }
    }

    private void offer(RegisteredQuery query, String tradeId, String product) {
        arena.reset();
        RowLayout layout = RowLayout.of(TRADE);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, tradeId).setString(1, product);
        writer.weight(1L).eventTimestampNanos(0).sequence(1).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        BinaryRowView row = new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
        while (!query.accept(row)) {
            // The lane's inbox is full; let it drain.
            query.awaitApplied(Duration.ofMillis(50));
        }
    }

    @Test
    void theFirstBatchIsTheViewIncludingTheCommitInFlightAndEveryCommitFollows() throws Exception {
        client.register("feed", SQL, List.of(0));
        RegisteredQuery query = registry.require("feed");
        apply(query, "T-1", "SWAP", true);
        // Applied, not committed: the rows a plain subscription and a read beside it both miss.
        apply(query, "T-2", "EQUITY", false);

        Copy copy = new Copy();
        Subscription subscription = client.subscribeFromSnapshot("feed", copy::accept);
        Thread reader = Thread.ofVirtual().start(subscription::run);
        await(() -> copy.batches() >= 1, "the snapshot");
        apply(query, "T-3", "SWAP", true);
        await(() -> copy.batches() >= 2, "the commit after it");
        subscription.close();
        reader.join(5_000);

        assertThat(copy.kinds()).containsExactly(true, false);
        assertThat(copy.frontiers().get(0)).isGreaterThan(Long.MIN_VALUE);
        assertThat(copy.rows())
                .containsOnlyKeys(List.of("T-1", "SWAP"), List.of("T-2", "EQUITY"), List.of("T-3", "SWAP"));
        assertThat(copy.rows().keySet()).hasSize(query.view().scan().size());
    }

    @Test
    void aSnapshotLargerThanOneArrowBatchArrivesAsOneAndAnEmptyOneStillArrives() throws Exception {
        client.register("feed", SQL, List.of(0));
        RegisteredQuery query = registry.require("feed");

        Copy empty = new Copy();
        Subscription none = client.subscribeFromSnapshot("feed", Map.of("product_type", "SWAP"), empty::accept);
        Thread first = Thread.ofVirtual().start(none::run);
        await(() -> empty.batches() >= 1, "an empty snapshot");
        none.close();
        first.join(5_000);
        assertThat(empty.kinds()).containsExactly(true);
        assertThat(empty.rows()).isEmpty();

        for (int i = 0; i < 5_000; i++) {
            offer(query, "T-" + i, i % 2 == 0 ? "SWAP" : "EQUITY");
        }
        assertThat(query.awaitApplied(Duration.ofSeconds(30))).isTrue();
        query.commit();
        Copy big = new Copy();
        Subscription subscription = client.subscribeFromSnapshot("feed", big::accept);
        Thread reader = Thread.ofVirtual().start(subscription::run);
        await(() -> big.batches() >= 1, "the snapshot");
        subscription.close();
        reader.join(5_000);
        assertThat(big.kinds()).containsExactly(true);
        assertThat(big.rows()).hasSize(5_000);
    }

    @Test
    void aPlainSubscriptionIsAsItWasNoSnapshotAndNoFrontier() throws Exception {
        client.register("feed", SQL, List.of(0));
        RegisteredQuery query = registry.require("feed");
        apply(query, "T-1", "SWAP", true);

        Copy copy = new Copy();
        Subscription subscription = client.subscribe("feed", copy::accept);
        Thread reader = Thread.ofVirtual().start(subscription::run);
        await(() -> query.subscriberCount() > 0, "the subscription to attach");
        apply(query, "T-2", "SWAP", true);
        await(() -> copy.batches() >= 1, "a commit");
        subscription.close();
        reader.join(5_000);

        assertThat(copy.kinds()).containsExactly(false);
        assertThat(copy.frontiers()).containsExactly(Long.MIN_VALUE);
        assertThat(copy.rows()).containsOnlyKeys(List.of("T-2", "SWAP"));
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    /** A copy of the view from what a subscription delivered, as a Z-set. */
    static final class Copy {

        private final List<Boolean> kinds = new ArrayList<>();
        private final List<Long> frontiers = new ArrayList<>();
        private final Map<List<Object>, Long> rows = new HashMap<>();

        synchronized void accept(ChangeBatch batch) {
            kinds.add(batch.isSnapshot());
            frontiers.add(batch.frontier());
            for (Row row : batch) {
                rows.merge(List.of(row.toArray()), row.weight(), Long::sum);
            }
            rows.values().removeIf(weight -> weight == 0);
        }

        synchronized int batches() {
            return kinds.size();
        }

        synchronized List<Boolean> kinds() {
            return List.copyOf(kinds);
        }

        synchronized List<Long> frontiers() {
            return List.copyOf(frontiers);
        }

        synchronized Map<List<Object>, Long> rows() {
            return Map.copyOf(rows);
        }
    }
}
