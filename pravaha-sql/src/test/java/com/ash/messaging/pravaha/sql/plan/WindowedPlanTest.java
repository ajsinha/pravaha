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
package com.ash.messaging.pravaha.sql.plan;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowAssignOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.runtime.window.WindowSpec;
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Windowed GROUP BY, from SQL to a physical plan.
 *
 * <p>This is what makes a keyed aggregate legal: an unwindowed one is refused because its state
 * grows with the key space and never shrinks, and a windowed one releases each window's state when
 * the window closes. The planner has to tell the two apart, and it has to get the window's shape
 * right -- a HOP whose size and slide are swapped produces windows of the wrong width that still
 * fire plausibly.
 */
class WindowedPlanTest {

    private static final long SECOND = 1_000_000_000L;

    /** The window assignment feeding an aggregate, looking through the projection Calcite inserts. */
    private static WindowAssignOperator windowBelow(WindowedAggregateOperator aggregate) {
        PhysicalOperator current = aggregate.input();
        while (current != null) {
            if (current instanceof WindowAssignOperator window) {
                return window;
            }
            current = current.inputs().isEmpty() ? null : current.inputs().get(0);
        }
        return null;
    }

    private static PhysicalOperator plan(String sql) {
        StreamSchema schema = StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema).plan(sql));
    }

    @Test
    void aTumblingGroupByIsAdmittedAndKeepsItsWindow() {
        PhysicalOperator plan = plan("SELECT window_start, window_end, user_id, COUNT(*) FROM "
                + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end, user_id");

        assertThat(plan).isInstanceOf(WindowedAggregateOperator.class);
        WindowedAggregateOperator aggregate = (WindowedAggregateOperator) plan;
        assertThat(aggregate.spec().kind()).isEqualTo(WindowSpec.Kind.TUMBLING);
        assertThat(aggregate.spec().sizeNanos()).isEqualTo(10 * SECOND);
        assertThat(aggregate.isStateful()).isTrue();
        assertThat(windowBelow(aggregate))
                .as("the window assignment feeds it, through the projection Calcite inserts")
                .isNotNull();
    }

    @Test
    void aHoppingWindowKeepsItsSizeAndSlideTheRightWayRound() {
        // Calcite passes HOP as (slide, size), which is the reverse of how the SQL reads. Swapping
        // them yields windows of the wrong width that still fire on schedule and produce numbers, so
        // the mapping is asserted rather than trusted.
        PhysicalOperator plan = plan("SELECT window_start, window_end, user_id, COUNT(*) FROM "
                + "TABLE(HOP(TABLE txn, DESCRIPTOR(event_time), INTERVAL '5' SECOND, INTERVAL '60' SECOND)) "
                + "GROUP BY window_start, window_end, user_id");

        WindowedAggregateOperator aggregate = (WindowedAggregateOperator) plan;
        assertThat(aggregate.spec().kind()).isEqualTo(WindowSpec.Kind.HOPPING);
        assertThat(aggregate.spec().sizeNanos()).as("60-second window").isEqualTo(60 * SECOND);
        assertThat(aggregate.spec().slideNanos()).as("hopping every 5 seconds").isEqualTo(5 * SECOND);
        assertThat(aggregate.spec().sliceSizeNanos()).isEqualTo(5 * SECOND);
        assertThat(aggregate.spec().slicesPerWindow()).isEqualTo(12);
    }

    @Test
    void theWindowAssignerAddsItsBoundaryColumns() {
        PhysicalOperator plan = plan("SELECT window_start, window_end, user_id, COUNT(*) FROM "
                + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end, user_id");

        WindowAssignOperator assign = windowBelow((WindowedAggregateOperator) plan);
        assertThat(assign.outputSchema().fieldCount())
                .as("the source's four columns plus two boundaries")
                .isEqualTo(6);
        assertThat(assign.outputSchema().field(4).name()).isEqualTo("window_start");
        assertThat(assign.outputSchema().field(5).name()).isEqualTo("window_end");
        assertThat(assign.eventTimeOrdinal())
                .as("resolved at planning, never looked up per row")
                .isEqualTo(3);
    }

    @Test
    void anUnwindowedKeyedAggregateIsStillRefused() {
        // The window is what makes the difference; without it nothing has changed.
        assertThatThrownBy(() -> plan("SELECT user_id, COUNT(*) FROM txn GROUP BY user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2050")
                .hasMessageContaining("GROUP BY user_id");
    }

    @Test
    void groupingOverAWindowWithoutGroupingByItIsRefused() {
        // The unbounded case wearing a window's clothes: the stream is windowed but the aggregate
        // spans every window at once, so its state never gets released.
        assertThatThrownBy(() -> plan("SELECT user_id, COUNT(*) FROM "
                        + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("Group by both")
                .hasMessageContaining("window_start");
    }

    @Test
    void sessionWindowsSayWhyTheyAreNotAvailableYet() {
        // They exist in the runtime and are not reachable from SQL, which is a more useful thing to
        // be told than "unsupported function".
        assertThatThrownBy(() -> plan("SELECT window_start, window_end, user_id, COUNT(*) FROM "
                        + "TABLE(SESSION(TABLE txn, DESCRIPTOR(event_time), INTERVAL '30' SECOND)) "
                        + "GROUP BY window_start, window_end, user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("keyed state store");
    }

    @Test
    void anAggregateOverAnExpressionStillFindsItsWindow() {
        // SUM(amount * 2) puts a ComputeOperator between the window assigner and the aggregate.
        // While the search for the assigner walked projections but not computations, the window was
        // invisible and this was refused as an unbounded aggregate -- a correct-looking refusal for
        // an entirely ordinary query, and the kind of thing only a support call would have found.
        PhysicalOperator root = plan("SELECT window_start, window_end, SUM(amount * 2) FROM "
                + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end");

        assertThat(PhysicalPlanBuilder.explain(root)).contains("WindowedAggregate");
    }

    @Test
    void aDescriptorOnTheWrongTimestampColumnIsRefused() {
        // TIME-2. Calcite refuses a descriptor on a non-temporal column, which is a *type* check --
        // it says nothing about which timestamp column, and a schema with two of them walks straight
        // through it. The two queries differ by one identifier; both used to be accepted, and one
        // answered with windows cut from a column no watermark tracks.
        StreamSchema twoStamps = StreamSchema.builder("ev")
                .field("id", Types.int64())
                .field("event_time", Types.timestamp())
                .field("other_time", Types.timestamp())
                .eventTime("event_time")
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(twoStamps)
                                .plan("SELECT COUNT(*) FROM TABLE(TUMBLE(TABLE ev, DESCRIPTOR(other_time), "
                                        + "INTERVAL '10' SECOND)) GROUP BY window_start, window_end")))
                .as("a window grid keyed to an undeclared column is closed by a clock that knows nothing "
                        + "about it -- the answer is wrong rather than late")
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("other_time")
                .hasMessageContaining("event_time");
    }

    @Test
    void aDescriptorOnTheDeclaredEventTimeIsAccepted() {
        // The property the guard must not cost: the correct query, one identifier away, still plans.
        StreamSchema twoStamps = StreamSchema.builder("ev")
                .field("id", Types.int64())
                .field("event_time", Types.timestamp())
                .field("other_time", Types.timestamp())
                .eventTime("event_time")
                .build();

        assertThat(new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(twoStamps)
                                .plan("SELECT COUNT(*) FROM TABLE(TUMBLE(TABLE ev, DESCRIPTOR(event_time), "
                                        + "INTERVAL '10' SECOND)) GROUP BY window_start, window_end")))
                .isNotNull();
    }

    @Test
    void theGroupByFormOfTheWrongTimestampColumnIsRefusedToo() {
        // Found while closing TIME-6. TIME-2's guard was added to buildWindowAssign alone, and
        // Calcite lowers `GROUP BY TUMBLE(...)` through buildGroupedWindow instead -- so the
        // spelling most queries actually use walked straight past it and cut its windows from a
        // column no watermark tracks. The two spellings have to answer the same.
        StreamSchema twoStamps = StreamSchema.builder("ev")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .field("other_time", Types.timestamp())
                .eventTime("event_time")
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(twoStamps)
                                .plan("SELECT SUM(amount) FROM ev GROUP BY TUMBLE(other_time, "
                                        + "INTERVAL '10' SECOND)")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("other_time")
                .hasMessageContaining("event_time");

        assertThat(new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(twoStamps)
                                .plan("SELECT SUM(amount) FROM ev GROUP BY TUMBLE(event_time, "
                                        + "INTERVAL '10' SECOND)")))
                .as("and the correct one, one identifier away, still plans")
                .isNotNull();
    }
}
