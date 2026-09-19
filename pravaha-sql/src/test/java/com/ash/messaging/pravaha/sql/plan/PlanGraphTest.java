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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan as a structure, so nothing has to parse {@code explain}'s indentation to draw it.
 */
class PlanGraphTest {

    private static PhysicalOperator plan(String sql) {
        StreamSchema txn = StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(txn).plan(sql));
    }

    @Test
    void theGraphHasOneNodePerLineOfTheTextAndInTheSameOrder() {
        PhysicalOperator root = plan("SELECT txn_id, amount FROM txn WHERE amount > 100");

        PlanGraph graph = PlanGraph.of(root);
        List<String> lines =
                PhysicalPlanBuilder.explain(root).lines().map(String::strip).toList();

        assertThat(graph.nodes()).extracting(PlanGraph.Node::detail).containsExactlyElementsOf(lines);
        assertThat(graph.nodes().get(0).id()).isEqualTo("n0");
        assertThat(graph.nodes().get(0).fields()).containsExactly("txn_id", "amount");
    }

    @Test
    void edgesRunTheWayRowsFlowFromTheScanToTheRoot() {
        PlanGraph graph = PlanGraph.of(plan("SELECT txn_id FROM txn WHERE amount > 100"));

        PlanGraph.Node scan = graph.nodes().stream()
                .filter(node -> node.operator().equals("Scan"))
                .findFirst()
                .orElseThrow();
        assertThat(graph.edges()).extracting(PlanGraph.Edge::from).contains(scan.id());
        assertThat(graph.edges())
                .as("the root consumes and is consumed by nothing")
                .extracting(PlanGraph.Edge::from)
                .doesNotContain("n0");
        assertThat(graph.edges()).hasSize(graph.nodes().size() - 1);
    }

    @Test
    void aWindowedAggregateIsMarkedStatefulFromTheOperatorNotFromItsLabel() {
        PlanGraph graph = PlanGraph.of(plan("SELECT window_start, window_end, user_id, COUNT(*) FROM "
                + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end, user_id"));

        assertThat(graph.nodes().get(0).operator()).isEqualTo("WindowedAggregate");
        assertThat(graph.nodes().get(0).stateful()).isTrue();
        assertThat(graph.nodes())
                .filteredOn(node -> node.operator().equals("Scan"))
                .allSatisfy(node -> assertThat(node.stateful()).isFalse());
    }
}
