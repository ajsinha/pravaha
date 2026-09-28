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
package com.ash.messaging.pravaha.plugin.kafka;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.Subscription;
import com.ash.messaging.pravaha.registry.SubscriptionFilter;
import com.ash.messaging.pravaha.registry.SubscriptionListener;
import com.ash.messaging.pravaha.registry.SubscriptionOptions;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewChange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-054's exact seam over a real Kafka broker, where it matters (SEAMKAFKA-1).
 *
 * <p>{@code ExactSharingTest} proves the seam over a test source whose positions are dense. Kafka's
 * are not: a transactional producer writes a commit marker after every transaction, and an aborted
 * transaction leaves records a {@code read_committed} reader never sees -- offsets with nothing to
 * hand over. A seam can fall on one of them, and a catch-up counting records rather than comparing
 * offsets would overshoot it. So every record here is written in small transactions, with an aborted
 * one after every few, so markers and gaps sit beside every position the shared reader can stand at.
 *
 * <p>Each query is a count and a sum -- a duplicate or a gap moves them, where a keyed view would
 * absorb either -- and {@code rowsIn} counts what ingest delivered. An aborted record carries an
 * amount that would show in every sum. Order is watched through a subscription on a projection:
 * after its snapshot, every commit's rows must be later in the topic than everything before.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class KafkaExactSharingBrokerTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "acme", java.util.Set.of("analyst"), Map.of());

    /** An aborted record's amount: in every query's filter, so a leak moves every sum. */
    private static final long ABORTED = -1_000_000L;

    // Three different questions, so three computations sharing one reader rather than one.
    private static final String COUNT_A = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE amount <> 0";
    private static final String COUNT_B = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE amount > -2000000";
    private static final String COUNT_C = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE amount < 2000000";
    private static final String ROWS = "SELECT user_id, amount FROM txn WHERE amount <> 1";

    @TempDir
    Path root;

    private final List<QueryRegistry> registries = new ArrayList<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private KafkaProducer<byte[], byte[]> producer;

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
        registries.forEach(QueryRegistry::close);
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    void aQueryJoiningBehindTheSharedReaderOverTransactionMarkersGetsEveryRecordOnceInOrder() throws Exception {
        String topic = KafkaBroker.topic("seam", 1, false);
        producer = KafkaBroker.producer("seam-" + topic);
        write(topic, 1, 300, 2);

        QueryRegistry registry = registry(topic, null);
        RegisteredQuery first = registry.register("early", COUNT_A, List.of(0), DANA);
        RegisteredQuery rows = registry.register("projected", ROWS, List.of(0), DANA);
        OrderWatch rowsOrder = watch(rows);
        awaitAnswer(first, 300);

        // Records keep arriving while the second query catches up, so the seam moves under it.
        Thread writer = Thread.ofPlatform().start(() -> write(topic, 301, 600, 1));
        RegisteredQuery second = registry.register("joiner", COUNT_B, List.of(0), DANA);
        OrderWatch secondRows = watch(registry.register("joiner_rows", ROWS + " AND amount > -5", List.of(0), DANA));
        writer.join();
        write(topic, 601, 700, 3);

        for (RegisteredQuery query : List.of(first, second)) {
            awaitAnswer(query, 700);
        }
        Thread.sleep(500);
        assertThat(second.feed().describe())
                .as("the joiner is on the one shared reader, not a reader of its own")
                .contains("one reader shared with 3 other queries");
        assertExactly(first, 700, 700);
        assertExactly(second, 700, 700);
        assertThat(rows.rowsIn()).isEqualTo(700);
        assertThat(rowsOrder.violations())
                .as("the first projection, in topic order")
                .isEmpty();
        assertThat(secondRows.violations())
                .as("the joiner's projection, in topic order across the seam")
                .isEmpty();
        assertThat(secondRows.seen())
                .as("every record once to the joiner's projection: all 700 but amount 1, which it filters")
                .isEqualTo(699);
    }

    @Test
    void aQueryRestoredAheadAndOneRestoredBehindEachGetEveryRecordOnce() throws Exception {
        String topic = KafkaBroker.topic("restore", 1, false);
        producer = KafkaBroker.producer("restore-" + topic);
        Path checkpoints = root.resolve("checkpoints");
        write(topic, 1, 100, 2);

        try (QueryRegistry before = registry(topic, checkpoints)) {
            RegisteredQuery older = before.register("older", COUNT_A, List.of(0), DANA);
            RegisteredQuery newer = before.register("newer", COUNT_B, List.of(0), DANA);
            awaitAnswer(older, 100);
            awaitAnswer(newer, 100);
            before.pause("older");
            Thread.sleep(100);
            // A wide gap, so the shared reader of the restart is still crossing it when the newer
            // query registers ahead of it. Small transactions at its two ends, where the seams fall.
            write(topic, 101, 150, 1);
            write(topic, 151, 20_150, 200);
            write(topic, 20_151, 20_200, 1);
            awaitAnswer(newer, 20_200);
            checkpointerOf(older).checkpointNow();
            checkpointerOf(newer).checkpointNow();
            registries.remove(before);
        }
        // Written while nothing runs, so the records at the newer query's position are there when
        // the shared reader reaches it: the bound then has a record on it, not a marker, and a
        // reader that handed that record over would have given it to the older query alone.
        write(topic, 20_201, 20_250, 1);

        try (QueryRegistry after = registry(topic, checkpoints)) {
            // Older first: the shared reader starts at 100, and the newer, at 20,200, joins ahead of it.
            RegisteredQuery older = after.register("older", COUNT_A, List.of(0), DANA);
            RegisteredQuery newer = after.register("newer", COUNT_B, List.of(0), DANA);
            // And a third, from nothing: behind both.
            RegisteredQuery fresh = after.register("fresh", COUNT_C, List.of(0), DANA);
            write(topic, 20_251, 20_300, 1);
            for (RegisteredQuery query : List.of(older, newer, fresh)) {
                awaitAnswer(query, 20_300);
            }
            Thread.sleep(500);
            assertThat(newer.feed().describe()).contains("one reader shared with 2 other queries");
            assertExactly(older, 20_300, 20_200);
            assertExactly(newer, 20_300, 100);
            assertExactly(fresh, 20_300, 20_300);
            registries.remove(after);
        }
    }

    // ---------------------------------------------------------------------------------------

    private QueryRegistry registry(String topic, Path checkpoints) {
        Map<String, String> options = new HashMap<>();
        options.put("bootstrap.servers", KafkaBroker.bootstrap());
        options.put("topic", topic);
        options.put("schema", "user_id:STRING,amount:INT64");
        PluginSourceFeeds feeds = new PluginSourceFeeds().bind(new SourceBinding("txn", "kafka", options));
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN).feedingFrom(feeds);
        if (checkpoints != null) {
            registry.checkpointingTo(
                    checkpoints,
                    Configuration.builder()
                            .set("pravaha.checkpoint.interval", "1h")
                            .set("pravaha.checkpoint.timeout", "30s")
                            .build());
        }
        registries.add(registry);
        return registry;
    }

    /**
     * Records {@code from..to}, amount equal to the number, in committed transactions of {@code
     * perTransaction}; after every third, an aborted transaction of two records nobody may see.
     */
    private void write(String topic, int from, int to, int perTransaction) {
        int transactions = 0;
        for (int start = from; start <= to; start += perTransaction) {
            producer.beginTransaction();
            for (int i = start; i < Math.min(start + perTransaction, to + 1); i++) {
                KafkaBroker.sendAsync(producer, topic, 0, "u" + i, "{\"user_id\":\"u" + i + "\",\"amount\":" + i + "}");
            }
            producer.commitTransaction();
            if (++transactions % 3 == 0) {
                producer.beginTransaction();
                KafkaBroker.sendAsync(producer, topic, 0, "x", "{\"user_id\":\"x\",\"amount\":" + ABORTED + "}");
                KafkaBroker.sendAsync(producer, topic, 0, "x", "{\"user_id\":\"x\",\"amount\":" + ABORTED + "}");
                producer.flush();
                producer.abortTransaction();
            }
        }
    }

    private static long sum(long to) {
        return to * (to + 1) / 2;
    }

    private static List<Long> answer(RegisteredQuery query) {
        query.commit();
        for (Object[] row : query.view().scan()) {
            return List.of(
                    row[0] == null ? 0L : ((Number) row[0]).longValue(),
                    row[1] == null ? 0L : ((Number) row[1]).longValue());
        }
        return List.of(0L, 0L);
    }

    private static void awaitAnswer(RegisteredQuery query, long count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (answer(query).get(0) < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(query.name() + " holds " + answer(query) + ", expected " + count
                        + " records (query " + query.state() + ", " + query.failure() + ")");
            }
            Thread.sleep(20);
        }
    }

    private static void assertExactly(RegisteredQuery query, long count, long ingested) {
        assertThat(answer(query))
                .as("%s: every committed record once, and no aborted one", query.name())
                .isEqualTo(List.of(count, sum(count)));
        assertThat(query.rowsIn())
                .as("%s: what ingest delivered since it started or was restored", query.name())
                .isEqualTo(ingested);
    }

    private OrderWatch watch(RegisteredQuery query) {
        OrderWatch watch = new OrderWatch();
        Subscription subscription = query.subscribeFromSnapshot(
                SubscriptionOptions.of(1_000_000, SubscriptionOptions.Overflow.FAIL), SubscriptionFilter.none(), watch);
        closeables.add(subscription);
        return watch;
    }

    /** After the snapshot, every commit's inserts are later in the topic than all before them. */
    static final class OrderWatch implements SubscriptionListener {
        private final AtomicReference<Long> highest = new AtomicReference<>(Long.MIN_VALUE);
        private final List<String> violations = new CopyOnWriteArrayList<>();
        private volatile long seen;

        @Override
        public void onSnapshot(List<ViewChange> rows, long frontier) {
            for (ViewChange row : rows) {
                highest.accumulateAndGet(((Number) row.values()[1]).longValue(), Math::max);
                seen += row.weight();
            }
        }

        @Override
        public void onCommit(List<ViewChange> changes, long frontier) {
            long before = highest.get();
            for (ViewChange change : changes) {
                if (change.weight() <= 0) {
                    violations.add("a retraction from an append-only topic: " + change);
                    continue;
                }
                long amount = ((Number) change.values()[1]).longValue();
                if (amount <= before) {
                    violations.add(amount + " arrived after " + before);
                }
                highest.accumulateAndGet(amount, Math::max);
                seen += change.weight();
            }
        }

        List<String> violations() {
            return violations;
        }

        long seen() {
            return seen;
        }
    }

    private static PeriodicCheckpointer checkpointerOf(RegisteredQuery query) throws ReflectiveOperationException {
        java.lang.reflect.Field field = RegisteredQuery.class.getDeclaredField("checkpointer");
        field.setAccessible(true);
        return (PeriodicCheckpointer) field.get(query);
    }
}
