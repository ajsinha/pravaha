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
package com.ash.messaging.pravaha.spring;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.embedded.RowChange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @PravahaListener}: a bean method receiving a query's committed changes, retractions
 * included, off the engine's thread, and let go of cleanly when the context closes.
 */
class PravahaListenerTest {

    /** Every row changes the answer, so every commit after the first withdraws a row and adds one. */
    static final String TOTALS = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PravahaAutoConfiguration.class))
            .withPropertyValues(
                    "pravaha.streams.txn.schema=user_id:STRING,amount:INT64",
                    "pravaha.queries.totals.sql=" + TOTALS,
                    "pravaha.queries.totals.keys=n");

    record Totals(long n, long total) {}

    /** The record shape: a row by column name, and whether it withdraws one. */
    static class RecordListener {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @PravahaListener(query = "totals")
        void on(Totals row, boolean retraction) {
            seen.add((retraction ? "-" : "+") + row.n() + "/" + row.total());
        }
    }

    /** The other two shapes, the per-change one on two threads. */
    static class ChangeListeners {
        final List<RowChange> changes = new CopyOnWriteArrayList<>();
        final List<List<RowChange>> commits = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> maps = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();

        @PravahaListener(query = "totals", concurrency = 2)
        public void change(RowChange change) {
            threads.add(Thread.currentThread().getName());
            changes.add(change);
        }

        @PravahaListener(query = "totals")
        public void commit(List<RowChange> commit) {
            commits.add(commit);
        }

        @PravahaListener(query = "totals")
        public void map(Map<String, Object> row, Boolean retraction) {
            maps.add(row);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordListenerConfiguration {
        @Bean
        RecordListener recordListener() {
            return new RecordListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ChangeListenersConfiguration {
        @Bean
        ChangeListeners changeListeners() {
            return new ChangeListeners();
        }
    }

    @Test
    void aRecordListenerSeesTheInsertTheRetractionAndTheReplacementInOrder() {
        runner.withUserConfiguration(RecordListenerConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            PravahaTemplate template = context.getBean(PravahaTemplate.class);
            RecordListener listener = context.getBean(RecordListener.class);

            template.push("txn", new Object[] {"u1", 300L});
            template.push("txn", new Object[] {"u2", 200L});

            await(() -> listener.seen.size() >= 3, "three changes");
            assertThat(listener.seen).containsExactly("+1/300", "-1/300", "+2/500");
            assertThat(listener.seen)
                    .as("delivered off the engine's thread, and nothing more")
                    .hasSize(3);
        });
    }

    @Test
    void theChangeAndCommitShapesAndConcurrencyByKey() {
        runner.withUserConfiguration(ChangeListenersConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            PravahaTemplate template = context.getBean(PravahaTemplate.class);
            ChangeListeners listeners = context.getBean(ChangeListeners.class);

            template.push("txn", new Object[] {"u1", 300L});
            template.push("txn", new Object[] {"u2", 200L});

            await(() -> listeners.changes.size() >= 3 && listeners.maps.size() >= 3, "every change");
            await(() -> listeners.commits.size() >= 2, "both commits");
            assertThat(listeners.changes).filteredOn(RowChange::isRetraction).hasSize(1);
            assertThat(listeners.commits.get(1))
                    .as("the second commit: the old answer withdrawn, then the new one")
                    .extracting(RowChange::isRetraction)
                    .containsExactly(true, false);
            assertThat(listeners.maps).contains(Map.of("n", 2L, "total", 500L));
            assertThat(listeners.threads).allMatch(name -> name.startsWith("pravaha-listener-totals-"));

            PravahaListenerProcessor processor = context.getBean(PravahaListenerProcessor.class);
            assertThat(processor.containers()).hasSize(3).allMatch(ListenerContainer::isRunning);
            await(
                    () -> processor.containers().stream()
                                    .mapToLong(ListenerContainer::delivered)
                                    .sum()
                            == 3 + 2 + 3,
                    "the delivery counts");
        });
    }

    @Test
    void aListenerOnAQueryRegisteredByABeanAtStartupIsSubscribed() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PravahaAutoConfiguration.class))
                .withPropertyValues("pravaha.streams.txn.schema=user_id:STRING,amount:INT64")
                .withUserConfiguration(RecordListenerConfiguration.class)
                .withBean(QueryOwner.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    context.getBean(PravahaTemplate.class).push("txn", new Object[] {"u1", 5L});
                    RecordListener listener = context.getBean(RecordListener.class);
                    await(() -> listener.seen.size() == 1, "the first change");
                    assertThat(listener.seen).containsExactly("+1/5");
                });
    }

    /** Registers the query its listener needs from its own initialisation, through the template. */
    static class QueryOwner {
        QueryOwner(PravahaTemplate template) {
            template.register("totals", TOTALS, "n");
        }
    }

    @Test
    void aListenerOnAMissingQueryOrOfTheWrongShapeFailsTheStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PravahaAutoConfiguration.class))
                .withPropertyValues("pravaha.streams.txn.schema=user_id:STRING,amount:INT64")
                .withUserConfiguration(RecordListenerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("recordListener.on")
                            .hasMessageContaining("'totals'")
                            .hasMessageContaining("pravaha.queries");
                });

        runner.withBean(WrongShape.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("boolean is not optional");
        });

        runner.withBean(WrongRecord.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("'userId'");
        });
    }

    static class WrongShape {
        @PravahaListener(query = "totals")
        void on(Totals row) {}
    }

    record Unrelated(String userId) {}

    static class WrongRecord {
        @PravahaListener(query = "totals")
        void on(Unrelated row, boolean retraction) {}
    }

    @Test
    void closingTheContextDetachesListenersStopsTheirThreadsAndClosesTheEngine() {
        PravahaEngine[] engine = new PravahaEngine[1];
        PravahaListenerProcessor[] processor = new PravahaListenerProcessor[1];
        runner.withUserConfiguration(ChangeListenersConfiguration.class).run(context -> {
            engine[0] = context.getBean(PravahaEngine.class);
            processor[0] = context.getBean(PravahaListenerProcessor.class);
            context.getBean(PravahaTemplate.class).push("txn", new Object[] {"u1", 1L});
            await(() -> context.getBean(ChangeListeners.class).changes.size() == 1, "a change");
            assertThat(listenerThreads()).isNotEmpty();
            assertThat(engine[0].find("totals").orElseThrow().subscriberCount()).isEqualTo(3);
        });
        assertThat(engine[0].state()).isEqualTo(EngineState.STOPPED);
        assertThat(processor[0].isRunning()).isFalse();
        assertThat(processor[0].containers()).noneMatch(ListenerContainer::isRunning);
        await(() -> listenerThreads().isEmpty(), "listener threads to end");
    }

    private static List<String> listenerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("pravaha-listener-"))
                .toList();
    }

    static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
