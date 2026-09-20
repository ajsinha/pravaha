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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SQL-surface singleton findings: W-6, X-6, X-7 and Y-5.
 *
 * <p>One class because they are one claim in four spellings -- <em>a refusal names what the person
 * wrote and what to write instead</em> -- and because each was found by reading a message rather
 * than by a wrong answer. A refusal with no code, or one naming a clause the statement does not
 * contain, is a defect of exactly the kind this register exists to catch: it costs an afternoon and
 * leaves no trace.
 */
class SingletonRefusalsTest {

    private static StreamSchema txn() {
        return StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("name", Types.string())
                .field("amount", Types.int64())
                .field("price", Types.float64())
                .field("event_time", Types.timestamp())
                .eventTime("event_time")
                .build();
    }

    private static void plan(String sql) {
        new PhysicalPlanBuilder().build(SqlPlanner.withStreams(txn()).plan(sql));
    }

    // --- W-6 -----------------------------------------------------------------------------------

    /**
     * W-6. Calcite parses {@code CUMULATE} and then answers about itself: "No match found for
     * function signature" in the {@code GROUP BY} form, and a complaint about {@code $SCALAR_QUERY}
     * receiving a record of six fields in the {@code TABLE(...)} form. Neither names CUMULATE.
     */
    @Test
    void w6_cumulateIsRefusedByNameInBothSpellings() {
        for (String sql : java.util.List.of(
                "SELECT window_start, COUNT(*) FROM TABLE(CUMULATE(TABLE txn, DESCRIPTOR(event_time), "
                        + "INTERVAL '2' SECOND, INTERVAL '10' SECOND)) GROUP BY window_start",
                "SELECT CUMULATE(event_time, INTERVAL '2' SECOND, INTERVAL '10' SECOND), COUNT(*) FROM txn "
                        + "GROUP BY CUMULATE(event_time, INTERVAL '2' SECOND, INTERVAL '10' SECOND)")) {
            assertThatThrownBy(() -> plan(sql))
                    .as(sql)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2020")
                    .hasMessageContaining("CUMULATE")
                    .hasMessageContaining("Use TUMBLE or HOP");
        }
    }

    /**
     * W-6. A hop wider than its window leaves gaps that rows fall into, and {@code WindowSpec}
     * refuses it -- with a raw {@link IllegalArgumentException} that reached the person with no
     * {@code PRV-} code and no help URL.
     */
    @Test
    void w6_aHopWiderThanItsWindowIsRefusedWithACode() {
        assertThatThrownBy(() -> plan("SELECT window_start, COUNT(*) FROM "
                        + "TABLE(HOP(TABLE txn, DESCRIPTOR(event_time), INTERVAL '60' SECOND, "
                        + "INTERVAL '10' SECOND)) GROUP BY window_start"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("leaves gaps");
    }

    // --- X-6 -----------------------------------------------------------------------------------

    /**
     * X-6's neighbour, found while confirming that X-6's own symptom is gone. {@code LIMIT} and
     * {@code OFFSET} parse into the same node as {@code ORDER BY} with an empty order list, and the
     * refusal printed {@code 'ORDER BY '} -- naming a clause the statement does not contain, with
     * nothing between the quotes.
     */
    @Test
    void x6_aRowLimitIsRefusedAsItselfAndNotAsAnEmptyOrderBy() {
        for (String sql : java.util.List.of(
                "SELECT amount FROM txn LIMIT 5",
                "SELECT amount FROM txn OFFSET 5 ROWS",
                "SELECT amount FROM txn LIMIT ?")) {
            assertThatThrownBy(() -> plan(sql))
                    .as(sql)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2020")
                    .hasMessageNotContaining("ORDER BY ''")
                    .hasMessageNotContaining("'ORDER BY '");
        }
        assertThatThrownBy(() -> plan("SELECT amount FROM txn LIMIT 5")).hasMessageContaining("LIMIT is not executed");
        assertThatThrownBy(() -> plan("SELECT amount FROM txn OFFSET 5 ROWS"))
                .hasMessageContaining("OFFSET is not executed");
    }

    /** X-6's own symptom: {@code ORDER BY ?} used to be {@code PRV-2010} with a raw Java class name. */
    @Test
    void x6_orderByAPlaceholderIsTheOrderByRefusalAndNotARawClassName() {
        assertThatThrownBy(() -> plan("SELECT amount FROM txn ORDER BY ?"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("ORDER BY is not executed")
                .hasMessageNotContaining("SqlDynamicParam");
    }

    // --- X-7 -----------------------------------------------------------------------------------

    /**
     * X-7. The best-written refusal in the tree -- the one naming {@code JOIN dim FOR SYSTEM_TIME
     * AS OF} -- was unreachable from either correlated shape a person writes.
     */
    @Test
    void x7_bothCorrelatedShapesReachTheLookupJoinRefusal() {
        for (String sql : java.util.List.of(
                "SELECT amount FROM txn t WHERE EXISTS (SELECT 1 FROM txn u WHERE u.amount = t.amount)",
                "SELECT (SELECT MAX(u.amount) FROM txn u WHERE u.user_id = t.user_id) FROM txn t")) {
            assertThatThrownBy(() -> plan(sql))
                    .as(sql)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2020")
                    .hasMessageContaining("correlated subquery")
                    .hasMessageContaining("FOR SYSTEM_TIME AS OF");
        }
    }

    // --- Y-5 -----------------------------------------------------------------------------------

    /**
     * Y-5. The cast refusal is what a person actually meets for {@code name || amount}, because
     * SQL's own coercion inserts the cast before {@code Expression.Concat} is ever built. It has to
     * say that writing the cast by hand is refused identically, or the reader goes looking for a
     * spelling that does not exist.
     */
    @Test
    void y5_theTextCastRefusalSaysThatWritingTheCastOutDoesNotHelp() {
        for (String sql : java.util.List.of(
                "SELECT name || amount FROM txn", "SELECT name || CAST(amount AS " + "VARCHAR) FROM txn")) {
            assertThatThrownBy(() -> plan(sql))
                    .as(sql)
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-2021")
                    .hasMessageContaining("numbers only")
                    .hasMessageContaining("refused identically");
        }
    }

    /**
     * Y-5. {@code amount * 2.5} is refused as DECIMAL arithmetic over a query that mentions no
     * decimal, while {@code price * 2.5} plans. The asymmetry is SQL's own typing and is correct;
     * the message has to explain it, and has to name a rewrite that works.
     */
    @Test
    void y5_theDecimalRefusalNamesTheLiteralAndARewriteThatPlans() {
        assertThatThrownBy(() -> plan("SELECT amount * 2.5 FROM txn"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("DECIMAL literal in SQL")
                .hasMessageContaining("2.5e0");

        assertThatCode(() -> plan("SELECT amount * 2.5e0 FROM txn"))
                .as("the rewrite the message names has to plan")
                .doesNotThrowAnyException();
        assertThatCode(() -> plan("SELECT CAST(amount AS DOUBLE) * 2.5 FROM txn"))
                .as("and so does the other one")
                .doesNotThrowAnyException();
        assertThatCode(() -> plan("SELECT price * 2.5 FROM txn"))
                .as("the accepted half of the asymmetry the message explains")
                .doesNotThrowAnyException();
    }
}
