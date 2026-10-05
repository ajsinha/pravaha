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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewChange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry's locks under contention, on virtual threads (ADR-062).
 *
 * <p>ADR-062 turned the registry's monitor, the replacement and rebalance locks, the commit lock and
 * the snapshot hand-off into {@code ReentrantLock}s. A conversion that leaves one unreleased on an
 * exception path, or a new order between two of them, shows up as a node that stops answering --
 * not as a wrong answer. So this runs the operations that take those locks against each other, from
 * virtual threads, while a feed commits and checkpoints are cut: registrations that share and do not
 * share a computation, drops, pauses, subscriptions (plain and from a snapshot), replacements with
 * cutover and abandonment, lane rebalances and the read-only listings.
 *
 * <p>Seeded and capped by iterations, not by time. What it asserts is that every worker finishes,
 * no thread is deadlocked, and nothing threw except a coded refusal -- a raw
 * {@code NullPointerException} or {@code ConcurrentModificationException} from a race is a defect.
 */
class RegistryLockStressTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private static final List<String> SQL = List.of(
            "SELECT 'all' AS bucket, SUM(amount) AS total FROM txn",
            "SELECT 'all' AS bucket, SUM(amount) AS total, COUNT(*) AS payments FROM txn",
            "SELECT 'all' AS bucket, MAX(amount) AS top FROM txn");

    private static final int WORKERS = 12;
    private static final int ITERATIONS = Integer.getInteger("pravaha.test.registry.stress.iterations", 150);
    private static final int NAMES = 6;

    @Test
    @Timeout(240)
    void registryOperationsFromVirtualThreadsNeitherDeadlockNorThrowUncoded(@TempDir Path root) throws Exception {
        ReplayableLog log = new ReplayableLog(TXN);
        for (int i = 0; i < 50; i++) {
            log.append("u" + (i % 5), (long) i);
        }
        ConcurrentLinkedQueue<Throwable> uncoded = new ConcurrentLinkedQueue<>();
        AtomicBoolean stop = new AtomicBoolean();
        LaneRebalance rebalance = new LaneRebalance();
        try (QueryRegistry registry = new QueryRegistry(
                        new ViewCatalog(), SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN)
                .feedingFrom(log)
                .multiplexingLanes(2, 16, 2)
                .checkpointingTo(
                        root,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1s")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build())) {
            Thread appender = Thread.ofVirtual().name("stress-appender").start(() -> {
                int i = 0;
                while (!stop.get()) {
                    log.append("u" + (i % 5), (long) (i++ % 100));
                    LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(200));
                }
            });
            CountDownLatch done = new CountDownLatch(WORKERS);
            for (int w = 0; w < WORKERS; w++) {
                long seed = 0x5EED_0000L + w;
                Thread.ofVirtual().name("stress-worker-" + w).start(() -> {
                    try {
                        work(registry, rebalance, new Random(seed), uncoded);
                    } finally {
                        done.countDown();
                    }
                });
            }
            boolean finished = done.await(180, TimeUnit.SECONDS);
            stop.set(true);
            appender.join(10_000);
            if (!finished) {
                throw new AssertionError("workers did not finish within 180 s:\n" + threadDump());
            }
            long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
            assertThat(deadlocked).as("deadlocked threads").isNull();
            // Still answers after the storm.
            assertThat(registry.names()).isNotNull();
        }
        System.out.println("REGISTRY LOCK STRESS outcomes: " + OUTCOMES);
        assertThat(uncoded).as("exceptions that were not a coded refusal").isEmpty();
    }

    /** What each operation came to, printed so a run that only collected refusals is visible as one. */
    private static final Map<String, LongAdder> OUTCOMES = new ConcurrentSkipListMap<>();

    private static void work(
            QueryRegistry registry, LaneRebalance rebalance, Random random, ConcurrentLinkedQueue<Throwable> uncoded) {
        for (int i = 0; i < ITERATIONS; i++) {
            String name = "q" + random.nextInt(NAMES);
            int op = random.nextInt(12);
            try {
                switch (op) {
                    case 0, 1 -> registry.register(name, SQL.get(random.nextInt(SQL.size())), List.of(0), DANA);
                    case 2 -> registry.drop(name);
                    case 3 -> {
                        registry.pause(name);
                        registry.resume(name);
                    }
                    case 4 -> {
                        RegisteredQuery query = registry.require(name);
                        query.subscribe(changes -> {}).close();
                    }
                    case 5 -> {
                        RegisteredQuery query = registry.require(name);
                        query.subscribeFromSnapshot(new SubscriptionListener() {
                                    @Override
                                    public void onSnapshot(List<ViewChange> rows, long frontier) {}

                                    @Override
                                    public void onCommit(List<ViewChange> changes, long frontier) {}
                                })
                                .close();
                    }
                    case 6 -> {
                        registry.replacements()
                                .replace(
                                        name,
                                        SQL.get(random.nextInt(SQL.size())),
                                        List.of(0),
                                        DANA,
                                        ReplacementOptions.defaults());
                        if (random.nextBoolean()) {
                            registry.replacements().abandon(name, DANA);
                        } else {
                            registry.replacements().cutOver(name, DANA);
                        }
                    }
                    case 7 -> {
                        if (random.nextBoolean()) {
                            registry.replacements().finish(name, DANA);
                        } else {
                            registry.replacements().abandon(name, DANA);
                        }
                    }
                    case 8 -> rebalance.start(registry, DANA);
                    case 9 -> {
                        registry.queries().forEach(q -> {
                            q.state();
                            q.lanePlacement();
                            q.subscriberCount();
                        });
                        registry.tenantUsage();
                        registry.pipelinesPerSharedLane();
                        registry.replacements().all();
                        rebalance.status(registry);
                    }
                    case 10 -> registry.debugSessions().fork(name, null, DANA);
                    default -> registry.find(name).ifPresent(q -> q.awaitApplied(Duration.ofMillis(50)));
                }
                OUTCOMES.computeIfAbsent(op + ":ok", k -> new LongAdder()).increment();
            } catch (PravahaException refused) {
                // A coded refusal: the name is gone, taken, being replaced, and so on.
                OUTCOMES.computeIfAbsent(op + ":" + refused.errorCode(), k -> new LongAdder())
                        .increment();
            } catch (RuntimeException | Error escaped) {
                uncoded.add(new AssertionError("op " + op + " on " + name + ": " + escaped, escaped));
            }
        }
    }

    private static String threadDump() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        StringBuilder dump = new StringBuilder();
        long[] deadlocked = threads.findDeadlockedThreads();
        dump.append("deadlocked: ")
                .append(deadlocked == null ? "none" : deadlocked.length)
                .append('\n');
        for (ThreadInfo info : threads.dumpAllThreads(true, true)) {
            dump.append(info);
        }
        return dump.toString();
    }
}
