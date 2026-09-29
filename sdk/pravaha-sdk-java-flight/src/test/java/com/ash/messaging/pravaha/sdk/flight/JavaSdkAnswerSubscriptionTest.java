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
 * SUBANSWERWIRE-1: an answer-following subscription over Flight, through the Java SDK.
 *
 * <p>A keyed view over a stream that only inserts keeps the latest row per key. Its changelog hands a
 * second row under a key as {@code +1} with no {@code -1} for the row it replaced, so a subscriber
 * summing weights holds two rows where a reader sees one (KEYEDWT-1). Following the answer, the
 * weights sum to exactly what a reader sees. It was reachable only in the engine and the embedded
 * API; the ticket now carries it.
 */
@Timeout(120)
class JavaSdkAnswerSubscriptionTest {

    private static final String SQL = "SELECT user_id, amount FROM txn";

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private QueryRegistry registry;
    private PravahaFlightServer server;
    private PravahaFlightClient client;
    private RowArena arena;

    @BeforeEach
    void start() {
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN);
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

    @Test
    void theAnswersWeightsSumToTheViewWhereTheChangelogsDoNot() throws Exception {
        client.register("latest", SQL, List.of(0));
        RegisteredQuery query = registry.require("latest");

        Copy changelog = new Copy();
        Copy answer = new Copy();
        Subscription plain = client.subscribe("latest", Map.of(), changelog::accept);
        Subscription following = client.subscribeToAnswer("latest", Map.of(), answer::accept);
        Thread first = Thread.ofVirtual().start(plain::run);
        Thread second = Thread.ofVirtual().start(following::run);
        await(() -> query.subscriberCount() >= 2, "both subscriptions to attach");

        apply(query, "u1", 10, 1);
        // An upsert: the view replaces u1's row; the changelog carries only the +1.
        apply(query, "u1", 20, 1);
        apply(query, "u2", 5, 1);
        await(() -> answer.batches() >= 3 && changelog.batches() >= 3, "three commits on each");
        plain.close();
        following.close();
        first.join(5_000);
        second.join(5_000);

        Map<List<Object>, Long> shown = new HashMap<>();
        for (Object[] row : query.view().scan()) {
            shown.put(List.of(row), 1L);
        }
        assertThat(shown).containsOnlyKeys(List.of("u1", 20L), List.of("u2", 5L));
        assertThat(answer.rows()).as("the answer's weights are the view").isEqualTo(shown);
        assertThat(changelog.rows())
                .as("the changelog's are the view's Z-set: the replaced row is still there")
                .containsOnlyKeys(List.of("u1", 10L), List.of("u1", 20L), List.of("u2", 5L));
    }

    @Test
    void anAnswerSnapshotStartsFromTheRowsAReaderSees() throws Exception {
        client.register("latest", SQL, List.of(0));
        RegisteredQuery query = registry.require("latest");
        apply(query, "u1", 10, 1);
        apply(query, "u1", 20, 1);

        Copy copy = new Copy();
        Subscription subscription = client.subscribeToAnswerFromSnapshot("latest", Map.of(), copy::accept);
        Thread reader = Thread.ofVirtual().start(subscription::run);
        await(() -> copy.batches() >= 1, "the snapshot");
        assertThat(copy.rows()).as("the view holds u1 twice and shows 20").containsOnlyKeys(List.of("u1", 20L));

        apply(query, "u1", 30, 1);
        await(() -> copy.rows().containsKey(List.of("u1", 30L)), "the upsert");
        subscription.close();
        reader.join(5_000);

        assertThat(copy.rows()).isEqualTo(Map.of(List.of("u1", 30L), 1L));
    }

    private void apply(RegisteredQuery query, String user, long amount, long weight) {
        arena.reset();
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user).setLong(1, amount);
        writer.weight(weight).eventTimestampNanos(0).sequence(1).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        assertThat(query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))))
                .isTrue();
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

    /** What a subscription delivered, summed by weight. */
    static final class Copy {

        private int batches;
        private final Map<List<Object>, Long> rows = new HashMap<>();

        synchronized void accept(ChangeBatch batch) {
            batches++;
            for (Row row : batch) {
                rows.merge(List.of(row.toArray()), row.weight(), Long::sum);
            }
            rows.values().removeIf(weight -> weight == 0);
        }

        synchronized int batches() {
            return batches;
        }

        synchronized Map<List<Object>, Long> rows() {
            return Map.copyOf(rows);
        }
    }
}
