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
package com.ash.messaging.pravaha.it.qa.window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code docs/project/qa/cases/WIN.md}, sections 9-14: row volume and the 200k-230k blocker, boundaries,
 * close triggers, lateness, empty windows, and aggregates inside a window.
 *
 * <p>See {@link WindowAnswerTest} for sections 1-8 and {@link WindowTestSupport} for the shared
 * datasets and harnesses both files use.
 */
@Tag("qa")
class WindowClosingAnswerTest extends WindowTestSupport {

    // ================================================================== 9. volume

    @Test
    void win121_aHundredThousandRowsIntoTenWindowsOverAHundredKeys(@TempDir Path dir) throws Exception {
        // WIN-121, dataset V(100000, 100). This is the one case in this file that runs at real
        // volume; WIN-119, 120, 122-126 and 131-136 are the same arithmetic at other N and K and
        // are covered by it. Row i carries user_id = i mod 100, amount 1, event_time = i ms, so
        // window j holds rows i in [10000j, 10000j + 9999].
        //
        // Keyed on (window_start, window_end, user_id), which is what stops round 1's mistake:
        // keying on user_id alone collapses ten windows into a correct-looking 100 rows.
        int n = 100_000;
        StringBuilder csv = new StringBuilder(n * 24);
        for (int i = 1; i <= n; i++) {
            csv.append(i)
                    .append(',')
                    .append(i % 100)
                    .append(",1,")
                    .append(i * MS)
                    .append('\n');
        }
        List<String> rows = configured(
                dir, csv.toString(), SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), n, 1_000);

        // Ten windows x 100 users = 1,000 rows.
        assertThat(rows).hasSize(1_000);
        // 9,999 + 9 * 10,000 = 99,999. Row 100,000 is at 100.000s, in [100,110), which never
        // closes because the watermark stops at the highest event time in the file.
        assertThat(sumOf(rows, 3)).isEqualTo(99_999);
        assertThat(rows.stream()
                        .map(r -> Long.parseLong(r.split("\\|", -1)[0]))
                        .distinct()
                        .count())
                .isEqualTo(10);
        // Window 0 holds rows 1..9,999, so user 0 gets 99 rows and the other 99 users get 100.
        assertThat(countOf(rows, 0L)).isEqualTo(9_999);
    }

    @Test
    void win165_theLastWindowOfABoundedSourceNeverCloses(@TempDir Path dir) throws Exception {
        // WIN-165, at a size that runs in seconds rather than the case's 60,000 rows. A filesystem
        // source is read once and never followed, and when every partition is idle the watermark
        // stays where it is -- so with ten seconds of declared lateness the last window's worth of
        // rows is never emitted. FINDINGS T-1 records why: a partition that spoke once can never
        // go idle, so idle exclusion cannot rescue it.
        //
        // 20,000 rows at i ms: the highest event time is 20.000, the watermark settles at
        // 20 - 10 = 10.000, and exactly one window end (10.000) is <= it.
        int n = 20_000;
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            csv.append(i)
                    .append(',')
                    .append(i % 10)
                    .append(",1,")
                    .append(i * MS)
                    .append('\n');
        }
        List<String> rows = configured(
                dir, csv.toString(), SPEC, Duration.ofSeconds(10), tumble("s0", "10' SECOND"), List.of(0, 1, 2), n, 10);
        assertThat(rows).hasSize(10); // one window x 10 users
        // 9,999 of 20,000 rows emitted: 10,001 are in windows that never close.
        assertThat(sumOf(rows, 3)).isEqualTo(9_999);
        assertThat(rows.stream().map(r -> Long.parseLong(r.split("\\|", -1)[0])).distinct())
                .containsExactly(0L);
    }

    // ================================================================== 10. boundaries

    @Test
    void win141To146_everyBoundaryRowLandsInTheWindowStartingAtIt(@TempDir Path dir) throws Exception {
        // WIN-141 through WIN-146 in one pass over dataset B, whose amounts are powers of two so
        // every possible mis-assignment produces a sum that occurs nowhere else. A closed upper
        // bound would give totals 7, 28, 240, 128 and SUM(n) = 12.
        List<String> rows =
                configured(dir, B_PLUS, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 9, 4);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "0|10000000000|100|2|3", // 1 (at 0.000) + 2 (at 9.999999999) = 3
                        "10000000000|20000000000|100|2|12", // 4 (at 10.000) + 8 (at 19.999999999) = 12
                        "20000000000|30000000000|100|3|112", // 16 + 32 (both at 20.000) + 64 = 112
                        "30000000000|40000000000|100|1|128"); // 128 at 30.000
        assertThat(sumOf(rows, 3)).isEqualTo(8); // 2 + 2 + 3 + 1 = 8
        assertThat(sumOf(rows, 4)).isEqualTo(255); // 3 + 12 + 112 + 128 = 255 = 2^8 - 1
    }

    @Test
    void win150_negativeEventTimesWindowAsOrdinaryOnes(@TempDir Path dir) throws Exception {
        // WIN-150, neg.csv: rows before the epoch at -15s, -10s, -1ns and 0, amounts 1, 2, 4, 8.
        // SUM(total) = 1 + 6 + 8 = 15 = 2^4 - 1, so no row is lost or double counted.
        String data =
                csv(row(1, 100, 1, -15 * SECOND), row(2, 100, 2, -10 * SECOND), row(3, 100, 4, -1L), row(4, 100, 8, 0L))
                        + row(5, 999, 0, 30 * SECOND);
        List<String> rows =
                configured(dir, data, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 5, 3);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "-20000000000|-10000000000|100|1|1", // the row at -15s
                        "-10000000000|0|100|2|6", // 2 (at -10s) + 4 (at -1ns) = 6
                        "0|10000000000|100|1|8"); // 8 at 0
        assertThat(sumOf(rows, 4)).isEqualTo(15);
    }

    @Test
    void win157_aWindowWhoseEndEqualsTheWatermarkFires(@TempDir Path dir) throws Exception {
        // WIN-157, exact.csv. The pusher sits at exactly 10.000000000, so the watermark is exactly
        // the window's end. windowsCompletedBetween fires ends <= the watermark, so [0,10) closes;
        // an implementation using < would leave it open for ever and look identical to a stall.
        String data = row(1, 100, 7, 5 * SECOND) + row(2, 999, 0, 10 * SECOND);
        List<String> rows =
                configured(dir, data, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 2, 1);
        assertThat(rows).containsExactly("0|10000000000|100|1|7");
    }

    @Test
    void win159_eachRowClosesTheWindowBeforeItAndNeverItsOwn(@TempDir Path dir) throws Exception {
        // WIN-159, step.csv: rows at 5s, 15s and 25s with amounts 5, 7, 9 and no pusher. The
        // watermark reaches 25s, so [0,10) and [10,20) fire and [20,30) -- which holds the row
        // that moved the watermark there -- does not.
        String data = csv(row(1, 100, 5, 5 * SECOND), row(2, 100, 7, 15 * SECOND), row(3, 100, 9, 25 * SECOND));
        List<String> rows =
                configured(dir, data, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 3, 2);
        assertThat(rows).containsExactlyInAnyOrder("0|10000000000|100|1|5", "10000000000|20000000000|100|1|7");
    }

    @Test
    void win152_aRowExactlyOnASlideBoundaryUnderHopShiftsWhichWindowsItIsIn(@TempDir Path dir) throws Exception {
        // WIN-152. Half-open applies at both edges of every overlapping window: a boundary row's
        // *set* of windows shifts by one nanosecond, it neither grows nor shrinks. The arithmetic
        // (windowEndsContaining at 9.999999999s versus 10s) is WindowArithmeticTest's
        // win152And153; this is the end-to-end confirmation through a real server run that the same
        // shift lands in the actual view.
        String data = csv(row(1, 100, 1, 9_999_999_999L), row(2, 100, 2, 10 * SECOND)) + row(3, 999, 0, 60 * SECOND);
        List<String> rows = configured(
                dir, data, SPEC, Duration.ZERO, hop("s0", "10' SECOND", "20' SECOND"), List.of(0, 1, 2), 3, 3);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "-10000000000|10000000000|100|1|1",
                        "0|20000000000|100|2|3", // 1 + 2
                        "10000000000|30000000000|100|1|2");
        assertThat(sumOf(rows, 3)).isEqualTo(4); // 2 rows x 2 windows each
    }

    @Test
    void win156_aWindowNeverFiresTwiceForOneWatermarkAndANonAdvancingWatermarkIsANoOp() throws Exception {
        // WIN-156. advanceWatermark returns immediately on watermarkNanos <= watermark.
        List<Integer> emittedPerAdvance = advanceSequenceAndCountEmissions(
                "SELECT window_start, window_end, user_id, COUNT(*) AS n FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id",
                0L,
                List.of(new long[] {5 * SECOND, 1}),
                List.of(10 * SECOND, 10 * SECOND, 9 * SECOND, 20 * SECOND));
        assertThat(emittedPerAdvance)
                .as("one row on the first close, nothing on the repeat, nothing on a smaller or empty later window")
                .containsExactly(1, 0, 0, 0);
    }

    @Test
    void win160_aWindowDoesNotCloseOneNanosecondEarly() throws Exception {
        // WIN-160. The other side of WIN-157/159: windowsCompletedBetween uses end <= watermark.
        List<Integer> emittedPerAdvance = advanceSequenceAndCountEmissions(
                "SELECT window_start, window_end, user_id, COUNT(*) AS n FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id",
                0L,
                List.of(new long[] {5 * SECOND, 1}),
                List.of(9_999_999_999L, 10 * SECOND));
        assertThat(emittedPerAdvance).containsExactly(0, 1);
    }

    @Test
    void win161_aWindowedQueryHasExactlyOneWatermarkPartitionByConstruction() throws Exception {
        // WIN-161. A windowed aggregate needs one stream: a stream-to-stream join above a window is
        // refused before a second partition could ever exist. FilesystemSourcePlugin.partitions
        // returns one partition per file, so "minimum across partitions" and idle exclusion are
        // simply not reachable for this area -- they belong to TIME, not WIN.
        assertThatThrownBy(
                        () -> plan(
                                "SELECT s0.user_id, COUNT(*) FROM s0 JOIN s0 AS s1 ON s0.user_id = s1.user_id GROUP BY s0.user_id"))
                .isInstanceOf(PravahaException.class);
        // The partition-naming format QueryExecution.trackEventTimeOf builds, confirmed by reading
        // the source rather than by instantiating a private method.
        String source = java.nio.file.Files.readString(repoRoot()
                .resolve("pravaha-runtime/src/main/java/com/ash/messaging/pravaha/runtime/exec/QueryExecution.java"));
        assertThat(source).contains("streamName + \"#\" + laneIndex + \"/\"");
    }

    @Test
    void win163_idlePartitionExclusionCannotCloseAWindowOnThisPath(@TempDir Path dir) throws Exception {
        // WIN-163. With a single partition, WatermarkTracker.advance returns the watermark unchanged
        // once every partition is idle -- it does not "jump to infinity". Idle exclusion exists to
        // stop one quiet partition holding back others; with one partition there are no others.
        List<String> rows = configured(dir, A, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 6, 4);
        Thread.sleep(3000); // 3x the enforced-minimum idle-after, far more than enough to go idle
        assertThat(rows)
                .as("still exactly WIN-001's four rows; idle exclusion did not conjure a fifth")
                .hasSize(4);
    }

    @Test
    void win164_everyPartitionIdleFreezesTheWatermarkRatherThanReleasingIt() {
        // WIN-164. The explicit decision, pinned directly on WatermarkTracker: idle never advances
        // the watermark to infinity, because the tracker cannot tell a quiet unbounded stream from a
        // bounded one that has ended.
        com.ash.messaging.pravaha.runtime.time.WatermarkTracker tracker =
                new com.ash.messaging.pravaha.runtime.time.WatermarkTracker(SECOND);
        long t0 = 0L;
        tracker.addPartition(
                "p", com.ash.messaging.pravaha.runtime.time.WatermarkGenerator.boundedOutOfOrderness(0), t0);
        tracker.observe("p", 25 * SECOND, t0);
        assertThat(tracker.advance(t0)).isEqualTo(25 * SECOND);
        assertThat(tracker.advance(t0 + 2 * SECOND)).isEqualTo(25 * SECOND);
        assertThat(tracker.isIdle("p")).isTrue();
        assertThat(tracker.idleExclusions()).isGreaterThan(0);
    }

    @Test
    void win167_finishWouldFireTheLastWindowsAndRunsOnlyAtLaneShutdown() throws Exception {
        // WIN-167. WindowedAggregate.finish() advances past every window the highest event time
        // could be in, and it is wired to lane shutdown alone.
        // The two call sites this case expects are ViewQuery's bounded read path and the lane
        // processor's close(). That processor was a nested record inside QueryExecution.java until
        // B6 extracted it -- QueryExecution had reached the size at which this project extracts
        // rather than grows -- so the second name is now LanePipeline.java. The claim is unchanged:
        // two call sites, one of them lane shutdown.
        String grepOutput = grepMainSources("\\.finish\\(\\)");
        assertThat(grepOutput.lines().sorted().toList())
                .as("only ViewQuery and the lane processor (LanePipeline.close) call finish()")
                .containsExactly("LanePipeline.java", "ViewQuery.java");

        List<Integer> emittedPerAdvance = advanceSequenceAndCountEmissionsThenFinish(
                "SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id",
                List.of(
                        new long[] {1 * SECOND, 10},
                        new long[] {5 * SECOND, 20},
                        new long[] {7 * SECOND, 30},
                        new long[] {11 * SECOND, 40},
                        new long[] {19 * SECOND, 50},
                        new long[] {25 * SECOND, 60}),
                25 * SECOND);
        // This harness fixes every row to one key, so WIN-001's two-user dataset A becomes two rows
        // ([0,10) n=3, [10,20) n=2) rather than four -- the shape, not the count, is what WIN-001
        // already covers with the real two-key data. advanceWatermark(25s) fires those two; finish()
        // advances to 25+10+10=45s, firing [20,30) too (n=1, the row that pushed the watermark to
        // 25s) and producing the otherwise-unreachable third row.
        assertThat(emittedPerAdvance.get(0)).isEqualTo(2);
        assertThat(emittedPerAdvance.get(1))
                .as("finish() emits the final, otherwise-unreachable window")
                .isEqualTo(1);
    }

    @Test
    void win168_droppingTheQueryRunsFinishAndDiscardsWhatItProduces(@TempDir Path dir) throws Exception {
        // WIN-168. RegisteredQuery.close() sets state = DROPPED and closes the feed before the
        // execution; RegisteredQuery.commit() returns early unless state == RUNNING. finish() still
        // runs at lane shutdown and still computes the last windows -- but nothing downstream is
        // listening by the time it does, so dropping cannot be used to flush a query's tail.
        Files.createDirectories(dir);
        Path data = dir.resolve("data.csv");
        Files.writeString(data, A); // no pusher: [20,30) never closes while the query is alive
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = registry(views, dir, data, SPEC, Duration.ZERO)) {
            RegisteredQuery query =
                    registry.register("v_drop", tumble("s0", "10' SECOND"), List.of(0, 1, 2), Principal.ANONYMOUS);
            awaitRowsIn(query, 6);
            awaitView(views, "SELECT * FROM v_drop", 4);
            assertThat(render(
                            new ViewQuery(views).execute("SELECT * FROM v_drop").rows()))
                    .as("before drop: WIN-001's four rows, [20,30) still open")
                    .hasSize(4);
            registry.drop("v_drop");
            Thread.sleep(500);
            assertThatThrownBy(() -> new ViewQuery(views).execute("SELECT * FROM v_drop"))
                    .as("dropped, not flushed: the view is gone entirely rather than gaining the fifth row")
                    .isInstanceOf(RuntimeException.class);
        }
    }

    private static final String LATE_SQL = "SELECT window_start, window_end, key, COUNT(*) AS n, SUM(amount) AS "
            + "total FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end, key";

    @Test
    void win170_aCorrectionIsEmittedBeforeTheNewlyCompletedWindows() {
        // WIN-170. advanceWatermark drains `dirty` (corrections) before firing newly completed
        // windows, so a consumer sees the fix for an old window before any newer window's result --
        // out of window-end order, deliberately.
        try (H1 h1 = new H1(LATE_SQL, 30 * SECOND, List.of(2))) {
            h1.feed(1, 5, 5 * SECOND);
            List<com.ash.messaging.pravaha.serving.ViewChange> batch1 = h1.advance(10 * SECOND);
            assertThat(batch1).hasSize(1);
            assertThat(batch1.get(0).weight()).isEqualTo(1);
            assertThat(batch1.get(0).values()[4]).isEqualTo(5L); // total

            h1.feed(1, 3, 25 * SECOND); // pushes the watermark on to 30s
            h1.feed(1, 100, 7 * SECOND); // late, but within the 30s lateness
            List<com.ash.messaging.pravaha.serving.ViewChange> batch2 = h1.advance(30 * SECOND);
            // Correction first: -1 of the old [0,10) row, then +1 of the corrected one, and only
            // after that the newly completed [20,30). [10,20) is empty and emits nothing.
            assertThat(batch2).hasSize(3);
            assertThat(batch2.get(0).weight()).isEqualTo(-1);
            assertThat(batch2.get(0).values()[4]).isEqualTo(5L); // the old total, withdrawn exactly
            assertThat(batch2.get(1).weight()).isEqualTo(1);
            assertThat(batch2.get(1).values()[4]).isEqualTo(105L); // 5 + 100
            assertThat(batch2.get(2).weight()).isEqualTo(1);
            assertThat(batch2.get(2).values()[2]).isEqualTo(1L); // key
            assertThat(batch2.get(2).values()[4]).isEqualTo(3L); // [20,30)
        }
    }

    @Test
    void win171_aRecordLaterThanTheAllowedLatenessIsRoutedToASinkThatDiscardsIt() {
        // WIN-171. With the production default (zero lateness), a too-late record is rejected in
        // process() before it ever reaches the accumulator: never silently dropped -- lateRecords()
        // counts it -- and never allowed to contradict an answer already sent.
        try (H1 h1 = new H1(LATE_SQL, 0L, List.of(2))) {
            List<RowView> late = new ArrayList<>();
            h1.lateOutput(late::add);
            assertThat(h1.lateRecords()).isZero();

            h1.feed(1, 5, 5 * SECOND);
            h1.advance(10 * SECOND);
            h1.feed(1, 100, 7 * SECOND); // too late: its window already closed at watermark 10s
            List<com.ash.messaging.pravaha.serving.ViewChange> batch = h1.advance(20 * SECOND);

            assertThat(h1.lateRecords()).isEqualTo(1);
            assertThat(h1.corrections()).isZero();
            assertThat(batch)
                    .as("no shipped surface sees this -- the view is not touched a second time")
                    .isEmpty();
            assertThat(late)
                    .as("wired to a collector, the row arrives there intact")
                    .hasSize(1);
        }
    }

    @Test
    void win172_neitherLateRecordsNorCorrectionsIsObservableFromAnyShippedSurface() throws Exception {
        // WIN-172. The counters exist and reach nobody: PravahaMetrics registers exactly rows.in,
        // view.size, view.evicted, view.updates, view.removals, watermark.lag.seconds and running.
        String metricsSource = java.nio.file.Files.readString(repoRoot()
                .resolve("pravaha-server/src/main/java/com/ash/messaging/pravaha/server/PravahaMetrics.java"));
        // Not a bare "late"/"correction" substring check -- PravahaMetrics legitimately talks about
        // processing "latency" elsewhere, which contains "late" without being this gauge.
        assertThat(metricsSource.toLowerCase(java.util.Locale.ROOT))
                .as("no late-records or corrections gauge is registered anywhere in PravahaMetrics")
                .doesNotContain("laterecords")
                .doesNotContain("late.records")
                .doesNotContain("late_records")
                .doesNotContain("correction");
        // And the counters are real, non-trivial numbers when a lateness-aware pipeline is run --
        // it is not that nothing happens, only that nothing shipped reports it.
        try (H1 h1 = new H1(LATE_SQL, 30 * SECOND, List.of(2))) {
            h1.feed(1, 5, 5 * SECOND);
            h1.advance(10 * SECOND);
            h1.feed(1, 100, 7 * SECOND);
            h1.advance(11 * SECOND);
            assertThat(h1.corrections()).isGreaterThan(0);
        }
    }

    @Test
    void win173_allowedLatenessIsNowDeclarablePerStreamAndReachesThePlanner() {
        // WIN-173, re-run against the build under test. The case as authored predicted allowed
        // lateness was hard-wired to zero with no configuration key and no SQL clause -- true when
        // it was written. It no longer is: StreamSchema.Builder.allowedLateness(Duration) now exists,
        // and PhysicalPlanBuilder.allowedLatenessOf reads it from the scan beneath the aggregate,
        // through the ordinary planner path rather than by rebuilding the operator by hand. The
        // EMIT CHANGES WITH (...) SQL clause the case named still does not exist -- that half of the
        // prediction still holds -- but "cannot be set from SQL or configuration" no longer does,
        // because a stream's own declaration is configuration.
        StreamSchema declaredLate = StreamSchema.builder("s0")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .allowedLateness(Duration.ofSeconds(30))
                .build();
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(declaredLate).plan(tumble("s0", "10' SECOND")));
        com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator windowed =
                (com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator) plan;
        assertThat(windowed.allowedLatenessNanos()).isEqualTo(30 * SECOND);

        // The SQL clause itself is still unbuilt: EMIT CHANGES WITH (...) is not a construct Calcite
        // knows, so it is refused (a parse error), not accepted and then ignored.
        assertThatThrownBy(() -> plan("SELECT window_start, window_end, user_id, COUNT(*) FROM "
                        + "TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id EMIT CHANGES WITH "
                        + "('allowed.lateness' = '30' SECOND)"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void win173b_allowedLatenessDefaultsToZeroWhenAStreamDeclaresNone() {
        // WIN-173, the control for the fix above: a stream that declares nothing must still get the
        // documented default of zero, or "now configurable" would have silently become "now always
        // on" -- a behaviour change nobody asked for disguised as a bug fix.
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan = plan(tumble("s0", "10' SECOND"));
        assertThat(((com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator) plan).allowedLatenessNanos())
                .isZero();
    }

    @Test
    void win174_withLatenessAboveZeroACorrectedWindowRetractsExactlyAndOnlyWhatChanged() {
        // WIN-174. emitWindow compares with Arrays.equals: a key whose values did not change
        // produces no retract/insert pair at all -- "two rows that consolidate to nothing" never
        // reach the wire in the first place.
        try (H1 h1 = new H1(LATE_SQL, 30 * SECOND, List.of(2))) {
            h1.feed(1, 5, 5 * SECOND); // key A
            h1.feed(2, 9, 6 * SECOND); // key B
            List<com.ash.messaging.pravaha.serving.ViewChange> batch1 = h1.advance(10 * SECOND);
            assertThat(batch1).hasSize(2);
            assertThat(batch1).allMatch(c -> c.weight() == 1);

            h1.feed(1, 100, 7 * SECOND); // late, key A only; key B is untouched
            List<com.ash.messaging.pravaha.serving.ViewChange> batch2 = h1.advance(11 * SECOND);
            assertThat(batch2)
                    .as("exactly two changes, both for key A -- key B is silent because nothing about it changed")
                    .hasSize(2);
            assertThat(batch2).allMatch(c -> ((Long) c.values()[2]) == 1L);
            assertThat(batch2.get(0).weight()).isEqualTo(-1);
            assertThat(batch2.get(0).values()[4]).isEqualTo(5L);
            assertThat(batch2.get(1).weight()).isEqualTo(1);
            assertThat(batch2.get(1).values()[4]).isEqualTo(105L);
        }
    }
    // ================================================================== 12. empty windows

    @Test
    void win175And176_awindowWithNoRowsEmitsNothingRatherThanAZero(@TempDir Path dir) throws Exception {
        // WIN-175 and WIN-176, holey.csv: rows at 1.000 and 41.000 with a pusher at 90.000. Nine
        // window ends are walked and seven of them are empty. An engine that emitted a zero row
        // for each would be answering a question nobody asked -- and WIN-182 records that no
        // document anywhere states which of the two this engine does.
        String data = csv(row(1, 100, 5, SECOND), row(2, 100, 7, 41 * SECOND)) + row(3, 999, 0, 90 * SECOND);
        List<String> rows =
                configured(dir, data, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 3, 2);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "0|10000000000|100|1|5", // the row at 1.000
                        "40000000000|50000000000|100|1|7"); // the row at 41.000
        assertThat(rows).as("nothing at all for [10,20), [20,30) or [30,40)").hasSize(2);
    }

    @Test
    void win178_awindowEmitsOnlyTheKeysThatHaveRowsInIt(@TempDir Path dir) throws Exception {
        // WIN-178, keygap.csv. User 200 has a row in [0,10) and none in [10,20), so [10,20) must
        // carry user 100 alone -- not a zero row for 200, and not 200's previous total carried
        // forward, which is the failure a per-key running total would produce.
        String data = csv(row(1, 100, 5, SECOND), row(2, 200, 7, 2 * SECOND), row(3, 100, 9, 11 * SECOND))
                + row(4, 999, 0, 40 * SECOND);
        List<String> rows =
                configured(dir, data, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 4, 3);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "0|10000000000|100|1|5", "0|10000000000|200|1|7", "10000000000|20000000000|100|1|9");
    }

    @Test
    void win182_nothingDocumentsThatAnEmptyWindowEmitsNothing() throws Exception {
        // WIN-182. The decision (§12's own heading) is defensible and undocumented, which makes it
        // indistinguishable from a bug to whoever needs a zero in a time series.
        String docs = java.nio.file.Files.readString(
                repoRoot().resolve("docs/guides/CONTINUOUS_QUERIES.md"), java.nio.charset.StandardCharsets.UTF_8);
        String concepts = java.nio.file.Files.readString(
                repoRoot().resolve("docs/guides/CONCEPTS.md"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat((docs + concepts).toLowerCase(java.util.Locale.ROOT))
                .as("no statement anywhere that an empty window emits nothing")
                .doesNotContain("empty window");
    }

    // ================================================================== 14. aggregates in a window

    @Test
    void win195_windowedCountStarCountsEveryRowIncludingOneWithANullColumn(@TempDir Path dir) throws Exception {
        // WIN-195, agg.csv. COUNT(*) counts rows, so the NULL-amount row counts: user 100 sees 4.
        List<String> rows = aggregate(dir, "COUNT(*) AS v");
        assertThat(rows).containsExactlyInAnyOrder("0|10000000000|100|4", "0|10000000000|200|1");
    }

    @Test
    void win198_windowedSumSkipsNothingAndTreatsTheNullAsZero(@TempDir Path dir) throws Exception {
        // WIN-198. SQL says SUM ignores NULLs, and the answer is the same either way here because
        // the accumulator's scratch for a NULL is 0: 5 + 5 + 7 + 0 = 17.
        List<String> rows = aggregate(dir, "SUM(amount) AS v");
        assertThat(rows).containsExactlyInAnyOrder("0|10000000000|100|17", "0|10000000000|200|11");
    }

    @Test
    void win203And204_windowedAvgDividesRatherThanReturningTheSum(@TempDir Path dir) throws Exception {
        // WIN-203 and WIN-204, and FINDINGS W-1, which recorded windowed AVG as returning the SUM
        // because WindowedAggregate mapped `case SUM, AVG -> SUM` and SlicedAggregateState had no
        // divisor. It divides now: user 100's amounts are 5, 5, 7 and a NULL, so 17 / 3 = 5.
        //
        // User 200 has a single row, so its AVG and its SUM are both 11 and it cannot tell the two
        // apart -- which is why only a group with more than one row discriminates, and why W-1
        // survived as long as it did. This is the assertion that keeps it closed.
        List<String> rows = aggregate(dir, "AVG(amount) AS v");
        assertThat(rows)
                .as("windowed AVG must divide: 17 / 3 = 5, not the sum 17")
                .containsExactlyInAnyOrder("0|10000000000|100|5", "0|10000000000|200|11");
    }

    @Test
    void win196_windowedCountOfAColumnIgnoresNulls(@TempDir Path dir) throws Exception {
        // WIN-196, and the windowed half of FINDINGS Q-3. COUNT(amount) over 5, 5, 7 and a NULL is
        // 3 by SQL, and Q-3 recorded the null test as having gone into GlobalAggregate only, so
        // that the windowed path answered 4 -- the same number as COUNT(*), which would make the
        // two forms indistinguishable. It answers 3 now, and WIN-195 asserts the 4 beside it, so
        // the pair discriminates rather than either one alone.
        List<String> rows = aggregate(dir, "COUNT(amount) AS v");
        assertThat(rows).containsExactlyInAnyOrder("0|10000000000|100|3", "0|10000000000|200|1");
    }

    @Test
    void win200_windowedMinIgnoresNulls(@TempDir Path dir) throws Exception {
        // WIN-200. MIN(amount) over 5, 5, 7 and NULL is 5 by SQL. The engine reads the NULL row's
        // scratch as 0 and answers 0 -- which is also what it answers over an all-positive column
        // with no NULL at all if the accumulator was never seeded, so the value carries no
        // information about the data.
        List<String> rows = aggregate(dir, "MIN(amount) AS v");
        assertThat(rows).containsExactlyInAnyOrder("0|10000000000|100|5", "0|10000000000|200|11");
    }

    @Test
    void win201_windowedMaxOverAColumnContainingNull(@TempDir Path dir) throws Exception {
        // WIN-201. MAX is the mirror of WIN-200 and happens to be right here, because the NULL's
        // zero is below every real amount: max(max(max(5,5),7),0) = 7. It is right by luck rather
        // than by a null test, which WIN-200 is the proof of.
        List<String> rows = aggregate(dir, "MAX(amount) AS v");
        assertThat(rows).containsExactlyInAnyOrder("0|10000000000|100|7", "0|10000000000|200|11");
    }

    @Test
    void win197_countDistinctInAWindowNowExcludesNullRatherThanCollidingWithZero(@TempDir Path dir) throws Exception {
        // WIN-197, re-run against the build under test. The case as authored predicted the windowed
        // COUNT(DISTINCT) path counted a NULL row as the literal value 0 -- true of the mechanism
        // COUNT(DISTINCT)'s string-length defect shared. It no longer is: SlicedAggregateState's
        // COUNT_DISTINCT arm is now wrapped in the same `if (present[i])` guard as COUNT(col), so a
        // NULL row never reaches `seen.merge` at all, matching SQL's own exclusion rather than
        // colliding with a real zero. User 100's values are 5, 5, 7, NULL -- SQL counts {5, 7} = 2.
        List<String> rows = aggregate(dir, "COUNT(DISTINCT amount) AS n");
        assertThat(rows)
                .as("2 distinct non-null values for user 100, matching SQL -- not the 3 a null-as-zero bug would give")
                .containsExactlyInAnyOrder("0|10000000000|100|2", "0|10000000000|200|1");
    }

    @Test
    void win199_sumOverAWindowWhereEveryValueIsNullReturnsNull(@TempDir Path dir) throws Exception {
        // WIN-199. SQL says SUM over no non-null values is NULL. The accumulator is a long[] and it
        // returned 0 until ALLNULLAGG-1, which reads the non-null count the slices already keep. The
        // group is not skipped -- fire() only skips a key whose weight-count is zero, and here it is 2.
        String data = csv("1,300,,1000000000\n", "2,300,,2000000000\n") + row(3, 999, 0, 40 * SECOND);
        List<String> rows = configured(
                dir,
                data,
                NULLABLE_SPEC,
                Duration.ZERO,
                "SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total FROM "
                        + "TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id",
                List.of(0, 1, 2),
                3,
                1);
        assertThat(rows).containsExactly("0|10000000000|300|2|null");
    }

    @Test
    void win205_severalAggregatesInOneWindowAreAccumulatedIndependently(@TempDir Path dir) throws Exception {
        // WIN-205. One pass, one accumulator array, five columns: a bug in the loop would show as
        // one column contaminating another. All five values differ, so a swap is visible.
        List<String> rows = aggregate(
                dir,
                "COUNT(*) AS n, SUM(amount) AS s, MIN(amount) AS mn, MAX(amount) AS mx, "
                        + "COUNT(DISTINCT amount) AS d");
        assertThat(rows)
                .filteredOn(r -> r.contains("|100|"))
                // n=4 (incl. the NULL row), s=5+5+7+0=17, mn=5 (NULL excluded per WIN-200), mx=7,
                // d=2 (NULL excluded per WIN-197's re-run above).
                .containsExactly("0|10000000000|100|4|17|5|7|2");
    }

    @Test
    void win206_aFloatingPointColumnInAWindowedAggregateIsRefusedExceptForCount() {
        // WIN-206. refuseFloatingPointAggregate refuses SUM/MIN/MAX/AVG over FLOAT32/FLOAT64 with
        // PRV-2020, and exempts COUNT and COUNT(DISTINCT) -- the round-1 defect the refusal exists
        // to close, and the boundary it draws.
        StreamSchema sf = StreamSchema.builder("sf")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("price", Types.float64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
        for (String refused : List.of("SUM(price)", "MIN(price)", "MAX(price)", "AVG(price)")) {
            assertThatThrownBy(() -> new PhysicalPlanBuilder()
                            .build(SqlPlanner.withStreams(sf)
                                    .plan("SELECT window_start, window_end, user_id, " + refused + " FROM "
                                            + "TABLE(TUMBLE(TABLE sf, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                                            + "GROUP BY window_start, window_end, user_id")))
                    .as(refused + " over a FLOAT64 column must be refused")
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2020");
        }
        for (String accepted : List.of("COUNT(price)", "COUNT(DISTINCT price)")) {
            com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withStreams(sf)
                            .plan("SELECT window_start, window_end, user_id, " + accepted + " FROM "
                                    + "TABLE(TUMBLE(TABLE sf, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                                    + "GROUP BY window_start, window_end, user_id"));
            assertThat(plan).as(accepted + " over a FLOAT64 column is accepted").isNotNull();
        }
    }

    @Test
    void win207_anExpressionInsideAWindowedAggregateIsComputedBeforeItIsFolded(@TempDir Path dir) throws Exception {
        // WIN-207. SUM(amount * 2) over dataset A + pusher: the doubling happens per row, so every
        // total is exactly twice WIN-079's and the grand total is 2 * 210 = 420.
        List<String> rows = configured(
                dir,
                A_PLUS,
                SPEC,
                Duration.ZERO,
                "SELECT window_start, window_end, user_id, SUM(amount * 2) AS total FROM "
                        + "TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id",
                List.of(0, 1, 2),
                7,
                5);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "0|10000000000|100|60", // (10 + 20) * 2 = 60
                        "0|10000000000|200|60", // 30 * 2 = 60
                        "10000000000|20000000000|100|80", // 40 * 2 = 80
                        "10000000000|20000000000|200|100", // 50 * 2 = 100
                        "20000000000|30000000000|100|120"); // 60 * 2 = 120
        assertThat(sumOf(rows, 3)).isEqualTo(420); // 2 * (10+20+30+40+50+60) = 2 * 210
    }

    @Test
    void win208_havingFiltersTheWindowsResultsAndNotTheWindows(@TempDir Path dir) throws Exception {
        // WIN-208. Every window still fires; HAVING discards four of WIN-079's five result rows
        // and leaves the one group with more than a single row.
        List<String> rows = configured(
                dir,
                A_PLUS,
                SPEC,
                Duration.ZERO,
                "SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total FROM "
                        + "TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id HAVING COUNT(*) > 1",
                List.of(0, 1, 2),
                7,
                1);
        assertThat(rows).containsExactly("0|10000000000|100|2|30"); // 10 + 20 = 30
    }

    @Test
    void win209And210_aGroupByOverAWindowedStreamMustIncludeTheWindow() {
        // WIN-209 and WIN-210. Grouping by the key alone spans every window at once, which is an
        // unbounded aggregate wearing a window's clothes -- so it is refused. Grouping by
        // window_start alone is accepted, because the start identifies the window for a TUMBLE.
        assertThatThrownBy(() -> plan("SELECT user_id, COUNT(*) FROM TABLE(TUMBLE(TABLE s0, "
                        + "DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2050");
        // WIN-210 predicted that grouping by window_start alone would be accepted. It is not:
        // both halves of the window boundary are required, so the refusal is stricter than the
        // case expected. Recorded here because a later relaxation would change an answer.
        assertThatThrownBy(() -> plan("SELECT window_start, user_id, COUNT(*) AS n FROM TABLE(TUMBLE(TABLE s0, "
                        + "DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY window_start, user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2050");
    }
}
