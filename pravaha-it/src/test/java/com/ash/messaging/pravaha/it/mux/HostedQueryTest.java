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
package com.ash.messaging.pravaha.it.mux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;
import com.ash.messaging.pravaha.runtime.lane.LaneGroup;
import com.ash.messaging.pravaha.runtime.lane.LaneMultiplexer;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A query hosted on lanes it does not own, and what closing it must not take down with it.
 *
 * <p>W9-8. A lane is owned by the execution that created it, so {@code QueryExecution.close()} closes
 * it — which on a lane shared by three hundred queries would stop the lane serving the other two
 * hundred and ninety-nine. `LaneMultiplexer` has been built and tested since Wave 9 and wired to
 * nothing, and <strong>this seam is what it was missing</strong>: a way for a query to contribute a
 * pipeline to somebody else's lane and to leave again without ending it.
 *
 * <p>What is deliberately <em>not</em> here: the registry does not share groups yet, a watermark
 * advance still clamps the lane's batch (W9-10), and nothing decides which lane a registration lands
 * on. Those are recorded on W9-8/W9-10 rather than approximated, because wiring the registry before
 * they are settled would risk the wave's measured wins for the 1,024 KiB still on the table.
 */
@Timeout(120)
class HostedQueryTest {

    private static final String SQL = "SELECT user_id, amount FROM txn";

    private static StreamSchema schema() {
        // With a stream id, because the multiplexer refuses a schema without one rather than
        // mis-dispatching its rows (W9-9). A registry assigns ids as streams join its catalogue; a
        // schema built by hand has none, and the refusal saying so is the guard working.
        return StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build()
                .withStreamId(7);
    }

    private static LaneConfig config() {
        return LaneConfig.defaults()
                .withInbox(64, 256)
                .withBatchSize(8)
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK);
    }

    /** A group of multiplexed lanes, which is what a registry would own. */
    private static LaneGroup multiplexedGroup() {
        LaneGroup group = new LaneGroup(1, config(), MemoryAccess.best(), context -> new LaneMultiplexer());
        group.start();
        return group;
    }

    @Test
    void closingOneHostedQueryLeavesTheOtherRunningOnTheSameLane() {
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));
        List<CapturingRowWriter.Captured> first = java.util.Collections.synchronizedList(new ArrayList<>());
        List<CapturingRowWriter.Captured> second = java.util.Collections.synchronizedList(new ArrayList<>());

        LaneGroup shared = multiplexedGroup();
        try {
            QueryExecution a = QueryExecution.startOn(
                    shared,
                    "query-a",
                    plan,
                    () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), first::add),
                    Map.of(),
                    MemoryAccess.best());
            QueryExecution b = QueryExecution.startOn(
                    shared,
                    "query-b",
                    plan,
                    () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), second::add),
                    Map.of(),
                    MemoryAccess.best());

            // The property: closing one is "drop my pipeline", not "stop this lane".
            a.close();

            assertThat(shared.lane(0).state())
                    .as("the lane must survive one of its queries leaving -- that is the whole of W9-8")
                    .isEqualTo(com.ash.messaging.pravaha.runtime.lane.Lane.State.RUNNING);
            b.checkHealth();
            b.close();
        } finally {
            shared.close();
        }
    }

    @Test
    void aGroupThatIsNotMultiplexedIsRefusedRatherThanFailingAtTheFirstRow() {
        // Checked at the call that is wrong, not lazily: a pipeline registered into the wrong kind
        // of processor would otherwise fail at the first row, a long way from the mistake.
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL));
        List<CapturingRowWriter.Captured> sink = new ArrayList<>();

        LaneGroup plain = new LaneGroup(1, config(), MemoryAccess.best(), context -> (region, offsets, n) -> n);
        plain.start();
        try {
            assertThatThrownBy(() -> QueryExecution.startOn(
                            plain,
                            "intruder",
                            plan,
                            () -> (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), sink::add),
                            Map.of(),
                            MemoryAccess.best()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not multiplexed");
        } finally {
            plain.close();
        }
    }
}
