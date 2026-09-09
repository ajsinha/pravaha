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
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhysicalPlanBuilderTest {

    private static StreamSchema txnSchema() {
        return StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("status", Types.string())
                .field("flagged", Types.bool())
                .field("note", Types.string().withNullable(true))
                .build();
    }

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(txnSchema()).plan(sql));
    }

    @Test
    void aBareSelectBecomesAScan() {
        PhysicalOperator root = plan("SELECT txn_id, user_id, amount, status, flagged, note FROM txn");
        // Calcite may or may not insert a trivial projection; either way the leaf is a scan.
        PhysicalOperator leaf = root;
        while (!leaf.inputs().isEmpty()) {
            leaf = leaf.inputs().get(0);
        }
        assertThat(leaf).isInstanceOf(ScanOperator.class);
        assertThat(((ScanOperator) leaf).streamName()).isEqualTo("txn");
    }

    @Test
    void aWhereClauseBecomesAFilterOverAScan() {
        String explained = PhysicalPlanBuilder.explain(plan("SELECT user_id FROM txn WHERE amount > 100"));
        assertThat(explained).contains("Filter(").contains("amount > 100").contains("Scan(txn)");
    }

    @Test
    void aProjectionRecordsItsSourceOrdinals() {
        PhysicalOperator root = plan("SELECT status, user_id FROM txn");
        ProjectOperator project = (ProjectOperator) root;
        // Reordering columns is the case a naive builder gets wrong by assuming identity ordinals.
        assertThat(project.sourceOrdinals()).containsExactly(3, 1);
        assertThat(project.outputSchema().fields().stream().map(f -> f.name()).toList())
                .containsExactly("status", "user_id");
    }

    @Test
    void compilesTheComparisonsItClaimsTo() {
        assertThat(PhysicalPlanBuilder.explain(plan("SELECT user_id FROM txn WHERE amount >= 100")))
                .contains("amount >= 100");
        assertThat(PhysicalPlanBuilder.explain(plan("SELECT user_id FROM txn WHERE status = 'COMPLETED'")))
                .contains("status = 'COMPLETED'");
        assertThat(PhysicalPlanBuilder.explain(plan("SELECT user_id FROM txn WHERE note IS NULL")))
                .contains("note IS NULL");
        assertThat(PhysicalPlanBuilder.explain(plan("SELECT user_id FROM txn WHERE flagged")))
                .contains("flagged");
    }

    @Test
    void aReversedComparisonIsNormalised() {
        // 100 < amount means amount > 100. Getting the direction wrong here inverts a filter, which
        // is the sort of bug that produces plausible-looking but wrong results.
        assertThat(PhysicalPlanBuilder.explain(plan("SELECT user_id FROM txn WHERE 100 < amount")))
                .contains("amount > 100");
    }

    @Test
    void conjunctionsAndDisjunctionsCompile() {
        String explained = PhysicalPlanBuilder.explain(
                plan("SELECT user_id FROM txn WHERE amount > 100 AND status = 'COMPLETED'"));
        assertThat(explained)
                .contains("amount > 100")
                .contains("status = 'COMPLETED'")
                .contains("AND");
    }

    @Test
    void aGlobalAggregateIsAllowedBecauseItsStateIsBoundedByConstruction() {
        PhysicalOperator root = plan("SELECT COUNT(*) FROM txn");
        PhysicalOperator aggregate =
                root instanceof AggregateOperator ? root : root.inputs().get(0);
        assertThat(aggregate).isInstanceOf(AggregateOperator.class);
        assertThat(aggregate.isStateful()).isTrue();
        assertThat(((AggregateOperator) aggregate).groupKeyOrdinals()).isEmpty();
        assertThat(((AggregateOperator) aggregate).isFullyLinear())
                .as("COUNT accumulates weighted deltas directly")
                .isTrue();
    }

    @Test
    void aKeyedAggregateIsRefusedUntilItsStateCanBeBounded() {
        // Design 9.6. Unbounded integration is how incremental engines die in production, and
        // refusing the query is the only intervention that reliably works. The message says what
        // to do about it rather than just refusing.
        assertThatThrownBy(() -> plan("SELECT user_id, COUNT(*) FROM txn GROUP BY user_id"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2050")
                .hasMessageContaining("grow without limit")
                .hasMessageContaining("Add a window");
    }

    @Test
    void aComputedProjectionIsRefusedRatherThanSilentlyWrong() {
        assertThatThrownBy(() -> plan("SELECT amount * 2 FROM txn"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("computed");
    }

    @Test
    void anUnsupportedOperatorNamesItselfAndWhatIsSupported() {
        StreamSchema other =
                StreamSchema.builder("other").field("user_id", Types.string()).build();
        var planner = SqlPlanner.withStreams(txnSchema(), other);
        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(planner.plan("SELECT t.user_id FROM txn t JOIN other o ON t.user_id = o.user_id")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("Supported:");
    }

    @Test
    void explainRendersAnIndentedTree() {
        String explained = PhysicalPlanBuilder.explain(plan("SELECT user_id FROM txn WHERE amount > 100"));
        String[] lines = explained.split("\n");
        assertThat(lines.length).isGreaterThanOrEqualTo(2);
        assertThat(lines[lines.length - 1]).startsWith("    ").contains("Scan");
    }

    @Test
    void aSinkRefusesUpsertWithoutKeyFields() {
        // Nothing to upsert on means rows accumulate instead of replacing each other, which looks
        // like a duplicate-data problem rather than a configuration mistake.
        PhysicalOperator input = ScanOperator.of("txn", txnSchema());
        assertThatThrownBy(() -> new SinkOperator(
                        input, "out", com.ash.messaging.pravaha.api.data.EmitMode.UPSERT, java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no key fields");
        assertThat(new SinkOperator(
                                input, "out", com.ash.messaging.pravaha.api.data.EmitMode.APPEND, java.util.List.of())
                        .label())
                .contains("APPEND");
    }
}
