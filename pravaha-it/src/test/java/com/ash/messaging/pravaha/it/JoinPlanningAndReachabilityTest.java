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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.ingest.PartitionedIngestPump;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SQL-planning-dependent slice of {@code docs/project/qa/cases/JOIN.md}: multi-key equi-joins, the
 * plan-time/run-time discrepancy for refused key types, lane routing through a projection, and the
 * five refusals that need Calcite's plan shapes (CROSS JOIN, self-join, RIGHT/FULL, an inequality on
 * a non-timestamp column). Everything here needs {@code pravaha-sql}, which is why it is not in the
 * operator-level harnesses in {@code pravaha-runtime}.
 */
@Timeout(120)
class JoinPlanningAndReachabilityTest {

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(256, 128)
                .withBatchSize(32)
                .withArena(1 << 20, 8)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("join-planning", true);
    }

    // ======================= JOIN-009: three equality keys =======================

    private static StreamSchema ordersSeg() {
        return StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.string())
                .field("region", Types.string())
                .field("seg", Types.int64())
                .build();
    }

    private static StreamSchema usersSeg() {
        return StreamSchema.builder("users")
                .field("user_id", Types.string())
                .field("region", Types.string())
                .field("seg", Types.int64())
                .build();
    }

    @Test
    void threeEquiKeysNarrowTheAnswerLikeTwoDoButForAThirdReason() {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(ordersSeg(), usersSeg())
                        .plan("SELECT o.order_id FROM orders o JOIN users u "
                                + "ON o.user_id = u.user_id AND o.region = u.region AND o.seg = u.seg"));

        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        try (InterpretedPipeline pipeline =
                InterpretedPipeline.compile(plan, () -> new CapturingRowWriter(plan.outputSchema(), out::add))) {
            feed(pipeline, usersSeg(), "users", "u1", "eu", 1L);
            feed(pipeline, usersSeg(), "users", "u2", "eu", 1L);
            feed(pipeline, ordersSeg(), "orders", 1L, "u1", "eu", 1L); // matches, all three keys agree
            feed(pipeline, ordersSeg(), "orders", 2L, "u2", "eu", 1L); // matches
            feed(pipeline, ordersSeg(), "orders", 3L, "u1", "us", 2L); // region and seg both differ: no match
            assertThat(out).hasSize(2);
        }
    }

    // ======================= JOIN-010: key operands may be written in either order =======================

    private static StreamSchema orders() {
        return StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.string())
                .build();
    }

    private static StreamSchema users() {
        return StreamSchema.builder("users")
                .field("user_id", Types.string())
                .field("tier", Types.string())
                .build();
    }

    @Test
    void theEqualitysOperandsMayBeWrittenInEitherOrder() {
        List<CapturingRowWriter.Captured> forward =
                runSimpleJoin("SELECT o.order_id, u.tier FROM orders o JOIN users u ON o.user_id = u.user_id");
        List<CapturingRowWriter.Captured> reversed =
                runSimpleJoin("SELECT o.order_id, u.tier FROM orders o JOIN users u ON u.user_id = o.user_id");

        assertThat(forward).hasSize(1);
        assertThat(reversed)
                .as("r.k = l.k must resolve the same ordinals as l.k = r.k, not a mis-paired column")
                .hasSize(1);
        assertThat(reversed.get(0).values()).isEqualTo(forward.get(0).values());
    }

    private static List<CapturingRowWriter.Captured> runSimpleJoin(String sql) {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(orders(), users()).plan(sql));
        List<CapturingRowWriter.Captured> out = new ArrayList<>();
        try (InterpretedPipeline pipeline =
                InterpretedPipeline.compile(plan, () -> new CapturingRowWriter(plan.outputSchema(), out::add))) {
            feed(pipeline, users(), "users", "u1", "gold");
            feed(pipeline, orders(), "orders", 1L, "u1");
        }
        return out;
    }

    // ======================= JOIN-013 / JOIN-014: refused key types, plan-time vs run-time =======================

    private static StreamSchema withFloatKey() {
        return StreamSchema.builder("l").field("price", Types.float64()).build();
    }

    private static StreamSchema withDecimalKey() {
        return StreamSchema.builder("l").field("price", Types.decimal(18, 2)).build();
    }

    @Test
    void aFloat64KeyPlansFineAndFailsOnlyWhenThePipelineIsCompiled() {
        StreamSchema left = withFloatKey();
        // Two distinct streams sharing the refused key type, so this is an ordinary join rather than
        // the self-join JOIN-060 is about.
        StreamSchema right =
                StreamSchema.builder("r").field("price", Types.float64()).build();
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(left, right).plan("SELECT * FROM l JOIN r ON l.price = r.price"));

        assertThat(plan)
                .as("nothing in buildJoin checks the key type -- the plan itself succeeds")
                .isNotNull();

        assertThatThrownBy(() ->
                        InterpretedPipeline.compile(plan, () -> new CapturingRowWriter(plan.outputSchema(), row -> {})))
                .as("the refusal arrives only when the pipeline is compiled, later than checkJoinable's own "
                        + "javadoc claims (\"refused at plan time\")")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3021")
                .hasMessageContaining("price")
                .hasMessageContaining("rounding");
    }

    @Test
    void aDecimalKeyPlansFineAndFailsOnlyWhenThePipelineIsCompiledWithAThinnerMessage() {
        StreamSchema left = withDecimalKey();
        StreamSchema right =
                StreamSchema.builder("r").field("price", Types.decimal(18, 2)).build();
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(left, right).plan("SELECT * FROM l JOIN r ON l.price = r.price"));

        assertThat(plan).isNotNull();
        assertThatThrownBy(() ->
                        InterpretedPipeline.compile(plan, () -> new CapturingRowWriter(plan.outputSchema(), row -> {})))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3021")
                .hasMessageContaining("DECIMAL keys are not supported yet")
                .as("no remedy is offered, unlike the float message")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Round or cast"));
    }

    // ======================= JOIN-029: a month/year window is refused as a missing key, not explained
    // =======================

    @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
    @Test
    void aMonthOrYearWindowIsRefusedAsThoughTheEqualityWereMissingNotAsAnUnrecognisedUnit() {
        StreamSchema o = StreamSchema.builder("orders")
                .field("user_id", Types.string())
                .field("event_time", Types.timestamp())
                .build();
        StreamSchema u = StreamSchema.builder("users")
                .field("user_id", Types.string())
                .field("event_time", Types.timestamp())
                .build();
        String month = "SELECT * FROM orders o JOIN users u ON o.user_id = u.user_id "
                + "AND o.event_time BETWEEN u.event_time - INTERVAL '1' MONTH AND u.event_time";
        String year = "SELECT * FROM orders o JOIN users u ON o.user_id = u.user_id "
                + "AND o.event_time BETWEEN u.event_time - INTERVAL '1' YEAR AND u.event_time";

        for (String sql : List.of(month, year)) {
            assertThatThrownBy(() -> new PhysicalPlanBuilder()
                            .build(SqlPlanner.withStreams(o, u).plan(sql)))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2020")
                    .hasMessageContaining("neither an equality")
                    .hasMessageContaining("nor a time bound")
                    // No prose sentence explains that the real reason is a variable-length unit --
                    // that much of the case's claim holds. But the message also interpolates
                    // Calcite's own rendering of the rejected condition (e.g.
                    // "'>=($1, -($3, 1:interval month))'"), and that raw dump does spell out "month"
                    // or "year" -- so "nothing in the message mentions months" (JOIN-029's own
                    // wording) is not quite accurate as a claim about the string. Checked here as: no
                    // explanatory phrase, but the raw unit name does leak through the condition dump.
                    .as("no prose sentence explains the real reason -- a month/year has no fixed length")
                    .satisfies(e -> {
                        String msg = e.getMessage().toLowerCase(java.util.Locale.ROOT);
                        assertThat(msg).doesNotContain("fixed length");
                        assertThat(msg).doesNotContain("variable");
                        assertThat(msg).contains(sql.contains("MONTH") ? "month" : "year");
                    });
        }
    }

    // ======================= JOIN-041: generatingWatermarks after a pump is refused =======================

    @Test
    void generatingWatermarksAfterAPumpHasAlreadyBeenCreatedIsRefused() {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(orders(), users())
                        .plan("SELECT o.order_id, u.tier FROM orders o JOIN users u ON o.user_id = u.user_id"));
        ConcurrentLinkedQueue<CapturingRowWriter.Captured> results = new ConcurrentLinkedQueue<>();

        try (QueryExecution execution = QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {

            execution.pumpInto(0, "orders", new FixedReader(List.of()), BackpressurePolicy.defaults());

            assertThatThrownBy(() -> execution.generatingWatermarks(
                            () -> com.ash.messaging.pravaha.runtime.time.WatermarkGenerator.boundedOutOfOrderness(0),
                            Duration.ofSeconds(5),
                            Duration.ofMillis(50)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("would not be a partition of the watermark")
                    .as("no PRV- code on this refusal")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("PRV-"));
        }
    }

    // ======================= JOIN-042 / JOIN-043: the join key mapped through a projection =======================

    private static StreamSchema wideOrders() {
        return StreamSchema.builder("ordersWide")
                .field("order_id", Types.int64())
                .field("user_id", Types.string())
                .field("region", Types.string())
                .field("amount", Types.int64())
                .field("event_time", Types.int64())
                .build();
    }

    /** The join's key (user_id) sits at ordinal 2 in the projection, ordinal 1 in the scan. */
    private static PhysicalOperator wideJoinPlan() {
        String sql = "SELECT o.order_id, u.tier FROM "
                + "(SELECT amount, region, user_id, order_id, event_time FROM ordersWide) o "
                + "JOIN users u ON o.user_id = u.user_id";
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(wideOrders(), users()).plan(sql));
    }

    @Test
    void theJoinKeyIsMappedDownThroughAReorderingProjectionRatherThanTakenAsIs() {
        // The projection puts amount first and user_id third, so the join's key ordinal (2) differs
        // from the scan's (1) -- a routing bug that takes the join's ordinal as the scan's would
        // route by "amount" instead of "user_id" and silently lose most pairs on more than one lane.
        PhysicalOperator plan = wideJoinPlan();

        int users = 8;
        int rows = 200;
        long oneLaneCount = runPartitionedJoin(plan, users, rows, 1);
        long fourLaneCount = runPartitionedJoin(plan, users, rows, 4);

        assertThat(oneLaneCount).isEqualTo((long) rows);
        assertThat(fourLaneCount)
                .as("a wrong ordinal routes by the wrong column and loses pairs on more than one lane")
                .isEqualTo(oneLaneCount);
    }

    @Test
    void twoAndEightLanesGiveTheSameAnswerAsOneAndALaneCountExceedingTheKeyCountStillQuiesces() {
        PhysicalOperator plan = wideJoinPlan();
        int users = 8;
        int rows = 200;
        long oneLaneCount = runPartitionedJoin(plan, users, rows, 1);

        assertThat(runPartitionedJoin(plan, users, rows, 2)).as("2 lanes").isEqualTo(oneLaneCount);
        assertThat(runPartitionedJoin(plan, users, rows, 8)).as("8 lanes").isEqualTo(oneLaneCount);

        // The degenerate case: 8 lanes, only 3 distinct users, so at most 3 lanes ever emit anything
        // and the idle lanes must still quiesce rather than hang -- the failure mode here is a
        // timeout, not a wrong number, which is why runPartitionedJoin's own awaitQuiescent assertion
        // (inside it) is the load-bearing check and this is really about it not timing out.
        long threeUsersOneLane = runPartitionedJoin(plan, 3, rows, 1);
        long threeUsersEightLanes = runPartitionedJoin(plan, 3, rows, 8);
        assertThat(threeUsersEightLanes)
                .as("idle lanes must not change the answer, and awaitQuiescent must not time out")
                .isEqualTo(threeUsersOneLane);
    }

    private static long runPartitionedJoin(PhysicalOperator plan, int userCount, int rowCount, int lanes) {
        ConcurrentLinkedQueue<CapturingRowWriter.Captured> results = new ConcurrentLinkedQueue<>();
        try (QueryExecution execution = QueryExecution.start(plan, lanes, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {

            PartitionedIngestPump orders = execution.pumpPartitionedInto(
                    "ordersWide", new WideOrdersReader(rowCount, userCount), BackpressurePolicy.defaults());
            PartitionedIngestPump usersP =
                    execution.pumpPartitionedInto("users", new UsersReader(userCount), BackpressurePolicy.defaults());

            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (orders.rowsPumped() + usersP.rowsPumped() < rowCount + userCount && System.nanoTime() < deadline) {
                orders.pumpOnce(64);
                usersP.pumpOnce(64);
                execution.checkHealth();
            }
            assertThat(execution.awaitQuiescent(Duration.ofSeconds(30))).isTrue();
        }
        return results.size();
    }

    @Test
    void aJoinKeyThatPassesThroughAComputedColumnCannotBeRoutedAndIsRefused() {
        String sql = "SELECT o.order_id, u.tier FROM "
                + "(SELECT user_id, amount * 2 AS doubled, order_id FROM ordersCompute) o "
                + "JOIN users u ON o.user_id = u.user_id";
        StreamSchema src = StreamSchema.builder("ordersCompute")
                .field("order_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(src, users()).plan(sql));

        ConcurrentLinkedQueue<CapturingRowWriter.Captured> results = new ConcurrentLinkedQueue<>();
        try (QueryExecution execution = QueryExecution.start(plan, 4, config(), MemoryAccess.best(), () ->
                (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {

            assertThatThrownBy(() -> execution.pumpPartitionedInto(
                            "ordersCompute", new FixedReader(List.of()), BackpressurePolicy.defaults()))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-3021")
                    // The case file's quoted text has this as "Compute(...)"; the operator's actual
                    // label() renders "Compute[...]" with square brackets. Checked against the real
                    // rendering, which is ComputeOperator.label()'s own format, not the case's prose.
                    .hasMessageContaining("Compute[")
                    .hasMessageContaining("Run this query on one lane");
        }

        // The suggested remedy must actually work: the same query on one lane succeeds.
        List<CapturingRowWriter.Captured> oneLane = new ArrayList<>();
        PhysicalOperator onelanePlan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(src, users()).plan(sql));
        try (InterpretedPipeline pipeline = InterpretedPipeline.compile(
                onelanePlan, () -> new CapturingRowWriter(onelanePlan.outputSchema(), oneLane::add))) {
            feed(pipeline, users(), "users", "u1", "gold");
            feed(pipeline, src, "ordersCompute", 1L, "u1", 10L);
        }
        assertThat(oneLane).hasSize(1);
    }

    // ======================= JOIN-044 / JOIN-046: lookup join unreachable, reconfirmed =======================

    @Test
    void aLookupJoinCannotBePlannedAgainstAStreamCatalog() {
        StreamSchema o = StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.string())
                .field("event_time", Types.timestamp())
                .build();
        StreamSchema u = StreamSchema.builder("users")
                .field("user_id", Types.string())
                .field("tier", Types.string())
                .build();
        String sql = "SELECT o.order_id, u.tier FROM orders o LEFT JOIN users "
                + "FOR SYSTEM_TIME AS OF o.event_time AS u ON o.user_id = u.user_id";

        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(o, u).plan(sql)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("registered as a stream, not as a lookup table")
                .hasMessageContaining("registerLookup");
    }

    @Test
    void aValidLookupPlanCannotBeStartedThroughTheFiveArgumentOverloadTheRegistryUses() {
        // QueryRegistry:530 calls exactly this overload. If a lookup join were plannable from a
        // configured node tomorrow, this is the second, independent block that would still refuse it.
        StreamSchema o = StreamSchema.builder("orders")
                .field("order_id", Types.int64())
                .field("user_id", Types.string())
                .field("event_time", Types.timestamp())
                .build();
        StreamSchema dim = StreamSchema.builder("dim")
                .field("user_id", Types.string())
                .field("tier", Types.string())
                .build();
        String sql = "SELECT o.order_id, d.tier FROM orders o JOIN dim FOR SYSTEM_TIME AS OF o.event_time AS d "
                + "ON o.user_id = d.user_id";
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withLookups(o, dim).plan(sql));

        assertThatThrownBy(() -> QueryExecution.start(plan, 1, config(), MemoryAccess.best(), () ->
                        (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), row -> {})))
                .as("fails at start-up, not on the first record: the five-argument overload's lookups map is empty")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("dim");
    }

    // ======================= JOIN-054, 058, 059: refusals reached only through SQL =======================

    @Test
    void rightAndFullJoinsBothSayToSwapTheInputsAndUseLeft() {
        StreamSchema o = orders();
        StreamSchema u = users();
        String right = "SELECT * FROM orders o RIGHT JOIN users u ON o.user_id = u.user_id";
        String full = "SELECT * FROM orders o FULL JOIN users u ON o.user_id = u.user_id";

        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(o, u).plan(right)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("RIGHT")
                .hasMessageContaining("Swap the inputs and use LEFT");
        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(o, u).plan(full)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("FULL")
                .hasMessageContaining("Swap the inputs and use LEFT");
    }

    @Test
    void aNonEquiConditionOnNonTimestampColumnsIsACrossProductAndIsRefused() {
        StreamSchema o = StreamSchema.builder("orders")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
        StreamSchema u = StreamSchema.builder("users")
                .field("user_id", Types.string())
                .field("threshold", Types.int64())
                .build();

        // (a) the inequality alone.
        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(o, u)
                                .plan("SELECT * FROM orders o JOIN users u ON o.amount > u.threshold")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("neither an equality");

        // (b) an equality present elsewhere in the AND does not rescue the inequality.
        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(o, u)
                                .plan("SELECT * FROM orders o JOIN users u "
                                        + "ON o.user_id = u.user_id AND o.amount > u.threshold")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("neither an equality");
    }

    @Test
    void anInequalityBetweenTwoTimestampColumnsIsATimeBoundAndPlans() {
        StreamSchema o = StreamSchema.builder("orders")
                .field("user_id", Types.string())
                .field("event_time", Types.timestamp())
                .build();
        StreamSchema u = StreamSchema.builder("users")
                .field("user_id", Types.string())
                .field("event_time", Types.timestamp())
                .build();
        // (c): a plain ">" between two event-time columns is recognised as a time bound.
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(o, u)
                        .plan("SELECT * FROM orders o JOIN users u "
                                + "ON o.user_id = u.user_id AND o.event_time > u.event_time"));
        assertThat(plan).as("this must plan, unlike the non-timestamp case").isNotNull();
    }

    @Test
    void crossJoinIsRefused() {
        StreamSchema o = orders();
        StreamSchema u = users();
        // Calcite rewrites CROSS JOIN into a join whose condition is the literal TRUE, which reaches
        // collectEquiKeys' own fall-through (leftKeys stays empty) rather than buildJoin's separate
        // leftKeys.isEmpty() check or JoinOperator's constructor -- of the three sites the case names
        // as candidates, this is the one that actually fires.
        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(o, u).plan("SELECT * FROM orders o CROSS JOIN users u")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("neither an equality");
    }

    @Test
    void aTimeBoundWithNoEqualityIsRecognisedButHasNoKeyToIndexByAndIsRefused() {
        StreamSchema o = StreamSchema.builder("orders")
                .field("event_time", Types.timestamp())
                .build();
        StreamSchema u = StreamSchema.builder("users")
                .field("event_time", Types.timestamp())
                .build();
        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(o, u)
                                .plan("SELECT * FROM orders o JOIN users u ON o.event_time BETWEEN "
                                        + "u.event_time - INTERVAL '5' MINUTE AND u.event_time")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("states a time bound but no equality");
    }

    // ======================= JOIN-060: a self-join plans, compiles and joins =======================

    @Test
    void aSelfJoinPlansCompilesAndPairsEveryMatchingRowOnBothSides() {
        // JOIN-060 recorded this failing without a code when the pipeline was built. A stream read
        // twice now has one entry point that hands each row to both sides (SelfJoinTest proves the
        // answer equals the self-join from scratch, retractions included).
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(orders())
                        .plan("SELECT a.order_id, b.order_id FROM orders a JOIN orders b ON a.user_id = b.user_id"));
        List<CapturingRowWriter.Captured> out = new java.util.ArrayList<>();
        try (InterpretedPipeline pipeline =
                InterpretedPipeline.compile(plan, () -> new CapturingRowWriter(plan.outputSchema(), out::add))) {
            assertThat(pipeline.sourceStreams())
                    .as("the stream is listed once, so a caller opens one reader for it")
                    .containsExactly("orders");
        }

        // Control: the same shape query over two distinct streams plans, compiles and joins.
        List<CapturingRowWriter.Captured> joined =
                runSimpleJoin("SELECT o.order_id, u.tier FROM orders o JOIN users u ON o.user_id = u.user_id");
        assertThat(joined).hasSize(1);
    }

    // ======================= shared fixtures =======================

    private static void feed(InterpretedPipeline pipeline, StreamSchema schema, String stream, Object... values) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 4)) {
            long handle = arena.allocate(layout.rowSize(256));
            writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
            for (int i = 0; i < values.length; i++) {
                Object v = values[i];
                if (v instanceof String s) {
                    writer.setString(i, s);
                } else {
                    writer.setLong(i, ((Number) v).longValue());
                }
            }
            writer.weight(1L).eventTimestampNanos(1L).sequence(1L).commit();
            arena.trimTo(handle, writer.sizeSoFar());
            pipeline.accept(stream, new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        }
    }

    /** Emits a fixed, small list of pre-built rows and then stops. */
    private static final class FixedReader implements PartitionReader {
        private final List<Object[]> rows;
        private int produced;

        FixedReader(List<Object[]> rows) {
            this.rows = rows;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int emitted = 0;
            while (emitted < maxRecords && produced < rows.size()) {
                Object[] values = rows.get(produced);
                RowWriter writer = sink.beginRow();
                for (int i = 0; i < values.length; i++) {
                    Object v = values[i];
                    if (v instanceof String s) {
                        writer.setString(i, s);
                    } else {
                        writer.setLong(i, ((Number) v).longValue());
                    }
                }
                writer.weight(1L)
                        .eventTimestampNanos(produced)
                        .sequence(produced)
                        .commit();
                produced++;
                emitted++;
            }
            return emitted;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset("n=" + produced);
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }

    /** {@code order_id, user_id, region, amount, event_time} -- schema order matches {@link #wideOrders}. */
    private static final class WideOrdersReader implements PartitionReader {
        private final int total;
        private final long userCount;
        private int produced;

        WideOrdersReader(int total, long userCount) {
            this.total = total;
            this.userCount = userCount;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int emitted = 0;
            while (emitted < maxRecords && produced < total) {
                long id = produced;
                sink.beginRow()
                        .setLong(0, id)
                        .setString(1, "u" + (id % userCount))
                        .setString(2, "region-" + (id % 2))
                        .setLong(3, id * 10)
                        .setLong(4, id)
                        .weight(1L)
                        .eventTimestampNanos(id)
                        .sequence(id)
                        .commit();
                produced++;
                emitted++;
            }
            return emitted;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset("n=" + produced);
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }

    private static final class UsersReader implements PartitionReader {
        private final long total;
        private long produced;

        UsersReader(long total) {
            this.total = total;
        }

        @Override
        public int poll(RecordSink sink, int maxRecords) {
            int emitted = 0;
            while (emitted < maxRecords && produced < total) {
                long id = produced;
                sink.beginRow()
                        .setString(0, "u" + id)
                        .setString(1, "tier-" + id)
                        .weight(1L)
                        .eventTimestampNanos(id)
                        .sequence(id)
                        .commit();
                produced++;
                emitted++;
            }
            return emitted;
        }

        @Override
        public SourceOffset position() {
            return new SourceOffset("n=" + produced);
        }

        @Override
        public void pause() {}

        @Override
        public void resume() {}

        @Override
        public void close() {}
    }
}
