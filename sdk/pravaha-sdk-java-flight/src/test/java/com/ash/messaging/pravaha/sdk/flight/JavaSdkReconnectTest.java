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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
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
import com.ash.messaging.pravaha.sdk.PravahaClientException;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A subscription that outlives a server restart: {@link ReconnectingSubscription}. */
@Timeout(120)
class JavaSdkReconnectTest {

    private static final String SQL = "SELECT trade_id, product_type FROM trade";

    private static final StreamSchema TRADE = StreamSchema.builder("trade")
            .field("trade_id", Types.string())
            .field("product_type", Types.string())
            .build();

    // Started by startNode in each test, and stopped by it or @AfterEach -- an initialiser NullAway
    // cannot see.
    @SuppressWarnings("NullAway.Init")
    private QueryRegistry registry;

    @SuppressWarnings("NullAway.Init")
    private PravahaFlightServer server;

    @SuppressWarnings("NullAway.Init")
    private PravahaFlightClient client;

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);

    /** A node on {@code port} (0 for any) hosting {@code feed}; the restart brings up another. */
    private int startNode(int port) {
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TRADE);
        server = new PravahaFlightServer(views).hosting(registry).start("localhost", port);
        registry.register("feed", SQL, List.of(0), com.ash.messaging.pravaha.security.Principal.ANONYMOUS);
        return server.port();
    }

    private void stopNode() {
        server.close();
        registry.close();
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        stopNode();
        arena.close();
    }

    @Test
    void aSnapshotSubscriptionReopensAfterARestartAndItsFirstBatchIsTheNewView() throws Exception {
        int port = startNode(0);
        client = PravahaFlightClient.connect("grpc://localhost:" + port);
        commit(registry.require("feed"), "T-1");

        List<ChangeBatch> seen = new CopyOnWriteArrayList<>();
        AtomicInteger reconnected = new AtomicInteger();
        AtomicInteger batchesWhenReconnected = new AtomicInteger(-1);
        ReconnectingSubscription subscription = client.subscribeFromSnapshot(
                "feed",
                Map.of(),
                ReconnectingSubscription.Reconnect.defaults().onReconnected(() -> {
                    reconnected.incrementAndGet();
                    batchesWhenReconnected.set(seen.size());
                }),
                batch -> seen.add(copyOf(batch)));
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                subscription.run();
            } catch (Throwable t) {
                failed.set(t);
            }
        });
        await(() -> seen.size() >= 1, "the first snapshot");

        // The restart: the node goes away, and a new one comes up on the same port with T-2.
        stopNode();
        Thread.sleep(300);
        startNode(port);
        commit(registry.require("feed"), "T-2");

        await(() -> reconnected.get() == 1 && seen.size() > batchesWhenReconnected.get(), "the reopened stream");
        subscription.close();
        reader.join(5_000);

        assertThat(failed.get()).isNull();
        assertThat(subscription.reconnections()).isGreaterThanOrEqualTo(1);
        ChangeBatch first = seen.get(0);
        ChangeBatch afterRestart = seen.get(batchesWhenReconnected.get());
        assertThat(first.isSnapshot()).isTrue();
        assertThat(afterRestart.isSnapshot())
                .as("the batch after reopening is a fresh snapshot")
                .isTrue();
        assertThat(afterRestart.rows()).extracting(row -> row.toArray()[0]).containsExactly("T-2");
    }

    @Test
    void aRefusalThatWillNotChangeIsThrownAtOnce() {
        int port = startNode(0);
        client = PravahaFlightClient.connect("grpc://localhost:" + port);

        ReconnectingSubscription subscription =
                client.subscribe("nosuch", Map.of(), ReconnectingSubscription.Reconnect.defaults(), batch -> {});

        assertThatThrownBy(subscription::run)
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("nosuch");
        assertThat(subscription.reconnections()).isZero();
    }

    @Test
    void aNodeThatStaysDownPastTheLimitIsReported() {
        int port = startNode(0);
        client = PravahaFlightClient.connect("grpc://localhost:" + port);
        stopNode();
        startNode(0); // somewhere else, so AfterEach has a node to stop; the client's port stays dead

        ReconnectingSubscription subscription = client.subscribe(
                "feed", Map.of(), new ReconnectingSubscription.Reconnect(Duration.ofMillis(600), () -> {}), b -> {});

        assertThatThrownBy(subscription::run)
                .isInstanceOf(PravahaClientException.class)
                .satisfies(e ->
                        assertThat(((PravahaClientException) e).retryable()).isTrue());
    }

    private static ChangeBatch copyOf(ChangeBatch batch) {
        List<Row> rows = new java.util.ArrayList<>();
        for (Row row : batch) {
            rows.add(row.detach());
        }
        return new ChangeBatch(rows, batch.isSnapshot(), batch.frontier());
    }

    private void commit(RegisteredQuery query, String tradeId) {
        arena.reset();
        RowLayout layout = RowLayout.of(TRADE);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, tradeId).setString(1, "SWAP");
        writer.weight(1L).eventTimestampNanos(0).sequence(1).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        BinaryRowView row = new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
        while (!query.accept(row)) {
            query.awaitApplied(Duration.ofMillis(50));
        }
        assertThat(query.awaitApplied(Duration.ofSeconds(10))).isTrue();
        query.commit();
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
}
