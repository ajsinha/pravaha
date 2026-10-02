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

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.window.SessionWindows;
import com.ash.messaging.pravaha.runtime.window.SlicedAggregateState;
import com.ash.messaging.pravaha.runtime.window.SlicedWindows;
import com.ash.messaging.pravaha.runtime.window.WindowSpec;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of {@code docs/project/qa/cases/WIN.md} that no configured server can reach.
 *
 * <p>WIN.md section 0.6 names three of them. SESSION windows are implemented in
 * {@link SessionWindows} and reachable from no plan node, so section 3 of the file is written
 * against the class. The slicing arithmetic -- which slice a row falls in, which windows a slice
 * belongs to, when a slice may be discarded -- decides every answer the other half asserts, and a
 * boundary case one nanosecond wide cannot be expressed as a CSV. And a window spec whose slide
 * exceeds its size is refused before a query can be built, so the only place to check what it would
 * have meant is here.
 *
 * <p>Its companion, {@link WindowAnswerTest}, runs the same file's cases through the path a
 * deployment has: a bound source, a registered query, and the answer read out of the served view.
 */
@Tag("qa")
class WindowArithmeticTest {

    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    /** One row of WIN-022/WIN-077's slice-arithmetic table. */
    private record SliceCase(long size, long slide, long sliceSize, int slicesPerWindow) {}

    /** One cell of WIN-071's window-count grid. */
    private record CountCase(long size, long slide, long t, int windows) {}

    /** One row of WIN-095's live-slice table. */
    private record LiveCase(long size, long slide, int liveSlices) {}

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
        // WIN-071. Windows start on multiples of the slide (HOPALIGN-1, as SQL's HOP), so count(t) =
        // floor(t / D) - floor((t - S) / D). Two cells of this grid are the ones FINDINGS W-5
        // corrects: at size 10s slide 3s a row at t=0 is in four windows and a row at t=2s is in
        // three, so no constant is right.
        List<CountCase> cases = List.of(
                new CountCase(20 * SECOND, 10 * SECOND, 5 * SECOND, 2),
                new CountCase(20 * SECOND, 5 * SECOND, 5 * SECOND, 4),
                new CountCase(10 * SECOND, 3 * SECOND, 0, 4),
                new CountCase(10 * SECOND, 3 * SECOND, 2 * SECOND, 3),
                new CountCase(7 * SECOND, 2 * SECOND, 0, 4),
                new CountCase(7 * SECOND, 2 * SECOND, SECOND, 3),
                new CountCase(10 * SECOND, 10 * SECOND, 0, 1),
                new CountCase(10 * SECOND, 10 * SECOND, 7 * SECOND, 1),
                new CountCase(20 * SECOND, 10 * SECOND, -1, 2));
        for (CountCase c : cases) {
            SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(c.size(), c.slide()));
            long expected = Math.floorDiv(c.t(), c.slide()) - Math.floorDiv(c.t() - c.size(), c.slide());
            assertThat(windows.windowEndsContaining(c.t()))
                    .as("size=%d slide=%d t=%d", c.size(), c.slide(), c.t())
                    .hasSize(c.windows())
                    .hasSize((int) expected);
        }
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
        // WIN-152 and WIN-153. Under HOP(3s slide, 10s size) a row at 2.999999999 is in three
        // windows and a row at 3.000000000 is in four: the window starting at 3s is gained across a
        // single nanosecond, which is the shape of every off-by-one in a windowing engine. Windows
        // start on multiples of the slide (HOPALIGN-1); their ends fall at 1s past one.
        SlicedWindows hop20 = new SlicedWindows(WindowSpec.hopping(20 * SECOND, 10 * SECOND));
        assertThat(hop20.windowEndsContaining(10 * SECOND - 1)).containsExactly(10 * SECOND, 20 * SECOND);
        assertThat(hop20.windowEndsContaining(10 * SECOND)).containsExactly(20 * SECOND, 30 * SECOND);

        SlicedWindows hop3 = new SlicedWindows(WindowSpec.hopping(10 * SECOND, 3 * SECOND));
        assertThat(hop3.windowEndsContaining(3 * SECOND - 1)).containsExactly(4 * SECOND, 7 * SECOND, 10 * SECOND);
        assertThat(hop3.windowEndsContaining(3 * SECOND))
                .containsExactly(4 * SECOND, 7 * SECOND, 10 * SECOND, 13 * SECOND);
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

    /** Plans SQL against the WIN.md standing schema, so a refusal can be asserted on. */
    private static PhysicalOperator plan(String sql) {
        StreamSchema s0 = StreamSchema.builder("s0")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(s0).plan(sql));
    }
}
