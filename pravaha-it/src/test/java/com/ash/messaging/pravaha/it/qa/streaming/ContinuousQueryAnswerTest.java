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
package com.ash.messaging.pravaha.it.qa.streaming;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A continuous query answering while its stream is still open.
 *
 * <p>The distinction this file exists for: a query that produces the right answer <em>after</em> its
 * input ends is a batch query with extra steps. Round 1 recorded that a registered aggregate emitted
 * only at end of input, and a stream has no end -- so the canonical continuous query ingested
 * everything and published nothing, while reporting RUNNING throughout.
 *
 * <p>Nothing here ever closes the stream. Every assertion is made with the source still open and
 * more rows still to come, which is the only way to tell the two apart.
 */
@Timeout(90)
class ContinuousQueryAnswerTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("txn_id", Types.int64())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .build();

    private ViewCatalog views;
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void start() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, TXN);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void stop() {
        registry.close();
        arena.close();
    }

    /** Pushes one row and commits, the way a client feeding a registered query does. */
    private void push(String name, long id, String user, long amount, long weight) {
        push(name, id, user, amount, weight, id);
    }

    private void push(String name, long id, String user, long amount, long weight, long eventTime) {
        RegisteredQuery query = registry.require(name);
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id);
        writer.setString(1, user);
        writer.setLong(2, amount);
        writer.setLong(3, eventTime);
        writer.weight(weight).eventTimestampNanos(eventTime).sequence(id).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        // The lane applies on its own thread, so committing first would publish a frontier the row
        // has not reached and tell a reader about a change it cannot see.
        query.awaitApplied(Duration.ofSeconds(10));
        query.commit();
    }

    private List<String> read(String sql) {
        List<String> rows = new ArrayList<>();
        new ViewQuery(views).execute(sql).rows().forEach(row -> rows.add(row[0] + "=" + row[1]));
        rows.sort(String::compareTo);
        return rows;
    }

    private static final long SECOND = 1_000_000_000L;

    private static final String WINDOWED = "SELECT user_id, SUM(amount) AS total FROM txn "
            + "GROUP BY TUMBLE(event_time, INTERVAL '1' SECOND), user_id";

    /** Moves event time on, the way a source's watermark does. The push path advances it explicitly. */
    private void advanceTo(String name, long nanos) {
        registry.require(name).advanceWatermark(nanos);
        registry.require(name).commit();
    }

    // ==================================================== lifecycle

    @Test
    void cq010_theSameQuestionTwiceIsOneComputationAndBothNamesAnswer() {
        // The claim the whole sharing model rests on: ten desks asking the same question read one
        // computation. If the second name got its own, the source would be read twice and the two
        // views could disagree under load.
        registry.register("a", WINDOWED, List.of(0), Principal.ANONYMOUS);
        registry.register(
                "b",
                "SELECT t.user_id, SUM(t.amount) AS total FROM txn AS t "
                        + "GROUP BY TUMBLE(t.event_time, INTERVAL '1' SECOND), t.user_id",
                List.of(0),
                Principal.ANONYMOUS);

        assertThat(registry.size()).as("one computation, two names").isEqualTo(1);
        assertThat(registry.require("b").fingerprint())
                .isEqualTo(registry.require("a").fingerprint());

        push("a", 1, "ann", 100, 1, 100_000_000L);
        push("a", 2, "cat", 900, 1, 1_500_000_000L);
        advanceTo("a", 1_500_000_000L);

        assertThat(read("SELECT user_id, total FROM a")).containsExactly("ann=100");
        assertThat(read("SELECT user_id, total FROM b"))
                .as("both names answer, because both name the same computation")
                .containsExactly("ann=100");
    }

    @Test
    void cq011_droppingOneNameLeavesTheOtherRunning() {
        // Neither desk knows the other exists, so dropping yours must not stop theirs.
        registry.register("a", WINDOWED, List.of(0), Principal.ANONYMOUS);
        registry.register(
                "b",
                "SELECT t.user_id, SUM(t.amount) AS total FROM txn AS t "
                        + "GROUP BY TUMBLE(t.event_time, INTERVAL '1' SECOND), t.user_id",
                List.of(0),
                Principal.ANONYMOUS);

        registry.drop("a");

        assertThat(registry.find("a")).isEmpty();
        assertThat(registry.require("b").state()).isEqualTo(QueryState.RUNNING);

        push("b", 1, "ann", 100, 1, 100_000_000L);
        push("b", 2, "cat", 900, 1, 1_500_000_000L);
        advanceTo("b", 1_500_000_000L);
        assertThat(read("SELECT user_id, total FROM b")).containsExactly("ann=100");
    }

    @Test
    void cq012_aPausedQueryKeepsAnsweringAndStopsAdvancing() {
        // Pause is not stop. The view keeps answering where it reached -- a dashboard does not go
        // blank -- and rows stop being applied.
        registry.register("p", WINDOWED, List.of(0), Principal.ANONYMOUS);
        push("p", 1, "ann", 100, 1, 100_000_000L);
        push("p", 2, "cat", 900, 1, 1_500_000_000L);
        advanceTo("p", 1_500_000_000L);
        assertThat(read("SELECT user_id, total FROM p")).containsExactly("ann=100");

        registry.pause("p");
        assertThat(registry.require("p").state()).isEqualTo(QueryState.PAUSED);
        long applied = registry.require("p").rowsIn();
        push("p", 3, "bob", 500, 1, 2_100_000_000L);

        assertThat(registry.require("p").rowsIn())
                .as("a paused query applies nothing")
                .isEqualTo(applied);
        assertThat(read("SELECT user_id, total FROM p"))
                .as("and still answers what it had reached")
                .containsExactly("ann=100");

        registry.resume("p");
        assertThat(registry.require("p").state()).isEqualTo(QueryState.RUNNING);
        push("p", 4, "bob", 500, 1, 2_100_000_000L);
        // Past the end of bob's second, not merely past his row: a window closes when time passes
        // its end, and 2.5s leaves [2,3) open.
        advanceTo("p", 3_500_000_000L);
        assertThat(read("SELECT user_id, total FROM p"))
                .as("and picks up from where it was, without replaying what it missed")
                .containsExactly("ann=100", "bob=500", "cat=900");
    }

    @Test
    void cq013_aDroppedNameStopsAnsweringRatherThanServingStaleRows() {
        registry.register("gone", WINDOWED, List.of(0), Principal.ANONYMOUS);
        push("gone", 1, "ann", 100, 1, 100_000_000L);
        push("gone", 2, "cat", 900, 1, 1_500_000_000L);
        advanceTo("gone", 1_500_000_000L);
        assertThat(read("SELECT user_id, total FROM gone")).isNotEmpty();

        registry.drop("gone");

        assertThat(registry.find("gone")).isEmpty();
        assertThatThrownBy(() -> new ViewQuery(views).execute("SELECT user_id FROM gone"))
                .as("a dropped view must be gone, not stale")
                .isInstanceOf(RuntimeException.class);
    }

    // ==================================================== windows over a live stream

    @Test
    void cq020_severalWindowsCloseInOrderAsTimeMovesOn() {
        // Windows fire in order and each fires once. A consumer applying two results for one window
        // in arrival order keeps whichever arrived last, so out-of-order firing is a wrong answer
        // that looks like a right one.
        registry.register("w", WINDOWED, List.of(0), Principal.ANONYMOUS);

        push("w", 1, "ann", 10, 1, 100_000_000L);
        push("w", 2, "ann", 20, 1, 1_100_000_000L);
        push("w", 3, "ann", 30, 1, 2_100_000_000L);
        push("w", 4, "ann", 40, 1, 3_100_000_000L);
        advanceTo("w", 3_500_000_000L);

        // Three windows closed; the fourth is still open and must not appear.
        assertThat(read("SELECT user_id, total FROM w")).hasSize(1);
        List<Object[]> rows =
                new ViewQuery(views).execute("SELECT user_id, total FROM w").rows();
        assertThat(rows.get(0)[1])
                .as("the view is keyed on user_id alone, so the latest closed window stands")
                .isEqualTo(30L);
    }

    @Test
    void cq021_anEmptyWindowProducesNoRowRatherThanAZero() {
        // A window nobody wrote to is not a window with a zero in it. Emitting one would put a row
        // in the answer that no data supports.
        registry.register("w", WINDOWED, List.of(0), Principal.ANONYMOUS);

        push("w", 1, "ann", 10, 1, 100_000_000L);
        // Nothing in [1,2). A row in [2,3) closes both.
        push("w", 2, "ann", 30, 1, 2_100_000_000L);
        advanceTo("w", 2_500_000_000L);

        List<Object[]> rows =
                new ViewQuery(views).execute("SELECT user_id, total FROM w").rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1])
                .as("the first window's 10, with no zero row for the empty second")
                .isEqualTo(10L);
    }

    @Test
    void cq022_aWindowIsNotPublishedBeforeItIsComplete() {
        // The invariant a streaming engine is judged on. A partial window published as final is a
        // wrong answer that never corrects itself.
        registry.register("w", WINDOWED, List.of(0), Principal.ANONYMOUS);

        for (long i = 1; i <= 20; i++) {
            push("w", i, "ann", 1, 1, i * 10_000_000L);
            assertThat(read("SELECT user_id, total FROM w"))
                    .as("nothing may be published while the second is still being assembled")
                    .isEmpty();
        }

        push("w", 99, "cat", 900, 1, 1_500_000_000L);
        advanceTo("w", 1_500_000_000L);
        List<Object[]> rows =
                new ViewQuery(views).execute("SELECT user_id, total FROM w").rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1])
                .as("all twenty, at once, when the window closed")
                .isEqualTo(20L);
    }

    @Test
    void cq023_aRowBeyondTheAllowedLatenessIsCountedRatherThanApplied() {
        // Zero allowed lateness means a window is final when it closes. The row is not applied --
        // and it is not silently dropped either: a query that loses rows must be able to say so.
        registry.register("w", WINDOWED, List.of(0), Principal.ANONYMOUS);

        push("w", 1, "ann", 100, 1, 100_000_000L);
        push("w", 2, "cat", 900, 1, 1_500_000_000L);
        advanceTo("w", 1_500_000_000L);
        assertThat(read("SELECT user_id, total FROM w")).containsExactly("ann=100");

        // Late, for a window already closed and final.
        push("w", 3, "ann", 50, 1, 200_000_000L);
        advanceTo("w", 1_600_000_000L);

        assertThat(read("SELECT user_id, total FROM w"))
                .as("the published answer stands; the late row did not silently join it")
                .containsExactly("ann=100");
    }

    // ==================================================== aggregates over a live stream

    @Test
    void cq030_everyAggregateShapeAnswersMidStream() {
        registry.register(
                "agg",
                "SELECT user_id, COUNT(*) AS n, SUM(amount) AS total, AVG(amount) AS mean, "
                        + "MIN(amount) AS lo, MAX(amount) AS hi FROM txn "
                        + "GROUP BY TUMBLE(event_time, INTERVAL '1' SECOND), user_id",
                List.of(0),
                Principal.ANONYMOUS);

        push("agg", 1, "ann", 10, 1, 100_000_000L);
        push("agg", 2, "ann", 30, 1, 200_000_000L);
        push("agg", 3, "ann", 20, 1, 300_000_000L);
        push("agg", 4, "cat", 900, 1, 1_500_000_000L);
        advanceTo("agg", 1_500_000_000L);

        List<Object[]> rows = new ViewQuery(views)
                .execute("SELECT user_id, n, total, mean, lo, hi FROM agg")
                .rows();
        assertThat(rows).hasSize(1);
        Object[] ann = rows.get(0);
        assertThat(ann[1]).as("count").isEqualTo(3L);
        assertThat(ann[2]).as("sum").isEqualTo(60L);
        assertThat(ann[3]).as("avg, integer division as SQL says").isEqualTo(20L);
        assertThat(ann[4]).as("min").isEqualTo(10L);
        assertThat(ann[5]).as("max").isEqualTo(30L);
    }

    @Test
    void cq031_aRetractionRevisesEveryShapeThatCanBeRevised() {
        registry.register(
                "agg",
                "SELECT user_id, COUNT(*) AS n, SUM(amount) AS total, AVG(amount) AS mean FROM txn "
                        + "GROUP BY TUMBLE(event_time, INTERVAL '1' SECOND), user_id",
                List.of(0),
                Principal.ANONYMOUS);

        push("agg", 1, "ann", 10, 1, 100_000_000L);
        push("agg", 2, "ann", 30, 1, 200_000_000L);
        push("agg", 2, "ann", 30, -1, 200_000_000L);
        push("agg", 3, "cat", 900, 1, 1_500_000_000L);
        advanceTo("agg", 1_500_000_000L);

        List<Object[]> rows = new ViewQuery(views)
                .execute("SELECT user_id, n, total, mean FROM agg")
                .rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[1]).as("the retracted row is not counted").isEqualTo(1L);
        assertThat(rows.get(0)[2]).as("nor summed").isEqualTo(10L);
        assertThat(rows.get(0)[3]).as("and the mean is over what remains").isEqualTo(10L);
    }

    @Test
    void cq032_aGroupWhoseWeightNetsToZeroDoesNotAppear() {
        // The Z-set rule: a key is present while its weights sum positive. One insert and one
        // retraction is not a key with a zero in it.
        registry.register("w", WINDOWED, List.of(0), Principal.ANONYMOUS);

        push("w", 1, "ann", 100, 1, 100_000_000L);
        push("w", 1, "ann", 100, -1, 100_000_000L);
        push("w", 2, "cat", 900, 1, 1_500_000_000L);
        advanceTo("w", 1_500_000_000L);

        assertThat(read("SELECT user_id, total FROM w"))
                .as("ann's window netted to nothing and must not appear as a zero")
                .isEmpty();
    }

    // ==================================================== the stream keeps running

    @Test
    void cq040_aQueryKeepsAnsweringAcrossManyCommits() {
        // Endurance in miniature: the answer must stay right over a long sequence of commits, not
        // just the first. A state leak or a frontier that stops advancing shows up here.
        registry.register("w", WINDOWED, List.of(0), Principal.ANONYMOUS);

        long expected = 0;
        for (long second = 1; second <= 30; second++) {
            push("w", second, "ann", second, 1, second * SECOND + 100_000_000L);
            advanceTo("w", second * SECOND + 500_000_000L);
            if (second > 1) {
                expected = second - 1;
                List<Object[]> rows = new ViewQuery(views)
                        .execute("SELECT user_id, total FROM w")
                        .rows();
                assertThat(rows).as("at second %d", second).hasSize(1);
                assertThat(rows.get(0)[1])
                        .as("the window that just closed, at second %d", second)
                        .isEqualTo(expected);
            }
        }
        assertThat(registry.require("w").state()).isEqualTo(QueryState.RUNNING);
        assertThat(registry.require("w").failure()).isEmpty();
    }

    @Test
    void cq041_twoDifferentQueriesOverOneStreamBothStayRight() {
        registry.register("sums", WINDOWED, List.of(0), Principal.ANONYMOUS);
        registry.register(
                "counts",
                "SELECT user_id, COUNT(*) AS n FROM txn " + "GROUP BY TUMBLE(event_time, INTERVAL '1' SECOND), user_id",
                List.of(0),
                Principal.ANONYMOUS);

        assertThat(registry.size()).as("two questions, two computations").isEqualTo(2);

        for (String name : List.of("sums", "counts")) {
            push(name, 1, "ann", 10, 1, 100_000_000L);
            push(name, 2, "ann", 20, 1, 200_000_000L);
            push(name, 3, "cat", 900, 1, 1_500_000_000L);
            advanceTo(name, 1_500_000_000L);
        }

        assertThat(read("SELECT user_id, total FROM sums")).containsExactly("ann=30");
        assertThat(read("SELECT user_id, n FROM counts")).containsExactly("ann=2");
    }

    @Test
    void cq001_aKeyedAggregateAnswersWhileTheStreamIsStillOpen() {
        // An unwindowed GROUP BY is refused on purpose (PRV-2050): its key space has no bound, so
        // its state grows for ever. A keyed continuous aggregate is therefore a windowed one, and
        // what makes it continuous is that a *later row* closes the window -- not the end of input,
        // which a stream does not have.
        registry.register("totals", WINDOWED, List.of(0), Principal.ANONYMOUS);

        push("totals", 1, "ann", 100, 1, 100_000_000L);
        push("totals", 2, "bob", 250, 1, 200_000_000L);
        push("totals", 3, "ann", 50, 1, 300_000_000L);
        assertThat(read("SELECT user_id, total FROM totals"))
                .as("the second is not over; publishing now would publish a half-assembled total")
                .isEmpty();

        // A row in the next second, and nothing else. No close, no flush, no end of input.
        push("totals", 4, "cat", 900, 1, 1_500_000_000L);
        advanceTo("totals", 1_500_000_000L);

        assertThat(read("SELECT user_id, total FROM totals"))
                .as("the first second's answer, published because time moved on and while more rows are coming")
                .containsExactly("ann=150", "bob=250");
    }

    @Test
    void cq002_aRetractionRevisesAWindowBeforeItCloses() {
        registry.register("totals", WINDOWED, List.of(0), Principal.ANONYMOUS);

        push("totals", 1, "ann", 100, 1, 100_000_000L);
        push("totals", 2, "ann", 50, 1, 200_000_000L);
        // The same row again with weight -1: a correction is a retraction plus an insert, and this
        // is the retraction half arriving on its own, before the window has been published.
        push("totals", 2, "ann", 50, -1, 200_000_000L);

        push("totals", 3, "cat", 900, 1, 1_500_000_000L);
        advanceTo("totals", 1_500_000_000L);

        assertThat(read("SELECT user_id, total FROM totals"))
                .as("100, not 150: an aggregate that ignored the weight would carry the retracted row")
                .containsExactly("ann=100");
    }

    @Test
    void cq003_aGlobalAggregateEmitsWithoutAnEndOfInput() {
        registry.register(
                "overall", "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn", List.of(0), Principal.ANONYMOUS);

        push("overall", 1, "ann", 100, 1);
        assertThat(new ViewQuery(views)
                        .execute("SELECT n, total FROM overall").rows().stream()
                                .map(row -> row[0] + "/" + row[1])
                                .toList())
                .as("a global aggregate that only emitted at end of input would be empty here for ever")
                .containsExactly("1/100");

        push("overall", 2, "bob", 250, 1);
        assertThat(new ViewQuery(views)
                        .execute("SELECT n, total FROM overall").rows().stream()
                                .map(row -> row[0] + "/" + row[1])
                                .toList())
                .as("one row, revised -- not two rows, one per commit")
                .containsExactly("2/350");
    }

    @Test
    void cq004_aFilteredContinuousQueryKeepsFilteringAsRowsArrive() {
        registry.register("big", "SELECT txn_id, amount FROM txn WHERE amount > 100", List.of(0), Principal.ANONYMOUS);

        push("big", 1, "ann", 100, 1);
        assertThat(read("SELECT txn_id, amount FROM big"))
                .as("100 is not > 100, and a continuous filter must hold on the first row as on the thousandth")
                .isEmpty();

        push("big", 2, "bob", 250, 1);
        push("big", 3, "cat", 50, 1);
        assertThat(read("SELECT txn_id, amount FROM big")).containsExactly("2=250");
    }
}
