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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.IngestPump;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;
import com.ash.messaging.pravaha.state.checkpoint.FileCheckpointStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATE-050..064 -- restore: what round-trips, and what does not.
 *
 * <p>The section the area exists for. As executed on current {@code develop}, the case file's own
 * "Three facts" premise (fact 1: nothing calls {@code restore}) no longer holds -- see the package
 * Javadoc and STATE-050 below, which re-derives the facts live with the exact commands the case
 * names rather than trusting the case's cached answer.
 */
@Timeout(180)
class StateRestoreTest extends StateTestSupport {

    /** Walks up from the working directory to find the repository root (where pravaha-server lives). */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("pravaha-server/src/main/resources/application.yaml"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("must be able to find the repository root").isNotNull();
        return dir;
    }

    private static List<String> grep(String pattern, Path root) throws Exception {
        // --exclude-dir=.claude, because QA agents work in git worktrees under it and those are
        // full copies of this repository. Without it a check that counts call sites reports one per
        // running agent -- three here, against an expected one -- and reads as a product change.
        Process process = new ProcessBuilder(
                        "grep",
                        "-rn",
                        pattern,
                        "--include=*.java",
                        "--exclude-dir=.claude",
                        "--exclude-dir=target",
                        ".")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        process.waitFor();
        return lines;
    }

    @Test
    void state050_noShippedCodePathCallsRestoreOrLatest_asAuthored() throws Exception {
        // FAIL as authored, recorded rather than silently reconciled -- see FINDINGS.md ST-2 and the
        // package Javadoc. The case's fact 1 ("every reference outside pravaha-state/src/main is a
        // test") is no longer true: QueryRegistry.restoreFrom (pravaha-registry/src/main) calls both
        // CheckpointStore.latest() and QueryExecution.restore(), and it is wired into register(), a
        // real, shipped code path. Re-derived live with the case's own grep commands rather than
        // trusting a cached answer. The calls moved from QueryRegistry.restoreFrom to
        // QueryCheckpoints.restore when the checkpoint placement came out of the registry
        // (ADR-046); they are the same calls on the same shipped path -- register() still makes
        // them on every registration a checkpoint root is configured for.
        Path root = repoRoot();

        List<String> latestCalls = grep("\\.latest()", root).stream()
                .filter(l -> l.contains("/src/main/"))
                .toList();
        assertThat(latestCalls)
                .as("STATE-050 expects only the CheckpointStore declaration and FileCheckpointStore's "
                        + "implementation; there is now a third, real caller")
                .hasSize(1);
        assertThat(latestCalls.get(0)).contains("QueryCheckpoints.java");

        List<String> restoreCalls = grep("\\.restore(", root).stream()
                .filter(l -> l.contains("/src/main/"))
                .toList();
        assertThat(restoreCalls)
                .as("STATE-050 expects only PartitionHandoff.java (an unrelated type); there are now two "
                        + "more, and both are the one shipped path: QueryCheckpoints.restore calls "
                        + "execution.restore, and QueryRegistry.start calls QueryCheckpoints.restore")
                .hasSize(3);
        assertThat(restoreCalls.stream().anyMatch(l -> l.contains("QueryCheckpoints.java")))
                .as("QueryCheckpoints.restore calls execution.restore(...), for register()")
                .isTrue();

        List<String> restoreStateCalls = grep("restoreState", root).stream()
                .filter(l -> l.contains("/src/main/"))
                .toList();
        // This one is unchanged from the case's expectation: the declaration, and its one caller
        // inside QueryExecution.restore -- which is itself now reachable, but the grep for
        // restoreState alone does not show that; STATE-050's own conclusion ("the write half is
        // wired, the read half is not") is what this whole case disproves, not this specific grep.
        assertThat(restoreStateCalls).hasSize(2);

        String pravahaNode = Files.readString(
                root.resolve("pravaha-server/src/main/java/com/ash/messaging/pravaha/server/PravahaNode.java"));
        assertThat(pravahaNode).contains("checkpointingTo");
        assertThat(pravahaNode).doesNotContain(".restore(");
        // PravahaNode itself still never calls restore directly -- but it does not need to: as of
        // this build, QueryRegistry.register calls QueryCheckpoints.restore on every registration a
        // checkpoint root is configured for, PravahaNode.start's registry.checkpointingTo call
        // (:370) is what arms it, and every subsequent journal-replay registration on restart goes
        // through the same register() method. The restore path is reachable from a live node without
        // PravahaNode itself naming "restore" anywhere.
    }

    @Test
    void state051_aProjectionsCheckpointContainsNoOperatorStateAtAll(@TempDir Path dir) throws Exception {
        try (RawExecution prj = rawProjection()) {
            InMemoryTxnReader reader = InMemoryTxnReader.ofRows(10_000, "u1", 1L, 0L);
            IngestPump pump = prj.execution.pumpInto(0, reader, BackpressurePolicy.defaults());
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (pump.rowsPumped() < 10_000) {
                pump.pumpOnce(2000);
                prj.execution.checkHealth();
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("pump did not deliver all rows in time");
                }
            }
            assertThat(prj.execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
            assertThat(prj.execution.metrics().get(0).rowsIn()).isEqualTo(10_000L);

            com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer checkpointer =
                    new com.ash.messaging.pravaha.runtime.exec.PeriodicCheckpointer(
                            prj.execution,
                            new FileCheckpointStore(dir),
                            Duration.ofHours(1),
                            3,
                            Duration.ofSeconds(5),
                            m -> {});
            Checkpoint c = checkpointer.checkpointNow();

            assertThat(c.operatorState()).isEmpty();
            assertThat(c.sizeBytes()).isZero();
            assertThat(c.offsets()).hasSize(1);
            assertThat(c.toString()).isEqualTo("Checkpoint[1, 1 partitions, 0 operators, 0 bytes]");

            String token = c.offsets().values().iterator().next();
            long fileSize = Files.size(dir.resolve("checkpoint-1.bin"));
            // header(24) + offsetCount(4) + one entry: writeUTF("partition-0")=2+11=13, writeUTF(token)
            // =2+tokenBytes + stateCount(4) + trailer(12).
            long expected = 24 + 4 + 13 + (2 + utf8(token).length) + 4 + 12;
            assertThat(fileSize).as("token=<" + token + ">").isEqualTo(expected);
        }
    }

    @Test
    void state052_aKeyedNonWindowedAggregateIsAlsoNotStateful() {
        // FAIL as authored, in a new and more interesting way: a keyed, non-windowed GROUP BY is not
        // merely "not stateful" in a checkpoint -- the planner refuses to build it at all now, with
        // PRV-2050 (SQL_UNBOUNDED_STATE): "GROUP BY user_id has no bound on its key space, so its
        // state grows with the number of distinct keys and never shrinks." So the plan shape
        // STATE-052 says checkpoints nothing can no longer be registered in the first place -- a
        // stronger, earlier guard than the one this case describes. See FINDINGS.md ST-2.
        assertThatThrownBy(() -> raw(TXN, "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2050")
                .hasMessageContaining("GROUP BY user_id has no bound on its key space");
        // Global aggregate. This used to checkpoint nothing too, and this case recorded that as the
        // expected answer -- it was the defect CKPT-2: the view came back from the checkpoint and
        // the accumulators did not, so the next answer was published beside the restored one. Its
        // accumulators and last published answer are now a lane snapshot like any other operator's.
        try (RawExecution count = raw(TXN, "SELECT COUNT(*) AS n FROM txn")) {
            count.feed("u1", 100);
            count.feed("u2", 5);
            assertThat(count.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            Checkpoint c = count.execution.checkpoint(1, Duration.ofSeconds(5));
            assertThat(c.operatorState().keySet()).containsExactly("lane-0");
            byte[] s = c.operatorState().get("lane-0");
            assertThat(intAt(s, 0)).isEqualTo(0x50565354);
            assertThat(intAt(s, 4)).isEqualTo(5);
        }
        // Filter.
        try (RawExecution filtered = raw(TXN, "SELECT user_id, amount FROM txn WHERE amount > 50")) {
            filtered.feed("u1", 100);
            filtered.feed("u1", 10);
            assertThat(filtered.execution.awaitQuiescent(Duration.ofSeconds(10)))
                    .isTrue();
            assertRawViewCheckpointsNothing(filtered);
        }
    }

    private static void assertRawViewCheckpointsNothing(RawExecution execution) {
        Checkpoint c = execution.execution.checkpoint(1, Duration.ofSeconds(5));
        assertThat(c.operatorState()).isEmpty();
        assertThat(c.sizeBytes()).isZero();
    }

    @Test
    void state053_aWindowedAggregateWritesRealBytesAsAVersionedSnapshot() {
        try (RawExecution win = rawWindowed()) {
            win.feedAt("u1", 100, 1_000_000_000L);
            win.feedAt("u1", 102, 2_000_000_000L);
            assertThat(win.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();

            Checkpoint c = win.execution.checkpoint(1, Duration.ofSeconds(5));
            assertThat(c.operatorState().keySet()).containsExactly("lane-0");
            byte[] s = c.operatorState().get("lane-0");
            assertThat(s.length).isGreaterThan(0);
            assertThat(c.sizeBytes()).isEqualTo(s.length);

            assertThat(s[0]).isEqualTo((byte) 0x50);
            assertThat(s[1]).isEqualTo((byte) 0x56);
            assertThat(s[2]).isEqualTo((byte) 0x53);
            assertThat(s[3]).isEqualTo((byte) 0x54);
            assertThat(intAt(s, 4)).isEqualTo(5); // SNAPSHOT_VERSION -- 5 since ADR-044 changed the windowed section
            assertThat(intAt(s, 8)).isEqualTo(1); // windowed operator count
        }
    }

    private static int intAt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24)
                | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8)
                | (b[offset + 3] & 0xFF);
    }

    @Test
    void state054_aJoinWritesBytesThroughTheOtherBranchOfIsStateful() {
        try (RawJoinExecution join = rawJoin()) {
            join.feedTxn("u1", 100, 0L);
            join.feedTxn("u2", 5, 0L);
            join.feedTxn("u3", 7, 0L);
            join.feedLkp("u1", "gold");
            join.feedLkp("u2", "silver");
            assertThat(join.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();

            Checkpoint c = join.execution.checkpoint(1, Duration.ofSeconds(5));
            byte[] s = c.operatorState().get("lane-0");
            assertThat(s).isNotNull();
            assertThat(s.length).isGreaterThan(0);
            assertThat(intAt(s, 0)).isEqualTo(0x50565354);
            assertThat(intAt(s, 4)).isEqualTo(5);
            assertThat(intAt(s, 8))
                    .as("windowed count is zero for a join-only plan")
                    .isEqualTo(0);
            // Bytes 12.. are the join count then join entries; not decoded further here, the
            // count-and-format check is STATE-053's job and this case is about isStateful()'s other
            // branch reaching the same mechanism, contrasted with STATE-051's empty projection over
            // the same shape of harness.
        }
    }

    @Test
    void state055_aWindowedAggregatesOpenWindowSurvivesACheckpointRestoreRoundTrip(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        RawExecution a = rawWindowed();
        a.feedAt("u1", 100, 1_000_000_000L);
        a.feedAt("u1", 102, 2_000_000_000L);
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        Checkpoint c = a.execution.checkpoint(1, Duration.ofSeconds(5));
        store.store(c);
        a.abort();

        try (RawExecution b = rawWindowed()) {
            b.execution.restore(store.latest().orElseThrow(), Duration.ofSeconds(30));
            b.feedAt("u1", 5, 3_000_000_000L);
            assertThat(b.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            b.advanceWatermark(11_000_000_000L);
            b.execution.checkHealth();

            long total = b.emitted.stream()
                    .filter(row -> "u1".equals(row.asString(0)))
                    .mapToLong(row -> row.asLong(1))
                    .sum();
            assertThat(total)
                    .as("207 = 100+102+5 if state was restored; 5 if it was not; 202 if the new row was lost")
                    .isEqualTo(207L);
        }
    }

    @Test
    void state056_aJoinsUnmatchedRowsSurviveARoundTrip(@TempDir Path dir) {
        FileCheckpointStore store = new FileCheckpointStore(dir);
        RawJoinExecution a = rawJoin();
        a.feedTxn("u1", 300, 0L);
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        Checkpoint c = a.execution.checkpoint(1, Duration.ofSeconds(5));
        store.store(c);
        a.abort();

        try (RawJoinExecution b = rawJoin()) {
            b.execution.restore(store.latest().orElseThrow(), Duration.ofSeconds(30));
            b.feedLkp("u1", "gold");
            assertThat(b.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();

            assertThat(b.emitted).hasSize(1);
            var row = b.emitted.get(0);
            assertThat(row.asString(0)).isEqualTo("u1");
            assertThat(row.asLong(1)).isEqualTo(300L);
            assertThat(row.asString(2)).isEqualTo("gold");
        }
    }

    @Test
    void state057_restoringWithoutRewindingTheSourcesDoubleCountsEverythingSinceTheCheckpoint(@TempDir Path dir)
            throws Exception {
        FileCheckpointStore store = new FileCheckpointStore(dir);

        // Control: no checkpoint at all, all 100 rows once -- the correct total.
        try (RawExecution control = rawWindowed()) {
            IngestPump pump = control.execution.pumpInto(
                    0, InMemoryTxnReader.ofRows(100, "u1", 1L, 1_000_000L), BackpressurePolicy.defaults());
            drivePump(pump, 100, control.execution);
            assertThat(control.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            control.advanceWatermark(11_000_000_000L);
            control.execution.checkHealth();
            long correctTotal = control.emitted.stream()
                    .filter(r -> "u1".equals(r.asString(0)))
                    .mapToLong(r -> r.asLong(1))
                    .sum();
            assertThat(correctTotal).isEqualTo(100L);
        }

        RawExecution a = rawWindowed();
        IngestPump pumpA = a.execution.pumpInto(
                0, InMemoryTxnReader.ofRows(100, "u1", 1L, 1_000_000L), BackpressurePolicy.defaults());
        drivePumpPartial(pumpA, 60, a.execution);
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        Checkpoint c = a.execution.checkpoint(1, Duration.ofSeconds(5));
        store.store(c);
        assertThat(c.offsets()).hasSize(1);
        String token = c.offsets().values().iterator().next();
        assertThat(token).isNotBlank();
        a.abort();

        try (RawExecution b = rawWindowed()) {
            b.execution.restore(store.latest().orElseThrow(), Duration.ofSeconds(30));
            // Nothing seeks to c.offsets(): replay the source from the beginning, which is what a
            // caller who does not consume the offsets does.
            IngestPump pumpB = b.execution.pumpInto(
                    0, InMemoryTxnReader.ofRows(100, "u1", 1L, 1_000_000L), BackpressurePolicy.defaults());
            drivePump(pumpB, 100, b.execution);
            assertThat(b.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            b.advanceWatermark(11_000_000_000L);
            b.execution.checkHealth();

            long total = b.emitted.stream()
                    .filter(r -> "u1".equals(r.asString(0)))
                    .mapToLong(r -> r.asLong(1))
                    .sum();
            assertThat(total)
                    .as("60 restored + 100 replayed = 160, not the correct 100")
                    .isEqualTo(160L);
        }

        // FAIL as authored, in the most consequential way this whole area found: STATE-057 says "no
        // shipped code reads offsets() back", citing a grep that returned only QueryExecution.java,
        // which writes it. As executed, QueryCheckpoints.restore (QueryCheckpoints.java, the
        // registry's checkpoint placement since ADR-046) returns
        // latest.get().offsets() to its caller, register() passes it to feeds.open(...) as
        // resumeFrom, and PluginSourceFeeds.open (PluginSourceFeeds.java, around ":126"-":131") uses
        // it to seek each partition's reader to SourceOffset(token) instead of SourceOffset.BEGINNING
        // -- with its own comment: "Reading from the beginning after a restore would replay every
        // record between the checkpoint and the failure on top of the state that already counted
        // them." So for the real ingest path (a query fed by PluginSourceFeeds, exactly what
        // PluginSourceFeedsTest.aQueryResumesFromItsCheckpointRatherThanReplayingTheWholeFile and
        // .aRestartedQueryFinishesAWindowItHadOnlyPartlySeen already demonstrate end to end), the
        // 160-vs-100 double-count this case demonstrates does NOT happen: the raw harness above
        // reproduces it only because it pumps rows in directly and ignores the returned offsets,
        // which is a real, but no longer the only, way to drive this engine. See FINDINGS.md ST-2.
        List<String> offsetReaders = grep("offsets()", repoRoot()).stream()
                .filter(l -> l.contains("/src/main/") && !l.contains("/pravaha-state/"))
                .toList();
        assertThat(offsetReaders)
                .as("offsets() now has a real, non-pravaha-state reader")
                .anyMatch(l -> l.contains("QueryCheckpoints.java"));
        String pluginSourceFeeds = Files.readString(
                repoRoot()
                        .resolve(
                                "pravaha-bindings/src/main/java/com/ash/messaging/pravaha/bindings/ingest/PluginSourceFeeds.java"));
        assertThat(pluginSourceFeeds).contains("resumeFrom.get(");
        assertThat(pluginSourceFeeds).contains("SourceOffset.BEGINNING");
    }

    /** A raw execution over a two-stream plan, for shapes {@link StateTestSupport#raw} cannot build. */
    private static RawExecution raw2(
            com.ash.messaging.pravaha.api.data.StreamSchema s1,
            com.ash.messaging.pravaha.api.data.StreamSchema s2,
            String sql) {
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan =
                new com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder()
                        .build(com.ash.messaging.pravaha.sql.SqlPlanner.withStreams(s1, s2)
                                .plan(sql));
        List<com.ash.messaging.pravaha.testkit.CapturingRowWriter.Captured> emitted = new ArrayList<>();
        com.ash.messaging.pravaha.runtime.exec.QueryExecution execution =
                com.ash.messaging.pravaha.runtime.exec.QueryExecution.start(
                        plan,
                        1,
                        laneConfig(),
                        com.ash.messaging.pravaha.common.memory.MemoryAccess.best(),
                        () -> (com.ash.messaging.pravaha.runtime.exec.RowOutput) () ->
                                new com.ash.messaging.pravaha.testkit.CapturingRowWriter(plan.outputSchema(), row -> {
                                    synchronized (emitted) {
                                        emitted.add(row);
                                    }
                                }));
        // s1 only: this helper is used where the caller never feeds directly (restore + watermark
        // only), so RawExecution's feed layout is unused but must still type-check.
        return new RawExecution(execution, s1, emitted);
    }

    private static void drivePump(
            IngestPump pump, long total, com.ash.messaging.pravaha.runtime.exec.QueryExecution execution) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (pump.rowsPumped() < total) {
            pump.pumpOnce(50);
            execution.checkHealth();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("pump did not deliver all rows in time");
            }
        }
    }

    private static void drivePumpPartial(
            IngestPump pump, long count, com.ash.messaging.pravaha.runtime.exec.QueryExecution execution) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (pump.rowsPumped() < count) {
            pump.pumpOnce(5);
            execution.checkHealth();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("pump did not deliver the partial rows in time");
            }
        }
    }

    @Test
    void state058_aSnapshotFromAPlanWithADifferentNumberOfStatefulOperatorsIsRefused() {
        RawExecution a = rawWindowed();
        a.feedAt("u1", 100, 1_000_000_000L);
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        Checkpoint c = a.execution.checkpoint(1, Duration.ofSeconds(5));
        a.abort();

        // UNION ALL of two windowed aggregates is not a plan shape the engine executes yet
        // (PRV-2020, LogicalUnion). A join of two windowed sub-aggregates over the *same* stream is
        // refused too, as a self-join ("stream 'txn' appears on both sides"); a second stream with
        // its own event-time column avoids that while keeping the windowed-count mismatch this case
        // is about. The windowed-count check in restoreState throws before it ever reaches the join
        // count, so the one join this shape adds does not confuse the assertion below (STATE-059 is
        // where the join count itself is exercised).
        com.ash.messaging.pravaha.api.data.StreamSchema txn2 = com.ash.messaging.pravaha.api.data.StreamSchema.builder(
                        "txn2")
                .field("user_id", com.ash.messaging.pravaha.api.data.Types.string())
                .field("amount", com.ash.messaging.pravaha.api.data.Types.int64())
                .field("ts", com.ash.messaging.pravaha.api.data.Types.timestamp())
                .eventTime("ts")
                .build();
        String twoWindowSql = "SELECT a.user_id, a.t1, b.t2 FROM "
                + "(SELECT user_id, SUM(amount) AS t1 FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)) a "
                + "JOIN "
                + "(SELECT user_id, SUM(amount) AS t2 FROM txn2 GROUP BY user_id, TUMBLE(ts, INTERVAL '20' SECOND)) b "
                + "ON a.user_id = b.user_id";
        try (RawExecution b = raw2(TXN_T, txn2, twoWindowSql)) {
            assertThatThrownBy(() -> b.execution.restore(c, Duration.ofSeconds(30)))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("the checkpoint holds 1 stateful operators and this plan has 2")
                    .hasMessageContaining(
                            "restoring part of it would resume with some operators holding history and others "
                                    + "empty");
            // The control task's exception marks the lane failed (Lane.runControlTasks), so a
            // further call on this execution -- advanceWatermark, checkHealth -- would itself throw
            // "lane 0 stopped after a failure" rather than confirming anything new; the case's own
            // point (B's windows remain empty) is checkable from what was already emitted, which is
            // nothing, without touching the now-dead lane again.
            assertThat(b.emitted).isEmpty();
        }
    }

    @Test
    void state059_theOperatorCountCheckNowCoversJoinsToo_drift() {
        // FAIL as authored: STATE.md says "the check ignores joins," a gap left open by
        // InterpretedPipeline.restoreState comparing operators only against windowed.size(). As
        // executed, restoreState also compares the join count against joins.size() and throws a
        // parallel, equally clear message -- the gap this case describes has been closed. See
        // FINDINGS.md ST-2.
        RawJoinExecution a = rawJoin();
        a.feedTxn("u1", 100, 0L);
        a.feedLkp("u1", "gold");
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        Checkpoint c = a.execution.checkpoint(1, Duration.ofSeconds(5));
        a.abort();

        String twoJoinSql = "SELECT t.user_id, t.amount, l.tier, l2.tier FROM txn t JOIN lkp l ON "
                + "t.user_id = l.user_id JOIN lkp2 l2 ON t.user_id = l2.user_id";
        try (RawJoinExecution b = new RawJoinExecutionForSql(twoJoinSql)) {
            assertThatThrownBy(() -> b.execution.restore(c, Duration.ofSeconds(30)))
                    .as("the join-count guard now exists and refuses cleanly, contrary to STATE-059 as authored")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("the checkpoint holds 1 joins and this plan has 2");
        }
    }

    /** A second-join plan needs a third stream (lkp2); a tiny subclass to reuse the harness shape. */
    private static final class RawJoinExecutionForSql extends RawJoinExecution {
        RawJoinExecutionForSql(String sql) {
            super(buildTwoJoinExecution(sql), new java.util.ArrayList<>());
        }
    }

    private static com.ash.messaging.pravaha.runtime.exec.QueryExecution buildTwoJoinExecution(String sql) {
        com.ash.messaging.pravaha.api.data.StreamSchema lkp2 = com.ash.messaging.pravaha.api.data.StreamSchema.builder(
                        "lkp2")
                .field("user_id", com.ash.messaging.pravaha.api.data.Types.string())
                .field("tier", com.ash.messaging.pravaha.api.data.Types.string())
                .build();
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan =
                new com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder()
                        .build(com.ash.messaging.pravaha.sql.SqlPlanner.withStreams(TXN_T, LKP, lkp2)
                                .plan(sql));
        return com.ash.messaging.pravaha.runtime.exec.QueryExecution.start(
                plan,
                1,
                laneConfig(),
                com.ash.messaging.pravaha.common.memory.MemoryAccess.best(),
                () -> (com.ash.messaging.pravaha.runtime.exec.RowOutput)
                        () -> new com.ash.messaging.pravaha.testkit.CapturingRowWriter(plan.outputSchema(), row -> {}));
    }

    @Test
    void state060_bytesThatAreNotASnapshotAreRefusedBeforeTheyAreParsed() {
        // A fresh execution per arm: a lane whose control task threw once is not assumed reusable
        // for a second restore attempt (a failed restoreState may leave the lane's failure field
        // set, per QueryExecution.restore's own lane.checkHealth() call right after awaitControlTask).
        String expectedMessage = "this is not a Pravaha operator snapshot. Restoring it would read "
                + "whatever bytes these are as rows and weights, and the result would be believed "
                + "rather than rejected.";

        // Arm (a): four zero bytes.
        try (RawExecution b = rawWindowed()) {
            Checkpoint capZero =
                    new Checkpoint(1, 0, java.util.Map.of(), java.util.Map.of("lane-0", new byte[] {0, 0, 0, 0}));
            assertThatThrownBy(() -> b.execution.restore(capZero, Duration.ofSeconds(30)))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining(expectedMessage);
        }

        // Arm (b): 4096 random bytes, fixed seed -- must not allocate from a random length field.
        try (RawExecution b = rawWindowed()) {
            byte[] random = new byte[4096];
            new Random(20260909L).nextBytes(random);
            Checkpoint capRandom = new Checkpoint(1, 0, java.util.Map.of(), java.util.Map.of("lane-0", random));
            assertThatThrownBy(() -> b.execution.restore(capRandom, Duration.ofSeconds(30)))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining(expectedMessage);
        }

        // Arm (c): a real snapshot with the first int flipped by one.
        RawExecution real = rawWindowed();
        real.feedAt("u1", 1, 0L);
        assertThat(real.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        byte[] bytes = real.execution
                .checkpoint(1, Duration.ofSeconds(5))
                .operatorState()
                .get("lane-0")
                .clone();
        real.abort();
        bytes[3] = (byte) (bytes[3] + 1);
        Checkpoint capFlipped = new Checkpoint(1, 0, java.util.Map.of(), java.util.Map.of("lane-0", bytes));
        try (RawExecution b = rawWindowed()) {
            assertThatThrownBy(() -> b.execution.restore(capFlipped, Duration.ofSeconds(30)))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining(expectedMessage);
        }
    }

    @Test
    void state061_aVersion1SnapshotIsRefusedRatherThanReadIntoCurrentLayouts() {
        RawExecution a = rawWindowed();
        a.feedAt("u1", 1, 0L);
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        byte[] bytes = a.execution
                .checkpoint(1, Duration.ofSeconds(5))
                .operatorState()
                .get("lane-0")
                .clone();
        a.abort();

        // Patch bytes 4..7 (SNAPSHOT_VERSION) from 2 to 1.
        bytes[4] = 0;
        bytes[5] = 0;
        bytes[6] = 0;
        bytes[7] = 1;
        Checkpoint patched = new Checkpoint(1, 0, java.util.Map.of(), java.util.Map.of("lane-0", bytes));

        try (RawExecution b = rawWindowed()) {
            assertThatThrownBy(() -> b.execution.restore(patched, Duration.ofSeconds(30)))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("this snapshot is version 1 and this engine writes version 5")
                    .hasMessageContaining("Replay the stream from a source offset instead.");
        }
    }

    /**
     * A version 4 or 3 snapshot holding windowed state is refused. ADR-044 changed the windowed
     * aggregate's own section (its non-null counts, and {@code COUNT(DISTINCT)}'s values moved out of
     * each accumulator into a section of their own, off-heap), so version 5 is not version 4 plus
     * something at the end: reading one as the other would parse fields in the wrong places.
     *
     * <p>It used to be the other way round for version 3 -- a windowed plan restored from it, because
     * version 4 only appended a section. That stopped being true when the windowed section itself
     * changed.
     */
    @Test
    void state061b_anOlderSnapshotWithWindowedStateIsRefusedByVersion() {
        RawExecution a = rawWindowed();
        a.feedAt("u1", 100, 1_000_000_000L);
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        byte[] current =
                a.execution.checkpoint(1, Duration.ofSeconds(5)).operatorState().get("lane-0");
        a.abort();
        for (int old : new int[] {4, 3}) {
            byte[] bytes = current.clone();
            bytes[7] = (byte) old;
            Checkpoint patched = new Checkpoint(1, 0, java.util.Map.of(), java.util.Map.of("lane-0", bytes));
            try (RawExecution b = rawWindowed()) {
                assertThatThrownBy(() -> b.execution.restore(patched, Duration.ofSeconds(30)))
                        .isInstanceOf(PravahaException.class)
                        .hasMessageContaining("this snapshot is version " + old + " and this engine writes version 5");
            }
        }
    }

    /**
     * ...but a version 4 snapshot of a plan with no windowed aggregate is the same bytes version 5
     * writes, and still restores: an upgrade does not throw away an unwindowed aggregate's count.
     */
    @Test
    void state061d_aVersion4SnapshotStillRestoresIntoAPlanWithNoWindowedAggregate() {
        RawExecution a = raw(TXN, "SELECT COUNT(*) AS n FROM txn");
        a.feed("u1", 100);
        a.feed("u2", 5);
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        byte[] bytes = a.execution
                .checkpoint(1, Duration.ofSeconds(5))
                .operatorState()
                .get("lane-0")
                .clone();
        a.abort();
        bytes[7] = 4;
        Checkpoint old = new Checkpoint(1, 0, java.util.Map.of(), java.util.Map.of("lane-0", bytes));

        try (RawExecution b = raw(TXN, "SELECT COUNT(*) AS n FROM txn")) {
            b.execution.restore(old, Duration.ofSeconds(30));
            b.execution.checkHealth();
            byte[] after = b.execution
                    .checkpoint(2, Duration.ofSeconds(5))
                    .operatorState()
                    .get("lane-0");
            assertThat(java.util.Arrays.copyOfRange(after, 8, after.length))
                    .as("the restored aggregate checkpoints exactly what the version 4 snapshot held")
                    .isEqualTo(java.util.Arrays.copyOfRange(bytes, 8, bytes.length));
        }
    }

    /**
     * ...but not into a plan with an unwindowed aggregate, whose state a version 3 snapshot never
     * held: restoring it would resume that aggregate from zero beside a restored view, which is
     * CKPT-2 itself.
     */
    @Test
    void state061c_aVersion3SnapshotIsRefusedByAPlanWithAnUnwindowedAggregate() {
        RawExecution a = raw(TXN, "SELECT COUNT(*) AS n FROM txn");
        a.feed("u1", 100);
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        byte[] bytes = a.execution
                .checkpoint(1, Duration.ofSeconds(5))
                .operatorState()
                .get("lane-0")
                .clone();
        a.abort();
        bytes[7] = 3;
        Checkpoint old = new Checkpoint(1, 0, java.util.Map.of(), java.util.Map.of("lane-0", bytes));

        try (RawExecution b = raw(TXN, "SELECT COUNT(*) AS n FROM txn")) {
            assertThatThrownBy(() -> b.execution.restore(old, Duration.ofSeconds(30)))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("this snapshot is version 3 and this engine writes version 5");
        }
    }

    @Test
    void state062_aCheckpointHoldingNoEntryForAStatefulLaneIsRefusedNotSkipped(@TempDir Path dir) throws Exception {
        try (RawExecution prj = rawProjection()) {
            InMemoryTxnReader reader = InMemoryTxnReader.ofRows(3, "u1", 1L, 0L);
            IngestPump pump = prj.execution.pumpInto(0, reader, BackpressurePolicy.defaults());
            drivePump(pump, 3, prj.execution);
            assertThat(prj.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            Checkpoint c = prj.execution.checkpoint(1, Duration.ofSeconds(5));
            assertThat(c.operatorState()).isEmpty();

            // The checkpoint came from a projection, which is not stateful and therefore stores no
            // operator entry. Restoring it into a *windowed* plan used to be a silent skip: the
            // source offsets came back, the accumulators did not, and the query resumed past every
            // row the checkpoint covered while answering from an empty operator and reporting
            // RUNNING. It is the same class of mistake restoreState's "operator count differs"
            // refusal exists for, and it slipped past because there was no state to count.
            try (RawExecution b = rawWindowed()) {
                assertThatThrownBy(() -> b.execution.restore(c, Duration.ofSeconds(30)))
                        .isInstanceOf(PravahaException.class)
                        .hasMessageContaining("the checkpoint holds no state for lane 0")
                        .hasMessageContaining("is stateful");
            }
        }
    }

    @Test
    void state064_aCrashSimulatedWithCloseInsteadOfAbortInvalidatesARecoveryTest() {
        RawExecution run1 = rawWindowed();
        run1.feedAt("u1", 100, 1_000_000_000L);
        run1.feedAt("u1", 102, 2_000_000_000L);
        assertThat(run1.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        Checkpoint c1 = run1.execution.checkpoint(1, Duration.ofSeconds(5));
        run1.abort();
        assertThat(run1.emitted)
                .as("abort() discards the open window, nothing emitted")
                .isEmpty();

        RawExecution run2 = rawWindowed();
        run2.feedAt("u1", 100, 1_000_000_000L);
        run2.feedAt("u1", 102, 2_000_000_000L);
        assertThat(run2.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        Checkpoint c2 = run2.execution.checkpoint(1, Duration.ofSeconds(5));
        run2.close();
        assertThat(run2.emitted)
                .as("close() runs finish(), which releases the open window on the way out")
                .hasSize(1);
        assertThat(run2.emitted.get(0).asString(0)).isEqualTo("u1");
        assertThat(run2.emitted.get(0).asLong(1)).isEqualTo(202L);

        // Restore each into a fresh execution, from its own checkpoint, and close it: both now emit
        // 202 once more. Run 2's total across the whole exercise is 202 twice; run 1's is 202 once --
        // the duplicate close() produced is the artefact this case is about.
        RawExecution afterAbort = rawWindowed();
        afterAbort.execution.restore(c1, Duration.ofSeconds(30));
        afterAbort.advanceWatermark(11_000_000_000L);
        afterAbort.close();
        assertThat(afterAbort.emitted).hasSize(1);
        assertThat(afterAbort.emitted.get(0).asLong(1)).isEqualTo(202L);

        RawExecution afterClose = rawWindowed();
        afterClose.execution.restore(c2, Duration.ofSeconds(30));
        afterClose.advanceWatermark(11_000_000_000L);
        afterClose.close();
        assertThat(afterClose.emitted).hasSize(1);
        assertThat(afterClose.emitted.get(0).asLong(1)).isEqualTo(202L);
    }

    @Test
    @Timeout(120)
    void state063_aServerRestartRecoversEveryDefinitionAndZeroAccumulatedAnswers_asAuthored(@TempDir Path dir)
            throws Exception {
        // FAIL as authored, and the single most consequential result of this whole section: as
        // executed, the server *does* recover accumulated answers, for both plan shapes this case
        // names, because two mechanisms this case's own harness (H-SRV, via PravahaNode) exercises
        // together: checkpointingViewWith (every checkpoint carries the served view, independent of
        // isStateful()) and PluginSourceFeeds' resumeFrom handling (STATE-057's finding) are both
        // wired into the same register() call the journal replays through. Not attempted with a
        // bound filesystem source (PluginSourceFeedsTest already covers that combination end to end,
        // e.g. aRestartedQueryFinishesAWindowItHadOnlyPartlySeen) -- rows are pushed directly via
        // RegisteredQuery.accept, the way CliAgainstServerTest and PravahaNodeTest already do, to
        // isolate the served-view half of the recovery from the offset-seeking half. See
        // FINDINGS.md ST-2. "kill -9" is approximated with PravahaNode.stop() (graceful) rather than
        // a real SIGKILL of a subprocess; noted as an adaptation, not a hidden substitution -- see
        // STATE-064 just above for why a graceful close is not a crash, and the note below on why it
        // does not change this case's own numbers.
        Path journal = dir.resolve("registry.journal");
        Path checkpoints = dir.resolve("checkpoints");

        com.ash.messaging.pravaha.server.catalog.StreamCatalog catalog =
                new com.ash.messaging.pravaha.server.catalog.StreamCatalog();
        catalog.register(TXN_T);

        com.ash.messaging.pravaha.server.security.SecurityProperties security =
                new com.ash.messaging.pravaha.server.security.SecurityProperties();
        security.setAllowAnonymous(true);

        com.ash.messaging.pravaha.server.state.PersistenceProperties persistence =
                new com.ash.messaging.pravaha.server.state.PersistenceProperties();
        persistence.getRegistry().setJournal(journal.toString());
        persistence.getCheckpoint().setDirectory(checkpoints.toString());
        persistence.getCheckpoint().setInterval(Duration.ofSeconds(1));
        persistence.getCheckpoint().setKeep(3);

        com.ash.messaging.pravaha.server.PravahaNode first = com.ash.messaging.pravaha.server.PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(new com.ash.messaging.pravaha.server.ingest.SourceBindingProperties())
                .withDeclaredStreams(new com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties())
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("state-063-node")
                .build();
        first.start();
        long checkpointA;
        try {
            com.ash.messaging.pravaha.registry.QueryRegistry registry =
                    first.registry().orElseThrow();
            com.ash.messaging.pravaha.registry.RegisteredQuery w = registry.register("w", WIN_SQL, List.of(0), DANA);
            // A plain, keyed, non-windowed, non-aggregating query as STATE-063's "agg" stand-in: a
            // keyed GROUP BY aggregate (STATE-063's own "agg", as literally authored) is now refused
            // outright at plan time (PRV-2050, SQL_UNBOUNDED_STATE -- see STATE-052's FAIL) and a
            // keyless one is refused by the registry itself ("a view with no key is a log"). A plain
            // projection is the nearest registrable shape that is still isStateful()==false and still
            // demonstrates the same point: whether its served view survives a restart.
            com.ash.messaging.pravaha.registry.RegisteredQuery agg =
                    registry.register("agg", "SELECT user_id, amount FROM txn", List.of(0), DANA);

            RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
            feedAt(w, arena, TXN_T, "u1", 100, 1_000_000_000L);
            feedAt(w, arena, TXN_T, "u1", 102, 2_000_000_000L);
            w.commit();
            // Distinct keys: agg is a served (keyed) view, so two rows for "u1" would be one entry
            // last-write-wins, not two -- a different user is what makes "2 rows served" checkable.
            feedAt(agg, arena, TXN_T, "u1", 100, 1_000_000_000L);
            feedAt(agg, arena, TXN_T, "u2", 102, 2_000_000_000L);
            agg.commit();
            arena.close();

            List<Object[]> aggRows = agg.view().scan();
            assertThat(aggRows).as("2 rows served before the restart").hasSize(2);

            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline
                    && (new FileCheckpointStore(checkpoints.resolve("w"))
                                    .availableIds()
                                    .isEmpty()
                            || new FileCheckpointStore(checkpoints.resolve("agg"))
                                    .availableIds()
                                    .isEmpty())) {
                Thread.sleep(50);
            }
            List<Long> wIds = new FileCheckpointStore(checkpoints.resolve("w")).availableIds();
            List<Long> aggIds = new FileCheckpointStore(checkpoints.resolve("agg")).availableIds();
            assertThat(wIds).isNotEmpty();
            assertThat(aggIds).isNotEmpty();
            checkpointA = wIds.get(0);
            long wSize = Files.size(checkpoints.resolve("w").resolve("checkpoint-" + wIds.get(0) + ".bin"));
            long aggSize = Files.size(checkpoints.resolve("agg").resolve("checkpoint-" + aggIds.get(0) + ".bin"));
            assertThat(wSize).as("w's checkpoint carries real window state").isGreaterThan(60);
            // As authored, STATE-063 expects agg's file to be the ~60-byte pure framing of STATE-051.
            // As executed, it too carries real bytes: checkpointingViewWith puts the served view
            // (agg's two served rows, non-trivially encoded) into every checkpoint, agg included.
            assertThat(aggSize)
                    .as("agg's checkpoint now also carries real bytes (the served view)")
                    .isGreaterThan(60);
        } finally {
            first.stop();
        }

        com.ash.messaging.pravaha.server.PravahaNode second = com.ash.messaging.pravaha.server.PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(new com.ash.messaging.pravaha.server.ingest.SourceBindingProperties())
                .withDeclaredStreams(new com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties())
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("state-063-node")
                .build();
        second.start();
        try {
            com.ash.messaging.pravaha.registry.QueryRegistry registry =
                    second.registry().orElseThrow();
            assertThat(registry.names()).containsExactlyInAnyOrder("w", "agg");

            List<Object[]> aggAfter = registry.find("agg").orElseThrow().view().scan();
            assertThat(aggAfter)
                    .as("STATE-063 as authored expects zero rows here; the served view was restored "
                            + "from the checkpoint instead")
                    .hasSize(2);

            // w's window is also restored: one more row in the same still-open window, at the state
            // it was at (202), not from empty.
            com.ash.messaging.pravaha.registry.RegisteredQuery w2 =
                    registry.find("w").orElseThrow();
            RowArena arena2 = new RowArena(MemoryAccess.best(), 1 << 20, 8);
            feedAt(w2, arena2, TXN_T, "u1", 5, 3_000_000_000L);
            arena2.close();
            w2.advanceWatermark(11_000_000_000L);
            w2.commit();
            List<Object[]> wAfter = w2.view().scan();
            assertThat(wAfter).hasSize(1);
            assertThat(wAfter.get(0)[1])
                    .as("100+102+5=207 if the window's accumulators were restored, 5 if not")
                    .isEqualTo(207L);

            long idDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            List<Long> wIdsAfter;
            do {
                Thread.sleep(50);
                wIdsAfter = new FileCheckpointStore(checkpoints.resolve("w")).availableIds();
            } while (wIdsAfter.stream().mapToLong(Long::longValue).max().orElse(0) <= checkpointA
                    && System.nanoTime() < idDeadline);
            assertThat(wIdsAfter.stream().max(Long::compare).orElseThrow())
                    .as("ids resume above the pre-restart maximum -- the node writes checkpoints it will "
                            + "never read")
                    .isGreaterThan(checkpointA);
        } finally {
            second.stop();
        }
    }
}
