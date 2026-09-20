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
package com.ash.messaging.pravaha.sql;

import org.apache.calcite.rel.RelNode;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlPlannerTest {

    private static StreamSchema txnSchema() {
        return StreamSchema.builder("txn_stream")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.decimal(18, 4))
                .field("status", Types.string(16))
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
    }

    private static SqlPlanner planner() {
        return SqlPlanner.withStreams(txnSchema());
    }

    @Test
    void plansASelect() {
        RelNode plan = planner().plan("SELECT user_id, amount FROM txn_stream");
        assertThat(plan).isNotNull();
        assertThat(plan.getRowType().getFieldNames()).containsExactly("user_id", "amount");
    }

    @Test
    void plansAFilterAndPushesItBelowTheProjection() {
        // The projection must sit above the filter, or the optimiser has not done the one thing
        // that matters most before pushdown into a store can be attempted at all.
        String plan = planner().explain("SELECT user_id FROM txn_stream WHERE status = 'COMPLETED'");
        assertThat(plan).contains("LogicalProject").contains("LogicalFilter").contains("txn_stream");
        assertThat(plan.indexOf("LogicalProject"))
                .as("projection should be above the filter in:%n%s", plan)
                .isLessThan(plan.indexOf("LogicalFilter"));
    }

    @Test
    void identifiersKeepTheirCase() {
        // Calcite defaults to Oracle behaviour and would upper-case user_id, then fail to resolve
        // it against a schema discovered from a store that uses lower case -- which is nearly every
        // store. The resulting "column not found" for a column that plainly exists is very
        // confusing, so this is pinned.
        assertThat(planner().plan("SELECT user_id FROM txn_stream").getRowType().getFieldNames())
                .containsExactly("user_id");
    }

    @Test
    void anUnknownStreamCountsTheKnownOnesWithoutNamingThem() {
        // This asserted the opposite until SX-5, and the listing was a real kindness: a typo is the
        // commonest reason to land here, and being shown the right spelling ends the problem.
        //
        // It is also a catalogue dump. Validation happens during planning, before any authorization
        // can run, so the names went to anyone who asked for one that does not exist -- a caller
        // authorized for nothing could map the whole deployment by guessing. That is SX-5's
        // enumeration channel, and it was the widest of the three.
        //
        // The count survives because it separates two diagnoses that look identical otherwise:
        // "you misspelled one of forty" and "this node declared nothing at all", the second of
        // which is a real failure that has confused people before (StreamDeclarationProperties).
        assertThatThrownBy(() -> planner().plan("SELECT * FROM no_such_stream"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2002")
                .hasMessageContaining("no_such_stream")
                .hasMessageContaining("1 stream(s) declared")
                .hasMessageNotContaining("txn_stream");
    }

    @Test
    void anUnknownColumnIsAValidationFailure() {
        assertThatThrownBy(() -> planner().plan("SELECT nope FROM txn_stream"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2002")
                .hasMessageContaining("nope");
    }

    @Test
    void aSyntaxErrorKeepsCalcitesLineAndColumn() {
        // Line and column are most of what makes a syntax error fixable, so the original message is
        // preserved rather than replaced with something tidier and less useful.
        assertThatThrownBy(() -> planner().plan("SELECT FROM WHERE"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2001")
                .hasMessageContaining("line");
    }

    @Test
    void typesSurviveParsingAndValidation() {
        RelNode plan = planner().plan("SELECT txn_id, amount, event_time FROM txn_stream");
        var fields = plan.getRowType().getFieldList();
        assertThat(fields.get(0).getType().getSqlTypeName().getName()).isEqualTo("BIGINT");
        assertThat(fields.get(1).getType().getPrecision()).isEqualTo(18);
        assertThat(fields.get(1).getType().getScale()).isEqualTo(4);
        // An instant, not a wall-clock reading: Calcite's plain TIMESTAMP compares differently in
        // exactly the cases windowing depends on.
        assertThat(fields.get(2).getType().getSqlTypeName())
                .isEqualTo(org.apache.calcite.sql.type.SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE);
    }

    @Test
    void nullabilityIsCarriedThroughForTheOptimiser() {
        // A NOT NULL column lets Calcite eliminate null checks and simplify predicates. Declaring
        // everything nullable is free to write and quietly costs that.
        StreamSchema mixed = StreamSchema.builder("s")
                .field("required", Types.int64())
                .field("optional", Types.int64().withNullable(true))
                .build();
        var fields = SqlPlanner.withStreams(mixed)
                .plan("SELECT * FROM s")
                .getRowType()
                .getFieldList();
        assertThat(fields.get(0).getType().isNullable()).isFalse();
        assertThat(fields.get(1).getType().isNullable()).isTrue();
    }

    @Test
    void plansAnAggregate() {
        String plan = planner()
                .explain("SELECT user_id, COUNT(*) AS n, SUM(amount) AS total "
                        + "FROM txn_stream WHERE status = 'COMPLETED' GROUP BY user_id");
        assertThat(plan).contains("LogicalAggregate").contains("LogicalFilter");
    }

    @Test
    void plansAJoinBetweenTwoStreams() {
        StreamSchema profile = StreamSchema.builder("user_profile")
                .field("user_id", Types.string())
                .field("tier", Types.string())
                .build();
        String plan = SqlPlanner.withStreams(txnSchema(), profile)
                .explain(
                        "SELECT t.user_id, p.tier FROM txn_stream t " + "JOIN user_profile p ON t.user_id = p.user_id");
        assertThat(plan).contains("LogicalJoin");
    }

    @Test
    void aPlannerInstanceIsReusable() {
        // Calcite's own Planner is single-use; ours creates a fresh one per call so callers do not
        // have to know that.
        SqlPlanner planner = planner();
        assertThat(planner.plan("SELECT txn_id FROM txn_stream")).isNotNull();
        assertThat(planner.plan("SELECT user_id FROM txn_stream")).isNotNull();
        assertThat(planner.streamNames()).containsExactly("txn_stream");
    }

    @Test
    void insertIsRefusedByNameAndNamesTheWayToSayIt() {
        // B8 looked at building INSERT INTO <sink> SELECT and did not: it carries neither the name
        // the query is managed and read by nor the key its view needs, and both would have to be
        // invented. So the refusal has to hand the reader the spelling that says them.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> planner().plan("INSERT INTO user_volume_agg SELECT user_id, amount FROM txn_stream"))
                .isInstanceOfSatisfying(
                        com.ash.messaging.pravaha.api.PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(SqlErrors.UNSUPPORTED_OPERATOR))
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("INSERT is not built")
                .hasMessageContaining("WRITING TO <sink>")
                .hasMessageContaining("WITH (sink = '<sink>')")
                .hasMessageContaining("--sink")
                .hasMessageContaining("docs/CONTINUOUS_QUERIES.md");
    }

    @Test
    void theOtherDmlVerbsAreRefusedTheSameWay() {
        for (String sql : java.util.List.of(
                "UPDATE txn_stream SET user_id = 'x'",
                "DELETE FROM txn_stream WHERE user_id = 'x'",
                "MERGE INTO txn_stream t USING txn_stream s ON t.txn_id = s.txn_id "
                        + "WHEN MATCHED THEN UPDATE SET user_id = s.user_id")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner().plan(sql))
                    .as(sql)
                    .hasMessageContaining("PRV-2020")
                    .hasMessageContaining("no DML surface");
        }
    }

    @Test
    void severalStreamsCoexistInOneCatalog() {
        StreamSchema other =
                StreamSchema.builder("other").field("k", Types.string()).build();
        SqlPlanner planner = SqlPlanner.withStreams(txnSchema(), other);
        assertThat(planner.schema().contains("txn_stream")).isTrue();
        assertThat(planner.schema().contains("other")).isTrue();
        assertThat(planner.plan("SELECT k FROM other")).isNotNull();
    }
}
