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
package com.ash.messaging.pravaha.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.time.WatermarkGenerator;
import com.ash.messaging.pravaha.runtime.time.WatermarkTracker;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.server.ingest.SourceBinding;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code docs/qa/cases/TIME.md}, executed.
 *
 * <p>120 prose cases about the one number that decides what a streaming answer contains: the
 * watermark. Everything else in the engine can be right and a query still serve nothing, or serve a
 * window that should still be open, because the watermark was a second out.
 *
 * <p>The dataset is TIME.md's: {@code evB.csv}, 121 rows one per second from T0 to T0+120, where
 * {@code amount = id = k}. Window <em>i</em> covers ids 10(i-1) through 10i-1, so
 * {@code total(i) = 100i - 100 + 45} and the whole file sums to 7,260. Each case varies one thing
 * -- the declared lateness, the idle timeout, the tick -- and the expected answer is derived from
 * that table rather than copied from a run.
 *
 * <p>The {@link WatermarkTracker} cases run against the class directly, because the rules they
 * check -- minimum across partitions, exclusion of a quiet one, refusal to regress -- have no
 * shipped surface at all. FINDINGS T-1 and T-2 are both in that group, and both are mine.
 */
class EventTimeTest {

    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    /** 2026-01-01T00:00:00Z, TIME.md's T0. */
    private static final long T0 = 1_767_225_600_000_000_000L;

    private static final String SPEC = "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP";

    /** Q10 of TIME.md, registered with {@code --keys 0}: window_start is unique per window. */
    private static final String Q10 = "SELECT window_start, window_end, COUNT(*) AS n, SUM(amount) AS total FROM "
            + "TABLE(TUMBLE(TABLE ev, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end";

    /** {@code evB.csv}: 121 rows, {@code k,u{k mod 5},k,T0+k}. */
    private static String evB() {
        StringBuilder csv = new StringBuilder();
        for (int k = 0; k <= 120; k++) {
            csv.append(k)
                    .append(",u")
                    .append(k % 5)
                    .append(',')
                    .append(k)
                    .append(',')
                    .append(T0 + k * SECOND)
                    .append('\n');
        }
        return csv.toString();
    }

    /**
     * TIME.md's canonical arithmetic: {@code total(i) = 100i - 100 + 45} for window <em>i</em>.
     *
     * <p>Window 1 is 0+1+...+9 = 45, window 2 is 10+...+19 = 145, and so on. Written as the
     * formula rather than as eleven literals so that the test carries the derivation.
     */
    private static long total(int window) {
        return 100L * window - 100 + 45;
    }

    // ================================================== event-time declaration

    @Test
    void time001_theDefaultTenSecondLatenessFiresElevenOfThirteenWindows(@TempDir Path dir) throws Exception {
        // TIME-001, the base case every other case is measured against. The highest event time in
        // the file is T0+120, so with d = 10s the watermark settles at T0+110 and every window end
        // <= T0+110 fires: eleven of them, ends T0+10 through T0+110.
        List<String> rows = configured(dir, evB(), Duration.ofSeconds(10), Q10, 121, 11);
        assertThat(windowTotals(rows)).isEqualTo(expected(11));
        assertThat(rows).allMatch(r -> r.split("\\|")[2].equals("10"), "every window holds ten rows");
    }

    @Test
    void time002And009_aStreamWithNoDeclaredEventTimeIngestsEverythingAndServesNothing(@TempDir Path dir)
            throws Exception {
        // TIME-002 and TIME-009. Without an event-time column every row is stamped zero, the
        // watermark never reaches a window in the present, and the query reports RUNNING with
        // 121 rows in and nothing out -- for ever, with no warning anywhere. This is the failure
        // mode that looks exactly like a healthy query, and pinning it is the point.
        Path data = dir.resolve("evB.csv");
        Files.writeString(data, evB());
        StreamSchema noEventTime = StreamSchema.builder("ev")
                .field("id", Types.int64())
                .field("usr", Types.string())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .build();
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("ev", "filesystem", Map.of("path", data.toString(), "schema", SPEC)));
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, noEventTime)
                .feedingFrom(feeds)
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50))) {
            RegisteredQuery query = registry.register("w", Q10, List.of(0), Principal.ANONYMOUS);
            awaitRowsIn(query, 121);
            Thread.sleep(1_000);
            assertThat(new ViewQuery(views).execute("SELECT * FROM w").size())
                    .as("121 rows in, nothing out, and nothing says why")
                    .isZero();
            assertThat(query.failure()).isEmpty();
        }
    }

    // ================================================== out-of-orderness

    @Test
    void time030And075_zeroLatenessFiresTwelveWindows(@TempDir Path dir) throws Exception {
        // TIME-030 and TIME-075. With d = 0 the watermark is the highest event time, T0+120, so
        // twelve ends fire and the last holds 110+...+119 = 1145. Window 13 ([T0+120, T0+130))
        // holds the single row at T0+120 and never closes, which is 1 of 121 rows -- against the
        // eleven rows d = 10s loses, or 9% of the file.
        List<String> rows = configured(dir, evB(), Duration.ZERO, Q10, 121, 12);
        assertThat(windowTotals(rows)).isEqualTo(expected(12));
        assertThat(windowTotals(rows).get(11)).isEqualTo(1145L); // 110 + 111 + ... + 119
    }

    @Test
    void time027And034_asixtySecondLatenessFiresSixWindows(@TempDir Path dir) throws Exception {
        // TIME-027 and TIME-034 (the '1m' and '60s' spellings are the same Duration). Watermark
        // T0+120-60 = T0+60, so ends T0+10 through T0+60 fire: six windows, last total 545.
        List<String> rows = configured(dir, evB(), Duration.ofSeconds(60), Q10, 121, 6);
        assertThat(windowTotals(rows)).isEqualTo(expected(6));
        assertThat(windowTotals(rows).get(5)).isEqualTo(545L); // 50 + 51 + ... + 59
    }

    @Test
    void time032_oneMillisecondOfLatenessCostsAWholeWindow(@TempDir Path dir) throws Exception {
        // TIME-032. The watermark is T0+119.999, and the window ending at exactly T0+120 is one
        // millisecond short of closing -- so a millisecond of declared lateness costs the same
        // window that ten seconds of it does. Eleven windows, not twelve.
        List<String> rows = configured(dir, evB(), Duration.ofMillis(1), Q10, 121, 11);
        assertThat(windowTotals(rows)).isEqualTo(expected(11));
    }

    @Test
    void time033_theSchemaDefaultIsTenSeconds(@TempDir Path dir) throws Exception {
        // TIME-033. Declaring no lateness must be the same as declaring ten seconds, because that
        // is what StreamSchema.DEFAULT_OUT_OF_ORDERNESS says -- and if the default ever moves,
        // every query in every deployment changes its answer silently.
        assertThat(StreamSchema.DEFAULT_OUT_OF_ORDERNESS).isEqualTo(Duration.ofSeconds(10));
        List<String> rows = configured(dir, evB(), null, Q10, 121, 11);
        assertThat(windowTotals(rows)).isEqualTo(expected(11));
    }

    @Test
    void time039_twentyFiveSecondsOfLatenessLeavesFourWindowsOpen(@TempDir Path dir) throws Exception {
        // TIME-039. Watermark T0+95, so ends up to T0+90 fire: nine windows, last total 845. The
        // remaining four (ends 100, 110, 120, 130) stay open -- ceil(25/10) = 3 full windows plus
        // the partial one, which is the general rule this cell demonstrates.
        List<String> rows = configured(dir, evB(), Duration.ofSeconds(25), Q10, 121, 9);
        assertThat(windowTotals(rows)).isEqualTo(expected(9));
        assertThat(windowTotals(rows).get(8)).isEqualTo(845L); // 80 + 81 + ... + 89
    }

    @Test
    void time035_alatenessLargerThanTheDataServesNothingAndSaysNothing(@TempDir Path dir) throws Exception {
        // TIME-035. Ten minutes of declared lateness over two minutes of data puts the watermark
        // at T0-480 and no window ever fires. 121 rows in, nothing out, RUNNING, no warning --
        // indistinguishable from TIME-002's undeclared stream and from a broken engine, from every
        // surface a deployment has. A typed-in unit is all it takes.
        assertThat(configured(dir, evB(), Duration.ofMinutes(10), Q10, 121, 0)).isEmpty();
    }

    @Test
    @Disabled("PRV-TIME defect 2: a declared lateness far larger than the data stalls the lane at "
            + "shutdown. out-of-orderness 3650d over evB.csv leaves close() raising PRV-3010 'lane 0 "
            + "did not stop within PT5S' every run, where 10m over the same file closes in half a "
            + "second -- so a configuration typo costs the process its lane on the way out.")
    void time037_averyLargeLatenessStartsAndServesNothing(@TempDir Path dir) throws Exception {
        // TIME-037. 3,650 days of lateness puts the watermark in 2016 and no window fires. The
        // case expects that to be uneventful: no overflow, no error. It is not -- the query runs,
        // serves nothing, and then hangs its lane when the registry is closed.
        assertThat(configured(dir, evB(), Duration.ofDays(3650), Q10, 121, 0)).isEmpty();
    }

    @Test
    void time036_anegativeLatenessIsRefused() {
        // TIME-036. Zero means the source is strictly ordered; below zero means nothing at all,
        // and would put the watermark ahead of the data so that every row arrived late.
        assertThatThrownBy(() -> StreamSchema.builder("ev")
                        .field("event_time", Types.timestamp())
                        .outOfOrderness(Duration.ofSeconds(-1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out-of-orderness must not be negative");
        assertThatThrownBy(() -> WatermarkGenerator.boundedOutOfOrderness(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void time038_boundedOutOfOrdernessDoesNotSaturateAndCanWrapToTheFarFuture() {
        // TIME-038. maxSeen - d is computed without a guard, so an enormous d against a negative
        // event time wraps past Long.MAX_VALUE into a watermark 292 years in the future -- which
        // would close every window in one tick and drop the rest of the stream as late.
        //
        // Pinned as the engine's behaviour, not as correct behaviour. A saturating subtraction
        // would be an improvement and this test is what would notice it landing.
        WatermarkGenerator generator = WatermarkGenerator.boundedOutOfOrderness(Long.MAX_VALUE);
        generator.observe(-SECOND);
        // -10^9 - (2^63 - 1) wraps to +9,223,372,035,854,775,809 rather than saturating low.
        assertThat(generator.watermark()).isEqualTo(-SECOND - Long.MAX_VALUE).isPositive();
    }

    @Test
    void time043_asingleRowNeverClosesItsOwnWindow(@TempDir Path dir) throws Exception {
        // TIME-043. One row at T0+7 with two seconds of lateness: the watermark reaches T0+5,
        // which is below the window's end at T0+10, so the answer is empty for ever. With zero
        // lateness the watermark reaches T0+7 and it is still below T0+10. A bounded source
        // cannot close the window its own last row is in.
        String one = "7,u2,7," + (T0 + 7 * SECOND) + "\n";
        assertThat(configured(dir.resolve("d2"), one, Duration.ofSeconds(2), Q10, 1, 0))
                .isEmpty();
        assertThat(configured(dir.resolve("d0"), one, Duration.ZERO, Q10, 1, 0)).isEmpty();
    }

    // ================================================== tick and window size

    @Test
    void time082_oneSecondWindowsOverOneRowPerSecond(@TempDir Path dir) throws Exception {
        // TIME-082. With d = 0 the watermark is T0+120 and the 120 windows ending T0+1 through
        // T0+120 fire, each holding exactly one row. The window ending at T0+121 holds the row at
        // T0+120 and does not fire, so 120 of 121 rows are reported.
        List<String> rows = configured(
                dir,
                evB(),
                Duration.ZERO,
                "SELECT window_start, window_end, COUNT(*) AS n, SUM(amount) AS total FROM "
                        + "TABLE(TUMBLE(TABLE ev, DESCRIPTOR(event_time), INTERVAL '1' SECOND)) "
                        + "GROUP BY window_start, window_end",
                121,
                120);
        assertThat(rows).hasSize(120);
        assertThat(rows).allMatch(r -> r.split("\\|")[2].equals("1"), "one row per one-second window");
        // The window ending at T0+i holds the row at T0+i-1, whose amount is i-1: 0+1+...+119.
        assertThat(sumOf(rows, 3)).isEqualTo(119L * 120 / 2);
    }

    @Test
    void time085_oneMinuteWindowsOverHalfAnHourOfData(@TempDir Path dir) throws Exception {
        // TIME-085, at TIME.md's evLong.csv: 1,801 rows, ids 0..1800, one per second. With d = 0
        // the watermark is T0+1800 and thirty one-minute windows fire, each holding sixty rows.
        // Window i holds ids 60(i-1)..60i-1, so total(i) = 60*60(i-1) + (0+1+...+59)
        //                                               = 3600i - 3600 + 1770.
        StringBuilder csv = new StringBuilder();
        for (int k = 0; k <= 1800; k++) {
            csv.append(k)
                    .append(",u")
                    .append(k % 5)
                    .append(',')
                    .append(k)
                    .append(',')
                    .append(T0 + k * SECOND)
                    .append('\n');
        }
        List<String> rows = configured(
                dir,
                csv.toString(),
                Duration.ZERO,
                "SELECT window_start, window_end, COUNT(*) AS n, SUM(amount) AS total FROM "
                        + "TABLE(TUMBLE(TABLE ev, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)) "
                        + "GROUP BY window_start, window_end",
                1801,
                30);
        List<Long> totals = windowTotals(rows);
        assertThat(totals).hasSize(30);
        assertThat(totals.get(0)).isEqualTo(1770L); // 0 + 1 + ... + 59
        assertThat(totals.get(1)).isEqualTo(5370L); // 3600 * 2 - 3600 + 1770
        assertThat(totals.get(29)).isEqualTo(106_170L); // 3600 * 30 - 3600 + 1770
        assertThat(rows).allMatch(r -> r.split("\\|")[2].equals("60"), "sixty rows per minute");
    }

    // ================================================== ordering

    @Test
    void time105_theOrderedControlAccountsForEveryRowInTheFile(@TempDir Path dir) throws Exception {
        // TIME-105. The sum of the eleven emitted totals is (45 + 1045) * 11 / 2 = 5,995, and the
        // file's own sum is 0+1+...+120 = 7,260. The difference, 1,265, is exactly windows 12
        // (1,145) and 13 (120), which are still open. Nothing is lost -- it is held, and the
        // arithmetic is what distinguishes the two.
        List<String> rows = configured(dir, evB(), Duration.ofSeconds(10), Q10, 121, 11);
        long emitted = sumOf(rows, 3);
        assertThat(emitted).isEqualTo((45 + 1045) * 11 / 2).isEqualTo(5_995);
        long inFile = 120L * 121 / 2;
        assertThat(inFile).isEqualTo(7_260);
        assertThat(inFile - emitted).as("window 12 (1145) plus window 13 (120)").isEqualTo(1_145 + 120);
    }

    @Test
    void time108_roundingEveryEventTimeDownToTheWindowChangesNoTotal(@TempDir Path dir) throws Exception {
        // TIME-108, evDup.csv: every event time floored to a multiple of ten seconds. Each row
        // stays in the window it was already in, so the twelve totals are unchanged -- which is
        // the control proving the assignment depends on the window and not on the spread of times
        // inside it.
        StringBuilder csv = new StringBuilder();
        for (int k = 0; k <= 120; k++) {
            long floored = T0 + (k / 10) * 10 * SECOND;
            csv.append(k)
                    .append(",u")
                    .append(k % 5)
                    .append(',')
                    .append(k)
                    .append(',')
                    .append(floored)
                    .append('\n');
        }
        List<String> rows = configured(dir, csv.toString(), Duration.ZERO, Q10, 121, 12);
        assertThat(windowTotals(rows)).isEqualTo(expected(12));
        // (45 + 1145) * 12 / 2 = 7,140, and 7,260 - 7,140 = 120 is the single open row at T0+120.
        assertThat(sumOf(rows, 3)).isEqualTo(7_140);
    }

    @Test
    void time109_everyRowAtOneInstantNeverClosesItsWindow(@TempDir Path dir) throws Exception {
        // TIME-109, evSame.csv: all 121 rows at T0+60. The watermark is T0+60 for ever and the
        // only populated window ends at T0+70, so the view is empty however long anyone waits --
        // while 121 rows carrying a total of 7,260 sit in it. A single row at T0+80 would release
        // it, which is the difference between "no data" and "no watermark".
        StringBuilder csv = new StringBuilder();
        for (int k = 0; k <= 120; k++) {
            csv.append(k)
                    .append(",u")
                    .append(k % 5)
                    .append(',')
                    .append(k)
                    .append(',')
                    .append(T0 + 60 * SECOND)
                    .append('\n');
        }
        assertThat(configured(dir.resolve("stuck"), csv.toString(), Duration.ZERO, Q10, 121, 0))
                .isEmpty();

        // The release: one row at T0+80 pushes the watermark past T0+70.
        csv.append("121,u1,0,").append(T0 + 80 * SECOND).append('\n');
        List<String> released = configured(dir.resolve("freed"), csv.toString(), Duration.ZERO, Q10, 122, 1);
        assertThat(released).hasSize(1);
        assertThat(released.get(0).split("\\|")[2]).isEqualTo("121");
        assertThat(released.get(0).split("\\|")[3]).isEqualTo("7260"); // 0 + 1 + ... + 120
    }

    // ================================================== retention

    @Test
    void time117_retentionEvictsInEventTimeAndCountsWhatItRemoved(@TempDir Path dir) throws Exception {
        // TIME-117. Retention is measured in event time, not wall clock: with a thirty-second
        // horizon against a frontier at T0+120, twelve windows fire and only those whose end is at
        // or after T0+90 survive -- the four ending T0+90, T0+100, T0+110 and T0+120. The eight
        // older ones are evicted, which is what a bounded view costs and what makes it bounded.
        List<String> rows =
                configuredWith(dir, evB(), Duration.ZERO, Q10, 121, 4, Retention.ofAge(Duration.ofSeconds(30)));
        assertThat(rows).hasSize(4);
        assertThat(windowTotals(rows)).containsExactly(total(9), total(10), total(11), total(12));
        assertThat(windowTotals(rows)).containsExactly(845L, 945L, 1045L, 1145L);
        // Twelve fired, four kept; the eight evicted held 45 + 145 + ... + 745 = 3,160.
        assertThat(sumOf(rows, 3)).isEqualTo(845 + 945 + 1045 + 1145);
    }

    // ================================================== WatermarkTracker

    @Test
    void time047And050And052And060_theIdleTimeoutBoundsAreEnforcedAtConstruction() {
        // TIME-047, TIME-050, TIME-052, TIME-053 and TIME-060 in one place: the bounds are checked
        // where the tracker is built, which is what makes a bad pravaha.watermark.idle-after a
        // startup refusal rather than a query that quietly never fires.
        assertThat(WatermarkTracker.MINIMUM_IDLE_TIMEOUT).isEqualTo(Duration.ofSeconds(1));
        assertThat(WatermarkTracker.MAXIMUM_IDLE_TIMEOUT).isEqualTo(Duration.ofMinutes(10));
        assertThat(WatermarkTracker.DEFAULT_IDLE_TIMEOUT_NANOS).isEqualTo(30 * SECOND);

        assertThatThrownBy(() -> new WatermarkTracker(999 * MS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WatermarkTracker(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WatermarkTracker(-5 * SECOND)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WatermarkTracker(600_000_000_001L)).isInstanceOf(IllegalArgumentException.class);
        // And the two boundaries themselves are accepted, with no clamping.
        assertThat(new WatermarkTracker(SECOND).watermark()).isEqualTo(WatermarkGenerator.NOT_YET);
        assertThat(new WatermarkTracker(600 * SECOND).watermark()).isEqualTo(WatermarkGenerator.NOT_YET);
    }

    @Test
    void time061_theWatermarkIsTheMinimumAcrossPartitionsUntilOneGoesQuiet() {
        // TIME-061. Two partitions with two seconds of lateness: p0 at 100s and p1 at 50s, so the
        // lane's watermark is min(98s, 48s) = 48s. Sixty seconds later p0 has moved to 200s and p1
        // has said nothing, so p1 is excluded and the lane follows p0 alone: 198s.
        WatermarkTracker tracker = new WatermarkTracker(30 * SECOND);
        tracker.addPartition("p0", WatermarkGenerator.boundedOutOfOrderness(2 * SECOND), 0);
        tracker.addPartition("p1", WatermarkGenerator.boundedOutOfOrderness(2 * SECOND), 0);
        tracker.observe("p0", 100 * SECOND, 0);
        tracker.observe("p1", 50 * SECOND, 0);
        assertThat(tracker.advance(0)).isEqualTo(48 * SECOND); // min(100 - 2, 50 - 2)

        tracker.observe("p0", 200 * SECOND, 60 * SECOND);
        assertThat(tracker.advance(60 * SECOND)).isEqualTo(198 * SECOND); // 200 - 2, p1 excluded
        assertThat(tracker.idleExclusions()).isEqualTo(1);
        assertThat(tracker.isIdle("p1")).isTrue();
        assertThat(tracker.regressions()).isZero();
    }

    @Test
    void time062_onlyTheQuietPartitionIsExcluded() {
        // TIME-062. Five partitions, four of which keep speaking: the one that went quiet is
        // dropped from the minimum and the other four still bound it. The falsifier is the
        // watermark jumping to the maximum, which would mean exclusion is all-or-nothing.
        WatermarkTracker tracker = new WatermarkTracker(30 * SECOND);
        long[] times = {100, 110, 120, 130, 140};
        for (int i = 0; i < 5; i++) {
            tracker.addPartition("p" + i, WatermarkGenerator.boundedOutOfOrderness(2 * SECOND), 0);
            tracker.observe("p" + i, times[i] * SECOND, 0);
        }
        assertThat(tracker.advance(0)).isEqualTo(98 * SECOND); // min over all five: 100 - 2
        for (int i = 1; i < 5; i++) {
            tracker.observe("p" + i, times[i] * SECOND, 60 * SECOND);
        }
        assertThat(tracker.advance(60 * SECOND)).isEqualTo(108 * SECOND); // p0 excluded: 110 - 2
        assertThat(tracker.idleExclusions()).isEqualTo(1);
        assertThat(tracker.partitionCount()).isEqualTo(5);
    }

    @Test
    void time063_whenEverythingIsQuietTheWatermarkStaysWhereItIs() {
        // TIME-063. Not Long.MAX_VALUE, and not the wall clock: an all-idle lane holds its
        // watermark, because jumping it forward would close every open window against data that
        // may still arrive. And idleExclusions counts transitions rather than ticks, so it stops
        // at two however many times advance is called.
        WatermarkTracker tracker = new WatermarkTracker(30 * SECOND);
        tracker.addPartition("p0", WatermarkGenerator.boundedOutOfOrderness(2 * SECOND), 0);
        tracker.addPartition("p1", WatermarkGenerator.boundedOutOfOrderness(2 * SECOND), 0);
        tracker.observe("p0", 100 * SECOND, 0);
        tracker.observe("p1", 50 * SECOND, 0);
        assertThat(tracker.advance(0)).isEqualTo(48 * SECOND);
        assertThat(tracker.advance(60 * SECOND)).isEqualTo(48 * SECOND);
        assertThat(tracker.advance(120 * SECOND)).isEqualTo(48 * SECOND);
        assertThat(tracker.advance(600 * SECOND)).isEqualTo(48 * SECOND);
        assertThat(tracker.idleExclusions()).isEqualTo(2);
    }

    @Test
    void time064And065_awatermarkNeverGoesBackwardsAndSaysSoWhenItWouldHave() {
        // TIME-064 and TIME-065. A partition that wakes up behind the lane would drag the
        // watermark back, which would reopen closed windows and reissue answers that were already
        // served. The tracker refuses and counts it; the count is what makes a source with a
        // stale replica visible rather than merely survivable.
        WatermarkTracker tracker = new WatermarkTracker(30 * SECOND);
        tracker.addPartition("p0", WatermarkGenerator.boundedOutOfOrderness(2 * SECOND), 0);
        tracker.addPartition("p1", WatermarkGenerator.boundedOutOfOrderness(2 * SECOND), 0);
        tracker.observe("p0", 100 * SECOND, 0);
        tracker.observe("p1", 50 * SECOND, 0);
        tracker.advance(0);
        tracker.observe("p0", 200 * SECOND, 60 * SECOND);
        assertThat(tracker.advance(60 * SECOND)).isEqualTo(198 * SECOND);

        // p1 comes back at 150s, so the minimum would be 148s -- behind 198s.
        tracker.observe("p1", 150 * SECOND, 70 * SECOND);
        assertThat(tracker.advance(70 * SECOND)).isEqualTo(198 * SECOND);
        assertThat(tracker.regressions()).isEqualTo(1);

        // It catches up: 300 - 2 = 298 against p0's 198, so the minimum is still 198 and no
        // further regression is recorded.
        tracker.observe("p1", 300 * SECOND, 80 * SECOND);
        assertThat(tracker.advance(80 * SECOND)).isEqualTo(198 * SECOND);
        assertThat(tracker.regressions()).isEqualTo(1);

        // Now p0 moves too and the minimum is p1's.
        tracker.observe("p0", 400 * SECOND, 90 * SECOND);
        assertThat(tracker.advance(90 * SECOND)).isEqualTo(298 * SECOND); // min(398, 298)
    }

    @Test
    void time066And067_apartitionThatHasNeverSpokenHoldsTheWatermarkUntilItGoesIdle() {
        // TIME-066 and TIME-067. Until the idle timeout the lane reports NOT_YET, because a
        // partition that has said nothing might be about to say something old. At exactly the
        // timeout it is excluded and the lane follows the one that has spoken. And with no
        // partition having ever spoken, exclusion changes nothing: NOT_YET stands.
        WatermarkTracker tracker = new WatermarkTracker(30 * SECOND);
        tracker.addPartition("p0", WatermarkGenerator.boundedOutOfOrderness(2 * SECOND), 0);
        tracker.addPartition("p1", WatermarkGenerator.boundedOutOfOrderness(2 * SECOND), 0);
        // p0 keeps speaking throughout, so the only candidate for exclusion is p1, which never has.
        tracker.observe("p0", 100 * SECOND, 0);
        assertThat(tracker.advance(0)).isEqualTo(WatermarkGenerator.NOT_YET);
        tracker.observe("p0", 100 * SECOND, 10 * SECOND);
        assertThat(tracker.advance(10 * SECOND)).isEqualTo(WatermarkGenerator.NOT_YET);
        tracker.observe("p0", 100 * SECOND, 29 * SECOND);
        assertThat(tracker.advance(29 * SECOND)).isEqualTo(WatermarkGenerator.NOT_YET);
        tracker.observe("p0", 100 * SECOND, 30 * SECOND);
        assertThat(tracker.advance(30 * SECOND)).isEqualTo(98 * SECOND);
        tracker.observe("p0", 100 * SECOND, 31 * SECOND);
        assertThat(tracker.advance(31 * SECOND)).isEqualTo(98 * SECOND);

        WatermarkTracker silent = new WatermarkTracker(30 * SECOND);
        for (int i = 0; i < 3; i++) {
            silent.addPartition("q" + i, WatermarkGenerator.boundedOutOfOrderness(0), 0);
        }
        assertThat(silent.advance(0)).isEqualTo(WatermarkGenerator.NOT_YET);
        assertThat(silent.advance(29 * SECOND)).isEqualTo(WatermarkGenerator.NOT_YET);
        assertThat(silent.advance(31 * SECOND)).isEqualTo(WatermarkGenerator.NOT_YET);
        assertThat(silent.idleExclusions()).isEqualTo(3);
    }

    @Test
    void time069_theIdleBoundaryIsInclusive() {
        // TIME-069. At one nanosecond under the timeout the partition is still live; at exactly
        // the timeout it is idle. An exclusive comparison here would delay every exclusion by a
        // whole tick, which at a five-minute tick is five minutes of a stalled query.
        WatermarkTracker tracker = new WatermarkTracker(30 * SECOND);
        tracker.addPartition("p0", WatermarkGenerator.boundedOutOfOrderness(0), 0);
        tracker.addPartition("p1", WatermarkGenerator.boundedOutOfOrderness(0), 0);
        tracker.observe("p0", 100 * SECOND, 0);
        tracker.observe("p1", 50 * SECOND, 0);
        tracker.advance(30 * SECOND - 1);
        assertThat(tracker.isIdle("p1")).isFalse();
        tracker.advance(30 * SECOND);
        assertThat(tracker.isIdle("p1")).isTrue();
    }

    @Test
    void time070_idleExclusionsCountsTransitionsAndNotTicks() {
        // TIME-070. A partition that goes quiet, speaks again and goes quiet again is two
        // exclusions, not sixty. The distinction matters because the number is meant to tell an
        // operator how often a source stalls, not how often the clock ticked while it was stalled.
        WatermarkTracker tracker = new WatermarkTracker(30 * SECOND);
        tracker.addPartition("p0", WatermarkGenerator.boundedOutOfOrderness(0), 0);
        tracker.addPartition("p1", WatermarkGenerator.boundedOutOfOrderness(0), 0);
        tracker.observe("p1", 10 * SECOND, 0);
        for (long t = 0; t <= 20 * SECOND; t += 10 * SECOND) {
            tracker.observe("p0", 100 * SECOND, t);
            tracker.advance(t);
        }
        assertThat(tracker.idleExclusions()).isZero();
        tracker.observe("p0", 100 * SECOND, 30 * SECOND);
        tracker.advance(30 * SECOND);
        assertThat(tracker.idleExclusions()).isEqualTo(1);
        tracker.observe("p0", 100 * SECOND, 60 * SECOND);
        tracker.advance(60 * SECOND);
        assertThat(tracker.idleExclusions())
                .as("still quiet is not a second exclusion")
                .isEqualTo(1);
        tracker.observe("p1", 120 * SECOND, 70 * SECOND);
        tracker.advance(70 * SECOND);
        assertThat(tracker.idleExclusions()).isEqualTo(1);
        tracker.observe("p0", 200 * SECOND, 100 * SECOND);
        tracker.advance(100 * SECOND);
        assertThat(tracker.idleExclusions()).as("quiet again is the second").isEqualTo(2);
    }

    @Test
    void time071_excludingAQuietPartitionCanMoveTheWatermarkByFourMinutesInOneTick() {
        // TIME-071. p0 is far ahead and p1 is holding the lane back at T0+60. When p1 is excluded
        // the lane jumps to p0's 300s -- 240 seconds in a single tick, which under ten-second
        // windows fires twenty-four windows at once. Correct, and worth knowing before it happens
        // in production at three in the morning.
        WatermarkTracker tracker = new WatermarkTracker(30 * SECOND);
        tracker.addPartition("p0", WatermarkGenerator.boundedOutOfOrderness(0), 0);
        tracker.addPartition("p1", WatermarkGenerator.boundedOutOfOrderness(0), 0);
        tracker.observe("p0", 300 * SECOND, 0);
        tracker.observe("p1", 60 * SECOND, 0);
        assertThat(tracker.advance(0)).isEqualTo(60 * SECOND);
        tracker.observe("p0", 300 * SECOND, 40 * SECOND);
        assertThat(tracker.advance(40 * SECOND)).isEqualTo(300 * SECOND);
        // 300 - 60 = 240 seconds of event time crossed in one tick: 24 ten-second windows.
        assertThat((300 - 60) / 10).isEqualTo(24);
    }

    @Test
    void time104_arowAtLongMinValueIsIndistinguishableFromNoRowAtAll() {
        // TIME-104. NOT_YET is Long.MIN_VALUE, so a row genuinely carrying that event time cannot
        // be told apart from a partition that has never produced one -- and the query sits at
        // NOT_YET for ever with a row in hand.
        WatermarkGenerator generator = WatermarkGenerator.boundedOutOfOrderness(0);
        generator.observe(Long.MIN_VALUE);
        assertThat(generator.watermark()).isEqualTo(WatermarkGenerator.NOT_YET);

        // The other extreme closes everything: MAX_VALUE - d is past every window there is.
        WatermarkGenerator top = WatermarkGenerator.boundedOutOfOrderness(10 * SECOND);
        top.observe(Long.MAX_VALUE);
        assertThat(top.watermark()).isEqualTo(Long.MAX_VALUE - 10 * SECOND);
    }

    @Test
    void time110_eachPartitionAppliesItsOwnLatenessBeforeTheMinimumIsTaken() {
        // TIME-110. p0 is strictly ordered and p1 declares a minute of lateness, so the two
        // partitions' watermarks are computed under different rules and only then combined. The
        // lane's is the minimum -- T0+20 -- which is neither partition's headline number.
        WatermarkGenerator ordered = WatermarkGenerator.boundedOutOfOrderness(0);
        WatermarkGenerator late = WatermarkGenerator.boundedOutOfOrderness(60 * SECOND);
        for (long t : List.of(0L, 10 * SECOND, 20 * SECOND)) {
            ordered.observe(T0 + t);
        }
        for (long t : List.of(100 * SECOND, 5 * SECOND, 50 * SECOND)) {
            late.observe(T0 + t);
        }
        assertThat(ordered.watermark()).isEqualTo(T0 + 20 * SECOND);
        assertThat(late.watermark()).isEqualTo(T0 + 100 * SECOND - 60 * SECOND).isEqualTo(T0 + 40 * SECOND);
        assertThat(Math.min(ordered.watermark(), late.watermark())).isEqualTo(T0 + 20 * SECOND);
    }

    @Test
    @Disabled("PRV-TIME defect 1 (FINDINGS T-1): idle exclusion is unreachable for any partition that "
            + "has ever produced a row. advanceWatermarkQuietly re-observes each partition's retained "
            + "high-water mark every tick, which refreshes lastActivityNanos, so a partition that spoke "
            + "once and went quiet pins the lane's watermark for ever -- the exact case the feature "
            + "exists for. Only a partition that has never spoken can go idle.")
    void time072_apartitionThatSpokeAndWentQuietIsStillExcluded() {
        // TIME-072, expressed against the tracker. The engine's pump re-observes a retained
        // high-water mark on every tick rather than a per-tick delta, so this is what the lane
        // actually does: observe(p1, <the same value as last time>, now) at every tick.
        //
        // The assertion below is what idle exclusion promises. It fails, because re-observing the
        // retained value sets lastActivityNanos = now and the partition is never idle.
        WatermarkTracker tracker = new WatermarkTracker(SECOND);
        tracker.addPartition("busy", WatermarkGenerator.boundedOutOfOrderness(0), 0);
        tracker.addPartition("quiet", WatermarkGenerator.boundedOutOfOrderness(0), 0);
        tracker.observe("quiet", 30 * SECOND, 0);
        for (long now = 0; now <= 20 * SECOND; now += SECOND) {
            tracker.observe("busy", 100 * SECOND + now, now);
            // What advanceWatermarkQuietly does: the retained high-water mark, re-observed.
            tracker.observe("quiet", 30 * SECOND, now);
            tracker.advance(now);
        }
        assertThat(tracker.isIdle("quiet"))
                .as("a partition silent for twenty seconds under a one-second timeout must be idle")
                .isTrue();
    }

    // ------------------------------------------------------------------ helpers

    /** The window totals TIME.md's table gives for the first {@code n} windows. */
    private static List<Long> expected(int n) {
        List<Long> totals = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            totals.add(total(i));
        }
        return totals;
    }

    private static List<Long> windowTotals(List<String> rows) {
        return rows.stream()
                .sorted(java.util.Comparator.comparingLong(r -> Long.parseLong(r.split("\\|")[0])))
                .map(r -> Long.parseLong(r.split("\\|")[3]))
                .toList();
    }

    private static long sumOf(List<String> rows, int ordinal) {
        long total = 0;
        for (String row : rows) {
            total += Long.parseLong(row.split("\\|")[ordinal]);
        }
        return total;
    }

    private static List<String> configured(
            Path dir, String csv, Duration outOfOrderness, String sql, long expectRowsIn, int expectViewRows)
            throws Exception {
        return configuredWith(dir, csv, outOfOrderness, sql, expectRowsIn, expectViewRows, null);
    }

    /**
     * The configured path: binding, registry, watermark clock, view.
     *
     * <p>An expected count of zero is a real expectation here and not an absence of one -- half
     * these cases are about a watermark that never reaches a window -- so it waits a fixed second
     * and then insists the view is still empty, rather than passing the moment it looks.
     */
    private static List<String> configuredWith(
            Path dir,
            String csv,
            Duration outOfOrderness,
            String sql,
            long expectRowsIn,
            int expectViewRows,
            Retention retention)
            throws Exception {
        Files.createDirectories(dir);
        Path data = dir.resolve("ev.csv");
        Files.writeString(data, csv);

        StreamSchema.Builder builder = StreamSchema.builder("ev")
                .field("id", Types.int64())
                .field("usr", Types.string())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time");
        if (outOfOrderness != null) {
            builder.outOfOrderness(outOfOrderness);
        }
        StreamSchema ev = builder.build();

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "ev",
                        "filesystem",
                        Map.of("path", data.toString(), "schema", SPEC, "event.time", "event_time")));
        ViewCatalog views = new ViewCatalog();
        QueryRegistry registry = new QueryRegistry(views, ev)
                .feedingFrom(feeds)
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50));
        if (retention != null) {
            registry.retaining(retention);
        }
        try (QueryRegistry open = registry) {
            RegisteredQuery query = open.register("w", sql, List.of(0), Principal.ANONYMOUS);
            awaitRowsIn(query, expectRowsIn);
            ViewQuery reader = new ViewQuery(views);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline
                    && reader.execute("SELECT * FROM w").size() < expectViewRows) {
                Thread.sleep(20);
            }
            // Settle: a view that overshoots is as much a failure as one that undershoots, and
            // only a read after quiet can tell.
            Thread.sleep(400);
            List<Object[]> rows = reader.execute("SELECT * FROM w").rows();
            assertThat(rows)
                    .as("the view settled on %d rows; %d were expected", rows.size(), expectViewRows)
                    .hasSize(expectViewRows);
            List<String> rendered = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) {
                        text.append('|');
                    }
                    text.append(row[i]);
                }
                rendered.add(text.toString());
            }
            return rendered;
        }
    }

    private static void awaitRowsIn(RegisteredQuery query, long atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline && query.rowsIn() < atLeast) {
            Thread.sleep(10);
        }
        assertThat(query.rowsIn())
                .as("the feed delivered %d rows; %d are in the file", query.rowsIn(), atLeast)
                .isGreaterThanOrEqualTo(atLeast);
        assertThat(query.failure()).isEmpty();
    }
}
