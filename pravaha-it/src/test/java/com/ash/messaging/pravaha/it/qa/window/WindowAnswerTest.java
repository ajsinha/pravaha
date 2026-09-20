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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.window.SessionWindows;
import com.ash.messaging.pravaha.runtime.window.SlicedAggregateState;
import com.ash.messaging.pravaha.runtime.window.SlicedWindows;
import com.ash.messaging.pravaha.runtime.window.WindowSpec;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code docs/qa/cases/WIN.md}, sections 1-8: TUMBLE, HOP, SESSION, CUMULATE, size, hop
 * slide-versus-size, open windows, and key cardinality.
 *
 * <p>See {@link WindowClosingAnswerTest} for sections 9-14 (volume, boundaries, close triggers,
 * lateness, empty windows, aggregates) and {@link WindowTestSupport} for the shared datasets and
 * harnesses both files use.
 */
@Tag("qa")
class WindowAnswerTest extends WindowTestSupport {

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
    void win003_theGroupedFunctionFormOfTumbleIsAcceptedAndAgreesWithTheTableForm(@TempDir Path dir) throws Exception {
        // WIN-003. The case predicted that TUMBLE_START(...) beside GROUP BY TUMBLE(...) would hit
        // buildGroupedWindow's "sits beside a windowing function" refusal, the same shape as a raw
        // scalar expression next to the window call. It does not: Calcite's own SqlToRelConverter
        // resolves GROUP BY TUMBLE(...) with TUMBLE_START/END in the select list into its own
        // Aggregate + Project before PhysicalPlanBuilder ever sees a bare TUMBLE_START call, so the
        // RexInputRef branch is what actually runs. Outcome (a), not the predicted (b): the query
        // plans to a WindowedAggregate and gives the same answer as WIN-001's table-function form.
        List<String> rows = configured(
                dir,
                A,
                SPEC,
                Duration.ZERO,
                "SELECT TUMBLE_START(event_time, INTERVAL '10' SECOND) AS window_start, user_id, "
                        + "COUNT(*) AS n, SUM(amount) AS total FROM s0 "
                        + "GROUP BY TUMBLE(event_time, INTERVAL '10' SECOND), user_id",
                List.of(0, 1),
                6,
                4);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "0|100|2|30", // 10 + 20 = 30
                        "0|200|1|30",
                        "10000000000|100|1|40",
                        "10000000000|200|1|50");
    }

    @Test
    void win004_quickstartSection4sRegistrationRunsAsWrittenAndAgreesWithWin001(@TempDir Path dir) throws Exception {
        // WIN-004. docs/QUICKSTART.md section 4, verbatim but for txn -> s0 and the status filter
        // dropped (its own schema has no event_time, per DOC-011). The case predicted TUMBLE_END in
        // the projection would hit the PRV-2020 refusal WIN-003 also predicted; per WIN-003 that
        // refusal does not fire for the grouped form, so this registers and runs, keyed on user_id
        // alone (--keys 1) exactly as the quickstart says. The remaining, real hazard the case
        // named survives: keying on user_id alone means each window overwrites the last, so the
        // view converges on the *last* window's numbers rather than holding one row per window.
        List<String> rows = configured(
                dir,
                A,
                SPEC,
                Duration.ZERO,
                "SELECT STREAM TUMBLE_END(event_time, INTERVAL '10' SECOND) AS window_end, "
                        + "user_id, COUNT(*) AS txn_count, SUM(amount) AS total FROM s0 "
                        + "GROUP BY TUMBLE(event_time, INTERVAL '10' SECOND), user_id",
                List.of(1),
                6,
                2);
        // Keyed on user_id alone: one row per user, holding whichever window last wrote that key --
        // here window [10,20), the last one to fire for each user, not "one row per window".
        assertThat(rows).containsExactlyInAnyOrder("20000000000|100|1|40", "20000000000|200|1|50");
    }

    @Test
    void win005_tumbleOverAStreamWithNoDeclaredEventTimeIsRefusedAtRegistration(@TempDir Path dir) throws Exception {
        // WIN-005, with a NEW OUTCOME. It was recorded as "never fires rather than refusing": the
        // registration succeeded, six rows went in, nothing came out, and nothing anywhere said
        // why. That was true of the engine when the case was run, and it is the behaviour TIME-6
        // then measured from four directions -- state=RUNNING, a climbing ROWS IN, an empty view,
        // a NaN lag gauge and not one log line, indistinguishable on every surface from a query
        // that was working.
        //
        // It is now refused at plan time, under the owner's standing rule: "Where the engine could
        // accept something doubtful or refuse it, it refuses -- by name, with a code and the way to
        // say it properly. Silent coercions, guessed units and papering defaults are the bugs
        // nobody finds." A window over a stream with no declared event time is not a slow answer;
        // no watermark advances over such a stream, so the window cannot close, ever. Recording
        // "it stays empty" as the expected outcome is recording the bug.
        //
        // The control is unchanged and still passes in the same run, so the refusal is about this
        // declaration and not about the server being dead.
        List<String> controlRows =
                configured(dir, A, SPEC, Duration.ZERO, tumble("s0", "10' SECOND"), List.of(0, 1, 2), 6, 4);
        assertThat(controlRows).hasSize(4);

        Path data2 = dir.resolve("a2.csv");
        Files.writeString(data2, A);
        ViewCatalog views = new ViewCatalog();
        // snoevent: the identical schema, minus the event-time declaration.
        StreamSchema snoevent = StreamSchema.builder("snoevent")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .build();
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding("snoevent", "filesystem", Map.of("path", data2.toString(), "schema", SPEC)));
        try (QueryRegistry registry = new QueryRegistry(views, snoevent)
                .feedingFrom(feeds)
                .generatingWatermarks(Duration.ofSeconds(1), Duration.ofMillis(50))) {
            assertThatThrownBy(() -> registry.register(
                            "v_noev",
                            "SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total FROM "
                                    + "TABLE(TUMBLE(TABLE snoevent, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                                    + "GROUP BY window_start, window_end, user_id",
                            List.of(0, 1, 2),
                            Principal.ANONYMOUS))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2002")
                    .hasMessageContaining("'snoevent' declares no event-time column")
                    .as("the refusal names the stream, says what would have happened, and gives the key")
                    .hasMessageContaining("pravaha.streams.snoevent.event-time: event_time");
            // And nothing was registered: there is no half-built query left behind to go quiet.
            assertThat(registry.names()).doesNotContain("v_noev");
        }
    }

    @Test
    void win006_descriptorOnANonTemporalColumnIsRefusedByCalciteNotByPravaha() {
        // WIN-006. descriptorOrdinal itself does no type check (it matches by name or ordinal), so
        // the case predicted DESCRIPTOR(amount) would resolve and the engine would window on money.
        // In practice Calcite's own operand type checker for its built-in TUMBLE validates the
        // descriptor's column type before Pravaha's descriptorOrdinal ever runs, and refuses this
        // query at the validation layer -- a defence in depth the case did not anticipate, and worth
        // recording precisely because the case's own premise ("no type check at all") is only true
        // of Pravaha's half of the check.
        assertThatThrownBy(() -> plan("SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total "
                        + "FROM TABLE(TUMBLE(TABLE s0, DESCRIPTOR(amount), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("Cannot apply 'TUMBLE'");
    }

    @Test
    void win012_explainRendersSizeAndSlideInMillisecondsNotNanoseconds() {
        // WIN-012. WindowAssignOperator.label() divides sizeNanos by 1_000_000 -- the one place a
        // human reads the window back is the one place the engine's own nanosecond time base is not
        // used. Contrast with WIN-054, where a 100-microsecond size truncates to "0ms".
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan = plan(tumble("s0", "10' SECOND"));
        assertThat(findByLabel(plan, "WindowedAggregate").label())
                .contains("WindowedAggregate(TUMBLING 10000ms")
                .contains("2 aggregate(s)");
        assertThat(findByLabel(plan, "WindowAssign").label())
                .isEqualTo("WindowAssign(TUMBLING size=10000ms slide=10000ms on event_time)");
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
    void win015_windowEndsContainingAndTheSlicingAgreeOnEveryRow() {
        // WIN-015. If a record's slice were not part of every window windowEndsContaining returns
        // for it, the slicing would be wrong. hopping(20s, 10s): slice width gcd(20,10) = 10s.
        SlicedWindows windows = new SlicedWindows(WindowSpec.hopping(20 * SECOND, 10 * SECOND));
        for (long t : new long[] {5 * SECOND, 15 * SECOND, 25 * SECOND, 0L, 10 * SECOND - 1, 10 * SECOND, -1L}) {
            long slice = windows.sliceStartFor(t);
            List<Long> ends = windows.windowEndsContaining(t);
            assertThat(ends).as("t=%d must belong to at least one window", t).isNotEmpty();
            for (long end : ends) {
                assertThat(windows.slicesOfWindowEnding(end))
                        .as("t=%d's slice %d must be part of the window ending at %d", t, slice, end)
                        .contains(slice);
            }
        }
        // The two worked examples from the case text, checked exactly.
        assertThat(windows.sliceStartFor(5 * SECOND)).isZero();
        assertThat(windows.windowEndsContaining(5 * SECOND)).containsExactly(10 * SECOND, 20 * SECOND);
        assertThat(windows.sliceStartFor(-1L)).isEqualTo(-10 * SECOND);
        assertThat(windows.windowEndsContaining(-1L)).containsExactly(0L, 10 * SECOND);
    }

    @Test
    void win016_aBareHopTableFunctionEmitsSliceBoundariesNotWindowBoundaries(@TempDir Path dir) throws Exception {
        // WIN-016. Without an aggregate above it, WindowAssign never has its slice boundaries
        // corrected to the window's own -- only WindowedAggregate.emitRow does that. A bare
        // TABLE(HOP(...)) over a 20s window therefore reports a 10s-wide "window": the slice, not
        // the window the query asked for, and no replication into every window the row belongs to.
        List<String> rows = configured(
                dir,
                C_PLUS,
                SPEC,
                Duration.ZERO,
                "SELECT txn_id, user_id, window_start, window_end FROM "
                        + "TABLE(HOP(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND, INTERVAL '20' SECOND))",
                List.of(0),
                4,
                4);
        assertThat(rows).hasSize(4); // one row per input row, not the 8 SQL:2016 windowing implies
        for (String row : rows) {
            String[] parts = row.split("\\|");
            long start = Long.parseLong(parts[2]);
            long end = Long.parseLong(parts[3]);
            assertThat(end - start)
                    .as("the boundary columns name the 10s slice, not the 20s window the query asked for")
                    .isEqualTo(10 * SECOND);
        }
    }

    @Test
    void win017_theSameBareTableFunctionOverTumbleIsCorrect(@TempDir Path dir) throws Exception {
        // WIN-017. The control that isolates WIN-016 to hops: for TUMBLE, slice == window, so the
        // bare table function's boundaries are correct even with no aggregate above it.
        List<String> rows = configured(
                dir,
                A_PLUS,
                SPEC,
                Duration.ZERO,
                "SELECT txn_id, user_id, window_start, window_end FROM "
                        + "TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND))",
                List.of(0),
                7,
                7);
        assertThat(rows).hasSize(7);
        for (String row : rows) {
            String[] parts = row.split("\\|");
            long start = Long.parseLong(parts[2]);
            long end = Long.parseLong(parts[3]);
            assertThat(end - start).isEqualTo(10 * SECOND);
        }
    }

    @Test
    void win018_theHopIntervalOrderIsSlideThenSize() {
        // WIN-018. PhysicalPlanBuilder reverses Calcite's argument order: the first interval in the
        // SQL text is the slide, the second is the size. Getting this backwards produces windows of
        // the wrong width that still fire plausibly, which is why the label is asserted exactly.
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan =
                plan("SELECT window_start, window_end, COUNT(*) FROM TABLE(HOP(TABLE s0, DESCRIPTOR(event_time), "
                        + "INTERVAL '10' SECOND, INTERVAL '20' SECOND)) GROUP BY window_start, window_end");
        assertThat(findByLabel(plan, "WindowAssign").label())
                .isEqualTo("WindowAssign(HOPPING size=20000ms slide=10000ms on event_time)");
    }

    @Test
    void win023_aHopAndATumbleOfTheSameSizeAgreeOnEverySlice() {
        // WIN-023. A record belongs to exactly one slice whatever the overlap: the hop's 1s slice
        // must always sit inside the tumble's 10s slice containing the same instant.
        for (long t : new long[] {0L, 1L, 999_999_999L, SECOND, 10 * SECOND - 1, 10 * SECOND, -1L, -10 * SECOND}) {
            long tumbleSlice = Math.floorDiv(t, 10 * SECOND) * 10 * SECOND;
            long hopSlice = Math.floorDiv(t, SECOND) * SECOND;
            assertThat(hopSlice)
                    .as("t=%d: the 1s slice must fall inside the 10s slice", t)
                    .isGreaterThanOrEqualTo(tumbleSlice)
                    .isLessThan(tumbleSlice + 10 * SECOND);
        }
    }

    @Test
    void win028_hopResultsAreZSetRowsWithWeightPlusOneAndNoDuplicate(@TempDir Path dir) throws Exception {
        // WIN-028. A window that fires once must produce exactly one +1 insert per (window, key) and
        // no retraction at all, because no late record arrives and dirty stays empty. The subscriber
        // is attached before any row is awaited, so it cannot miss the commit.
        Files.createDirectories(dir);
        Path data = dir.resolve("data.csv");
        Files.writeString(data, C_PLUS);
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = registry(views, dir, data, SPEC, Duration.ZERO)) {
            RegisteredQuery query = registry.register(
                    "v_h10_20", hop("s0", "10' SECOND", "20' SECOND"), List.of(0, 1, 2), Principal.ANONYMOUS);
            // pause() stops the feed itself, not just accept(), so it is the race-free way to attach
            // a subscriber before any row is committed on a dataset small enough to finish before a
            // subscribe() call issued right after register() would otherwise be guaranteed to win.
            registry.pause("v_h10_20");
            // Copy-on-write: the assertions below stream this while the subscriber's own thread may
            // still be delivering, which a synchronized list does not make safe.
            List<com.ash.messaging.pravaha.serving.ViewChange> changes =
                    new java.util.concurrent.CopyOnWriteArrayList<>();
            query.subscribe(batch -> changes.addAll(batch));
            registry.resume("v_h10_20");
            awaitRowsIn(query, 4);
            awaitView(views, "SELECT * FROM v_h10_20", 4);
            // Waited for, then settled. This was a flat 250ms sleep, which assumes the subscriber's
            // callback has run by then -- true on an idle machine and false on one running three
            // parallel builds, where it measured zero changes and reported it as a windowing defect.
            // The settle afterwards is what still catches a fifth change, which is the duplicate
            // this case exists to rule out.
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && changes.size() < 4) {
                Thread.sleep(10);
            }
            Thread.sleep(250);
            assertThat(changes).as("one change per (window, key) of WIN-013").hasSize(4);
            assertThat(changes).as("no retraction: no late record arrived").allMatch(c -> c.weight() == 1);
        }
    }

    @Test
    void win030_explainOnAHopNamesHoppingAndBothIntervals() {
        // WIN-030. The window kind and both intervals must be visible in the plan; the aggregate's
        // own label prints only the size, so the assign line is the only place the slide appears.
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan = plan(hop("s0", "10' SECOND", "20' SECOND"));
        assertThat(findByLabel(plan, "WindowedAggregate").label())
                .startsWith("WindowedAggregate(HOPPING 20000ms")
                .contains("2 aggregate(s)");
        assertThat(findByLabel(plan, "WindowAssign").label())
                .isEqualTo("WindowAssign(HOPPING size=20000ms slide=10000ms on event_time)");
    }

    // -------------------------------------------------------- 3. SESSION (no SQL surface)
    @Test
    void win042_aSessionsStateIsBoundedByOpenSessionsNotByHistory() {
        // WIN-042, blocked-by-syntax. The falsifier is state approaching the record count; the case
        // predicts a plateau of "roughly 1,000". Measured: because closedBy here runs only once per
        // 1,000 records, at a watermark 2s behind the batch's own newest record, each check finds the
        // most recent ~300 of that batch's keys not yet closeable (their 1s-gap session has not
        // aged past the watermark) and the other ~700 already closed -- so the plateau is ~300, not
        // ~1,000. Both numbers are "bounded, and nowhere near 100,000"; recording the true one rather
        // than forcing the predicted one is the point of running this case at all.
        SessionWindows sessions = new SessionWindows(SECOND);
        long closedTotal = 0;
        int lastKeyCount = 0;
        int lastOpenSessions = 0;
        for (int i = 1; i <= 100_000; i++) {
            sessions.record(i % 1000, i * 10_000_000L);
            if (i % 1000 == 0) {
                closedTotal += sessions.closedBy(i * 10_000_000L - 2 * SECOND).size();
                lastKeyCount = sessions.keyCount();
                lastOpenSessions = sessions.openSessions();
            }
        }
        assertThat(lastKeyCount)
                .as("bounded by open sessions, not by the 100,000 records fed")
                .isLessThan(2000);
        assertThat(lastOpenSessions).isEqualTo(lastKeyCount); // one session per key at this gap
        // Vacuity: every record is accounted for by either a still-open session or a closed one.
        assertThat(closedTotal + lastOpenSessions).isEqualTo(100_000);
    }

    // -------------------------------------------------------- 4. CUMULATE (does not exist)
    @Test
    void win044_groupByCumulateProducesADifferentErrorThanTheTableForm() {
        // WIN-044. isWindowFunction does not list CUMULATE, so the grouped form is handed to
        // ExpressionCompiler as an ordinary scalar call instead of being recognised as a window.
        assertThatThrownBy(() -> plan("SELECT user_id, COUNT(*) FROM s0 "
                        + "GROUP BY CUMULATE(event_time, INTERVAL '2' SECOND, INTERVAL '10' SECOND), user_id"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void win045_sqlSupportDocDoesNotMentionCumulateInEitherDirection() throws Exception {
        // WIN-045. The Aggregation table lists TUMBLE, HOP and SESSION; CUMULATE parses (WIN-043)
        // and has no row at all, so "not listed" and "refused" are not the same set.
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (!java.nio.file.Files.exists(root.resolve("docs/CONTINUOUS_QUERIES.md")) && root.getParent() != null) {
            root = root.getParent();
        }
        String doc = java.nio.file.Files.readString(root.resolve("docs/CONTINUOUS_QUERIES.md"));
        assertThat(doc.toLowerCase(java.util.Locale.ROOT)).doesNotContain("cumulate");
    }

    @Test
    void win049_anUnknownWindowFunctionNameIsRefused() {
        // WIN-049. The default arm must catch anything Calcite lets through with a windowing-looking
        // name that is not one of the three the engine builds.
        assertThatThrownBy(() ->
                        plan("SELECT * FROM TABLE(TUMBLING(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND)))"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void win050_lowerAndMixedCaseWindowFunctionNamesPlanIdentically() {
        // WIN-050. buildWindowAssign upper-cases the operator name with Locale.ROOT, so case must
        // not change whether a window is recognised.
        String upper =
                findByLabel(plan(tumble("s0", "10' SECOND")), "WindowAssign").label();
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator lower = plan("SELECT window_start, window_end, "
                + "user_id, COUNT(*) AS n, SUM(amount) AS total FROM "
                + "TABLE(tumble(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end, user_id");
        com.ash.messaging.pravaha.runtime.plan.PhysicalOperator mixed = plan("SELECT window_start, window_end, "
                + "user_id, COUNT(*) AS n, SUM(amount) AS total FROM "
                + "TABLE(Tumble(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end, user_id");
        assertThat(findByLabel(lower, "WindowAssign").label()).isEqualTo(upper);
        assertThat(findByLabel(mixed, "WindowAssign").label()).isEqualTo(upper);
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
        // WIN-067 and WIN-068 together, and TY-21 is why this case used to expect two rows.
        //
        // 1 DAY is 86,400,000,000,000 ns and all three windows fire. The default retention was
        // twenty-four hours of event time and settable from nowhere (WIN-069), so by the time the
        // frontier reached three days the first window was older than the horizon and had been
        // evicted: two rows survived and the total came to 28 instead of 31.
        //
        // This case recorded that as the expected answer. It is worth being blunt about what that
        // means -- the defect was not merely present, it had been written down as correct in the
        // window suite, so every later reading of these numbers confirmed it. That is exactly the
        // failure mode a silent eviction produces: a short answer and a complete one are
        // indistinguishable, including to the people writing the tests.
        //
        // The default is forever now, so a one-day window is no longer evicted one window after it
        // lands, and all three survive.
        List<String> rows = sizeSweep(dir, DAY, "1' DAY", 3);
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "0|86400000000000|100|2|3", // the window that used to disappear
                        "86400000000000|172800000000000|100|2|12", // 4 + 8 = 12
                        "172800000000000|259200000000000|100|1|16"); // 16
        assertThat(sumOf(rows, 4)).isEqualTo(31); // 3 + 12 + 16; nothing is missing now
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
}
