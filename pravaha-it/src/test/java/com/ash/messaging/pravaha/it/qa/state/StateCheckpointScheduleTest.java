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
package com.ash.messaging.pravaha.it.qa.state;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATE-001..012 -- checkpoints are written, and written on a schedule.
 *
 * <p>{@code PeriodicCheckpointer} exists because nothing was calling {@code QueryExecution.checkpoint}
 * on its own. These cases check the schedule itself.
 */
@Timeout(120)
class StateCheckpointScheduleTest extends StateTestSupport {

    private static long countCheckpointFiles(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("checkpoint-") && n.endsWith(".bin"))
                    .count();
        }
    }

    @Test
    void state001_theFirstCheckpointArrivesOneIntervalAfterStartNotImmediately(@TempDir Path root) throws Exception {
        ViewCatalog views = new ViewCatalog();
        Configuration cfg = Configuration.builder()
                .set("pravaha.checkpoint.interval", "200ms")
                .set("pravaha.checkpoint.keep", "3")
                .set("pravaha.checkpoint.timeout", "5s")
                .build();
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T).checkpointingTo(root, cfg)) {
            long t0 = System.nanoTime();
            registry.register("w", WIN_SQL, List.of(0), DANA);

            long deadlineFor100 = t0 + Duration.ofMillis(100).toNanos();
            while (System.nanoTime() < deadlineFor100) {
                Thread.onSpinWait();
            }
            assertThat(countCheckpointFiles(root.resolve("w")))
                    .as("no checkpoint yet at 100ms into a 200ms interval")
                    .isZero();

            long deadlineFor1000 = t0 + Duration.ofMillis(1000).toNanos();
            while (System.nanoTime() < deadlineFor1000) {
                Thread.sleep(5);
            }
            List<Long> ids = new FileCheckpointStore(root.resolve("w")).availableIds();
            assertThat(ids)
                    .as("at least one checkpoint by 1s at a 200ms interval")
                    .isNotEmpty();
            assertThat(ids.stream().max(Long::compare).orElseThrow()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void state002_checkpointsKeepBeingTakenAtRoughlyTheConfiguredInterval() throws Exception {
        // Copy-on-write, not a synchronized list. A synchronized list guards each call and not an
        // iteration, so streaming it while the checkpointer thread is still appending threw
        // ConcurrentModificationException out of a passing assertion under a loaded parallel build
        // -- a failure that names this test rather than the race inside it.
        List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (RawExecution win = rawWindowed()) {
            win.feedAt("u1", 100, 1_000_000_000L);
            win.feedAt("u1", 102, 2_000_000_000L);
            try (var dir = new TempDirHolder();
                    PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                            win.execution,
                            new FileCheckpointStore(dir.path),
                            Duration.ofMillis(200),
                            3,
                            Duration.ofSeconds(5),
                            log::add)) {
                checkpointer.start();
                sleep(2000);
                PeriodicCheckpointer.Stats stats = checkpointer.stats();
                assertThat(stats.taken()).as("taken in [8,11] over 2s at 200ms").isBetween(8L, 11L);
                assertThat(stats.failed()).isZero();
                assertThat(log.stream()
                                .filter(l -> l.matches("checkpoint \\d+ stored, \\d+ bytes"))
                                .count())
                        .isGreaterThanOrEqualTo(8);
            }
        }
    }

    @Test
    void state003_checkpointNowTakesExactlyOneCheckpointAndReturnsIt() throws Exception {
        try (RawExecution win = rawWindowed()) {
            win.feedAt("u1", 100, 1_000_000_000L);
            win.feedAt("u1", 102, 2_000_000_000L);
            try (var dir = new TempDirHolder();
                    PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                            win.execution,
                            new FileCheckpointStore(dir.path),
                            Duration.ofHours(1),
                            3,
                            Duration.ofSeconds(5),
                            m -> {})) {
                var c = checkpointer.checkpointNow();
                assertThat(c.id()).isEqualTo(1);
                try (Stream<Path> files = Files.list(dir.path)) {
                    List<String> names =
                            files.map(p -> p.getFileName().toString()).toList();
                    assertThat(names).containsExactly("checkpoint-1.bin");
                }
                PeriodicCheckpointer.Stats stats = checkpointer.stats();
                assertThat(stats.taken()).isEqualTo(1);
                assertThat(stats.failed()).isZero();
                assertThat(stats.pruned()).isZero();
                FileCheckpointStore store = new FileCheckpointStore(dir.path);
                assertThat(store.load(1)).hasValueSatisfying(loaded -> {
                    // Checkpoint is a record whose operatorState values are byte[], and
                    // byte[] has no structural equals -- comparing the record directly would
                    // always fail across a store/load round trip even when the bytes agree.
                    assertThat(loaded.id()).isEqualTo(c.id());
                    assertThat(loaded.offsets()).isEqualTo(c.offsets());
                    assertThat(loaded.operatorState().keySet())
                            .isEqualTo(c.operatorState().keySet());
                    loaded.operatorState()
                            .forEach((key, bytes) -> assertThat(bytes)
                                    .isEqualTo(c.operatorState().get(key)));
                });
            }
        }
    }

    @Test
    void state004_idsAreMonotonicWithinARun() throws Exception {
        try (RawExecution win = rawWindowed();
                var dir = new TempDirHolder();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        win.execution,
                        new FileCheckpointStore(dir.path),
                        Duration.ofHours(1),
                        10,
                        Duration.ofSeconds(5),
                        m -> {})) {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                ids.add(checkpointer.checkpointNow().id());
            }
            assertThat(ids).containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(new FileCheckpointStore(dir.path).availableIds()).containsExactly(5L, 4L, 3L, 2L, 1L);
        }
    }

    @Test
    void state005_idsResumeAboveTheHighestAlreadyOnDiskAfterARestart(@TempDir Path dir) throws Exception {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        store.store(cp(7));
        store.store(cp(9));

        try (RawExecution win = rawWindowed();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        win.execution, store, Duration.ofHours(1), 10, Duration.ofSeconds(5), m -> {})) {
            var c = checkpointer.checkpointNow();
            assertThat(c.id()).isEqualTo(10);
            assertThat(store.availableIds()).containsExactly(10L, 9L, 7L);
        }
    }

    @Test
    void state006_theCheckpointThreadIsADaemonNamedPravahaCheckpointer() throws Exception {
        try (RawExecution win = rawWindowed();
                var dir = new TempDirHolder();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        win.execution,
                        new FileCheckpointStore(dir.path),
                        Duration.ofMillis(200),
                        3,
                        Duration.ofSeconds(5),
                        m -> {})) {
            checkpointer.start();
            sleep(300);
            // The property is that checkpointing cannot be the reason a JVM will not exit. It used
            // to belong to a thread named pravaha-checkpointer, one per checkpointed query; W9-3
            // moved the schedule to the process's shared clock, so the property moved with it --
            // and there is now one such thread for the node rather than one per query, which is the
            // point of the change.
            List<Thread> named = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> "pravaha-clock".equals(t.getName()))
                    .toList();
            assertThat(named).as("one clock for the process").hasSize(1);
            assertThat(named.get(0).isDaemon())
                    .as("a checkpointer must never be why a JVM will not exit")
                    .isTrue();
        }
    }

    @Test
    void state007_closeStopsTheSchedule() throws Exception {
        try (RawExecution win = rawWindowed();
                var dir = new TempDirHolder()) {
            PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                    win.execution,
                    new FileCheckpointStore(dir.path),
                    Duration.ofMillis(100),
                    100,
                    Duration.ofSeconds(5),
                    m -> {});
            checkpointer.start();
            sleep(500);
            checkpointer.close();
            long n = checkpointer.stats().taken();
            assertThat(n).isBetween(3L, 6L);
            sleep(1000);
            long m = checkpointer.stats().taken();
            assertThat(m)
                    .as("no growth after close(), a 1000ms window is ten intervals wide")
                    .isEqualTo(n);
        }
    }

    @Test
    void state008_startTwiceDoesNotStartTwoSchedules() throws Exception {
        try (RawExecution win = rawWindowed();
                var dir = new TempDirHolder();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        win.execution,
                        new FileCheckpointStore(dir.path),
                        Duration.ofMillis(200),
                        100,
                        Duration.ofSeconds(5),
                        m -> {})) {
            checkpointer.start();
            checkpointer.start();
            sleep(1000);
            // The rate is the assertion, and it always was: at a 200ms interval over 1000ms, one
            // schedule gives 3-6 and two give twice that. The thread count beside it was a second
            // way of saying the same thing, and it stopped being available when W9-3 put every
            // query's schedule on one shared clock.
            assertThat(checkpointer.stats().taken())
                    .as("one schedule, not two: a second start() must be a no-op")
                    .isBetween(3L, 6L);
        }
    }

    @Test
    void state009_keepBelowOneIsRefusedAtConstruction(@TempDir Path dir) {
        try (RawExecution win = rawWindowed()) {
            FileCheckpointStore store = new FileCheckpointStore(dir);
            assertThatThrownBy(() -> new PeriodicCheckpointer(
                            win.execution, store, Duration.ofSeconds(1), 0, Duration.ofSeconds(5), m -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one checkpoint must be kept, asked to keep 0")
                    .hasMessageContaining("Keeping none means every restart starts from nothing");
            assertThatThrownBy(() -> new PeriodicCheckpointer(
                            win.execution, store, Duration.ofSeconds(1), -1, Duration.ofSeconds(5), m -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("asked to keep -1");
        }
    }

    @Test
    void state010_aNonPositiveIntervalIsRefusedAtConstruction(@TempDir Path dir) {
        try (RawExecution win = rawWindowed()) {
            FileCheckpointStore store = new FileCheckpointStore(dir);
            assertThatThrownBy(() ->
                            new PeriodicCheckpointer(win.execution, store, null, 3, Duration.ofSeconds(5), m -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("checkpoint interval must be positive, got null");
            assertThatThrownBy(() -> new PeriodicCheckpointer(
                            win.execution, store, Duration.ZERO, 3, Duration.ofSeconds(5), m -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("checkpoint interval must be positive, got PT0S");
            assertThatThrownBy(() -> new PeriodicCheckpointer(
                            win.execution, store, Duration.ofSeconds(-1), 3, Duration.ofSeconds(5), m -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("checkpoint interval must be positive, got PT-1S");
        }
    }

    @Test
    void state011_pravahaCheckpointStarIsReadExactlyAsFromSays(@TempDir Path dir) throws Exception {
        // (a) empty configuration -- default interval is 1 minute, so 1200ms takes nothing.
        try (RawExecution a = rawWindowed();
                PeriodicCheckpointer checkpointerA = PeriodicCheckpointer.from(
                        a.execution,
                        new FileCheckpointStore(dir.resolve("a")),
                        Configuration.builder().build(),
                        m -> {})) {
            checkpointerA.start();
            sleep(1200);
            assertThat(checkpointerA.stats().taken()).isZero();
        }

        // (b) interval, keep and timeout all set.
        Configuration cfgB = Configuration.builder()
                .set("pravaha.checkpoint.interval", "250ms")
                .set("pravaha.checkpoint.keep", "2")
                .set("pravaha.checkpoint.timeout", "7s")
                .build();
        try (RawExecution b = rawWindowed();
                PeriodicCheckpointer checkpointerB = PeriodicCheckpointer.from(
                        b.execution, new FileCheckpointStore(dir.resolve("b")), cfgB, m -> {})) {
            checkpointerB.start();
            sleep(1200);
            assertThat(checkpointerB.stats().taken()).isBetween(2L, 5L);
            assertThat(new FileCheckpointStore(dir.resolve("b")).availableIds()).hasSize(2);
        }

        // (c) only keep overridden -- interval is still the 1 minute default.
        Configuration cfgC =
                Configuration.builder().set("pravaha.checkpoint.keep", "2").build();
        try (RawExecution c = rawWindowed();
                PeriodicCheckpointer checkpointerC = PeriodicCheckpointer.from(
                        c.execution, new FileCheckpointStore(dir.resolve("c")), cfgC, m -> {})) {
            checkpointerC.start();
            sleep(1200);
            assertThat(checkpointerC.stats().taken()).isZero();
        }

        // INVERTED for CFG-17. This asserted the documentation gap as a fact: application.yaml's
        // checkpoint: block documented directory, interval and keep and never mentioned
        // pravaha.checkpoint.timeout, which PeriodicCheckpointer.from reads -- and that absence is
        // how docs/qa/cases/CFG.md came to record the key as one with a reader and no writer, which
        // it is not. The key is now in the file with the other three, and this asserts the closure
        // rather than the gap: all four names, in the block an operator reads.
        Path yaml = Path.of("").toAbsolutePath();
        Path root = yaml;
        while (root != null && !Files.exists(root.resolve("pravaha-server/src/main/resources/application.yaml"))) {
            root = root.getParent();
        }
        assertThat(root)
                .as("must be able to find application.yaml from the test's working directory")
                .isNotNull();
        String yamlText = Files.readString(root.resolve("pravaha-server/src/main/resources/application.yaml"));
        int checkpointBlock = yamlText.indexOf("checkpoint:");
        int nextTopLevel = yamlText.indexOf("\n  watermark:", checkpointBlock);
        String block = yamlText.substring(checkpointBlock, nextTopLevel);
        assertThat(block)
                .as("every key PeriodicCheckpointer.from reads has to be in the block an operator reads")
                .contains("directory:")
                .contains("interval:")
                .contains("keep:")
                .contains("timeout:");
    }

    @Test
    void state012_aCheckpointTakenWhileRowsAreArrivingDoesNotLoseOrDuplicateThem() throws Exception {
        try (RawExecution win = rawWindowed();
                var dir = new TempDirHolder();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        win.execution,
                        new FileCheckpointStore(dir.path),
                        Duration.ofHours(1),
                        10,
                        Duration.ofSeconds(5),
                        m -> {})) {
            Thread feeder = new Thread(() -> {
                for (int n = 0; n < 5000; n++) {
                    win.feedAt("u1", 1, (long) n * 1_000_000L);
                }
            });
            feeder.start();
            for (int i = 0; i < 5; i++) {
                checkpointer.checkpointNow();
                sleep(50);
            }
            feeder.join(Duration.ofSeconds(30).toMillis());
            assertThat(feeder.isAlive()).isFalse();
            assertThat(win.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            win.execution.checkHealth();
            win.advanceWatermark(11_000_000_000L);
            win.execution.checkHealth();

            var totalForU1 = win.emitted.stream()
                    .filter(row -> "u1".equals(row.asString(0)))
                    .mapToLong(row -> row.asLong(1))
                    .sum();
            assertThat(totalForU1).isEqualTo(5000L);
            assertThat(new FileCheckpointStore(dir.path).availableIds()).hasSize(5);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** A throwaway temp directory this test controls the lifetime of, outside {@code @TempDir}. */
    private static final class TempDirHolder implements AutoCloseable {
        final Path path;

        TempDirHolder() throws Exception {
            path = Files.createTempDirectory("state-qa-");
        }

        @Override
        public void close() throws Exception {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }
}
