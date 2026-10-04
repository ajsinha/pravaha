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
package com.ash.messaging.pravaha.spring.test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.embedded.RowChange;
import com.ash.messaging.pravaha.embedded.RowChangeListener;
import com.ash.messaging.pravaha.embedded.RowMapping;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.Subscription;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.spring.ListenerContainer;
import com.ash.messaging.pravaha.spring.PravahaListenerProcessor;

/**
 * Drives an embedded engine from a test, and waits for it without sleeping.
 *
 * <p>Three things a test of Pravaha code needs, each with a definite end:
 *
 * <ul>
 *   <li>{@link #push} -- rows into a stream. The engine applies and commits them before it returns,
 *       so a view read straight afterwards already answers with them.
 *   <li>{@link #awaitListeners} -- every {@code @PravahaListener} on a query has been handed
 *       everything committed so far. A marker is queued behind the listener's pending work on each
 *       of its threads, and the call returns when every marker has run: the wait ends exactly when
 *       the delivery does.
 *   <li>{@link #awaitView} -- a view's answer satisfies a condition. Checked at once, then again on
 *       each commit to the view, woken by a subscription rather than a timer; this is how a test
 *       waits for rows that arrive from a bound source, on the source's own schedule.
 * </ul>
 *
 * <p>A wait that runs out fails with an {@link AssertionError} naming what it was waiting for and,
 * for a view, the last answer it saw. The default limit is thirty seconds; {@link #withTimeout}
 * gives a tester with another.
 */
public class PravahaTester {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final PravahaEngine engine;
    private final @Nullable PravahaListenerProcessor listeners;
    private final Duration timeout;

    /**
     * @param engine the engine to drive
     * @param listeners where the application's {@code @PravahaListener} methods are, or null when it
     *     has none
     */
    public PravahaTester(PravahaEngine engine, @Nullable PravahaListenerProcessor listeners) {
        this(engine, listeners, DEFAULT_TIMEOUT);
    }

    private PravahaTester(PravahaEngine engine, @Nullable PravahaListenerProcessor listeners, Duration timeout) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.listeners = listeners;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** The same engine and listeners, waiting at most {@code limit} for each await. */
    public PravahaTester withTimeout(Duration limit) {
        if (limit.isNegative() || limit.isZero()) {
            throw new IllegalArgumentException("a wait needs a positive limit, not " + limit);
        }
        return new PravahaTester(engine, listeners, limit);
    }

    /** The engine underneath. */
    public PravahaEngine engine() {
        return engine;
    }

    /** Pushes rows, one {@code Object[]} per row in column order; applied and committed on return. */
    public PravahaTester push(String stream, Object[]... rows) {
        engine.push(stream, rows);
        return this;
    }

    /** Pushes one row given by column name; applied and committed on return. */
    public PravahaTester push(String stream, Map<String, ?> row) {
        engine.push(stream, row);
        return this;
    }

    /** Waits until every {@code @PravahaListener} in the context has been handed what is committed. */
    public PravahaTester awaitListeners() {
        for (ListenerContainer container : containers()) {
            await(container);
        }
        return this;
    }

    /** Waits until every {@code @PravahaListener} on {@code query} has been handed what is committed. */
    public PravahaTester awaitListeners(String query) {
        require(query);
        for (ListenerContainer container : containers()) {
            if (container.queryName().equals(query)) {
                await(container);
            }
        }
        return this;
    }

    /**
     * Waits until {@code SELECT * FROM query} satisfies {@code until}, each row as column name to
     * value, and returns that answer.
     */
    public List<Map<String, Object>> awaitView(String query, Predicate<List<Map<String, Object>>> until) {
        return awaitView(query, until, () -> {
            ViewQuery.Result result = engine.query("SELECT * FROM " + query);
            return result.rows().stream()
                    .map(row -> RowMapping.toMap(result.schema(), row))
                    .toList();
        });
    }

    /**
     * Waits until {@code SELECT * FROM query}, each row read into {@code rowType} by column name,
     * satisfies {@code until}, and returns that answer.
     */
    public <R> List<R> awaitView(String query, Class<R> rowType, Predicate<List<R>> until) {
        return awaitView(query, until, () -> engine.query(rowType, "SELECT * FROM " + query));
    }

    private <T> List<T> awaitView(String query, Predicate<List<T>> until, Supplier<List<T>> read) {
        RegisteredQuery registered = require(query);
        Object commits = new Object();
        long[] seen = {0};
        long deadline = System.nanoTime() + timeout.toNanos();
        // Subscribed from the view's snapshot, before the first read, so every commit after the
        // snapshot wakes this wait and the snapshot itself wakes it too (SUB-1). A plain subscription
        // starts at the next commit boundary: rows a source had already handed the query when it
        // attached were published to everyone but this wait, after a read that saw none of them, and
        // the wait slept through the answer it was waiting for. This used to force a commit to close
        // that gap; the engine's handoff has none to close.
        RowChangeListener wake = new RowChangeListener() {
            @Override
            public void onSnapshot(List<RowChange> rows, long frontier) {
                woken();
            }

            @Override
            public void onCommit(List<RowChange> changes, long frontier) {
                woken();
            }

            private void woken() {
                synchronized (commits) {
                    seen[0]++;
                    commits.notifyAll();
                }
            }
        };
        try (Subscription _ = engine.subscribeFromSnapshot(query, wake)) {
            while (true) {
                long before;
                synchronized (commits) {
                    before = seen[0];
                }
                List<T> answer = read.get();
                if (until.test(answer)) {
                    return answer;
                }
                synchronized (commits) {
                    while (seen[0] == before) {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) {
                            throw new AssertionError("timed out after " + timeout + " waiting for view '" + query
                                    + "' (" + registered.state() + ", " + registered.rowsIn()
                                    + " rows in, feed: " + registered.feed().describe()
                                    + ") to satisfy the condition; its answer was "
                                    + answer
                                    + registered
                                            .failure()
                                            .map(failure -> "; the query failed: " + failure.getMessage())
                                            .orElse(""));
                        }
                        commits.wait(Math.max(1, left / 1_000_000));
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for view '" + query + "'", e);
        }
    }

    private void await(ListenerContainer container) {
        boolean delivered;
        try {
            delivered = container.awaitDelivered(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + container, e);
        }
        if (!delivered) {
            throw new AssertionError("timed out after " + timeout + " waiting for @PravahaListener "
                    + container.listenerName() + " on '" + container.queryName() + "' to be handed what was "
                    + "committed; " + container.pending() + " commits still waiting");
        }
    }

    private List<ListenerContainer> containers() {
        return listeners == null ? List.of() : listeners.containers();
    }

    private RegisteredQuery require(String query) {
        return engine.find(query)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no query named '" + query + "' is registered; it has " + engine.queries()));
    }
}
