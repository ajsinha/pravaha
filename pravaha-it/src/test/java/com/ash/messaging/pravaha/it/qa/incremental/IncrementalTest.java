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
package com.ash.messaging.pravaha.it.qa.incremental;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.server.ingest.SourceBinding;
import com.ash.messaging.pravaha.serving.Consistency;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.serving.ViewResult;
import com.ash.messaging.pravaha.serving.ViewSink;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code docs/qa/cases/INCR.md}, executed.
 *
 * <p>The property under test is the one the whole engine rests on: maintaining an answer
 * incrementally must give the same answer as recomputing it from scratch. INCR.md states it as
 * procedure B -- reduce the input to its net form, feed that to a fresh pipeline, and compare --
 * and every expected number below is the result of doing that by hand, with the arithmetic in the
 * comment beside it.
 *
 * <p>Harness H1 of INCR.md: a physical plan, a {@link ServedView} behind a {@link ViewSink}, and an
 * {@link InterpretedPipeline} fed by a {@link BinaryRowWriter} whose {@code weight} is set per row.
 * It is the only way to put a weight other than {@code +1} into an arbitrary operator. One case
 * (INCR-003) goes through the configured server path instead, because the filesystem plugin's
 * {@code op.column} is now a route for a real retraction and that is worth proving end to end.
 *
 * <p>Standing fixture, INCR.md's D1: r1 = (u1, 100, 1s), r2 = (u2, 50, 2s), r3 = (u1, 200, 3s),
 * r4 = (u3, 7, 30s). W1 is TUMBLE 10 SECOND over [0s, 10s), and r4 exists only to push the
 * watermark past its end. The retractions are rho3 = r3 at weight -1 and rho1 = r1 at weight -1.
 */
@Tag("qa")
class IncrementalTest {

    private static final long SECOND = 1_000_000_000L;

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();

    private static final StreamSchema TXN_NULLABLE = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64().withNullable(true))
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();

    /** One arrival: a row and the weight it carries. */
    private record Change(String user, Long amount, long eventTime, long weight) {}

    private static Change at(String user, long amount, long seconds) {
        return new Change(user, amount, seconds * SECOND, 1);
    }

    private static Change retract(Change change) {
        return new Change(change.user(), change.amount(), change.eventTime(), -1);
    }

    private static final Change R1 = at("u1", 100, 1);
    private static final Change R2 = at("u2", 50, 2);
    private static final Change R3 = at("u1", 200, 3);
    private static final Change R4 = at("u3", 7, 30);

    private static final String W1_SUM = "SELECT user_id, SUM(amount) AS total FROM "
            + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY user_id, window_start, window_end";

    // ================================================== a retraction, per aggregate

    @Test
    void incr007_aRetractionInsideAnOpenWindowIsSubtractedFromTheSum() {
        // INCR-007. u1's window holds r1 and r3, then rho3 withdraws r3 before the window closes:
        // 100 + 200 - 200 = 100. u2 is untouched at 50, which is the control that says the
        // retraction was applied to a key rather than to the whole window.
        ServedView view = windowed(W1_SUM, List.of(0), 20 * SECOND, List.of(R1, R2, R3, retract(R3), R4));
        assertThat(value(view, "u1", 1)).isEqualTo(100L);
        assertThat(value(view, "u2", 1)).isEqualTo(50L);

        // C2 of INCR.md's vacuity kit: without the retraction the same rows give 300. A case
        // asserting 100 is only meaningful when the control asserts 300.
        ServedView control = windowed(W1_SUM, List.of(0), 20 * SECOND, List.of(R1, R2, R3, R4));
        assertThat(value(control, "u1", 1)).isEqualTo(300L); // 100 + 200
    }

    @Test
    void incr008And009_countAndSumAgreeWithEachOtherAfterARetraction() {
        // INCR-008 and INCR-009. The pair is what matters: a COUNT that contradicts its own SUM is
        // the shape FINDINGS Q-3 describes, and only asserting both together can see it.
        // 2 - 1 = 1 row, carrying 100 + 200 - 200 = 100.
        ServedView view = windowed(
                "SELECT user_id, SUM(amount) AS total, COUNT(*) AS n FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY user_id, window_start, window_end",
                List.of(0),
                20 * SECOND,
                List.of(R1, R2, R3, retract(R3), R4));
        assertThat(value(view, "u1", 1)).isEqualTo(100L);
        assertThat(value(view, "u1", 2)).isEqualTo(1L);
        assertThat(value(view, "u2", 2)).isEqualTo(1L);
    }

    @Test
    void incr012And013_avgIsTheRetractedSumOverTheRetractedCount() {
        // INCR-012. sums 100 + 200 - 200 = 100, counts 1 + 1 - 1 = 1, so the average is 100 / 1.
        // The control without the retraction is 300 / 2 = 150, so the two are distinguishable.
        ServedView view = windowed(
                "SELECT user_id, AVG(amount) AS mean FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY user_id, window_start, window_end",
                List.of(0),
                20 * SECOND,
                List.of(R1, R2, R3, retract(R3), R4));
        assertThat(value(view, "u1", 1)).isEqualTo(100L);

        // INCR-013: amounts 10, 11, 11 average to 32 / 3 = 10, and retracting one 11 gives
        // 21 / 2 = 10 as well -- the same answer from different sums and counts, which is why the
        // case also asserts the sum and the count underneath it.
        Change a = at("u1", 10, 1);
        Change b = at("u1", 11, 2);
        Change c = at("u1", 11, 3);
        ServedView before = windowed(
                "SELECT user_id, SUM(amount) AS total, COUNT(*) AS n, AVG(amount) AS mean FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY user_id, window_start, window_end",
                List.of(0),
                20 * SECOND,
                List.of(a, b, c, R4));
        assertThat(value(before, "u1", 1)).isEqualTo(32L); // 10 + 11 + 11
        assertThat(value(before, "u1", 2)).isEqualTo(3L);
        assertThat(value(before, "u1", 3)).isEqualTo(10L); // 32 / 3 = 10

        ServedView after = windowed(
                "SELECT user_id, SUM(amount) AS total, COUNT(*) AS n, AVG(amount) AS mean FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY user_id, window_start, window_end",
                List.of(0),
                20 * SECOND,
                List.of(a, b, c, retract(c), R4));
        assertThat(value(after, "u1", 1)).isEqualTo(21L); // 32 - 11
        assertThat(value(after, "u1", 2)).isEqualTo(2L); // 3 - 1
        assertThat(value(after, "u1", 3)).isEqualTo(10L); // 21 / 2 = 10
    }

    @Test
    void incr010And011_aWindowedMinOrMaxRefusesARetractionRatherThanGuessing() {
        // INCR-010 and INCR-011. Subtracting from a MIN is not possible without keeping every
        // value: the batch answer after retracting 200 from {100, 200} is 100, and an incremental
        // MIN holding only the extreme cannot get there. A refusal is right; leaving 100 in place
        // by luck and 200 in place by bad luck is not.
        for (String aggregate : List.of("MIN(amount)", "MAX(amount)")) {
            assertThatThrownBy(() -> windowed(
                            "SELECT user_id, " + aggregate + " AS extreme FROM "
                                    + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                                    + "GROUP BY user_id, window_start, window_end",
                            List.of(0),
                            20 * SECOND,
                            List.of(R1, R3, retract(R3), R4)))
                    .as("%s over a retraction", aggregate)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-3020")
                    .hasMessageContaining("ordered multiset per group");
        }
        // The control: without the retraction both are answerable, which is what makes the
        // refusal above about retraction rather than about MIN.
        ServedView control = windowed(
                "SELECT user_id, MIN(amount) AS lo, MAX(amount) AS hi FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY user_id, window_start, window_end",
                List.of(0),
                20 * SECOND,
                List.of(R1, R3, R4));
        assertThat(value(control, "u1", 1)).isEqualTo(100L);
        assertThat(value(control, "u1", 2)).isEqualTo(200L);
    }

    @Test
    void incr015_aDistinctValueSurvivesUntilItsLastCopyIsRetracted() {
        // INCR-015. COUNT(DISTINCT) has to hold multiplicity, not a set: 'a' arrives twice, and
        // retracting one copy must leave it in the distinct set. Only the second retraction, which
        // takes its multiplicity to zero, removes it.
        Change a1 = at("k", 1, 1);
        Change bb = at("k", 2, 2);
        Change a2 = at("k", 1, 3);
        String sql = "SELECT user_id, COUNT(DISTINCT amount) AS distinct_amounts FROM "
                + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY user_id, window_start, window_end";
        // 'a' at multiplicity 2 - 1 = 1 is still present, so {1, 2} has two distinct values.
        assertThat(value(windowed(sql, List.of(0), 20 * SECOND, List.of(a1, bb, a2, retract(a2), R4)), "k", 1))
                .isEqualTo(2L);
        // A second retraction takes it to 1 + 1 - 1 - 1 = 0 and it leaves the set: {2} alone.
        assertThat(value(
                        windowed(sql, List.of(0), 20 * SECOND, List.of(a1, bb, a2, retract(a2), retract(a1), R4)),
                        "k",
                        1))
                .isEqualTo(1L);
    }

    @Test
    void incr016_countOfAColumnAndSumOverANullAgreeAfterTheNullIsRetracted() {
        // INCR-016. Amounts 100, NULL and 200 in one window: SQL says COUNT(amount) = 2 and
        // SUM = 300. Retracting the NULL row must leave the SUM at 300, because the NULL never
        // contributed to it, and take nothing off the count that was not there.
        Change hundred = at("u1", 100, 1);
        Change missing = new Change("u1", null, 2 * SECOND, 1);
        Change twoHundred = at("u1", 200, 3);
        String sql = "SELECT user_id, COUNT(amount) AS n, SUM(amount) AS total FROM "
                + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY user_id, window_start, window_end";
        ServedView before = windowedNullable(sql, List.of(0), 20 * SECOND, List.of(hundred, missing, twoHundred, R4));
        assertThat(value(before, "u1", 1)).as("COUNT(amount) ignores the NULL").isEqualTo(2L);
        assertThat(before.get("u1").values().orElseThrow()[2]).isEqualTo(300L); // 100 + 200

        ServedView after = windowedNullable(
                sql, List.of(0), 20 * SECOND, List.of(hundred, missing, twoHundred, retract(missing), R4));
        assertThat(value(after, "u1", 1))
                .as("retracting a NULL takes nothing off the count")
                .isEqualTo(2L);
        assertThat(after.get("u1").values().orElseThrow()[2]).isEqualTo(300L);
    }

    // ================================================== the global aggregate

    @Test
    void incr018_aBoundedGlobalAggregateNetsARetractionOut() {
        // INCR-018. Over the whole of D1 plus rho3: 100 + 50 + 200 - 200 + 7 = 157, and the count
        // is 5 - 1 = 4. The control without the retraction is 357 over 5.
        ServedView view =
                global("SELECT SUM(amount) AS total, COUNT(*) AS n FROM txn", List.of(R1, R2, R3, retract(R3), R4));
        assertThat(view.scan()).hasSize(1);
        assertThat(view.scan().get(0)[0]).isEqualTo(157L);
        // Five arrivals carrying weights +1, +1, +1, -1, +1, which sum to 3 -- the net input is
        // r1, r2 and r4. INCR.md writes this as "5 - 1 = 4", counting arrivals rather than net
        // weight; the net form is three rows and 100 + 50 + 7 = 157 agrees with it.
        assertThat(view.scan().get(0)[1]).isEqualTo(3L);

        ServedView control = global("SELECT SUM(amount) AS total, COUNT(*) AS n FROM txn", List.of(R1, R2, R3, R4));
        assertThat(control.scan().get(0)[0]).isEqualTo(357L); // 100 + 50 + 200 + 7
        assertThat(control.scan().get(0)[1]).isEqualTo(4L);
    }

    @Test
    void incr023_aGlobalMinRefusesARetractionWithTheSameCodeAsTheWindowedOne() {
        // INCR-023. Three operators, one code: the message differs between them, which ERRC owns,
        // but PRV-3020 and the reason are the same and that is what a caller acts on.
        assertThatThrownBy(() -> global("SELECT MIN(amount) AS lo FROM txn", List.of(R1, R3, retract(R3))))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3020")
                .hasMessageContaining("ordered multiset per group");
    }

    @Test
    void incr005_aContinuouslyRegisteredGlobalAggregatePublishesItsAnswer(@TempDir Path dir) throws Exception {
        // INCR-005, and the close of FINDINGS I-2. GlobalAggregate emitted only at end of input,
        // and a stream has no end, so `SELECT COUNT(*) FROM txn` registered continuously produced
        // nothing for ever while reporting RUNNING. It publishes on commit now.
        //
        // Two commits, deliberately: the emission runs on the lane's thread and the commit picks
        // it up on the next pass, so a caller may see the previous answer once. That is the
        // documented contract and this is the test that holds it to it.
        Path data = dir.resolve("txn.csv");
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            csv.append("u").append(i).append(',').append(i).append(",0\n");
        }
        Files.writeString(data, csv);

        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn",
                        "filesystem",
                        Map.of("path", data.toString(), "schema", "user_id:STRING,amount:INT64,event_time:TIMESTAMP")));
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN).feedingFrom(feeds)) {
            RegisteredQuery query = registry.register(
                    "n", "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn", List.of(0), Principal.ANONYMOUS);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && query.rowsIn() < 20) {
                Thread.sleep(10);
            }
            assertThat(query.rowsIn()).isEqualTo(20);
            // Commit until it has published rather than guessing how many passes that takes: the
            // emission runs on the lane's thread and the commit picks it up on the next pass.
            List<Object[]> rows = List.of();
            for (int pass = 0; pass < 50 && rows.isEmpty(); pass++) {
                query.commit();
                rows = new ViewQuery(views).execute("SELECT * FROM n").rows();
                if (rows.isEmpty()) {
                    Thread.sleep(20);
                }
            }
            assertThat(rows)
                    .as("a continuous global aggregate must publish, not sit at RUNNING for ever")
                    .hasSize(1);
            assertThat(rows.get(0)[0]).isEqualTo(20L);
            assertThat(rows.get(0)[1]).isEqualTo(210L); // 1 + 2 + ... + 20 = 20 * 21 / 2
        }
    }

    @Test
    void incr003_aConfiguredSourceCanDeliverARetraction(@TempDir Path dir) throws Exception {
        // INCR-003, and the close of FINDINGS C-2. Four of five plugins hard-code weight +1 and
        // the name:TYPE grammar had no operation column, so the Z-set model had no route into a
        // configured deployment at all -- which is why every defect in it survived. The filesystem
        // plugin's op.column is that route, and this is the whole file's premise proved through
        // it rather than through a harness.
        Path data = dir.resolve("ops.csv");
        Files.writeString(data, "ann,100,I\nbob,250,I\ncat,50,I\nbob,250,D\n");

        StreamSchema opSchema = StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("op", Types.string())
                .build();
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn",
                        "filesystem",
                        Map.of(
                                "path", data.toString(),
                                "schema", "user_id:STRING,amount:INT64,op:STRING",
                                "op.column", "op")));
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, opSchema).feedingFrom(feeds)) {
            RegisteredQuery query =
                    registry.register("live", "SELECT user_id, amount FROM txn", List.of(0), Principal.ANONYMOUS);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline && query.rowsIn() < 4) {
                Thread.sleep(10);
            }
            ViewQuery reader = new ViewQuery(views);
            deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline
                    && reader.execute("SELECT * FROM live").size() != 2) {
                Thread.sleep(20);
            }
            // Three inserted, one retracted: 3 - 1 = 2 survive, and which two is the assertion.
            assertThat(reader.execute("SELECT user_id FROM live").rows().stream()
                            .map(r -> r[0])
                            .toList())
                    .containsExactlyInAnyOrder("ann", "cat");
        }
    }

    // ================================================== net zero

    @Test
    void incr004And025_aKeyWhoseWeightNetsToZeroIsNotInTheView() {
        // INCR-004 and INCR-025. An insert and its retraction cancel, so u1 has no rows in the
        // window and no row in the view -- not a row of zeros, which is a different answer that a
        // consumer cannot tell from a real zero.
        assertThat(windowed(W1_SUM, List.of(0), 20 * SECOND, List.of(R1, retract(R1), R4))
                        .get("u1")
                        .found())
                .isFalse();

        // Two keys' worth: r1 and r3 both retracted takes u1's count to 1 + 1 - 1 - 1 = 0, so the
        // view holds only W4's u3 row.
        ServedView view = windowed(W1_SUM, List.of(0), 20 * SECOND, List.of(R1, R3, retract(R1), retract(R3), R4));
        assertThat(view.get("u1").found()).isFalse();
        assertThat(view.scan()).isEmpty();

        // C3 of the vacuity kit: the key must have existed, or "it is gone" passes trivially.
        assertThat(windowed(W1_SUM, List.of(0), 20 * SECOND, List.of(R1, R3, R4))
                        .get("u1")
                        .found())
                .isTrue();
    }

    @Test
    void incr027_nothingIsVisibleUntilTheFrontierMoves() {
        // INCR-027. A retraction applied but not committed must not change what the view serves:
        // a reader between the two sees the number that was true at the last frontier, which is
        // the entire point of having a frontier.
        ServedView view = new ServedView("v", TXN, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L, 0L}, 1, 10 * SECOND);
        view.commit(10 * SECOND);
        assertThat(view.get("u1").found()).isTrue();

        view.applyValues(new Object[] {"u1", 300L, 0L}, -1, 20 * SECOND);
        assertThat(view.get("u1").found()).as("uncommitted, so not yet visible").isTrue();

        view.commit(20 * SECOND);
        assertThat(view.get("u1").found()).isFalse();
        assertThat(view.size()).isZero();
    }

    @Test
    void incr028_aWeightOfZeroChangesNothing() {
        // INCR-028, and the close of half of FINDINGS I-1. applyValues was last-write-wins, so a
        // weight of 0 was treated as an insert: it overwrote a key's values with the zero-weight
        // row's, and created a key that had never existed. A Z-set adds zero and leaves the row
        // alone, which is what a net-zero correction relies on.
        ServedView view = new ServedView("v", TXN, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L, 0L}, 1, 10 * SECOND);
        view.commit(10 * SECOND);

        view.applyValues(new Object[] {"u1", 999L, 0L}, 0, 20 * SECOND);
        view.applyValues(new Object[] {"u2", 5L, 0L}, 0, 20 * SECOND);
        view.commit(20 * SECOND);

        assertThat(view.get("u1").values().orElseThrow()[1])
                .as("a weight of zero must not overwrite a key's values")
                .isEqualTo(300L);
        assertThat(view.get("u2").found()).as("nor create one out of nothing").isFalse();
        assertThat(view.size()).isEqualTo(1);
    }

    @Test
    void incr029_weightsAreSummedSoAPartialRetractionLeavesTheRow() {
        // INCR-029, and the other half of FINDINGS I-1. A single -1 against a key of accumulated
        // weight 2 used to remove it outright, because the weight was never summed. 1 + 1 - 1 = 1
        // is still present, and the second -1 is what removes it.
        ServedView view = new ServedView("v", TXN, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L, 0L}, 1, SECOND);
        view.applyValues(new Object[] {"u1", 300L, 0L}, 1, SECOND);
        view.commit(SECOND);
        assertThat(view.get("u1").found()).isTrue();

        view.applyValues(new Object[] {"u1", 300L, 0L}, -1, 2 * SECOND);
        view.commit(2 * SECOND);
        assertThat(view.get("u1").found())
                .as("net weight 1 + 1 - 1 = 1, so the row stands")
                .isTrue();

        view.applyValues(new Object[] {"u1", 300L, 0L}, -1, 3 * SECOND);
        view.commit(3 * SECOND);
        assertThat(view.get("u1").found()).as("and 1 - 1 = 0 removes it").isFalse();
    }

    @Test
    void incr030_aRetractionAndItsReplacementCommuteInsideOneBatch() {
        // INCR-030. emitWindow emits the -1 before the +1, so the order the engine produces is
        // A -- and a view that got B wrong would be latently broken, waiting for an operator that
        // emits the other way round. Both orders must net to the new row present.
        ServedView a = new ServedView("a", TXN, List.of(0), 10_000);
        a.applyValues(new Object[] {"u1", 300L, 0L}, 1, SECOND);
        a.commit(SECOND);
        a.applyValues(new Object[] {"u1", 300L, 0L}, -1, 2 * SECOND);
        a.applyValues(new Object[] {"u1", 100L, 0L}, 1, 2 * SECOND);
        a.commit(2 * SECOND);
        assertThat(a.get("u1").values().orElseThrow()[1]).isEqualTo(100L);
    }

    @Test
    @Disabled("PRV-INCR defect 2 (FINDINGS I-1, remainder): a keyed ServedView stores whichever "
            + "value tuple was written last, sign ignored, so an update applied as +new then -old "
            + "leaves the OLD values in place with a positive weight. Applied as -old then +new it is "
            + "correct. emitWindow happens to emit -1 before +1, which is the only reason this is "
            + "latent rather than a live wrong answer.")
    void incr030b_theOtherOrderOfTheSameUpdateGivesTheSameRow() {
        // INCR-030, order B. Both orders have the same net input -- the old row withdrawn, the new
        // row present -- so both must leave the view holding 100. Order B leaves it holding 300.
        ServedView b = new ServedView("b", TXN, List.of(0), 10_000);
        b.applyValues(new Object[] {"u1", 300L, 0L}, 1, SECOND);
        b.commit(SECOND);
        b.applyValues(new Object[] {"u1", 100L, 0L}, 1, 2 * SECOND);
        b.applyValues(new Object[] {"u1", 300L, 0L}, -1, 2 * SECOND);
        b.commit(2 * SECOND);
        assertThat(b.get("u1").values().orElseThrow()[1])
                .as("the same net input in the other order must give the same row")
                .isEqualTo(100L);
    }

    // ================================================== order, and weights beyond one

    @Test
    void incr032_theAnswerDoesNotDependOnWhetherTheRetractionArrivedFirst() {
        // INCR-032. A Z-set is a multiset with signed multiplicities and addition is commutative,
        // so feeding rho3 before r3 must give the same window as feeding it after. An operator
        // that clamped a count at zero on the way past would break this and nothing else.
        ServedView forwards = windowed(
                "SELECT user_id, SUM(amount) AS total, COUNT(*) AS n FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY user_id, window_start, window_end",
                List.of(0),
                20 * SECOND,
                List.of(R1, R3, retract(R3), R4));
        ServedView backwards = windowed(
                "SELECT user_id, SUM(amount) AS total, COUNT(*) AS n FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY user_id, window_start, window_end",
                List.of(0),
                20 * SECOND,
                List.of(retract(R3), R1, R3, R4));
        // 100 + 200 - 200 = 100 either way, over 1 + 1 - 1 = 1 row.
        assertThat(value(forwards, "u1", 1)).isEqualTo(100L);
        assertThat(value(backwards, "u1", 1)).isEqualTo(100L);
        assertThat(value(forwards, "u1", 2)).isEqualTo(1L);
        assertThat(value(backwards, "u1", 2)).isEqualTo(1L);
    }

    @Test
    void incr039And040_aWeightGreaterThanOneIsThatManyCopies() {
        // INCR-039 and INCR-040. One arrival at weight 3 must equal three arrivals at weight 1:
        // 100 * 3 = 300 over a count of 3. Then -2 against it leaves 3 - 2 = 1 copy carrying
        // 300 - 200 = 100, which is the net form procedure B would have fed.
        Change three = new Change("u1", 100L, SECOND, 3);
        String sql = "SELECT user_id, SUM(amount) AS total, COUNT(*) AS n FROM "
                + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY user_id, window_start, window_end";
        ServedView tripled = windowed(sql, List.of(0), 20 * SECOND, List.of(three, R4));
        assertThat(value(tripled, "u1", 1)).isEqualTo(300L);
        assertThat(value(tripled, "u1", 2)).isEqualTo(3L);

        // The batch equivalent, fed as three separate +1 arrivals.
        ServedView expanded = windowed(
                sql, List.of(0), 20 * SECOND, List.of(at("u1", 100, 1), at("u1", 100, 1), at("u1", 100, 1), R4));
        assertThat(value(expanded, "u1", 1)).isEqualTo(300L);
        assertThat(value(expanded, "u1", 2)).isEqualTo(3L);

        ServedView reduced =
                windowed(sql, List.of(0), 20 * SECOND, List.of(three, new Change("u1", 100L, SECOND, -2), R4));
        assertThat(value(reduced, "u1", 1)).isEqualTo(100L); // 300 - 200
        assertThat(value(reduced, "u1", 2)).isEqualTo(1L); // 3 - 2
    }

    @Test
    void incr035_retractingMoreCopiesThanExistLeavesANegativeGroup() {
        // INCR-035. Three inserts and three retractions net to zero and the key disappears; a
        // fourth retraction takes the count to -1 and the sum to -200, and because the count is
        // not zero the group is emitted -- so the view serves a row that no batch answer could
        // ever produce. Pinned as what the engine does, because a consumer needs to know it can
        // happen at all.
        String sql = "SELECT user_id, SUM(amount) AS total, COUNT(*) AS n FROM "
                + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY user_id, window_start, window_end";
        List<Change> balanced = List.of(R3, R3, R3, retract(R3), retract(R3), retract(R3), R4);
        assertThat(windowed(sql, List.of(0), 20 * SECOND, balanced).get("u1").found())
                .as("3 - 3 = 0, so no row")
                .isFalse();

        List<Change> overdrawn = new ArrayList<>(balanced.subList(0, 6));
        overdrawn.add(retract(R3));
        overdrawn.add(R4);
        ServedView view = windowed(sql, List.of(0), 20 * SECOND, overdrawn);
        assertThat(value(view, "u1", 1)).as("0 - 200 = -200").isEqualTo(-200L);
        assertThat(value(view, "u1", 2)).as("0 - 1 = -1").isEqualTo(-1L);
    }

    @Test
    void incr033_aRetractionWithNoMatchingInsertProducesANegativeAnswer() {
        // INCR-033. A -1 for a row that was never inserted leaves an accumulator at count -1, and
        // because that is not zero the window emits it and the view inserts it. There is no batch
        // answer for this input at all -- the case's own words -- so the test records the engine's
        // behaviour rather than claiming it is right.
        ServedView view = windowed(
                "SELECT user_id, SUM(amount) AS total, COUNT(*) AS n FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY user_id, window_start, window_end",
                List.of(0),
                20 * SECOND,
                List.of(new Change("u9", 42L, 4 * SECOND, -1), R4));
        assertThat(value(view, "u9", 1)).isEqualTo(-42L);
        assertThat(value(view, "u9", 2)).isEqualTo(-1L);
    }

    // ================================================== an update, and composition

    @Test
    void incr046_anUpdateBeforeTheWindowClosesIsOneRowWithTheNewNumber() {
        // INCR-046. r3 is retracted and replaced by a 250 in the same open window, so the answer
        // is 100 + 200 - 200 + 250 = 350 in exactly one row -- not two rows, and not 550.
        ServedView view = windowed(W1_SUM, List.of(0), 20 * SECOND, List.of(R1, R3, retract(R3), at("u1", 250, 3), R4));
        assertThat(value(view, "u1", 1)).isEqualTo(350L);
        assertThat(view.scan().stream().filter(r -> "u1".equals(r[0])).count())
                .as("one row per key, not one per correction")
                .isEqualTo(1);
    }

    @Test
    void incr062_aFilterPassesTheRetractionThroughExactlyWhenItPassedTheInsert() {
        // INCR-062. amount > 150 admits r3 and its retraction and admits neither r1 nor rho1, so
        // the view ends empty -- and it ends empty by cancellation rather than by the filter
        // having dropped one side of a pair, which is the failure this shape would produce.
        String sql = "SELECT user_id, amount FROM txn WHERE amount > 150";
        assertThat(unwindowed(sql, List.of(0), List.of(R3)).get("u1").found()).isTrue();
        assertThat(unwindowed(sql, List.of(0), List.of(R3, retract(R3)))
                        .get("u1")
                        .found())
                .isFalse();
        // r1 is 100, below the threshold, so neither it nor its retraction reaches the view.
        assertThat(unwindowed(sql, List.of(0), List.of(R1, retract(R1))).size()).isZero();
        assertThat(unwindowed(sql, List.of(0), List.of(R1)).size()).isZero();
    }

    @Test
    void incr063_aProjectionCarriesTheWeightThroughToTheView() {
        // INCR-063. Projecting the amount away makes r1 and r3 the same output row, so the view's
        // key accumulates weight 1 + 1 and the single retraction of r3 leaves 1 -- the row stands.
        // An engine that projected and then deduplicated would lose that and remove the key.
        String sql = "SELECT user_id FROM txn";
        ServedView view = unwindowed(sql, List.of(0), List.of(R1, R3, retract(R3)));
        assertThat(view.get("u1").found())
                .as("1 + 1 - 1 = 1 after the amount is projected away")
                .isTrue();
        assertThat(unwindowed(sql, List.of(0), List.of(R1, R3, retract(R3), retract(R1)))
                        .get("u1")
                        .found())
                .as("and 1 + 1 - 1 - 1 = 0 removes it")
                .isFalse();
    }

    @Test
    void incr064_selectDistinctOverAStreamIsRefused() {
        // INCR-064. SELECT DISTINCT is a GROUP BY over an unbounded key space wearing different
        // syntax, and state that grows with distinct values and never shrinks is how an
        // incremental engine dies weeks after deployment. Refused with the same code as the
        // GROUP BY it is.
        assertThatThrownBy(() -> plan("SELECT DISTINCT user_id FROM txn"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2050");
    }

    // ================================================== the view's own contract

    @Test
    void incr066_aConsistentReadAndALatestReadDisagreeByExactlyTheUncommittedChange() {
        // INCR-066. A dashboard would rather have the newest number and be told it is provisional;
        // a reconciliation would rather wait. Both read the same view, and the difference between
        // the two answers is the staleness the consistent read reports: 20s - 10s = 10s.
        ServedView view = new ServedView("v", TXN, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L, 0L}, 1, 10 * SECOND);
        view.commit(10 * SECOND);
        view.applyValues(new Object[] {"u1", 300L, 0L}, -1, 20 * SECOND);

        ViewResult consistent = view.get(new Consistency.Consistent(), Duration.ZERO, "u1");
        assertThat(consistent.found()).isTrue();
        assertThat(consistent.values().orElseThrow()[1]).isEqualTo(300L);
        assertThat(consistent.frontierComplete()).isTrue();

        ViewResult latest = view.get(new Consistency.Latest(), Duration.ZERO, "u1");
        assertThat(latest.found())
                .as("the retraction is already applied, just not committed")
                .isFalse();
        assertThat(latest.frontierComplete())
                .as("and the reader is told the answer is provisional")
                .isFalse();
    }

    @Test
    void incr067_retentionEvictsInEventTimeAndSaysHowMuchItRemoved() {
        // INCR-067. A thirty-second horizon against a frontier at 100s drops the key committed at
        // 1s: 100 - 30 = 70, and 1 is below 70. The count is what makes a shrinking view
        // distinguishable from a losing one.
        ServedView view = new ServedView("v", TXN, List.of(0), 10_000, Retention.ofAge(Duration.ofSeconds(30)));
        view.applyValues(new Object[] {"u1", 300L, SECOND}, 1, SECOND);
        view.commit(SECOND);
        assertThat(view.get("u1").found()).isTrue();

        view.applyValues(new Object[] {"u2", 50L, 100 * SECOND}, 1, 100 * SECOND);
        view.commit(100 * SECOND);
        assertThat(view.evicted()).isEqualTo(1);
        assertThat(view.get("u1").found()).isFalse();
        assertThat(view.size()).isEqualTo(1);
    }

    @Test
    void incr068_aViewPastItsKeyCeilingRefusesRatherThanGrowing() {
        // INCR-068. The ceiling is what stops an unbounded key space taking the node down, and it
        // has to refuse loudly: a view that silently dropped the hundred-and-first key would serve
        // an answer that is wrong in a way nothing can detect.
        ServedView view = new ServedView("v", TXN, List.of(0), 100, Retention.forever());
        for (int i = 0; i < 100; i++) {
            view.applyValues(new Object[] {"k" + i, (long) i, 0L}, 1, SECOND);
        }
        view.commit(SECOND);
        assertThat(view.size()).isEqualTo(100);

        view.applyValues(new Object[] {"k100", 100L, 0L}, 1, 2 * SECOND);
        assertThatThrownBy(() -> view.commit(2 * SECOND))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4022")
                .hasMessageContaining("101")
                .hasMessageContaining("100");
    }

    @Test
    void incr069_aFrontierCannotGoBackwards() {
        // INCR-069. A frontier that regressed would let a reader see an answer, then an older
        // answer, then the newer one again -- and no consistency mode could describe that. The
        // refusal carries no PRV code, which ERRC owns; the refusal itself is what matters here.
        ServedView view = new ServedView("v", TXN, List.of(0), 10_000);
        view.commit(100 * SECOND);
        assertThatThrownBy(() -> view.commit(50 * SECOND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontier");
        // Committing the same frontier again is not a regression and must be allowed, because a
        // commit with nothing new in it is an ordinary event.
        view.commit(100 * SECOND);
    }

    @Test
    @Disabled("PRV-INCR defect 1 (FINDINGS I-5): a windowed key whose weight nets to zero after the "
            + "window has already been published is never withdrawn. emitWindow retracts a key whose "
            + "values changed and silently forgets one that has disappeared from state.fire(), so the "
            + "stale row stands for ever and no counter records it.")
    void incr026_aWindowKeyThatNetsToZeroAfterPublishingIsWithdrawn() {
        // INCR-026. With allowed lateness the window can be corrected after it fired. r1 and r3
        // are published as 300, then both are retracted: the correct answer is that u1 leaves the
        // view, because the net input for that window is empty. Instead fire() returns nothing for
        // u1, no -1 is emitted, and the view goes on serving 300.
        ServedView view = windowedWithLateness(
                W1_SUM,
                List.of(0),
                30 * SECOND,
                List.of(R1, R3, R4),
                List.of(retract(R1), retract(R3)),
                20 * SECOND,
                21 * SECOND);
        assertThat(view.get("u1").found())
                .as("100 + 200 - 100 - 200 = 0, so the key must be withdrawn")
                .isFalse();
    }

    // ------------------------------------------------------------------ harness

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(TXN).plan(sql));
    }

    private static Object value(ServedView view, String key, int ordinal) {
        return view.get(key).values().orElseThrow()[ordinal];
    }

    /** H1 over a windowed plan: feed, advance the watermark, commit, and hand back the view. */
    private static ServedView windowed(String sql, List<Integer> keys, long watermark, List<Change> changes) {
        return feed(TXN, sql, keys, watermark, changes, false);
    }

    private static ServedView windowedNullable(String sql, List<Integer> keys, long watermark, List<Change> changes) {
        return feed(TXN_NULLABLE, sql, keys, watermark, changes, false);
    }

    /** H1 over a bounded plan: feed, finish, commit. Used for the global aggregate. */
    private static ServedView global(String sql, List<Change> changes) {
        return feed(TXN, sql, List.of(0), Long.MIN_VALUE, changes, true);
    }

    /** H1 over a plan with no aggregate at all: every row flows through and the view keys it. */
    private static ServedView unwindowed(String sql, List<Integer> keys, List<Change> changes) {
        return feed(TXN, sql, keys, Long.MIN_VALUE, changes, true);
    }

    private static ServedView feed(
            StreamSchema schema, String sql, List<Integer> keys, long watermark, List<Change> changes, boolean finish) {
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema).plan(sql));
        ServedView view = new ServedView("v", plan.outputSchema(), keys, 100_000);
        ViewSink sink = new ViewSink(view, plan.outputSchema());
        RowLayout layout = RowLayout.of(schema);
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, (RowOutput) sink::begin)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView reader = new BinaryRowView(layout);
            long sequence = 0;
            for (Change change : changes) {
                long handle = arena.allocate(layout.rowSize(256));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                writer.setString(0, change.user());
                if (change.amount() == null) {
                    writer.setNull(1);
                } else {
                    writer.setLong(1, change.amount());
                }
                writer.setLong(2, change.eventTime())
                        .weight(change.weight())
                        .eventTimestampNanos(change.eventTime())
                        .sequence(++sequence)
                        .commit();
                arena.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(reader.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            if (watermark != Long.MIN_VALUE) {
                pipeline.advanceWatermark(watermark);
            }
            if (finish) {
                pipeline.finish();
            }
        }
        sink.commit(sink.appliedFrontier());
        return view;
    }

    /**
     * Two rounds under an explicit allowed lateness: publish, then correct.
     *
     * <p>SQL has nowhere to say allowed lateness -- FINDINGS T-3 -- so the operator is rebuilt with
     * it, which is the only way the correction path is reachable at all.
     */
    private static ServedView windowedWithLateness(
            String sql,
            List<Integer> keys,
            long latenessNanos,
            List<Change> first,
            List<Change> second,
            long firstWatermark,
            long secondWatermark) {
        PhysicalOperator plan = withLateness(
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(TXN).plan(sql)), latenessNanos);
        ServedView view = new ServedView("v", plan.outputSchema(), keys, 100_000);
        ViewSink sink = new ViewSink(view, plan.outputSchema());
        RowLayout layout = RowLayout.of(TXN);
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, (RowOutput) sink::begin)) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView reader = new BinaryRowView(layout);
            long sequence = 0;
            for (List<Change> round : List.of(first, second)) {
                for (Change change : round) {
                    long handle = arena.allocate(layout.rowSize(256));
                    writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                    writer.setString(0, change.user())
                            .setLong(1, change.amount())
                            .setLong(2, change.eventTime())
                            .weight(change.weight())
                            .eventTimestampNanos(change.eventTime())
                            .sequence(++sequence)
                            .commit();
                    arena.trimTo(handle, writer.sizeSoFar());
                    pipeline.accept(reader.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
                }
                pipeline.advanceWatermark(round == first ? firstWatermark : secondWatermark);
                sink.commit(sink.appliedFrontier());
            }
        }
        sink.commit(sink.appliedFrontier());
        return view;
    }

    private static PhysicalOperator withLateness(PhysicalOperator plan, long allowedLatenessNanos) {
        if (plan instanceof com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator aggregate) {
            return new com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator(
                    aggregate.input(),
                    aggregate.outputSchema(),
                    aggregate.spec(),
                    aggregate.groupKeys(),
                    aggregate.aggregates(),
                    aggregate.windowStartOrdinal(),
                    aggregate.windowEndOrdinal(),
                    aggregate.maxSlices(),
                    allowedLatenessNanos);
        }
        if (plan instanceof com.ash.messaging.pravaha.runtime.plan.ProjectOperator project) {
            return new com.ash.messaging.pravaha.runtime.plan.ProjectOperator(
                    withLateness(project.input(), allowedLatenessNanos),
                    project.outputSchema(),
                    project.sourceOrdinals());
        }
        throw new IllegalStateException("no windowed aggregate under " + plan.label());
    }
}
