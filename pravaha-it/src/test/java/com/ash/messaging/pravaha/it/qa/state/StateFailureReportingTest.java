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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.CheckpointStore;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STATE-043..049 -- a failed checkpoint is reported, every time.
 *
 * <p>"A query that has silently not checkpointed for six hours looks exactly like one that has."
 * {@code PeriodicCheckpointer} reports on every failure rather than the first, and {@code
 * QueryRegistry} routes the reports onto the query rather than discarding them.
 *
 * <p>STATE-045 is NOT RUN: it needs a lane genuinely stuck mid-batch inside {@code
 * QueryExecution}'s compiled {@code InterpretedPipeline}, and the shipped API has no seam to inject
 * that -- {@code QueryExecution.start} compiles a plan straight into a {@code LanePipeline} with no
 * way to substitute a blocking {@code LaneProcessor} the way {@code LaneTest} does at the raw {@code
 * Lane} level. Building one would mean forking {@code QueryExecution}/{@code Lane} internals, out of
 * scope for this round. STATE-048 is NOT RUN: it needs a real node (H-SRV), the CLI and the console
 * API standing up together, not attempted this round -- see the final report for what else that
 * applies to.
 */
@Timeout(120)
class StateFailureReportingTest extends StateTestSupport {

    private static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** A store that always fails, for STATE-043/047. */
    private static final class AlwaysFailsStore implements CheckpointStore {
        @Override
        public void store(Checkpoint checkpoint) {
            throw new java.io.UncheckedIOException("disk full", new IOException("ENOSPC"));
        }

        @Override
        public Optional<Checkpoint> latest() {
            return Optional.empty();
        }

        @Override
        public List<Long> availableIds() {
            return List.of();
        }

        @Override
        public Optional<Checkpoint> load(long id) {
            return Optional.empty();
        }

        @Override
        public int prune(int keep) {
            return 0;
        }
    }

    @Test
    void state043_aStoreFailureDoesNotStopTheScheduleAndIsCountedEveryTime() {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        try (RawExecution win = rawWindowed();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        win.execution,
                        new AlwaysFailsStore(),
                        Duration.ofMillis(100),
                        3,
                        Duration.ofSeconds(5),
                        log::add)) {
            checkpointer.start();
            // Waited for rather than timed. A 100ms period over a 1000ms sleep was asserted to
            // produce 8-11 failures, and a loaded machine produced 7 -- which says the machine was
            // busy, not that the schedule stopped. What the case is about is that failures do not
            // stop it and that each one is counted, so it waits for a run of them instead.
            awaitFailures(checkpointer, 5, Duration.ofSeconds(15));
            checkpointer.close(); // stopped before sampling, so stats and log cannot drift apart
            PeriodicCheckpointer.Stats stats = checkpointer.stats();
            assertThat(stats.taken()).isZero();
            assertThat(stats.failed())
                    .as("the schedule kept firing through every failure")
                    .isGreaterThanOrEqualTo(5L);
            // log also carries start()'s one-off "checkpointing every..." line, which is not a
            // per-checkpoint report and does not count toward failed().
            List<String> failureLines =
                    log.stream().filter(l -> l.startsWith("checkpoint failed")).toList();
            assertThat((long) failureLines.size()).isEqualTo(stats.failed());
            int expectedN = 1;
            for (String line : failureLines) {
                assertThat(line)
                        .matches("checkpoint failed \\(" + expectedN + " so far\\): disk full\\. Recovery will "
                                + "fall back to the newest stored checkpoint, which is getting older");
                expectedN++;
            }
        }
    }

    @Test
    void state044_theFailureIsRecordedOnTheQueryWhereAnOperatorAsksAboutIt(@TempDir Path root) throws Exception {
        Assumptions.assumeTrue(posix(root), "requires POSIX permissions");
        ViewCatalog views = new ViewCatalog();
        Configuration cfg = Configuration.builder()
                .set("pravaha.checkpoint.interval", "100ms")
                .set("pravaha.checkpoint.keep", "3")
                .build();
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T).checkpointingTo(root, cfg)) {
            RegisteredQuery q = registry.register("w", WIN_SQL, List.of(0), DANA);

            // Harness note, found while writing this case: chmod'ing root/w itself (as STATE-044
            // literally says -- "make root/w read-only") does NOT make the store fail. Every
            // FileCheckpointStore.store() call opens with SensitiveFiles.createOwnerOnly(temporary),
            // whose first act is an unconditional narrow(parent, "rwx------") -- so the very next
            // checkpoint attempt silently restores root/w's own write bit before writing anything.
            // Confirmed directly: chmod'ing the leaf keeps checkpoints succeeding.
            chmod(root.resolve("w"), "r-x------");
            sleepMillis(400);
            // A second, independent finding while writing this case (FINDINGS.md ST-4):
            // checkpointFailures()/lastCheckpointFailure() do not count failures specifically -- they
            // count every log line PeriodicCheckpointer emits, because QueryRegistry wires
            // query::recordCheckpointFailure as the *general* log consumer (:503), not a
            // failure-only callback. Since the leaf-chmod self-heals and every attempt here actually
            // succeeds, the "failure" counter still climbs, and "last failure" holds a success line.
            assertThat(q.checkpointFailures()).isGreaterThan(0);
            assertThat(q.lastCheckpointFailure())
                    .as("checkpointFailures()/lastCheckpointFailure() are mislabeled: this holds a "
                            + "*success* line, because every store() here actually succeeded")
                    .isPresent()
                    .hasValueSatisfying(msg -> assertThat(msg).matches("checkpoint \\d+ stored, \\d+ bytes"));
            chmod(root.resolve("w"), "rwx------");

            // What genuinely blocks a store(): removing traversal on the checkpoint *root*, which
            // narrow() cannot repair because it cannot even resolve the path root/w without it.
            chmod(root, "r--------");
            sleepMillis(1000);
            assertThat(q.checkpointFailures()).isGreaterThanOrEqualTo(8);
            assertThat(q.lastCheckpointFailure())
                    .isPresent()
                    .hasValueSatisfying(msg ->
                            assertThat(msg).contains("checkpoint failed (").contains("cannot store checkpoint"));
            chmod(root, "rwx------");
        }
    }

    @Test
    void state046_aDirectoryThatBecomesUnwritableMidLifeDegradesRecoveryWithoutEndingIt(@TempDir Path root)
            throws Exception {
        Assumptions.assumeTrue(posix(root), "requires POSIX permissions");
        ViewCatalog views = new ViewCatalog();
        Configuration cfg = Configuration.builder()
                .set("pravaha.checkpoint.interval", "200ms")
                .set("pravaha.checkpoint.keep", "3")
                .build();
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T).checkpointingTo(root, cfg)) {
            RegisteredQuery q = registry.register("w", WIN_SQL, List.of(0), DANA);
            PeriodicCheckpointer checkpointer = checkpointerOf(q);
            sleepMillis(1000);
            long a = checkpointer.stats().taken();
            assertThat(a).isBetween(2L, 6L);

            // Root, not root/w -- see STATE-044's harness note: chmod'ing the leaf directory is
            // silently self-healed by createOwnerOnly's unconditional narrow() on every store(). Read
            // stats() (in-memory) rather than the filesystem while root is unwritable: reading
            // root/w's own listing also needs to traverse root, which is exactly what is blocked.
            chmod(root, "r--------");
            sleepMillis(3000);
            long b = checkpointer.stats().taken();
            assertThat(b).as("no new checkpoint was stored while unwritable").isEqualTo(a);
            assertThat(q.checkpointFailures()).isGreaterThanOrEqualTo(12);

            chmod(root, "rwx------");
            sleepMillis(1000);
            long c = checkpointer.stats().taken();
            assertThat(c).as("recovers once writable again").isGreaterThan(b);
            assertThat(new FileCheckpointStore(root.resolve("w")).availableIds())
                    .as("the three pre-existing checkpoints were never lost, only not added to")
                    .hasSize(3);
            assertThat(q.state().toString()).isEqualTo("RUNNING");
            assertThat(registry.find("w")).isPresent();
        }
    }

    private static void chmod(Path dir, String posixString) throws IOException {
        java.nio.file.Files.setPosixFilePermissions(
                dir, java.nio.file.attribute.PosixFilePermissions.fromString(posixString));
    }

    @Test
    void state047_theFailureMessageSaysTheFallbackIsGettingOlder() {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        try (RawExecution win = rawWindowed();
                PeriodicCheckpointer checkpointer = new PeriodicCheckpointer(
                        win.execution,
                        new AlwaysFailsStore(),
                        Duration.ofMillis(100),
                        3,
                        Duration.ofSeconds(5),
                        log::add)) {
            checkpointer.start();
            sleepMillis(500);
            List<String> failureLines =
                    log.stream().filter(l -> l.startsWith("checkpoint failed")).toList();
            assertThat(failureLines.size()).isGreaterThanOrEqualTo(3);
            for (int i = 0; i < 3; i++) {
                int n = i + 1;
                assertThat(failureLines.get(i))
                        .isEqualTo("checkpoint failed (" + n + " so far): disk full. Recovery will fall back to "
                                + "the newest stored checkpoint, which is getting older");
                // The name is missing -- the message does not say which query is failing. Checked
                // against the exact string above (not a substring probe: ordinary English words in
                // the fixed text, like "newest", already contain single letters such as "w").
                assertThat(failureLines.get(i)).doesNotContain("'w'").doesNotContain("query 'w'");
            }
        }
    }

    @Test
    void state049_aSharedComputationHasOneCheckpointerAndOneFailureCounterReachableUnderEitherName(@TempDir Path root)
            throws Exception {
        Assumptions.assumeTrue(posix(root), "requires POSIX permissions");
        ViewCatalog views = new ViewCatalog();
        Configuration cfg = Configuration.builder()
                .set("pravaha.checkpoint.interval", "100ms")
                .set("pravaha.checkpoint.keep", "3")
                .build();
        try (QueryRegistry registry = new QueryRegistry(views, TXN_T).checkpointingTo(root, cfg)) {
            registry.register("alpha", WIN_SQL, List.of(0), DANA);
            registry.register("beta", WIN_SQL, List.of(0), DANA);
            // root, not root/alpha -- see STATE-044's harness note.
            chmod(root, "r--------");
            sleepMillis(1000);

            assertThat(registry.size()).isEqualTo(1);
            assertThat(registry.names()).containsExactly("alpha", "beta");
            RegisteredQuery a = registry.find("alpha").orElseThrow();
            RegisteredQuery b = registry.find("beta").orElseThrow();
            assertThat(a).isSameAs(b);
            assertThat(a.checkpointFailures()).isGreaterThanOrEqualTo(8);
            assertThat(a.checkpointFailures()).isEqualTo(b.checkpointFailures());

            long named = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> "pravaha-checkpointer".equals(t.getName()))
                    .count();
            assertThat(named).isEqualTo(1);
            chmod(root, "rwx------");
        }
    }

    private static void awaitFailures(PeriodicCheckpointer checkpointer, long target, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (checkpointer.stats().failed() < target) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("only " + checkpointer.stats().failed() + " of " + target
                        + " checkpoint failures were counted within " + within
                        + " -- the schedule stopped after a failure rather than continuing");
            }
            sleepMillis(20);
        }
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
