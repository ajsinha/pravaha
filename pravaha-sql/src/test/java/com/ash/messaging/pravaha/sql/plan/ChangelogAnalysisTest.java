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

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Does this query produce updates, and can this sink take them?
 *
 * <p>Gap G8, and the reason it must be answered at registration is what happens when it is not. The
 * query runs, results are written, and a retraction reaches a sink with no concept of one -- so it
 * is written as another row. The sink now holds the old answer and the new one, totals downstream
 * are double, and nothing has failed. Minutes of checking against days of reconciliation.
 */
class ChangelogAnalysisTest {

    private static final long SECOND = 1_000_000_000L;

    private static PhysicalOperator plan(String sql) {
        StreamSchema schema = StreamSchema.builder("txn")
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema).plan(sql));
    }

    private static final String TUMBLING = "SELECT window_start, window_end, user_id, SUM(amount) FROM "
            + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end, user_id";

    private static PhysicalOperator withLateness(PhysicalOperator plan, long lateness) {
        WindowedAggregateOperator w = (WindowedAggregateOperator) plan;
        return new WindowedAggregateOperator(
                w.input(),
                w.outputSchema(),
                w.spec(),
                w.groupKeys(),
                w.aggregates(),
                w.windowStartOrdinal(),
                w.windowEndOrdinal(),
                w.maxSlices(),
                lateness);
    }

    @Test
    void aFilterAndProjectionOnlyEverAppends() {
        ChangelogAnalysis.Result result = ChangelogAnalysis.analyse(plan("SELECT user_id FROM txn WHERE amount > 10"));

        assertThat(result.producesUpdates()).isFalse();
        assertThat(result.produces()).contains(EmitMode.APPEND);
    }

    @Test
    void aWindowWithNoAllowedLatenessNeverRevisesAndIsSafeForAnAppendOnlySink() {
        // The only aggregate shape that is, which is worth stating rather than leaving to be
        // rediscovered: a window that fires once and never re-fires adds rows and nothing else.
        ChangelogAnalysis.Result result = ChangelogAnalysis.analyse(plan(TUMBLING));

        assertThat(result.producesUpdates()).isFalse();
        assertThatCode(() ->
                        ChangelogAnalysis.checkAgainst(plan(TUMBLING), SinkCapabilities.appendOnly(), "orders_file"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowedLatenessMakesTheSameQueryProduceUpdates() {
        // The same SQL, one setting different. A late record re-emits a window already reported, as
        // a retraction of the old answer and the corrected one -- which an append-only sink would
        // write as two more rows.
        PhysicalOperator late = withLateness(plan(TUMBLING), 30 * SECOND);
        ChangelogAnalysis.Result result = ChangelogAnalysis.analyse(late);

        assertThat(result.producesUpdates()).isTrue();
        assertThat(result.produces()).contains(EmitMode.RETRACT, EmitMode.UPSERT);
        assertThat(result.reason()).contains("late record").contains("30000 ms");
    }

    @Test
    void anAppendOnlySinkRefusesAQueryThatRevisesAndSaysWhyAndHowToFixIt() {
        PhysicalOperator late = withLateness(plan(TUMBLING), 30 * SECOND);

        assertThatThrownBy(() -> ChangelogAnalysis.checkAgainst(late, SinkCapabilities.appendOnly(), "orders_file"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2041")
                .as("names the sink, not just the query")
                .hasMessageContaining("orders_file")
                .as("says which part of the query is responsible")
                .hasMessageContaining("allows 30000 ms of lateness")
                .as("and what to do instead")
                .hasMessageContaining("keyed upsert");
    }

    @Test
    void aSinkThatSupportsUpsertAcceptsTheSameQuery() {
        // The query was never wrong. The pair was, which is why the refusal is of the pair -- a
        // message blaming the query sends somebody to rewrite something that was fine.
        PhysicalOperator late = withLateness(plan(TUMBLING), 30 * SECOND);
        SinkCapabilities upsert = new SinkCapabilities(EnumSet.of(EmitMode.UPSERT), false, true, 500);

        assertThatCode(() -> ChangelogAnalysis.checkAgainst(late, upsert, "aerospike_totals"))
                .doesNotThrowAnyException();
    }

    @Test
    void aFilterOverAStreamThatDeletesRevisesAndIsRefusedByAnAppendOnlySink() {
        // HLP-3: a delete from a change feed is a row at weight -1, and a filter passes it on.
        PhysicalOperator filter = plan("SELECT user_id FROM txn WHERE amount > 10");

        ChangelogAnalysis.Result result = ChangelogAnalysis.analyse(filter, "txn"::equals);

        assertThat(result.producesUpdates()).isTrue();
        assertThat(result.reason()).contains("stream 'txn'").contains("emits deletes");
        assertThatThrownBy(() -> ChangelogAnalysis.checkAgainst(
                        filter, "txn"::equals, SinkCapabilities.appendOnly(), "orders_file"))
                .hasMessageContaining("PRV-2041");
        assertThat(ChangelogAnalysis.analyse(filter, "other"::equals).producesUpdates())
                .isFalse();
    }

    @Test
    void anOperatorThatRevisesMakesEverythingAboveItRevise() {
        // A corrected input produces a corrected output. The analysis is pessimistic on purpose:
        // being wrong optimistically means admitting a query that corrupts a sink.
        PhysicalOperator late = withLateness(plan(TUMBLING), 30 * SECOND);

        // A projection over the revising aggregate still revises.
        assertThat(ChangelogAnalysis.analyse(new com.ash.messaging.pravaha.runtime.plan.ProjectOperator(
                                late, late.outputSchema(), java.util.List.of(0, 1, 2, 3)))
                        .producesUpdates())
                .isTrue();
    }
}
