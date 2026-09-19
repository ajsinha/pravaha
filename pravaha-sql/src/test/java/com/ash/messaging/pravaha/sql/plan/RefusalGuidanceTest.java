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
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HLP-14: a refusal's advice has to be advice that works.
 *
 * <p>Each of these messages sent the operator the wrong way: a fix that is itself refused, a reason
 * about a construct the query did not use, a missing clause the query had. Each test pins the new
 * advice and, where the advice is a rewrite, that the rewrite plans.
 */
class RefusalGuidanceTest {

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("order_id", Types.string())
            .field("customer_id", Types.string())
            .field("status", Types.string().withNullable(true))
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();

    private static final StreamSchema CUSTOMERS = StreamSchema.builder("customers")
            .field("customer_id", Types.string())
            .field("segment", Types.string())
            .build();

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(ORDERS).plan(sql));
    }

    private static PhysicalOperator planWithLookup(String sql) {
        return new PhysicalPlanBuilder()
                .build(SqlPlanner.withLookups(ORDERS, CUSTOMERS).plan(sql));
    }

    @Test
    void aNullableBooleanColumnIsSentToIsTrueWhichPlans() {
        // (a) The hint was "add IS NOT NULL to the operand", and a query following it is refused
        // again. `(status = 'ok') IS TRUE` is the form that plans.
        assertThatThrownBy(() -> plan("SELECT order_id, status = 'ok' AS ok FROM orders"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("IS TRUE")
                .hasMessageNotContaining("IS NOT NULL to the operand");
        assertThatCode(() -> plan("SELECT order_id, (status = 'ok') IS TRUE AS ok FROM orders"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> plan("SELECT order_id, status IS NOT NULL AND status = 'ok' AS ok FROM orders"))
                .as("the old advice: Calcite still types the AND nullable, so it is refused again")
                .hasMessageContaining("PRV-2021");
    }

    @Test
    void aFilterOnALookedUpColumnSaysSoAndNamesTheFormThatPlans() {
        // (b) This used to talk about correlated subqueries, which the query did not have.
        String filtered = "SELECT o.order_id, c.segment FROM orders o "
                + "JOIN customers FOR SYSTEM_TIME AS OF o.event_time AS c ON c.customer_id = o.customer_id "
                + "WHERE c.segment = 'gold'";
        assertThatThrownBy(() -> planWithLookup(filtered))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("lookup")
                .hasMessageContaining("LEFT JOIN")
                .hasMessageNotContaining("correlated subqueries");
        assertThatCode(() -> planWithLookup(filtered.replace("JOIN customers", "LEFT JOIN customers")))
                .as("the form the message names plans")
                .doesNotThrowAnyException();
    }

    @Test
    void aRenamedWindowColumnIsToldToKeepItsName() {
        // (c) The query groups by the window; the refusal said it did not.
        String renamed = "SELECT window_start, window_end AS closes, customer_id, SUM(amount) AS total FROM "
                + "TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end, customer_id";
        String kept = "SELECT window_start, window_end, customer_id, SUM(amount) AS total FROM "
                + "TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                + "GROUP BY window_start, window_end, customer_id";
        try {
            plan(renamed);
            // Planned: nothing to pin, and the finding does not reproduce on this shape.
            assertThatCode(() -> plan(kept)).doesNotThrowAnyException();
        } catch (PravahaException e) {
            assertThat(e.getMessage())
                    .contains("PRV-2050")
                    .contains("keep")
                    .contains("window_end")
                    .doesNotContain("does not group by the window");
        }
    }
}
