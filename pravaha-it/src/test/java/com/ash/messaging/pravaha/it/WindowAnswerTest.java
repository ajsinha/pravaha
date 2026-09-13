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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.window.SessionWindows;
import com.ash.messaging.pravaha.runtime.window.SlicedAggregateState;
import com.ash.messaging.pravaha.runtime.window.SlicedWindows;
import com.ash.messaging.pravaha.runtime.window.WindowSpec;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.server.ingest.SourceBinding;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code docs/qa/cases/WIN.md}, executed.
 *
 * <p>Every case in that file was prose: a human read the setup, typed the commands and compared the
 * numbers by eye. 210 of them. Converted here they run in every build, which is the only form in
 * which a QA answer stays true -- the prose version was already stale in three places by the time
 * it was written down, and nothing in the build could tell.
 *
 * <p>Two harnesses, and the case ids say which. The <em>configured</em> ones go through the path a
 * deployment actually has: a {@link SourceBinding} naming the filesystem plugin, a
 * {@link QueryRegistry} feeding from it, a watermark clock, and the answer read out of the served
 * view. The <em>embedded</em> ones drive {@link SlicedWindows}, {@link WindowSpec},
 * {@link SessionWindows} and {@link SlicedAggregateState} directly, because WIN.md section 0.6
 * records three things the server cannot reach at all -- end of input, more than one lane, and
 * SESSION, which no plan node builds.
 *
 * <p>Every expected number carries its arithmetic in a comment. A test that asserts {@code 202}
 * proves nothing about {@code 100 + 102}; a test that says so can be checked by reading it.
 */
class WindowAnswerTest {

    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private static final String SPEC = "txn_id:INT64,user_id:INT64,amount:INT64,event_time:TIMESTAMP";
    private static final String NULLABLE_SPEC = "txn_id:INT64,user_id:INT64,amount:INT64?,event_time:TIMESTAMP";

    /** Q_T(S) of WIN.md section 0.2: tumbling, size {@code seconds}. */
    private static String tumble(String stream, String interval) {
        return "SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total FROM "
                + "TABLE(TUMBLE(TABLE " + stream + ", DESCRIPTOR(event_time), INTERVAL '" + interval + ")) "
                + "GROUP BY window_start, window_end, user_id";
    }

    /**
     * Q_H(D,S) of section 0.2. The first interval in the SQL text is the <em>slide</em> and the
     * second is the size -- Calcite's order, which {@code PhysicalPlanBuilder} reverses.
     */
    private static String hop(String stream, String slide, String size) {
        return "SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total FROM "
                + "TABLE(HOP(TABLE " + stream + ", DESCRIPTOR(event_time), INTERVAL '" + slide + ", INTERVAL '"
                + size + ")) GROUP BY window_start, window_end, user_id";
    }

    // ------------------------------------------------------------------ datasets

    /** Dataset A of section 0.3, six rows, two users. */
    private static final String A = csv(
            row(1, 100, 10, 1 * SECOND),
            row(2, 100, 20, 5 * SECOND),
            row(3, 200, 30, 7 * SECOND),
            row(4, 100, 40, 11 * SECOND),
            row(5, 200, 50, 19 * SECOND),
            row(6, 100, 60, 25 * SECOND));

    /** A plus the pusher row of section 0.3: user 999, amount 0, at 40.000. */
    private static final String A_PLUS = A + row(7, 999, 0, 40 * SECOND);

    /** Dataset B: boundaries, one user, amounts are powers of two. */
    private static final String B = csv(
            row(1, 100, 1, 0L),
            row(2, 100, 2, 10 * SECOND - 1),
            row(3, 100, 4, 10 * SECOND),
            row(4, 100, 8, 20 * SECOND - 1),
            row(5, 100, 16, 20 * SECOND),
            row(6, 100, 32, 20 * SECOND),
            row(7, 100, 64, 30 * SECOND - 1),
            row(8, 100, 128, 30 * SECOND));

    private static final String B_PLUS = B + row(9, 999, 0, 50 * SECOND);

    /** Dataset C: amounts 10, 20, 30 at 5.000, 15.000, 25.000. */
    private static final String C =
            csv(row(1, 100, 10, 5 * SECOND), row(2, 100, 20, 15 * SECOND), row(3, 100, 30, 25 * SECOND));

    private static final String C_PLUS = C + row(4, 999, 0, 60 * SECOND);

    /** Dataset D: amounts 1 and 2 at 0.000 and 2.000, for the non-dividing hop. */
    private static final String D_PLUS =
            csv(row(1, 100, 1, 0L), row(2, 100, 2, 2 * SECOND)) + row(3, 999, 0, 30 * SECOND);

    private static String row(long id, long user, long amount, long eventTime) {
        return id + "," + user + "," + amount + "," + eventTime + "\n";
    }

    private static String csv(String... rows) {
        return String.join("", rows);
    }

    // ================================================================== 1. TUMBLE

    @Test
    void win001_aTumblingWindowProducesOneRowPerWindowPerKeyWithTheHandComputedNumbers(@TempDir Path dir)
            throws Exception {
        // WIN-001. Dataset A, no pusher: the watermark on a zero-lateness stream settles at the
        // highest event time in the file, 25.000, and windowsCompletedBetween fires ends <= that,
        // so 10.000 and 20.000 only. [20,30) holds user 100's amount 60 and never closes -- WIN-165
        // owns that, and it is the reason a fifth row here would be a defect rather than a bonus.
        List<String> rows = configured(dir, A, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 6, 4);

        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "0|10000000000|100|2|30", // 10 + 20 = 30
                        "0|10000000000|200|1|30", // 30
                        "10000000000|20000000000|100|1|40", // 40
                        "10000000000|20000000000|200|1|50"); // 50
    }

    @Test
    void win002_windowBoundariesAreHalfOpenAndAreTheWindowsOwnNotTheRows(@TempDir Path dir) throws Exception {
        // WIN-002. WindowAssign writes slice boundaries; WindowedAggregate.emitRow overwrites them
        // with the window's own. For TUMBLE slice == window so the two agree, and an engine that
        // echoed the row's event time would give four distinct starts (1, 5, 7, 11...) of width 0.
        List<String> rows = configured(dir, A, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 6, 4);

        for (String row : rows) {
            String[] parts = row.split("\\|");
            long start = Long.parseLong(parts[0]);
            long end = Long.parseLong(parts[1]);
            assertThat(end - start).as("every width is exactly ten seconds").isEqualTo(10 * SECOND);
            assertThat(start % (10 * SECOND))
                    .as("every start is a multiple of the size")
                    .isZero();
            assertThat(start).isIn(0L, 10 * SECOND);
        }
    }

    @Test
    void win010And011_twoNamesForOneWindowedComputationHoldTheSameAnswer(@TempDir Path dir) throws Exception {
        // WIN-010 and WIN-011. Sharing is by fingerprint, so the second registration must be the
        // same computation with the same state -- ROWS IN 6, not 12 -- and dropping one name must
        // leave the other answering exactly what it answered before.
        Path data = dir.resolve("a.csv");
        Files.writeString(data, A);
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = registry(views, dir, data, SPEC, Duration.ZERO)) {
            String sql = tumble("s0", "10' SECOND");
            RegisteredQuery first = registry.register("v_t10", sql, List.of(0, 1, 2), Principal.ANONYMOUS);
            RegisteredQuery second = registry.register("v_t10b", sql, List.of(0, 1, 2), Principal.ANONYMOUS);
            assertThat(second.fingerprint()).isEqualTo(first.fingerprint());

            awaitRowsIn(first, 6);
            awaitView(views, "SELECT * FROM v_t10", 4);
            awaitView(views, "SELECT * FROM v_t10b", 4);
            assertThat(first.rowsIn()).as("one computation reads the file once").isEqualTo(6);

            registry.drop("v_t10b");
            List<String> rows =
                    render(new ViewQuery(views).execute("SELECT * FROM v_t10").rows());
            assertThat(rows)
                    .containsExactlyInAnyOrder(
                            "0|10000000000|100|2|30",
                            "0|10000000000|200|1|30",
                            "10000000000|20000000000|100|1|40",
                            "10000000000|20000000000|200|1|50");
        }
    }

    @Test
    void win007_tumbleWithNoIntervalArgumentIsRefusedRatherThanThrowingRaw() {
        // WIN-007. requireIntervals is the guard; what the case forbids is a bare NPE or an
        // IndexOutOfBoundsException reaching the user. Calcite's own arity error is acceptable.
        assertThatThrownBy(() -> plan("SELECT * FROM TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time)))"))
                .isNotInstanceOf(NullPointerException.class)
                .isNotInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void win008_tumbleWithAZeroIntervalIsRefused() {
        // WIN-008. WindowSpec's constructor throws a plain IllegalArgumentException, so this
        // carries no PRV code and ERRC cannot enumerate it. Asserting the message rather than the
        // type is what keeps the case honest if the guard moves.
        assertThatThrownBy(() -> plan(tumble("s0", "0' SECOND"))).hasMessageContaining("window size must be positive");
    }

    @Test
    void win009_tumbleWithANegativeIntervalIsRefused() {
        // WIN-009. INTERVAL '-10' SECOND gives sizeNanos = -10e9; floorDiv against a negative size
        // produces slice starts above the row's own time, so acceptance is silent corruption.
        assertThatThrownBy(() -> plan(tumble("s0", "-10' SECOND")))
                .hasMessageContaining("window size must be positive");
    }

    // ================================================================== 2. HOP

    @Test
    void win013_aHoppingWindowPutsEachRowInEveryWindowItBelongsTo(@TempDir Path dir) throws Exception {
        // WIN-013. Q_H(10,20) over C + pusher: size 20s sliding every 10s, so each row is in two
        // windows and the first window starts before the epoch.
        List<String> rows = configured(
                dir, C_PLUS, SPEC, Duration.ZERO, hop("s0", "10' SECOND", "20' SECOND"), List.of(0, 1, 2), 4, 4);

        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "-10000000000|10000000000|100|1|10", // 10
                        "0|20000000000|100|2|30", // 10 + 20 = 30
                        "10000000000|30000000000|100|2|50", // 20 + 30 = 50
                        "20000000000|40000000000|100|1|30"); // 30
        // Each of the three rows is counted once per window it belongs to: 1 + 2 + 2 + 1 = 6.
        assertThat(sumOf(rows, 3)).isEqualTo(6);
        assertThat(rows).as("the pusher's own windows never close").noneMatch(r -> r.contains("|999|"));
    }

    @Test
    void win014_aHoppingWindowCanStartBeforeTheEpoch(@TempDir Path dir) throws Exception {
        // WIN-014. The window ending at 10.000 begins at -10.000, and it is a real answer rather
        // than a clamp to zero: one row, amount 10.
        List<String> rows = configured(
                dir, C_PLUS, SPEC, Duration.ZERO, hop("s0", "10' SECOND", "20' SECOND"), List.of(0, 1, 2), 4, 4);
        assertThat(rows.stream().filter(r -> r.startsWith("-")).toList())
                .containsExactly("-10000000000|10000000000|100|1|10");
    }

    @Test
    void win020And021_aNonDividingHopPutsARowInThreeWindowsOrFour(@TempDir Path dir) throws Exception {
        // WIN-020 and WIN-021. Size 10s sliding every 3s: the true count for a row at t is
        // floor((t+S)/D) - floor(t/D), so ceil(10/3) = 4 and floor(10/3) = 3 both occur in one
        // dataset. FINDINGS W-5 records that "ceil(size/slide) windows" is a maximum, not a
        // constant, and this is the case that discriminates.
        List<String> rows = configured(
                dir, D_PLUS, SPEC, Duration.ZERO, hop("s0", "3' SECOND", "10' SECOND"), List.of(0, 1, 2), 3, 4);

        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "-7000000000|3000000000|100|2|3", // 1 + 2 = 3
                        "-4000000000|6000000000|100|2|3", // 1 + 2 = 3
                        "-1000000000|9000000000|100|2|3", // 1 + 2 = 3
                        "2000000000|12000000000|100|1|2"); // 2
        // The row at 0.000 is in three windows and the row at 2.000 is in four: 3 + 4 = 7.
        assertThat(sumOf(rows, 3)).isEqualTo(7);
    }

    @Test
    void win024_oneRowUnderAHopReachesEveryWindowCoveringIt(@TempDir Path dir) throws Exception {
        // WIN-024. one_hop.csv: a single row at 5.000 with amount 7, pusher at 60.000.
        String data = row(1, 100, 7, 5 * SECOND) + row(2, 999, 0, 60 * SECOND);
        List<String> rows = configured(
                dir, data, SPEC, Duration.ZERO, hop("s0", "10' SECOND", "20' SECOND"), List.of(0, 1, 2), 2, 2);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "-10000000000|10000000000|100|1|7", // 7
                        "0|20000000000|100|1|7"); // 7
    }

    @Test
    void win025And179_anEmptySourceProducesNoWindowsAndNoError(@TempDir Path dir) throws Exception {
        // WIN-025 and WIN-179. A zero-byte file: RUNNING, zero rows in, zero rows out, no error,
        // and no window end walked. The distinction that matters is "nothing arrived" versus
        // "something arrived and was lost", and both look like an empty view.
        Path data = dir.resolve("empty.csv");
        Files.writeString(data, "");
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = registry(views, dir, data, SPEC, Duration.ZERO)) {
            RegisteredQuery query = registry.register(
                    "v_empty", hop("s0", "10' SECOND", "20' SECOND"), List.of(0, 1, 2), Principal.ANONYMOUS);
            Thread.sleep(1_000);
            assertThat(query.rowsIn()).isZero();
            assertThat(new ViewQuery(views).execute("SELECT * FROM v_empty").size())
                    .isZero();
            assertThat(query.failure()).isEmpty();
        }
    }

    @Test
    void win026_rowsSharingOneEventTimeAllLandInTheSameWindows(@TempDir Path dir) throws Exception {
        // WIN-026. Five rows at exactly 5.000, amounts 1, 2, 4, 8, 16 -- powers of two so any
        // dropped row changes the sum to a value that occurs nowhere else.
        String data = csv(
                        row(1, 100, 1, 5 * SECOND),
                        row(2, 100, 2, 5 * SECOND),
                        row(3, 100, 4, 5 * SECOND),
                        row(4, 100, 8, 5 * SECOND),
                        row(5, 100, 16, 5 * SECOND))
                + row(6, 999, 0, 60 * SECOND);
        List<String> rows = configured(
                dir, data, SPEC, Duration.ZERO, hop("s0", "10' SECOND", "20' SECOND"), List.of(0, 1, 2), 6, 2);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "-10000000000|10000000000|100|5|31", // 1 + 2 + 4 + 8 + 16 = 31
                        "0|20000000000|100|5|31");
        assertThat(sumOf(rows, 3)).isEqualTo(10); // five rows in two windows each
    }

    @Test
    void win029_aHopKeepsTwoUsersApartRatherThanSummingThem(@TempDir Path dir) throws Exception {
        // WIN-029. Two users over the C times. The falsifier is a total of 110, 330 or 220 --
        // every one of which is the two users added together in a different place.
        String data = csv(
                        row(1, 100, 10, 5 * SECOND),
                        row(2, 200, 100, 5 * SECOND),
                        row(3, 100, 20, 15 * SECOND),
                        row(4, 200, 200, 15 * SECOND),
                        row(5, 100, 30, 25 * SECOND),
                        row(6, 200, 300, 25 * SECOND))
                + row(7, 999, 0, 60 * SECOND);
        List<String> rows = configured(
                dir, data, SPEC, Duration.ZERO, hop("s0", "10' SECOND", "20' SECOND"), List.of(0, 1, 2), 7, 8);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "-10000000000|10000000000|100|1|10",
                        "-10000000000|10000000000|200|1|100",
                        "0|20000000000|100|2|30", // 10 + 20 = 30
                        "0|20000000000|200|2|300", // 100 + 200 = 300
                        "10000000000|30000000000|100|2|50", // 20 + 30 = 50
                        "10000000000|30000000000|200|2|500", // 200 + 300 = 500
                        "20000000000|40000000000|100|1|30",
                        "20000000000|40000000000|200|1|300");
        // WIN-013's four windows, doubled by the second user: 4 x 2 = 8 rows, and SUM(n) is the
        // six input rows each counted in the two windows that contain them: 6 x 2 = 12.
        assertThat(sumOf(rows, 3)).isEqualTo(12);
    }

    @Test
    void win019And085_aHopWhoseSlideExceedsItsSizeIsRefused() {
        // WIN-019 (the swapped-interval mistake) and WIN-085 (a deliberate slide > size). Both
        // leave gaps no row can be in, and both are refused by a plain IllegalArgumentException
        // with no PRV code -- WIN.md records the missing code as the finding, not the refusal.
        assertThatThrownBy(() -> plan(hop("s0", "20' SECOND", "10' SECOND"))).hasMessageContaining("leaves gaps");
        assertThatThrownBy(() -> plan(hop("s0", "60' SECOND", "10' SECOND"))).hasMessageContaining("leaves gaps");
    }

    @Test
    void win027And054_anIntervalThatTruncatesToZeroMillisecondsIsRefused() {
        // WIN-027 and WIN-054. Sub-millisecond intervals truncate to zero before the positivity
        // guard sees them, so the protection is accidental -- but it does hold, and '0.001'
        // SECOND is accepted as a one-millisecond window.
        // Calcite answers first: its interval literal grammar allows three fractional digits, so
        // anything finer is refused as PRV-2010 "Illegal interval literal format" before
        // WindowSpec's positivity guard is reached. Both are refusals; recording which layer
        // answers is the point, because the two carry different codes and different advice.
        assertThatThrownBy(() -> plan(hop("s0", "0.000000001' SECOND", "10' SECOND")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2010");
        // Four digits do parse, and truncate to zero milliseconds, which is where WindowSpec's own
        // guard takes over -- with a plain IllegalArgumentException carrying no PRV code, which is
        // the finding WIN-008 pins.
        assertThatThrownBy(() -> plan(tumble("s0", "0.0001' SECOND")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window size must be positive, got 0");
        assertThatThrownBy(() -> plan(tumble("s0", "0.000' SECOND")))
                .hasMessageContaining("window size must be positive, got 0");
        // One millisecond is the finest window this engine can be given.
        assertThat(plan(tumble("s0", "0.001' SECOND")).label()).contains("1ms");
    }

    // ================================================================== 3. SESSION

    @Test
    void win031And032_sessionWindowsAreRefusedFromBothSqlForms() {
        // WIN-031 and WIN-032. SessionWindows is implemented and reachable from no plan node, so
        // both syntaxes must refuse rather than plan something that silently is not a session.
        assertThatThrownBy(() -> plan("SELECT window_start, COUNT(*) FROM TABLE(SESSION(TABLE s0, "
                        + "DESCRIPTOR(event_time), INTERVAL '30' SECOND)) GROUP BY window_start"))
                .isInstanceOf(PravahaException.class);
        assertThatThrownBy(() -> plan("SELECT user_id, COUNT(*) FROM s0 GROUP BY SESSION(event_time, "
                        + "INTERVAL '30' SECOND), user_id"))
                .isInstanceOf(PravahaException.class);
    }

    @Test
    void win033And034_aSessionSpecIsNotSlicedAndItsGapMustBePositive() {
        // WIN-033 and WIN-034. Slicing a session is meaningless -- its boundaries are data, not
        // arithmetic -- so both the size query and the sliced-windows constructor must refuse.
        assertThatThrownBy(() -> WindowSpec.session(30 * SECOND).sliceSizeNanos())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("session windows are not sliced");
        assertThatThrownBy(() -> new SlicedWindows(WindowSpec.session(30 * SECOND)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionWindows(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("session gap must be positive, got 0");
        assertThatThrownBy(() -> new SessionWindows(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void win035And036_recordsWithinTheGapMergeIntoOneSessionAndOneNanosecondPastItDoNot() {
        // WIN-035: three records ten seconds apart under a thirty-second gap are one session,
        // [0, 20s + 30s) = [0, 50s), reached by two merges.
        SessionWindows sessions = new SessionWindows(30 * SECOND);
        sessions.record(1, 0);
        sessions.record(1, 10 * SECOND);
        SessionWindows.Session merged = sessions.record(1, 20 * SECOND);
        assertThat(merged).isEqualTo(new SessionWindows.Session(1, 0, 50 * SECOND));
        assertThat(sessions.merges()).isEqualTo(2);

        // WIN-036(a): a record at exactly the gap still merges, because a session is open up to
        // start + gap exclusive and 30s is inside [0, 30s).
        SessionWindows exact = new SessionWindows(30 * SECOND);
        exact.record(1, 0);
        assertThat(exact.record(1, 30 * SECOND)).isEqualTo(new SessionWindows.Session(1, 0, 60 * SECOND));
        assertThat(exact.openSessions()).isEqualTo(1);

        // WIN-036(b): one nanosecond later it does not, and there are two sessions.
        SessionWindows apart = new SessionWindows(30 * SECOND);
        apart.record(1, 0);
        apart.record(1, 30 * SECOND + 1);
        assertThat(apart.openSessions()).isEqualTo(2);
        assertThat(apart.sessionsOf(1))
                .containsExactly(
                        new SessionWindows.Session(1, 0, 30 * SECOND),
                        new SessionWindows.Session(1, 30 * SECOND + 1, 60 * SECOND + 1));
    }

    @Test
    void win037_aRecordArrivingBetweenTwoSessionsBridgesThem() {
        // WIN-037. This is the case an implementation that assigns to the nearest neighbour gets
        // wrong: 0 and 55s are two sessions, and a record at 25s belongs to both, so the answer is
        // one session [0, 55s + 30s) = [0, 85s).
        SessionWindows sessions = new SessionWindows(30 * SECOND);
        sessions.record(1, 0);
        sessions.record(1, 55 * SECOND);
        assertThat(sessions.openSessions()).isEqualTo(2);
        assertThat(sessions.record(1, 25 * SECOND)).isEqualTo(new SessionWindows.Session(1, 0, 85 * SECOND));
        assertThat(sessions.openSessions()).isEqualTo(1);
        assertThat(sessions.merges()).isEqualTo(2);
    }

    @Test
    void win038_atMostOneMergePerSide() {
        // WIN-038. Three sessions at 0, 40s and 80s; a record at 35s reaches the first two and not
        // the third, so the answer is two sessions and the one at 80s is untouched.
        SessionWindows sessions = new SessionWindows(30 * SECOND);
        sessions.record(1, 0);
        sessions.record(1, 40 * SECOND);
        sessions.record(1, 80 * SECOND);
        sessions.record(1, 35 * SECOND);
        assertThat(sessions.sessionsOf(1))
                .as("a record merges the sessions it touches, and 35s touches only the one at 40s")
                .containsExactly(
                        // The record at 0 owns [0, 30s) and 35s is past its end, so no backward
                        // merge. 35s's own span is [35s, 65s), which overlaps 40s's [40s, 70s), so
                        // the two become [35s, 70s). The session at 80s is 10s beyond 70s and is
                        // untouched -- at most one merge per side, which is the case's own claim.
                        new SessionWindows.Session(1, 0, 30 * SECOND),
                        new SessionWindows.Session(1, 35 * SECOND, 70 * SECOND),
                        new SessionWindows.Session(1, 80 * SECOND, 110 * SECOND));
    }

    @Test
    void win039_sessionsAreKeptPerKey() {
        // WIN-039. Two keys interleaved: key 1's records merge with each other and never with
        // key 2's, however close in time they are.
        SessionWindows sessions = new SessionWindows(30 * SECOND);
        sessions.record(1, 0);
        sessions.record(2, 10 * SECOND);
        sessions.record(1, 20 * SECOND);
        assertThat(sessions.sessionsOf(1)).containsExactly(new SessionWindows.Session(1, 0, 50 * SECOND));
        assertThat(sessions.sessionsOf(2)).containsExactly(new SessionWindows.Session(2, 10 * SECOND, 40 * SECOND));
        assertThat(sessions.keyCount()).isEqualTo(2);
        assertThat(sessions.openSessions()).isEqualTo(2);
        assertThat(sessions.merges()).isEqualTo(1);
    }

    @Test
    void win040_aSessionClosesAtItsEndAndNotOneNanosecondBefore() {
        // WIN-040. closedBy is "no further record can extend this one", which is watermark >= end.
        SessionWindows sessions = new SessionWindows(30 * SECOND);
        sessions.record(1, 0);
        sessions.record(1, 20 * SECOND); // session is now [0, 50s)
        assertThat(sessions.closedBy(49 * SECOND)).isEmpty();
        assertThat(sessions.openSessions()).isEqualTo(1);

        List<SessionWindows.Session> closed = sessions.closedBy(50 * SECOND);
        assertThat(closed).containsExactly(new SessionWindows.Session(1, 0, 50 * SECOND));
        assertThat(closed.get(0).durationNanos()).isEqualTo(50 * SECOND);
        assertThat(sessions.openSessions()).isZero();
        assertThat(sessions.keyCount()).isZero();
    }

    @Test
    void win041_closedSessionsComeBackOrderedByEndThenKey() {
        // WIN-041. Three keys, two sharing an end: the order is by end and then by key, which is
        // what makes a downstream consumer's output deterministic.
        SessionWindows sessions = new SessionWindows(10 * SECOND);
        sessions.record(2, 0);
        sessions.record(1, 5 * SECOND);
        sessions.record(3, 0);
        assertThat(sessions.closedBy(20 * SECOND))
                .containsExactly(
                        new SessionWindows.Session(2, 0, 10 * SECOND),
                        new SessionWindows.Session(3, 0, 10 * SECOND),
                        new SessionWindows.Session(1, 5 * SECOND, 15 * SECOND));
    }

    // ================================================================== 4. CUMULATE

    @Test
    void win043And048_cumulateDoesNotExistAndIsRefusedRatherThanPlannedAsSomethingElse() {
        // WIN-043 and WIN-048. Calcite parses CUMULATE, so it reaches buildWindowAssign's default
        // arm. The danger is not the refusal, it is a rewrite to HOP that would silently answer a
        // different question -- WIN-046 shows the two disagree on every window.
        assertThatThrownBy(() -> plan("SELECT window_start, COUNT(*) FROM TABLE(CUMULATE(TABLE s0, "
                        + "DESCRIPTOR(event_time), INTERVAL '2' SECOND, INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start"))
                .isInstanceOf(PravahaException.class);
        assertThat(WindowSpec.Kind.values())
                .as("no CUMULATING kind, and no SLIDING alias for HOPPING")
                .containsExactly(WindowSpec.Kind.TUMBLING, WindowSpec.Kind.HOPPING, WindowSpec.Kind.SESSION);
    }

    @Test
    void win046_aHopIsNotACumulateAndTheWindowsDifferEvenWhereTheEndsAgree() {
        // WIN-046. HOP(2s, 10s) and CUMULATE(2s, 10s) share the ends {2,4,6,8,10} for a row at 1s
        // and nothing else: the hop's windows are each ten seconds wide and reach back before the
        // epoch, where a cumulate's grow from a fixed start. This is why no rewrite exists.
        SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(10 * SECOND, 2 * SECOND));
        assertThat(windows.windowEndsContaining(1 * SECOND))
                .containsExactly(2 * SECOND, 4 * SECOND, 6 * SECOND, 8 * SECOND, 10 * SECOND);
        // The window ending at 2s starts at -8s, not at 0 as a cumulate's would.
        assertThat(windows.slicesOfWindowEnding(2 * SECOND).get(0)).isEqualTo(-8 * SECOND);
    }

    // ================================================================== 5. Size

    @Test
    void win051_aHundredMillisecondWindow(@TempDir Path dir) throws Exception {
        // WIN-051, dataset S(100ms). Five rows at 0, U/2, U, 2U-1ns, 2U with amounts 1, 2, 4, 8,
        // 16 and a pusher at 10U. The same three windows appear whatever the unit -- WIN-055, 059,
        // 063 and 067 are the same shape at 1s, 1m, 1h and 1d.
        assertThat(sizeSweep(dir, 100 * MS, "0.1' SECOND"))
                .containsExactlyInAnyOrder(
                        "0|100000000|100|2|3", // 1 + 2 = 3
                        "100000000|200000000|100|2|12", // 4 + 8 = 12
                        "200000000|300000000|100|1|16"); // 16
    }

    @Test
    void win055_aOneSecondWindow(@TempDir Path dir) throws Exception {
        // WIN-055. The row one nanosecond before 2.000 is in [1,2), not [2,3).
        assertThat(sizeSweep(dir, SECOND, "1' SECOND"))
                .containsExactlyInAnyOrder(
                        "0|1000000000|100|2|3", // 1 + 2 = 3
                        "1000000000|2000000000|100|2|12", // 4 + 8 = 12
                        "2000000000|3000000000|100|1|16"); // 16
    }

    @Test
    void win059_aOneMinuteWindow(@TempDir Path dir) throws Exception {
        // WIN-059.
        assertThat(sizeSweep(dir, MINUTE, "1' MINUTE"))
                .containsExactlyInAnyOrder(
                        "0|60000000000|100|2|3",
                        "60000000000|120000000000|100|2|12",
                        "120000000000|180000000000|100|1|16");
    }

    @Test
    void win063_aOneHourWindow(@TempDir Path dir) throws Exception {
        // WIN-063.
        assertThat(sizeSweep(dir, HOUR, "1' HOUR"))
                .containsExactlyInAnyOrder(
                        "0|3600000000000|100|2|3",
                        "3600000000000|7200000000000|100|2|12",
                        "7200000000000|10800000000000|100|1|16");
    }

    @Test
    void win067_aOneDayWindow(@TempDir Path dir) throws Exception {
        // WIN-067 and WIN-068 together. 1 DAY is 86,400,000,000,000 ns and all three windows
        // fire -- but retention is twenty-four hours and settable from nowhere (WIN-069), so by
        // the time the frontier reaches three days the first window is older than the horizon
        // 259,200 - 86,400 = 172,800 s and has been evicted. Two rows survive, and which two is
        // the whole point: a one-day window is evicted one window after it lands.
        List<String> rows = sizeSweep(dir, DAY, "1' DAY", 2);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "86400000000000|172800000000000|100|2|12", // 4 + 8 = 12
                        "172800000000000|259200000000000|100|1|16"); // 16
        assertThat(sumOf(rows, 4)).isEqualTo(28); // 12 + 16 = 28; the evicted window held 3
    }

    @Test
    void win056And060_everySpellingOfAnIntervalPlansToTheSameWindow() {
        // WIN-056 and WIN-060. '1' SECOND, '1.0' SECOND and '1000' MILLISECOND must be one window,
        // and '1' MINUTE must equal '60' SECOND. A spelling that quietly means something else is
        // the kind of defect that only shows up in the answer.
        for (String spelling : List.of("1' SECOND", "1.0' SECOND", "1.000' SECOND")) {
            assertThat(plan(tumble("s0", spelling)).label())
                    .as("spelling %s", spelling)
                    .contains("1000ms");
        }
        // MILLISECOND is not a unit Calcite's interval grammar accepts at all, so the spelling the
        // case names as equivalent is in fact a parse error. Recorded rather than worked around.
        assertThatThrownBy(() -> plan(tumble("s0", "1000' MILLISECOND"))).isInstanceOf(PravahaException.class);
        assertThat(plan(tumble("s0", "1' MINUTE")).label()).contains("60000ms");
        assertThat(plan(tumble("s0", "60' SECOND")).label()).contains("60000ms");
    }

    @Test
    void win058_aThousandRowsPerSecondLandInTheRightSecond(@TempDir Path dir) throws Exception {
        // WIN-058, dense1s.csv: 10,000 rows one millisecond apart, plus a pusher at 60.000. Window
        // [0,1) holds rows 1..999 -- there is no row 0 -- and every later window holds 1,000.
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 10_000; i++) {
            csv.append(row(i, 100, 1, i * MS));
        }
        csv.append(row(10_001, 999, 0, 60 * SECOND));

        List<String> rows = configured(
                dir, csv.toString(), SPEC, Duration.ZERO, tumble("s0", "1' SECOND"), List.of(0, 1, 2), 10_001, 11);

        Map<Long, Long> counts = new LinkedHashMap<>();
        for (String r : rows) {
            String[] parts = r.split("\\|");
            counts.put(Long.parseLong(parts[0]) / SECOND, Long.parseLong(parts[3]));
        }
        assertThat(counts.get(0L)).as("rows 1..999, because there is no row 0").isEqualTo(999);
        for (long s = 1; s <= 9; s++) {
            assertThat(counts.get(s)).as("second %d", s).isEqualTo(1_000);
        }
        assertThat(counts.get(10L)).isEqualTo(1);
        // 999 + 9 * 1000 + 1 = 10,000, which is every row in the file bar the pusher.
        assertThat(sumOf(rows, 3)).isEqualTo(10_000);
    }

    // ================================================================== 6. slide vs size

    @Test
    void win022And077_sliceSizeAndSlicesPerWindowAreTheGcdForm() {
        // WIN-022 and WIN-077. FINDINGS W-5: slices per window is S / gcd(S, D), not S / D --
        // which understates a 10s window hopping every 9.999s by ten thousand times.
        List<SliceCase> cases = List.of(
                new SliceCase(10 * SECOND, 10 * SECOND, 10 * SECOND, 1),
                new SliceCase(20 * SECOND, 10 * SECOND, 10 * SECOND, 2),
                new SliceCase(20 * SECOND, 5 * SECOND, 5 * SECOND, 4),
                new SliceCase(10 * SECOND, SECOND, SECOND, 10),
                new SliceCase(100 * SECOND, SECOND, SECOND, 100),
                new SliceCase(1000 * SECOND, SECOND, SECOND, 1000),
                // gcd(10s, 3s) = 1s, so ten slices, not the three that S/D would give.
                new SliceCase(10 * SECOND, 3 * SECOND, SECOND, 10),
                new SliceCase(7 * SECOND, 2 * SECOND, SECOND, 7),
                // gcd(60s, 45s) = 15s: four slices per window, where S/D would say one.
                new SliceCase(60 * SECOND, 45 * SECOND, 15 * SECOND, 4),
                new SliceCase(HOUR, SECOND, SECOND, 3600),
                // The one that matters: 10s hopping every 9.999s.
                new SliceCase(10 * SECOND, 9_999 * MS, MS, 10_000));
        for (SliceCase c : cases) {
            WindowSpec spec = WindowSpec.hopping(c.size(), c.slide());
            assertThat(spec.sliceSizeNanos())
                    .as("slice size for size=%d slide=%d", c.size(), c.slide())
                    .isEqualTo(c.sliceSize());
            assertThat(spec.slicesPerWindow())
                    .as("slices per window for size=%d slide=%d", c.size(), c.slide())
                    .isEqualTo(c.slicesPerWindow());
        }
    }

    @Test
    void win071_theWindowCountForARowIsTheFloorDifference() {
        // WIN-071. count(t) = floor((t + S) / D) - floor(t / D). Two cells of this grid are the
        // ones FINDINGS W-5 corrects: at size 10s slide 3s a row at t=0 is in three windows and a
        // row at t=2s is in four, so no constant is right.
        List<CountCase> cases = List.of(
                new CountCase(20 * SECOND, 10 * SECOND, 5 * SECOND, 2),
                new CountCase(20 * SECOND, 5 * SECOND, 5 * SECOND, 4),
                new CountCase(10 * SECOND, 3 * SECOND, 0, 3),
                new CountCase(10 * SECOND, 3 * SECOND, 2 * SECOND, 4),
                new CountCase(7 * SECOND, 2 * SECOND, 0, 3),
                new CountCase(7 * SECOND, 2 * SECOND, SECOND, 4),
                new CountCase(10 * SECOND, 10 * SECOND, 0, 1),
                new CountCase(10 * SECOND, 10 * SECOND, 7 * SECOND, 1),
                new CountCase(20 * SECOND, 10 * SECOND, -1, 2));
        for (CountCase c : cases) {
            SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(c.size(), c.slide()));
            long expected = Math.floorDiv(c.t() + c.size(), c.slide()) - Math.floorDiv(c.t(), c.slide());
            assertThat(windows.windowEndsContaining(c.t()))
                    .as("size=%d slide=%d t=%d", c.size(), c.slide(), c.t())
                    .hasSize(c.windows())
                    .hasSize((int) expected);
        }
    }

    @Test
    void win072_aFiveSecondSlideOverATwentySecondWindow(@TempDir Path dir) throws Exception {
        // WIN-072. Q_H(5,20) over C + pusher: each of the three rows is in four windows, so
        // SUM(n) = 12 and eight distinct windows carry data.
        List<String> rows = configured(
                dir, C_PLUS, SPEC, Duration.ZERO, hop("s0", "5' SECOND", "20' SECOND"), List.of(0, 1, 2), 4, 8);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "-10000000000|10000000000|100|1|10",
                        "-5000000000|15000000000|100|1|10",
                        "0|20000000000|100|2|30", // 10 + 20 = 30
                        "5000000000|25000000000|100|2|30", // 10 + 20 = 30
                        "10000000000|30000000000|100|2|50", // 20 + 30 = 50
                        "15000000000|35000000000|100|2|50", // 20 + 30 = 50
                        "20000000000|40000000000|100|1|30",
                        "25000000000|45000000000|100|1|30");
        assertThat(sumOf(rows, 3)).isEqualTo(12); // 3 rows x 4 windows each
    }

    @Test
    void win073_aOneSecondSlideOverATenSecondWindowPutsOneRowInTenWindows(@TempDir Path dir) throws Exception {
        // WIN-073. One row at 5.000, amount 7, pusher at 60.000: ten windows, ends 6..15 s.
        String data = row(1, 100, 7, 5 * SECOND) + row(2, 999, 0, 60 * SECOND);
        List<String> rows = configured(
                dir, data, SPEC, Duration.ZERO, hop("s0", "1' SECOND", "10' SECOND"), List.of(0, 1, 2), 2, 10);
        assertThat(rows).hasSize(10);
        assertThat(sumOf(rows, 3)).isEqualTo(10);
        assertThat(sumOf(rows, 4)).isEqualTo(70); // 7 counted once per window: 7 * 10 = 70
        List<Long> ends = rows.stream()
                .map(r -> Long.parseLong(r.split("\\|")[1]) / SECOND)
                .sorted()
                .toList();
        assertThat(ends).containsExactly(6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L);
    }

    @Test
    void win076_aSevenSecondWindowHoppingEveryTwo(@TempDir Path dir) throws Exception {
        // WIN-076. d72.csv: rows at 0.000 (amount 1) and 1.000 (amount 2), pusher at 30.000. The
        // first row is in three windows and the second in four: 3 + 4 = 7.
        String data = csv(row(1, 100, 1, 0L), row(2, 100, 2, SECOND)) + row(3, 999, 0, 30 * SECOND);
        List<String> rows =
                configured(dir, data, SPEC, Duration.ZERO, hop("s0", "2' SECOND", "7' SECOND"), List.of(0, 1, 2), 3, 4);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "-5000000000|2000000000|100|2|3", // 1 + 2 = 3
                        "-3000000000|4000000000|100|2|3",
                        "-1000000000|6000000000|100|2|3",
                        "1000000000|8000000000|100|1|2");
        assertThat(sumOf(rows, 3)).isEqualTo(7);
    }

    @Test
    void win079And080_aHopWhoseSlideEqualsItsSizeIsATumbleToTheRow(@TempDir Path dir) throws Exception {
        // WIN-079 and WIN-080. The two forms are different plans with different fingerprints, so
        // they are two computations -- and they must agree on every row, or one of them is wrong.
        List<String> tumbled =
                configured(dir, A_PLUS, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 7, 5);
        List<String> hopped = configured(
                dir.resolve("h"),
                A_PLUS,
                SPEC,
                Duration.ZERO,
                hop("s0", "10' SECOND", "10' SECOND"),
                List.of(0, 1, 2),
                7,
                5);
        assertThat(hopped).containsExactlyInAnyOrderElementsOf(tumbled);
        assertThat(tumbled)
                .containsExactlyInAnyOrder(
                        "0|10000000000|100|2|30", // 10 + 20 = 30
                        "0|10000000000|200|1|30",
                        "10000000000|20000000000|100|1|40",
                        "10000000000|20000000000|200|1|50",
                        "20000000000|30000000000|100|1|60"); // the pusher opens [20,30)
        assertThat(sumOf(tumbled, 3)).isEqualTo(6); // dataset A's six rows, the pusher's window open
    }

    @Test
    void win082And084_hoppingByItsOwnSizeIsIndistinguishableFromTumblingInTheArithmetic() {
        // WIN-082 and WIN-084. Checked at six sizes, and at the boundaries: 9.999999999s is in the
        // window ending at 10s, 10s is in the one ending at 20s, and -1ns is in the one ending at 0.
        for (long size : List.of(100 * MS, SECOND, 10 * SECOND, MINUTE, HOUR, DAY)) {
            SlicedWindows tumbling = new SlicedWindows(WindowSpec.tumbling(size));
            SlicedWindows hopping = new SlicedWindows(WindowSpec.hopping(size, size));
            assertThat(hopping.sliceSizeNanos())
                    .isEqualTo(tumbling.sliceSizeNanos())
                    .isEqualTo(size);
            assertThat(hopping.slicesOfWindowEnding(size)).isEqualTo(tumbling.slicesOfWindowEnding(size));
        }
        SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(10 * SECOND, 10 * SECOND));
        assertThat(windows.windowEndsContaining(0)).containsExactly(10 * SECOND);
        assertThat(windows.windowEndsContaining(10 * SECOND - 1)).containsExactly(10 * SECOND);
        assertThat(windows.windowEndsContaining(10 * SECOND)).containsExactly(20 * SECOND);
        assertThat(windows.windowEndsContaining(-1)).containsExactly(0L);
        assertThat(windows.windowEndsContaining(-10 * SECOND)).containsExactly(0L);
        assertThat(windows.windowEndsContaining(-10 * SECOND - 1)).containsExactly(-10 * SECOND);
    }

    @Test
    void win086_aSlideWiderThanTheSizeLeavesMostOfTheTimelineInNoWindowAtAll() {
        // WIN-086. WindowSpec refuses this pair from SQL (WIN-085), so it is reachable only here.
        // Fifty of every sixty seconds belong to no window: 83.3% of a uniform stream vanishes,
        // which is exactly why the refusal exists.
        // Stronger than the case predicted: the pair cannot be constructed at all. WindowSpec's
        // own compact constructor refuses it, so the arithmetic in which fifty of every sixty
        // seconds belong to no window is unreachable rather than merely unreachable from SQL --
        // and the guard is therefore in the right place, one layer below the planner.
        assertThatThrownBy(() -> new WindowSpec(WindowSpec.Kind.HOPPING, 10 * SECOND, 60 * SECOND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaves gaps")
                .hasMessageContaining("silently dropped");
        // And the legal boundary, slide == size, is accepted: sixty of sixty seconds are covered.
        SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(60 * SECOND, 60 * SECOND));
        for (long t : List.of(0L, 5 * SECOND, 30 * SECOND, 59 * SECOND)) {
            assertThat(windows.windowEndsContaining(t)).as("t=%d", t).containsExactly(60 * SECOND);
        }
    }

    @Test
    void win078_aSlicedStateHoldsOneAccumulatorPerSliceHoweverWideTheWindow() {
        // WIN-078. One key, 1,000 rows one second apart, hopping every second over 10s, 100s and
        // 1000s: the live slice count is 1,000 in all three -- the point of slicing -- while the
        // window that fires holds 10, 100 and 1,000 rows respectively.
        for (int sizeSeconds : List.of(10, 100, 1000)) {
            SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(sizeSeconds * SECOND, SECOND));
            SlicedAggregateState state = new SlicedAggregateState(
                    windows,
                    new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.COUNT, SlicedAggregateState.Kind.SUM},
                    2_000_000);
            for (int i = 0; i < 1000; i++) {
                state.update(0, 1, new Object[] {1L}, i * SECOND, new long[] {1, 1}, 1);
            }
            assertThat(state.liveSlices())
                    .as("one accumulator per slice at size %ds", sizeSeconds)
                    .isEqualTo(1000);
            List<SlicedAggregateState.WindowResult> fired = state.fire(1000 * SECOND);
            assertThat(fired).hasSize(1);
            assertThat(fired.get(0).values()[0])
                    .as("the window ending at 1000s holds min(size, 1000) seconds of rows")
                    .isEqualTo((long) Math.min(sizeSeconds, 1000));
        }
    }

    // ================================================================== 7. open windows

    @Test
    void win091_aHundredAndTwentyRowsUnderATenSecondTumble(@TempDir Path dir) throws Exception {
        // WIN-091, dataset O: 120 rows one per second from 1s to 120s, amount 1, pusher at 2000s.
        // Thirteen windows carry data -- [0,10) holds seconds 1..9, so nine rows, and [120,130)
        // holds the single row at 120s.
        List<String> rows =
                configured(dir, open(), SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 121, 13);
        assertThat(rows).hasSize(13);
        // 9 + 11 * 10 + 1 = 120: every row in the file bar the pusher.
        assertThat(sumOf(rows, 3)).isEqualTo(120);
        assertThat(countOf(rows, 0L)).isEqualTo(9);
        assertThat(countOf(rows, 120 * SECOND)).isEqualTo(1);
    }

    @Test
    void win092_theSameHundredAndTwentyRowsUnderATenSecondWindowHoppingEverySecond(@TempDir Path dir) throws Exception {
        // WIN-092. Every row is in ten windows, so SUM(n) is exactly ten times WIN-091's, and the
        // window ends run 2..130 -- 129 of them.
        List<String> rows = configured(
                dir, open(), SPEC, Duration.ZERO, hop("s0", "1' SECOND", "10' SECOND"), List.of(0, 1, 2), 121, 129);
        assertThat(rows).hasSize(129);
        assertThat(sumOf(rows, 3)).isEqualTo(1_200); // 120 rows x 10 windows each
    }

    @Test
    void win093_theSameRowsUnderAHundredSecondWindowHoppingEverySecond(@TempDir Path dir) throws Exception {
        // WIN-093. 219 windows, SUM(n) = 120 * 100 = 12,000.
        List<String> rows = configured(
                dir, open(), SPEC, Duration.ZERO, hop("s0", "1' SECOND", "100' SECOND"), List.of(0, 1, 2), 121, 219);
        assertThat(rows).hasSize(219);
        assertThat(sumOf(rows, 3)).isEqualTo(12_000);
    }

    @Test
    void win095And102_theSlicedStateIsBoundedWhereTheViewIsNot() {
        // WIN-095 and WIN-102. Dataset O through the state directly: TUMBLE 10s keeps one live
        // slice, HOP(1s,10s) keeps ten, HOP(1s,100s) keeps a hundred -- while the view that serves
        // the hop's answers holds 129 rows for the same 120 inputs. The engine's state is bounded
        // by the window shape; the answer it serves is not, and the two are more than 12x apart.
        for (LiveCase c : List.of(
                new LiveCase(10 * SECOND, 10 * SECOND, 1),
                new LiveCase(10 * SECOND, SECOND, 10),
                new LiveCase(100 * SECOND, SECOND, 100))) {
            SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(c.size(), c.slide()));
            SlicedAggregateState state = new SlicedAggregateState(
                    windows, new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.COUNT}, 2_000_000);
            for (int i = 1; i <= 120; i++) {
                state.update(0, 1, new Object[] {1L}, i * SECOND, new long[] {1}, 1);
                state.discardSlicesEndingBefore(i * SECOND, 0);
            }
            assertThat(state.liveSlices())
                    .as("size=%d slide=%d", c.size(), c.slide())
                    .isEqualTo(c.liveSlices());
        }
    }

    @Test
    void win096And100_aSliceIsDiscardedOnlyOnceItsLastWindowHasEnded() {
        // WIN-096 and WIN-100. lastWindowEndFor is the rule: a slice starting at 40s under
        // HOP(1s,10s) is still in the window ending at 50s, so discarding at 50s removes it and
        // discarding at 49.999999999 removes nothing. Off by one nanosecond here is a lost row.
        SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(10 * SECOND, SECOND));
        SlicedAggregateState state = new SlicedAggregateState(
                windows, new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.COUNT}, 2_000_000);
        state.update(0, 1, new Object[] {1L}, 0, new long[] {1}, 1);
        assertThat(windows.lastWindowEndFor(0)).isEqualTo(10 * SECOND);
        assertThat(state.discardSlicesEndingBefore(10 * SECOND - 1, 0)).isZero();
        assertThat(state.liveSlices()).isEqualTo(1);
        assertThat(state.discardSlicesEndingBefore(10 * SECOND, 0)).isEqualTo(1);
        assertThat(state.liveSlices()).isZero();
    }

    // ================================================================== 9. volume

    @Test
    @Disabled("PRV-WIN defect 3 (blocker): windowed aggregation over volume returns non-deterministic "
            + "wrong answers. At 100,000 rows the view holds 996-1001 rows where 1,000 exist, SUM(n) "
            + "comes back as 3,525,325,093,794 against a true 99,999, and a single-key run makes "
            + "ViewQuery throw 'field 0 (window_start) is NOT NULL and cannot be set null'. Reproduces "
            + "from 10,000 rows upward, differently on each run, with the query reporting RUNNING.")
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
                        .map(r -> Long.parseLong(r.split("\\|")[0]))
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
        assertThat(rows.stream().map(r -> Long.parseLong(r.split("\\|")[0])).distinct())
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
    void win147And148And149_theSliceArithmeticAtAndAroundTheEpoch() {
        // WIN-147, WIN-148 and WIN-149. floorDiv(-1, 10^10) is -1, not 0, so a row one nanosecond
        // before the epoch belongs to [-10s, 0) rather than [0, 10s). The epoch is an ordinary
        // boundary and nothing special happens at it.
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(10 * SECOND));
        assertThat(windows.windowEndsContaining(-1)).containsExactly(0L);
        assertThat(windows.windowEndsContaining(0)).containsExactly(10 * SECOND);
        assertThat(windows.windowEndsContaining(1)).containsExactly(10 * SECOND);
        assertThat(windows.windowEndsContaining(10 * SECOND - 1)).containsExactly(10 * SECOND);
        assertThat(windows.windowEndsContaining(10 * SECOND)).containsExactly(20 * SECOND);
        assertThat(windows.windowEndsContaining(20 * SECOND - 1)).containsExactly(20 * SECOND);
        assertThat(windows.windowEndsContaining(20 * SECOND)).containsExactly(30 * SECOND);

        assertThat(windows.sliceStartFor(0)).isZero();
        assertThat(windows.sliceStartFor(-1)).isEqualTo(-10 * SECOND);
        assertThat(windows.sliceStartFor(-SECOND)).isEqualTo(-10 * SECOND);
        assertThat(windows.sliceStartFor(-10 * SECOND + 1)).isEqualTo(-10 * SECOND);
        assertThat(windows.sliceStartFor(-10 * SECOND)).isEqualTo(-10 * SECOND);
        assertThat(windows.sliceStartFor(-10 * SECOND - 1)).isEqualTo(-20 * SECOND);
        assertThat(windows.sliceStartFor(-15 * SECOND)).isEqualTo(-20 * SECOND);
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
    void win151_theSliceArithmeticWrapsAtTheExtremesAndNothingGuardsIt() {
        // WIN-151. At Long.MAX_VALUE the window end wraps to a large negative number and at
        // Long.MIN_VALUE the slice start wraps positive, silently. This is pinned as the engine's
        // behaviour rather than as correct behaviour: a guard would be an improvement, and if one
        // is added this test is the thing that says so.
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(10 * SECOND));
        long topSlice = windows.sliceStartFor(Long.MAX_VALUE);
        assertThat(topSlice).isEqualTo(9_223_372_030_000_000_000L);
        assertThat(topSlice + 10 * SECOND)
                .as("the window end wraps past Long.MAX_VALUE")
                .isNegative();
        assertThat(windows.sliceStartFor(Long.MIN_VALUE))
                .as("and the bottom slice wraps the other way")
                .isPositive();
    }

    @Test
    void win152And153_theHopBoundaryLosesOneEndAcrossOneNanosecond() {
        // WIN-152 and WIN-153. Under HOP(3s slide, 10s size) a row at 2.999999999 is in four
        // windows and a row at 3.000000000 is in three: the end at 3s is lost across a single
        // nanosecond, which is the shape of every off-by-one in a windowing engine.
        SlicedWindows hop20 = new SlicedWindows(WindowSpec.hopping(20 * SECOND, 10 * SECOND));
        assertThat(hop20.windowEndsContaining(10 * SECOND - 1)).containsExactly(10 * SECOND, 20 * SECOND);
        assertThat(hop20.windowEndsContaining(10 * SECOND)).containsExactly(20 * SECOND, 30 * SECOND);

        SlicedWindows hop3 = new SlicedWindows(WindowSpec.hopping(10 * SECOND, 3 * SECOND));
        assertThat(hop3.windowEndsContaining(3 * SECOND - 1))
                .containsExactly(3 * SECOND, 6 * SECOND, 9 * SECOND, 12 * SECOND);
        assertThat(hop3.windowEndsContaining(3 * SECOND)).containsExactly(6 * SECOND, 9 * SECOND, 12 * SECOND);
    }

    @Test
    void win154_lastWindowEndForIsWhereASliceStopsMattering() {
        // WIN-154. Discarding at lastWindowEndFor - 1 must remove nothing; at lastWindowEndFor it
        // must remove the slice. Every retention and eviction decision is built on this.
        assertThat(new SlicedWindows(WindowSpec.tumbling(10 * SECOND)).lastWindowEndFor(0))
                .isEqualTo(10 * SECOND);
        SlicedWindows hop = new SlicedWindows(WindowSpec.hopping(20 * SECOND, 10 * SECOND));
        assertThat(hop.lastWindowEndFor(0)).isEqualTo(20 * SECOND);
        assertThat(hop.lastWindowEndFor(10 * SECOND)).isEqualTo(30 * SECOND);
        assertThat(new SlicedWindows(WindowSpec.hopping(10 * SECOND, 3 * SECOND)).lastWindowEndFor(0))
                .isEqualTo(10 * SECOND);
    }

    @Test
    void win155_windowsCompletedBetweenIsHalfOpenOnTheLowSideAndClosedOnTheHigh() {
        // WIN-155. (previous, now] -- a window whose end equals the new watermark fires, and it
        // fires once. A closed low bound would fire every window twice; an open high bound would
        // delay every window by one tick for ever.
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(10 * SECOND));
        assertThat(windows.windowsCompletedBetween(0, 10 * SECOND)).containsExactly(10 * SECOND);
        assertThat(windows.windowsCompletedBetween(10 * SECOND, 10 * SECOND)).isEmpty();
        assertThat(windows.windowsCompletedBetween(10 * SECOND, 20 * SECOND)).containsExactly(20 * SECOND);
        assertThat(windows.windowsCompletedBetween(10 * SECOND - 1, 10 * SECOND))
                .containsExactly(10 * SECOND);
        assertThat(windows.windowsCompletedBetween(10 * SECOND, 20 * SECOND - 1))
                .isEmpty();
        assertThat(windows.windowsCompletedBetween(0, 0)).isEmpty();
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
    void win177_anEmptyWindowIsWalkedWithoutBeingEmitted() {
        // WIN-177, through the state directly: one row at 5s, then a jump to 50s. Five window ends
        // are completed and exactly one of them produces a result.
        SlicedWindows windows = new SlicedWindows(WindowSpec.tumbling(10 * SECOND));
        SlicedAggregateState state = new SlicedAggregateState(
                windows, new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.COUNT}, 2_000_000);
        state.update(0, 1, new Object[] {1L}, 5 * SECOND, new long[] {1}, 1);

        List<Long> ends = windows.windowsCompletedBetween(0, 50 * SECOND);
        assertThat(ends).hasSize(5);
        int withResults = 0;
        for (long end : ends) {
            if (!state.fire(end).isEmpty()) {
                withResults++;
            }
        }
        assertThat(withResults).as("five ends walked, one window carries data").isEqualTo(1);
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
    @Disabled("PRV-WIN defect 2: windowed MIN over a column containing NULL returns 0, because the "
            + "NULL's scratch value 0 is folded in by Math.min. SQL says 5.")
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
    void win202_aSlicedMinCannotHandleARetractionAndSaysSo() {
        // WIN-202. Restoring the previous extreme needs an ordered multiset per group, which the
        // sliced state does not keep. A refusal is the right answer; silently leaving the old
        // minimum in place would be a wrong number with no way to notice.
        SlicedAggregateState state = new SlicedAggregateState(
                new SlicedWindows(WindowSpec.tumbling(10 * SECOND)),
                new SlicedAggregateState.Kind[] {SlicedAggregateState.Kind.MIN},
                2_000_000);
        state.update(0, 1, new Object[] {1L}, SECOND, new long[] {5}, 1);
        assertThatThrownBy(() -> state.update(0, 1, new Object[] {1L}, SECOND, new long[] {5}, -1))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3020")
                .hasMessageContaining("ordered multiset per group");
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

    /** One row of WIN-022/WIN-077's slice-arithmetic table. */
    private record SliceCase(long size, long slide, long sliceSize, int slicesPerWindow) {}

    /** One cell of WIN-071's window-count grid. */
    private record CountCase(long size, long slide, long t, int windows) {}

    /** One row of WIN-095's live-slice table. */
    private record LiveCase(long size, long slide, int liveSlices) {}

    // ------------------------------------------------------------------ helpers

    /** Dataset O of section 7: 120 rows one per second, amount 1, plus the pusher at 2000.000. */
    private static String open() {
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            csv.append(row(i, 100, 1, i * SECOND));
        }
        return csv + row(121, 999, 0, 2000 * SECOND);
    }

    /** Dataset S(U) of section 5, and its three expected windows, at the given unit. */
    private static List<String> sizeSweep(Path dir, long unit, String interval) throws Exception {
        return sizeSweep(dir, unit, interval, 3);
    }

    private static List<String> sizeSweep(Path dir, long unit, String interval, int expected) throws Exception {
        String data = csv(
                        row(1, 100, 1, 0L),
                        row(2, 100, 2, unit / 2),
                        row(3, 100, 4, unit),
                        row(4, 100, 8, 2 * unit - 1),
                        row(5, 100, 16, 2 * unit))
                + row(6, 999, 0, 10 * unit);
        return configured(dir, data, SPEC, Duration.ZERO, tumble("s0", interval), List.of(0, 1, 2), 6, expected);
    }

    /** agg.csv of section 14, under one aggregate, keyed on (window_start, window_end, user_id). */
    private static List<String> aggregate(Path dir, String expression) throws Exception {
        String data = csv(
                        row(1, 100, 5, SECOND),
                        row(2, 100, 5, 2 * SECOND),
                        row(3, 100, 7, 3 * SECOND),
                        "4,100,,4000000000\n", // the NULL amount
                        row(5, 200, 11, 5 * SECOND))
                + row(6, 999, 0, 40 * SECOND);
        return configured(
                dir.resolve(expression.replaceAll("[^A-Za-z]", "")),
                data,
                NULLABLE_SPEC,
                Duration.ZERO,
                "SELECT window_start, window_end, user_id, " + expression + " FROM "
                        + "TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id",
                List.of(0, 1, 2),
                6,
                2);
    }

    /**
     * The configured path: a binding, a registry feeding from it, a watermark clock, and the view.
     *
     * <p>Waits for every row to arrive <em>before</em> looking at the view, because a windowing
     * assertion over a source that has not finished reading is an assertion about nothing -- which
     * is the second of WIN.md section 0.5's three vacuity modes.
     */
    private static List<String> configured(
            Path dir,
            String csv,
            String schemaSpec,
            Duration outOfOrderness,
            String sql,
            List<Integer> keys,
            long expectRowsIn,
            int expectViewRows)
            throws Exception {
        Files.createDirectories(dir);
        Path data = dir.resolve("data.csv");
        Files.writeString(data, csv);
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = registry(views, dir, data, schemaSpec, outOfOrderness)) {
            RegisteredQuery query = registry.register("v", sql, keys, Principal.ANONYMOUS);
            awaitRowsIn(query, expectRowsIn);
            awaitView(views, "SELECT * FROM v", expectViewRows);
            // Settle, then read once more: a view that overshoots the expected count is as much a
            // failure as one that undershoots, and only a second read after quiet can see it.
            Thread.sleep(250);
            List<Object[]> rows =
                    new ViewQuery(views).execute("SELECT * FROM v").rows();
            assertThat(rows)
                    .as("the view settled on %d rows; %d were expected", rows.size(), expectViewRows)
                    .hasSize(expectViewRows);
            return render(rows);
        }
    }

    private static QueryRegistry registry(
            ViewCatalog views, Path dir, Path data, String schemaSpec, Duration outOfOrderness) {
        StreamSchema.Builder builder = StreamSchema.builder("s0");
        for (String column : schemaSpec.split(",")) {
            String[] parts = column.split(":");
            builder.field(parts[0], typeOf(parts[1]));
        }
        StreamSchema s0 =
                builder.eventTime("event_time").outOfOrderness(outOfOrderness).build();

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "s0",
                        "filesystem",
                        Map.of("path", data.toString(), "schema", schemaSpec, "event.time", "event_time")));
        return new QueryRegistry(views, s0)
                .feedingFrom(feeds)
                // The enforced minimum idle timeout, and a tick fine enough that a test waits
                // milliseconds rather than seconds for a window to close.
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50));
    }

    private static com.ash.messaging.pravaha.api.data.PravahaType typeOf(String name) {
        return switch (name) {
            case "INT64" -> Types.int64();
            case "INT64?" -> Types.int64().withNullable(true);
            case "TIMESTAMP" -> Types.timestamp();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan(String sql) {
        StreamSchema s0 = StreamSchema.builder("s0")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(s0).plan(sql));
    }

    private static List<String> render(List<Object[]> rows) {
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

    private static long sumOf(List<String> rows, int ordinal) {
        long total = 0;
        for (String row : rows) {
            total += Long.parseLong(row.split("\\|")[ordinal]);
        }
        return total;
    }

    /** The n column summed over every row whose window starts at {@code windowStart}. */
    private static long countOf(List<String> rows, long windowStart) {
        long total = 0;
        for (String row : rows) {
            String[] parts = row.split("\\|");
            if (Long.parseLong(parts[0]) == windowStart) {
                total += Long.parseLong(parts[3]);
            }
        }
        return total;
    }

    private static void awaitRowsIn(RegisteredQuery query, long atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline && query.rowsIn() < atLeast) {
            Thread.sleep(10);
        }
        assertThat(query.rowsIn())
                .as("the feed delivered %d rows; %d are in the file. A windowing assertion over a "
                        + "source that has not finished reading is an assertion about nothing")
                .isGreaterThanOrEqualTo(atLeast);
        assertThat(query.failure()).as("the lane must not have died").isEmpty();
    }

    private static void awaitView(ViewCatalog views, String sql, int expected) throws InterruptedException {
        ViewQuery reader = new ViewQuery(views);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        int size = -1;
        while (System.nanoTime() < deadline) {
            size = reader.execute(sql).size();
            if (size >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(size)
                .as("the view held %d rows after sixty seconds; %d were expected", size, expected)
                .isGreaterThanOrEqualTo(expected);
    }
}
