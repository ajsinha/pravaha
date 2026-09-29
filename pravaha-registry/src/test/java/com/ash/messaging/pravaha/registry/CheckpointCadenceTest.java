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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OBS-1: a checkpointed query keeps its cadence while it is busy and the machine is loaded.
 *
 * <p>One query on the tutorials' node went four minutes without a checkpoint at a one-minute
 * interval, once, and it was not reproduced. This is the property that sighting doubted, held as a
 * test: the tutorials' shape (a per-user SUM over one-minute tumbling windows), checkpointed every
 * second, fed as fast as one thread can while as many threads as there are processors spin beside
 * it; no gap between stored checkpoints may exceed three intervals, and none may fail.
 *
 * <p>Twenty seconds by default, so it can sit in the ordinary suite; {@code
 * -Dpravaha.test.checkpoint.cadence.seconds=60} runs it for the minute OBS-1 asked for. Reading the
 * scheduler found two ways a schedule could stop for good ({@code SharedClock}: a firing that could
 * not be handed to a thread left its flag set, and an exception out of the timer cancelled the
 * schedule), both fixed; a firing that overruns its period now says so, so a second sighting is
 * explained by the log rather than doubted.
 */
class CheckpointCadenceTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("ts", Types.timestamp())
            .eventTime("ts")
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private static final String SPEND_PER_MINUTE = "SELECT window_start, window_end, user_id, SUM(amount) AS spent "
            + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(ts), INTERVAL '1' MINUTE)) "
            + "GROUP BY window_start, window_end, user_id";

    private static final Duration INTERVAL = Duration.ofSeconds(1);
    private static final long SECONDS = Long.getLong("pravaha.test.checkpoint.cadence.seconds", 20);

    @Test
    @Timeout(300)
    void aBusyQueryOnALoadedMachineIsCheckpointedEveryIntervalWithNoLongGap(@TempDir Path root) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(SECONDS).toNanos();
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> load = new ArrayList<>();
        try (QueryRegistry registry = new QueryRegistry(new ViewCatalog(), TXN)
                .checkpointingTo(
                        root,
                        Configuration.builder()
                                .set("pravaha.checkpoint.interval", "1s")
                                .set("pravaha.checkpoint.timeout", "10s")
                                .build())) {
            RegisteredQuery query = registry.register("spend_per_minute", SPEND_PER_MINUTE, List.of(0, 2), DANA);

            for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
                Thread spinner = new Thread(() -> spin(stop), "cadence-load-" + i);
                spinner.setDaemon(true);
                spinner.start();
                load.add(spinner);
            }
            AtomicReference<Throwable> feedFailed = new AtomicReference<>();
            AtomicLong fed = new AtomicLong();
            Thread feeder = new Thread(
                    () -> {
                        try {
                            feed(query, stop, fed);
                        } catch (Throwable failure) {
                            feedFailed.set(failure);
                        }
                    },
                    "cadence-feeder");
            feeder.setDaemon(true);
            feeder.start();

            // Watched from outside, as an operator's age gauge watches it: each change of the last
            // stored checkpoint's time is one checkpoint, observed within 20 ms of it.
            List<Long> observedNanos = new ArrayList<>();
            long started = System.nanoTime();
            Optional<Instant> last = Optional.empty();
            while (System.nanoTime() < deadline) {
                Optional<Instant> now = query.lastCheckpoint();
                if (now.isPresent() && !now.equals(last)) {
                    observedNanos.add(System.nanoTime());
                    last = now;
                }
                Thread.sleep(20);
            }
            stop.set(true);
            feeder.join(30_000);

            long longestGapMillis = 0;
            long previous = started;
            for (long at : observedNanos) {
                longestGapMillis = Math.max(longestGapMillis, (at - previous) / 1_000_000L);
                previous = at;
            }
            longestGapMillis = Math.max(longestGapMillis, (System.nanoTime() - previous) / 1_000_000L - 50);
            System.out.printf(
                    "CHECKPOINT CADENCE: %d checkpoints in %d s at a %d ms interval, longest gap %d ms, %d rows fed, "
                            + "%d spinning threads, load %.1f%n",
                    observedNanos.size(),
                    SECONDS,
                    INTERVAL.toMillis(),
                    longestGapMillis,
                    fed.get(),
                    load.size(),
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                            .getSystemLoadAverage());

            assertThat(feedFailed.get()).as("the feeder").isNull();
            assertThat(fed.get()).as("rows fed: the query was busy").isPositive();
            assertThat(query.lastCheckpointFailure()).as("a checkpoint failure").isEmpty();
            assertThat(longestGapMillis)
                    .as(
                            "the longest time without a stored checkpoint, against three %d ms intervals",
                            INTERVAL.toMillis())
                    .isLessThanOrEqualTo(3 * INTERVAL.toMillis());
            assertThat(observedNanos.size()).isGreaterThanOrEqualTo((int) (SECONDS / 3));
        } finally {
            stop.set(true);
            for (Thread spinner : load) {
                spinner.join(5_000);
            }
        }
    }

    /** Burns a processor until told to stop: the concurrent load. */
    private static void spin(AtomicBoolean stop) {
        long x = 1;
        while (!stop.get()) {
            for (int i = 0; i < 100_000; i++) {
                x = x * 6364136223846793005L + 1442695040888963407L;
            }
        }
        if (x == 42) {
            System.out.print("");
        }
    }

    /** One thread feeding a thousand users as fast as it can, event time moving a millisecond a row. */
    private static void feed(RegisteredQuery query, AtomicBoolean stop, AtomicLong fed) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        String[] users = new String[1000];
        for (int i = 0; i < users.length; i++) {
            users[i] = "u" + i;
        }
        long eventTime = 1_790_413_560_000_000_000L;
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            while (!stop.get()) {
                for (int i = 0; i < 1000; i++) {
                    long handle = arena.allocate(layout.rowSize(64));
                    writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                    writer.setString(0, users[i]).setLong(1, i).setLong(2, eventTime);
                    writer.weight(1L)
                            .eventTimestampNanos(eventTime)
                            .sequence(fed.get())
                            .commit();
                    arena.trimTo(handle, writer.sizeSoFar());
                    while (!query.accept("txn", view.wrap(arena.regionOf(handle), arena.offsetOf(handle)))) {
                        Thread.onSpinWait();
                    }
                    fed.incrementAndGet();
                    eventTime += 1_000_000L;
                }
                query.awaitApplied(Duration.ofSeconds(30));
                query.commit();
                query.advanceWatermark(eventTime - 5_000_000_000L);
                arena.reset();
            }
        }
    }
}
