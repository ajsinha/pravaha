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
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.catalog.CatalogErrors;
import com.ash.messaging.pravaha.runtime.plan.ComputeOperator;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.PlanNodes;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.security.SecurityErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A narrowing compiled into the operators that enforce it, and the uses of a masked column refused (ADR-059 §4). */
class NarrowingPlanTest {

    private static final StreamSchema PAYMENTS = StreamSchema.builder("payments")
            .field("id", Types.string())
            .field("region", Types.string())
            .field("card", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();

    private static final StreamSchema CARDS = StreamSchema.builder("cards")
            .field("card", Types.string())
            .field("holder", Types.string())
            .build();

    private static final Narrowing EU_MASKED = new Narrowing(
            Optional.of("region = 'EU'"), Map.of("card", "'XXXX-' || SUBSTRING(card FROM 13)"), List.of());

    private static PreparedContinuousQuery register(String sql, Narrowing narrowing, List<Integer> keys) {
        return PreparedContinuousQuery.of(
                sql,
                BoundParameters.none(),
                List.of(PAYMENTS, CARDS),
                List.of(),
                MaintainedViews.NONE,
                Map.of("payments", narrowing),
                keys);
    }

    private static void refusedAs(String sql, String use) {
        assertThatThrownBy(() -> register(sql, EU_MASKED, List.of(0)))
                .isInstanceOfSatisfying(PravahaException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(SecurityErrors.MASKED_COLUMN_USE);
                    assertThat(e.getMessage()).contains("payments.card").contains(use);
                });
    }

    @Test
    void theFilterSitsOnTheScanAndTheMasksAboveItKeepingTheScansSchema() {
        NarrowingPlan plan = NarrowingPlan.compile(PAYMENTS, EU_MASKED);
        assertThat(plan.maskedColumns()).containsExactly("card");
        PhysicalOperator scan = ScanOperator.of("payments", PAYMENTS);
        PhysicalOperator guarded = plan.over(scan);
        assertThat(guarded).isInstanceOf(ComputeOperator.class);
        assertThat(guarded.outputSchema()).isSameAs(PAYMENTS);
        assertThat(((ComputeOperator) guarded).input()).isInstanceOf(FilterOperator.class);
        assertThat(((FilterOperator) ((ComputeOperator) guarded).input()).input())
                .isSameAs(scan);

        assertThat(NarrowingPlan.compile(PAYMENTS, Narrowing.NONE).isNone()).isTrue();
        assertThat(NarrowingPlan.compile(PAYMENTS, new Narrowing(Optional.empty(), Map.of("ssn", "'x'"), List.of()))
                        .isNone())
                .as("a mask on a column the object does not carry masks nothing")
                .isTrue();
    }

    @Test
    void aRegistrationReadsItsInputThroughTheNarrowing() {
        PreparedContinuousQuery prepared =
                register("SELECT id, card, amount FROM payments WHERE amount > 10", EU_MASKED, List.of(0));
        List<PhysicalOperator> nodes = PlanNodes.preOrder(prepared.plan());
        int scan = indexOf(nodes, ScanOperator.class);
        assertThat(nodes.get(scan - 1)).isInstanceOf(FilterOperator.class);
        assertThat(((FilterOperator) nodes.get(scan - 1)).predicate().describe())
                .contains("EU");
        assertThat(nodes.get(scan - 2)).isInstanceOf(ComputeOperator.class);

        PreparedContinuousQuery whole =
                register("SELECT id, card, amount FROM payments WHERE amount > 10", Narrowing.NONE, List.of(0));
        assertThat(PlanNodes.preOrder(whole.plan())).noneMatch(ComputeOperator.class::isInstance);
        assertThat(PlanNodes.preOrder(prepared.plan()).stream()
                        .map(PhysicalOperator::identity)
                        .toList())
                .isNotEqualTo(PlanNodes.preOrder(whole.plan()).stream()
                        .map(PhysicalOperator::identity)
                        .toList());
    }

    @Test
    void aMaskedColumnMayBeShownAndNotCompared() {
        assertThatCode(() -> register("SELECT id, UPPER(card) AS c FROM payments", EU_MASKED, List.of(0)))
                .doesNotThrowAnyException();
        refusedAs("SELECT id, card FROM payments WHERE card = '4111'", "a filter operand");
        refusedAs("SELECT id, card FROM payments WHERE UPPER(card) LIKE '4%'", "a filter operand");
        refusedAs("SELECT card, COUNT(*) AS n FROM payments GROUP BY card", "a group key");
        refusedAs("SELECT region, COUNT(DISTINCT card) AS n FROM payments GROUP BY region", "the argument of COUNT");
        refusedAs("SELECT p.id, c.holder FROM payments p JOIN cards c ON p.card = c.card", "a join key");
        assertThatThrownBy(() -> register("SELECT card, id FROM payments", EU_MASKED, List.of(0)))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.getMessage()).contains("a key column of the view being registered"));
    }

    @Test
    void aFilterMustBeEnforceableAndAMaskMustKeepItsColumnAndType() {
        assertThatThrownBy(() -> NarrowingPlan.compile(
                        PAYMENTS, new Narrowing(Optional.of("country = 'DE'"), Map.of(), List.of())))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(SecurityErrors.FILTER_NOT_ENFORCEABLE));
        assertThatThrownBy(
                        () -> NarrowingPlan.compile(PAYMENTS, new Narrowing(Optional.of("TRUE"), Map.of(), List.of())))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.getMessage()).contains("true for every row"));
        assertThatThrownBy(() -> NarrowingPlan.compile(
                        PAYMENTS, new Narrowing(Optional.empty(), Map.of("amount", "'hidden'"), List.of())))
                .isInstanceOfSatisfying(PravahaException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(CatalogErrors.POLICY_INVALID);
                    assertThat(e.getMessage()).contains("keeps the column's type");
                });
        assertThatThrownBy(() -> NarrowingPlan.compile(
                        PAYMENTS, new Narrowing(Optional.empty(), Map.of("card", "card || region"), List.of())))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.getMessage()).contains("only the column it masks"));
        assertThatCode(() -> NarrowingPlan.compile(
                        PAYMENTS, new Narrowing(Optional.empty(), Map.of("amount", "0 * amount"), List.of())))
                .doesNotThrowAnyException();
    }

    private static int indexOf(List<PhysicalOperator> nodes, Class<?> type) {
        for (int i = 0; i < nodes.size(); i++) {
            if (type.isInstance(nodes.get(i))) {
                return i;
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + nodes);
    }
}
