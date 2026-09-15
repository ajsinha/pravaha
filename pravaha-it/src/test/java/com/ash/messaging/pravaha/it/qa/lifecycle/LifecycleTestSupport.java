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
package com.ash.messaging.pravaha.it.qa.lifecycle;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

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
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

/**
 * What every LIFE test needs: the standing fixture LIFE.md describes -- {@code txn(id, usr, amount,
 * event_time)} -- and the push/read/advance operations the case bodies are written against.
 *
 * <p>The 20-row CSV LIFE.md binds {@code txn} to is not a file here; each test states the rows it
 * pushes and the count it expects by hand, which is what the vacuity kit ("V-rows is a specific
 * expected number, not non-zero") asks for regardless of where the rows come from.
 */
abstract class LifecycleTestSupport {

    static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.int64())
            .field("usr", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .build();

    /** {@code S1} from LIFE.md's standing setup: output columns (usr, amount), ordinals 0 and 1. */
    static final String S1 = "SELECT usr, amount FROM txn";

    /** {@code S1'}: different text, same plan, same fingerprint (LIFE-084). */
    static final String S1_PRIME = "SELECT t.usr, t.amount FROM txn AS t";

    ViewCatalog views;
    QueryRegistry registry;
    RowArena arena;

    @BeforeEach
    void startLifecycleFixture() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, TXN);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void stopLifecycleFixture() {
        registry.close();
        arena.close();
    }

    /** Pushes one row into {@code name} and waits for it to be applied, the way a push client does. */
    void push(String name, long id, String usr, long amount, long weight) {
        push(registry, name, id, usr, amount, weight, id);
    }

    void push(String name, long id, String usr, long amount, long weight, long eventTime) {
        push(registry, name, id, usr, amount, weight, eventTime);
    }

    void push(QueryRegistry onto, String name, long id, String usr, long amount, long weight, long eventTime) {
        RegisteredQuery query = onto.require(name);
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id);
        writer.setString(1, usr);
        writer.setLong(2, amount);
        writer.setLong(3, eventTime);
        writer.weight(weight).eventTimestampNanos(eventTime).sequence(id).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        // The lane applies on its own thread; awaiting first means a read right after this call sees
        // the row rather than racing the lane that is about to apply it.
        query.awaitApplied(Duration.ofSeconds(10));
        query.commit();
    }

    /** Advances event time, the way a source's watermark generator or a feed would. */
    void advanceTo(String name, long nanos) {
        registry.require(name).advanceWatermark(nanos);
        registry.require(name).commit();
    }

    void advanceTo(QueryRegistry onto, String name, long nanos) {
        onto.require(name).advanceWatermark(nanos);
        onto.require(name).commit();
    }

    /** Every row of {@code sql}, as printed strings, sorted -- for a set comparison independent of order. */
    List<String> readSorted(String sql) {
        return readSorted(views, sql);
    }

    List<String> readSorted(ViewCatalog catalog, String sql) {
        List<String> rows = new java.util.ArrayList<>();
        new ViewQuery(catalog).execute(sql).rows().forEach(row -> rows.add(row[0] + "=" + row[1]));
        rows.sort(String::compareTo);
        return rows;
    }

    List<Object[]> rows(String sql) {
        return rows(views, sql);
    }

    List<Object[]> rows(ViewCatalog catalog, String sql) {
        return new ViewQuery(catalog).execute(sql).rows();
    }

    /**
     * Registers a windowed MIN and drives it to FAILED with a retraction, the way LIFE-126 says to:
     * MIN cannot invert a retraction (a query cannot restore a withdrawn extreme without an ordered
     * multiset), so the row is refused, the lane dies, and the query's reactive {@code state()}
     * reports FAILED from then on.
     */
    void driveToFailedByMinRetraction(String name) {
        registry.register(
                name,
                "SELECT usr, MIN(amount) AS lo FROM txn GROUP BY TUMBLE(event_time, INTERVAL '1' SECOND), usr",
                List.of(0),
                Principal.ANONYMOUS);
        push(name, 1, "ann", 100, 1, 100_000_000L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> push(name, 1, "ann", 100, -1, 100_000_000L))
                .as("MIN cannot invert a retraction")
                .isInstanceOf(com.ash.messaging.pravaha.api.PravahaException.class)
                .hasMessageContaining("MIN cannot handle a retraction");
    }

    static final Principal GUEST = Principal.of("guest");
    static final Principal ALICE = Principal.of("alice");
    static final Principal HR = Principal.of("hr");

    /**
     * Distinct computations the registry holds -- one per fingerprint, however many names point at it.
     *
     * <p>This counted threads named {@code pravaha-query*}, one per running query, which was a fair
     * proxy while a lane owned a thread. ADR-027's multiplexing removed those threads: lanes are now
     * driven by a fixed set of runner threads, so the proxy reads zero however many queries are
     * registered.
     *
     * <p>Asking the registry is what these cases meant anyway. "Ten names cost one lane" is a claim
     * about sharing, and counting computations states it directly instead of inferring it from a
     * thread that happened to exist per computation.
     */
    static long computations(QueryRegistry registry) {
        return registry.size();
    }

    /**
     * Polls {@link #computations(QueryRegistry)} until it reaches {@code expected}.
     *
     * <p>Kept polling rather than read once: a drop releases its computation asynchronously, and a
     * bare read racing that is a source of flakiness with nothing to do with the leak being checked.
     */
    static long awaitComputations(QueryRegistry registry, long expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long last;
        do {
            last = computations(registry);
            if (last == expected) {
                return last;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        } while (System.nanoTime() < deadline);
        return last;
    }
}
