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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import com.ash.messaging.pravaha.sdk.PravahaClientException;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Java SDK registering continuous queries and subscribing to them (ADR-025, ADR-026).
 *
 * <p>Deliberately the same assertions as the Python SDK's tests, against the same server behaviour.
 * Two SDKs that register differently is two sets of support calls.
 */
@Timeout(90)
class JavaSdkRegistryTest {

    private static final String SQL = "SELECT trade_id, product_type, trade_json FROM trade";

    private static final StreamSchema TRADE = StreamSchema.builder("trade")
            .field("trade_id", Types.string())
            .field("product_type", Types.string())
            .field("trade_json", Types.string())
            .build();

    private ViewCatalog views;
    private QueryRegistry registry;
    private PravahaFlightServer server;
    private PravahaFlightClient client;
    private RowArena arena;

    @BeforeEach
    void start() {
        views = new ViewCatalog();
        // PERMISSIVE, as a development server has: the default refuses registration to an
        // anonymous caller, which is right on a network and wrong for a test with no auth.
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TRADE);
        server = new PravahaFlightServer(views).hosting(registry).start("localhost", 0);
        client = PravahaFlightClient.connect("grpc://localhost:" + server.port());
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        registry.close();
        arena.close();
    }

    private void feed(String name, String tradeId, String product) {
        var query = registry.require(name);
        RowLayout layout = RowLayout.of(TRADE);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, tradeId);
        writer.setString(1, product);
        writer.setString(2, "{\"id\":\"" + tradeId + "\"}");
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.commit();
    }

    @Test
    void aContinuousQueryCanBeRegisteredAndListed() {
        RegisteredQueryInfo registered = client.register("feed", SQL, List.of(0));

        assertThat(registered.name()).isEqualTo("feed");
        assertThat(registered.isRunning()).isTrue();
        assertThat(registered.fingerprint()).isNotBlank();
        assertThat(client.queries()).extracting(RegisteredQueryInfo::name).contains("feed");
    }

    @Test
    void theSameQuestionRegisteredTwiceIsOneComputation() {
        RegisteredQueryInfo first = client.register("a", SQL, List.of(0));
        // Different text, same normalised plan.
        RegisteredQueryInfo second =
                client.register("b", "SELECT t.trade_id, t.product_type, t.trade_json FROM trade AS t", List.of(0));

        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(registry.size()).as("one computation, two names").isEqualTo(1);
    }

    @Test
    void aQueryCanBePausedResumedAndDropped() {
        client.register("life", SQL, List.of(0));

        client.pause("life");
        assertThat(stateOf("life")).isEqualTo("PAUSED");
        client.resume("life");
        assertThat(stateOf("life")).isEqualTo("RUNNING");

        client.drop("life");
        assertThat(client.queries()).extracting(RegisteredQueryInfo::name).doesNotContain("life");
    }

    @Test
    void droppingAnUnknownQueryIsRefused() {
        assertThatThrownBy(() -> client.drop("never-registered"))
                .isInstanceOf(PravahaClientException.class)
                .hasMessageContaining("PRV-8002");
    }

    @Test
    void aSubscriberReceivesCommittedChanges() throws Exception {
        client.register("feed", SQL, List.of(0));

        List<String> seen = new ArrayList<>();
        CountDownLatch got = new CountDownLatch(2);
        Subscription subscription = client.subscribe("feed", batch -> {
            for (Row row : batch) {
                synchronized (seen) {
                    // Copied inside the callback. The row is a flyweight over the Arrow batch that
                    // carried it, and that buffer is reused for the next commit.
                    seen.add(row.getString("trade_id"));
                }
                got.countDown();
            }
        });
        Thread reader = Thread.ofVirtual().start(subscription::run);

        awaitAttached("feed");
        feed("feed", "T-1", "SWAP");
        feed("feed", "T-2", "EQUITY");

        assertThat(got.await(30, TimeUnit.SECONDS)).isTrue();
        synchronized (seen) {
            assertThat(seen).contains("T-1", "T-2");
        }
        assertThat(subscription.batches()).isPositive();
        subscription.close();
        reader.join(5_000);
        awaitDetached("feed");
    }

    @Test
    void aSubscriberCanFilterAtTheTap() throws Exception {
        client.register("feed", SQL, List.of(0));

        List<String> seen = new ArrayList<>();
        CountDownLatch got = new CountDownLatch(1);
        Subscription subscription = client.subscribe("feed", Map.of("product_type", "SWAP"), batch -> {
            for (Row row : batch) {
                synchronized (seen) {
                    seen.add(row.getString("trade_id"));
                }
                got.countDown();
            }
        });
        Thread reader = Thread.ofVirtual().start(subscription::run);

        awaitAttached("feed");
        feed("feed", "T-equity", "EQUITY");
        feed("feed", "T-swap", "SWAP");

        assertThat(got.await(30, TimeUnit.SECONDS)).isTrue();
        synchronized (seen) {
            // The equity trade never crosses the wire: one computation, many filtered taps.
            assertThat(seen).containsExactly("T-swap");
        }
        subscription.close();
        reader.join(5_000);
        awaitDetached("feed");
    }

    @Test
    void aFilterNamingAnUnknownColumnIsRefusedRatherThanIgnored() {
        client.register("feed", SQL, List.of(0));

        Subscription subscription = client.subscribe("feed", Map.of("prodcut_type", "SWAP"), batch -> {});
        assertThatThrownBy(subscription::run)
                .as("a typo silently dropped would leave a consumer receiving everything")
                .hasMessageContaining("has no column");
        subscription.close();
    }

    @Test
    void closingTheClientReleasesTheServersSideOfASubscription() throws Exception {
        client.register("feed", SQL, List.of(0));

        Subscription subscription = client.subscribe("feed", batch -> {});
        Thread reader = Thread.ofVirtual().start(subscription::run);
        awaitAttached("feed");
        assertThat(registry.require("feed").subscriberCount()).isEqualTo(1);

        // Closing the *client* outright, without closing the subscription first -- which is what a
        // process exiting looks like from the server's side.
        client.close();
        client = null;

        awaitDetached("feed");
        assertThat(registry.require("feed").subscriberCount())
                .as("a client going away must not leave a listener attached to the query, or the "
                        + "engine keeps assembling batches for nobody")
                .isZero();
        reader.join(5_000);
    }

    private String stateOf(String name) {
        return client.queries().stream()
                .filter(q -> q.name().equals(name))
                .map(RegisteredQueryInfo::state)
                .findFirst()
                .orElse("ABSENT");
    }

    /** A subscription starts from now, so a change committed before it attaches reaches nobody. */
    private void awaitAttached(String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (registry.require(name).subscriberCount() > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the subscription never attached");
    }

    /** And the server releases its Arrow buffers on its next poll, not the instant we cancel. */
    private void awaitDetached(String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline
                && registry.find(name).map(q -> q.subscriberCount()).orElse(0) > 0) {
            Thread.sleep(20);
        }
    }
}
