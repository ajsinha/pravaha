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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCAN-1: an answer that depends on how many times a row arrived is refused over a source that
 * repeats rows, and a keyed view of the rows is not.
 *
 * <p>Each multiplicity-sensitive shape is refused over a repeating stream and admitted over the same
 * stream when its source is an exact changelog -- which is what {@code deletes: detect} makes a
 * {@code cassandra} or {@code aerospike} binding, and what the source answers per configuration.
 */
class RepeatedRowsAnalysisTest {

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("customer_id", Types.int64())
            .field("status", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();

    private static final StreamSchema PAYMENTS = StreamSchema.builder("payments")
            .field("customer_id", Types.int64())
            .field("paid", Types.int64())
            .build();

    private static final StreamSchema CUSTOMERS = StreamSchema.builder("customers")
            .field("customer_id", Types.int64())
            .field("segment", Types.string())
            .build();

    /** The binding a node would have for {@code orders} with {@code deletes: ignore}. */
    private static final Function<String, Optional<String>> ORDERS_REPEAT =
            stream -> stream.equals("orders") ? Optional.of("cassandra") : Optional.empty();

    /** The same binding with {@code deletes: detect}: nothing repeats. */
    private static final Function<String, Optional<String>> NOTHING_REPEATS = stream -> Optional.empty();

    private static final SinkCapabilities UPSERT =
            new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), false, true, 0);

    private static final SinkCapabilities CHANGELOG =
            new SinkCapabilities(EnumSet.of(EmitMode.APPEND, EmitMode.RETRACT), true, false, 0);

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(ORDERS, PAYMENTS).plan(sql));
    }

    private static PhysicalOperator planWithLookup(String sql) {
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withLookups(ORDERS, CUSTOMERS).plan(sql));
    }

    private static final String TUMBLING = "SELECT window_start, window_end, customer_id, SUM(amount) AS total "
            + "FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
            + "GROUP BY window_start, window_end, customer_id";

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT COUNT(*) AS n, SUM(amount) AS total FROM orders",
                "SELECT SUM(amount) AS total FROM orders WHERE amount > 10",
                "SELECT MIN(amount) AS lo, MAX(amount) AS hi FROM orders",
                "SELECT AVG(amount) AS mean FROM orders",
                // An unwindowed GROUP BY or DISTINCT is refused before this (PRV-2050); windowed, they plan.
                "SELECT window_start, window_end, COUNT(DISTINCT status) AS statuses FROM "
                        + "TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                        + "GROUP BY window_start, window_end",
                "SELECT DISTINCT window_start, window_end, status FROM "
                        + "TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '10' SECOND))"
            })
    void anAggregateOverARepeatingSourceIsRefusedAndNamesTheFix(String sql) {
        PhysicalOperator plan = plan(sql);

        assertThatThrownBy(() -> RepeatedRowsAnalysis.check(plan, ORDERS_REPEAT, null, null))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2042")
                .hasMessageContaining("stream 'orders'")
                .hasMessageContaining("cassandra")
                .hasMessageContaining("aggregate")
                .as("the fix is named, on the binding")
                .hasMessageContaining("`deletes: detect`");
        assertThatCode(() -> RepeatedRowsAnalysis.check(plan, NOTHING_REPEATS, null, null))
                .as("the same query over an exact changelog")
                .doesNotThrowAnyException();
    }

    @Test
    void aWindowedAggregateOverARepeatingSourceIsRefused() {
        PhysicalOperator plan = plan(TUMBLING);

        assertThatThrownBy(() -> RepeatedRowsAnalysis.check(plan, ORDERS_REPEAT, null, null))
                .hasMessageContaining("PRV-2042")
                .hasMessageContaining("windowed aggregate");
        assertThatCode(() -> RepeatedRowsAnalysis.check(plan, NOTHING_REPEATS, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void aJoinIsRefusedWhicheverSideRepeats() {
        PhysicalOperator plan = plan("SELECT o.customer_id, o.amount, p.paid FROM orders o "
                + "JOIN payments p ON o.customer_id = p.customer_id");
        Function<String, Optional<String>> paymentsRepeat =
                stream -> stream.equals("payments") ? Optional.of("aerospike") : Optional.empty();

        assertThatThrownBy(() -> RepeatedRowsAnalysis.check(plan, ORDERS_REPEAT, null, null))
                .hasMessageContaining("PRV-2042")
                .hasMessageContaining("join");
        assertThatThrownBy(() -> RepeatedRowsAnalysis.check(plan, paymentsRepeat, null, null))
                .hasMessageContaining("PRV-2042")
                .hasMessageContaining("stream 'payments'")
                .hasMessageContaining("aerospike");
        assertThatCode(() -> RepeatedRowsAnalysis.check(plan, NOTHING_REPEATS, null, null))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT customer_id, status, amount FROM orders",
                "SELECT customer_id, amount FROM orders WHERE status = 'OPEN'",
                "SELECT customer_id, amount * 2 AS doubled FROM orders WHERE amount > 10"
            })
    void aProjectionOrFilterServedAsAKeyedViewIsAdmitted(String sql) {
        // A copy overwrites its own key with the same values, and a source that repeats never
        // retracts, so the view holds each row once, as the store does.
        assertThatCode(() -> RepeatedRowsAnalysis.check(plan(sql), ORDERS_REPEAT, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void aLookupJoinEnrichesOneRowAtATimeAndIsAdmitted() {
        PhysicalOperator plan = planWithLookup("SELECT o.customer_id, o.amount, c.segment FROM orders o "
                + "LEFT JOIN customers FOR SYSTEM_TIME AS OF o.event_time AS c ON c.customer_id = o.customer_id");

        assertThatCode(() -> RepeatedRowsAnalysis.check(plan, ORDERS_REPEAT, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void aSinkThatCannotUpsertIsRefusedForEvenAProjection() {
        PhysicalOperator plan = plan("SELECT customer_id, status, amount FROM orders");

        assertThatThrownBy(() ->
                        RepeatedRowsAnalysis.check(plan, ORDERS_REPEAT, SinkCapabilities.appendOnly(), "audit_file"))
                .hasMessageContaining("PRV-2042")
                .hasMessageContaining("sink 'audit_file'")
                .hasMessageContaining("`deletes: detect`");
        assertThatThrownBy(() -> RepeatedRowsAnalysis.check(plan, ORDERS_REPEAT, CHANGELOG, "changes_topic"))
                .as("a changelog sink writes every copy as an event")
                .hasMessageContaining("PRV-2042")
                .hasMessageContaining("sink 'changes_topic'");
        assertThatCode(() -> RepeatedRowsAnalysis.check(plan, ORDERS_REPEAT, UPSERT, "orders_table"))
                .as("an upsert by key overwrites the row it repeats, as the view does")
                .doesNotThrowAnyException();
        assertThatCode(() ->
                        RepeatedRowsAnalysis.check(plan, NOTHING_REPEATS, SinkCapabilities.appendOnly(), "audit_file"))
                .doesNotThrowAnyException();
    }

    @Test
    void anAggregateIsRefusedEvenToAnUpsertSink() {
        PhysicalOperator plan = plan("SELECT COUNT(*) AS n FROM orders");

        assertThatThrownBy(() -> RepeatedRowsAnalysis.check(plan, ORDERS_REPEAT, UPSERT, "totals"))
                .hasMessageContaining("PRV-2042")
                .hasMessageContaining("aggregate");
    }

    @Test
    void eachStreamIsAskedOnceAndAStreamWithNothingBoundIsNotJudged() {
        List<String> asked = new ArrayList<>();
        Map<String, Optional<String>> answers = Map.of("orders", Optional.empty(), "payments", Optional.empty());
        PhysicalOperator plan =
                plan("SELECT COUNT(*) AS n FROM orders o " + "JOIN payments p ON o.customer_id = p.customer_id");

        RepeatedRowsAnalysis.check(
                plan,
                stream -> {
                    asked.add(stream);
                    return answers.get(stream);
                },
                null,
                null);

        assertThat(asked).containsExactlyInAnyOrder("orders", "payments");
    }
}
