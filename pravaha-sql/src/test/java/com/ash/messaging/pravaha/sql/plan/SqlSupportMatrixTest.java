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
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every SQL construct this engine does and does not support, asserted.
 *
 * <p>This is the executable half of {@code docs/SQL_SUPPORT.md}. That document exists because "what
 * can I write?" is the first question anybody adopting a SQL engine asks, and the worst answer is a
 * list somebody wrote once. Here the list is run: if a construct starts working, or stops, this test
 * fails and names the document that needs the edit.
 *
 * <p>A refusal is as much a feature as a success and is asserted with its error code. A query the
 * engine cannot run must fail at planning time with a code and an explanation, never by running and
 * returning something plausible -- which is why {@code LIMIT 5} being refused is recorded here as
 * correct behaviour rather than a gap to be embarrassed about.
 */
class SqlSupportMatrixTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("txn_id", Types.string())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("price", Types.float64())
            .field("status", Types.string().withNullable(true))
            .field("flagged", Types.bool())
            .field("event_time", Types.timestamp())
            .build();

    private static final StreamSchema OTHER = StreamSchema.builder("other")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("event_time", Types.timestamp())
            .build();

    private static final StreamSchema THIRD = StreamSchema.builder("third")
            .field("user_id", Types.string())
            .field("score", Types.int64())
            .build();

    private static final StreamSchema DIM = StreamSchema.builder("dim")
            .field("user_id", Types.string())
            .field("tier", Types.string())
            .build();

    private static final String TUMBLING = "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND))";

    /** One row of the support matrix: what it is, the SQL, and {@code "OK"} or the PRV code. */
    private record Case(String label, String sql, String expected, boolean lookup) {
        static Case ok(String label, String sql) {
            return new Case(label, sql, "OK", false);
        }

        static Case refused(String label, String sql, String code) {
            return new Case(label, sql, code, false);
        }

        static Case lookupOk(String label, String sql) {
            return new Case(label, sql, "OK", true);
        }
    }

    private static final List<Case> MATRIX = List.of(
            // --- Projection -------------------------------------------------------------------
            Case.ok("columns", "SELECT txn_id, amount FROM txn"),
            Case.ok("star", "SELECT * FROM txn"),
            Case.ok("column alias", "SELECT amount AS a FROM txn"),
            Case.ok("table alias with AS", "SELECT t.txn_id, t.amount FROM txn AS t"),
            Case.ok("table alias without AS", "SELECT t.txn_id FROM txn t"),
            Case.ok("qualified column in WHERE", "SELECT t.txn_id FROM txn AS t WHERE t.amount > 1"),
            Case.ok("unqualified column while aliased", "SELECT txn_id FROM txn AS t"),
            Case.ok("qualified star", "SELECT t.* FROM txn AS t"),
            Case.ok(
                    "table alias on both sides of a join",
                    "SELECT t.txn_id, o.region FROM txn AS t JOIN other AS o ON t.user_id = o.user_id"),
            // An alias may shadow the name of a different registered stream. Standard SQL: inside
            // this query `other` means txn, because the alias hides the base name.
            Case.ok("alias shadowing another stream's name", "SELECT other.txn_id FROM txn AS other"),
            Case.ok("integer arithmetic", "SELECT amount * 2 + 1 FROM txn"),
            Case.ok("floating arithmetic", "SELECT price / 2 FROM txn"),
            Case.ok("CAST", "SELECT CAST(amount AS DOUBLE) FROM txn"),
            Case.ok("literal", "SELECT 1 FROM txn"),
            Case.refused("CASE", "SELECT CASE WHEN amount > 1 THEN 1 ELSE 0 END FROM txn", "PRV-2021"),
            Case.refused("scalar function", "SELECT ABS(amount) FROM txn", "PRV-2021"),
            Case.refused("string function", "SELECT UPPER(user_id) FROM txn", "PRV-2021"),
            Case.refused("string concatenation", "SELECT user_id || 'x' FROM txn", "PRV-2021"),
            Case.refused("SELECT DISTINCT (over a stream)", "SELECT DISTINCT user_id FROM txn", "PRV-2050"),

            // --- WHERE ------------------------------------------------------------------------
            Case.ok("comparison", "SELECT txn_id FROM txn WHERE amount > 100"),
            Case.ok("AND, OR, NOT", "SELECT txn_id FROM txn WHERE amount > 1 AND (NOT flagged OR amount < 9)"),
            Case.ok("IN list", "SELECT txn_id FROM txn WHERE user_id IN ('a','b')"),
            Case.ok("BETWEEN", "SELECT txn_id FROM txn WHERE amount BETWEEN 1 AND 9"),
            Case.ok("IS NULL", "SELECT txn_id FROM txn WHERE status IS NULL"),
            Case.ok("arithmetic in a predicate", "SELECT txn_id FROM txn WHERE amount * 2 > 100"),
            Case.ok("boolean column", "SELECT txn_id FROM txn WHERE flagged"),
            Case.refused("LIKE", "SELECT txn_id FROM txn WHERE user_id LIKE 'u%'", "PRV-2021"),
            Case.refused("text inequality", "SELECT txn_id FROM txn WHERE status > user_id", "PRV-2021"),
            Case.refused("comparing text to a number", "SELECT txn_id FROM txn WHERE amount > txn_id", "PRV-2021"),

            // --- Aggregation ------------------------------------------------------------------
            Case.ok("global COUNT(*)", "SELECT COUNT(*) FROM txn"),
            Case.ok(
                    "TUMBLE",
                    "SELECT window_start, window_end, user_id, COUNT(*) FROM " + TUMBLING
                            + " GROUP BY window_start, window_end, user_id"),
            Case.ok(
                    "HOP",
                    "SELECT window_start, window_end, COUNT(*) FROM TABLE(HOP(TABLE txn, "
                            + "DESCRIPTOR(event_time), INTERVAL '5' SECOND, INTERVAL '10' SECOND)) "
                            + "GROUP BY window_start, window_end"),
            Case.ok(
                    "SUM, MIN, MAX, AVG",
                    "SELECT window_start, window_end, SUM(amount), MIN(amount), MAX(amount), AVG(amount) FROM "
                            + TUMBLING + " GROUP BY window_start, window_end"),
            Case.ok(
                    "COUNT(DISTINCT)",
                    "SELECT window_start, window_end, COUNT(DISTINCT user_id) FROM " + TUMBLING
                            + " GROUP BY window_start, window_end"),
            Case.ok(
                    "aggregate over an expression",
                    "SELECT window_start, window_end, SUM(amount * 2) FROM " + TUMBLING
                            + " GROUP BY window_start, window_end"),
            Case.ok(
                    "HAVING",
                    "SELECT window_start, window_end, COUNT(*) FROM " + TUMBLING
                            + " GROUP BY window_start, window_end HAVING COUNT(*) > 1"),
            Case.refused("GROUP BY without a window", "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id", "PRV-2050"),
            Case.refused(
                    "SESSION window",
                    "SELECT window_start, window_end, COUNT(*) FROM TABLE(SESSION(TABLE txn, "
                            + "DESCRIPTOR(event_time), DESCRIPTOR(user_id), INTERVAL '5' SECOND)) "
                            + "GROUP BY window_start, window_end",
                    "PRV-2020"),

            // --- Joins ------------------------------------------------------------------------
            Case.ok("inner equi-join", "SELECT t.txn_id FROM txn t JOIN other o ON t.user_id = o.user_id"),
            Case.ok(
                    "multi-column equi-join",
                    "SELECT t.txn_id FROM txn t JOIN other o "
                            + "ON t.user_id = o.user_id AND t.event_time = o.event_time"),
            Case.ok(
                    "three-way join, three distinct streams",
                    "SELECT t.txn_id FROM txn t JOIN other o ON t.user_id = o.user_id "
                            + "JOIN third d ON t.user_id = d.user_id"),
            // Refused when the pipeline is built, not when the plan is: both sides read one stream,
            // and rows enter a join by stream name, which cannot say which side a row is for.
            Case.refused(
                    "self join",
                    "SELECT a.txn_id FROM txn a JOIN txn b ON a.user_id = b.user_id",
                    "stream 'txn' appears on both sides of this plan; self-joins are not supported yet"),
            Case.lookupOk(
                    "lookup join against a dimension",
                    "SELECT t.txn_id, d.tier FROM txn t JOIN dim d ON t.user_id = d.user_id"),
            Case.refused(
                    "LEFT join", "SELECT t.txn_id FROM txn t LEFT JOIN other o ON t.user_id = o.user_id", "PRV-2020"),
            Case.refused(
                    "RIGHT join", "SELECT t.txn_id FROM txn t RIGHT JOIN other o ON t.user_id = o.user_id", "PRV-2020"),
            Case.refused(
                    "FULL join", "SELECT t.txn_id FROM txn t FULL JOIN other o ON t.user_id = o.user_id", "PRV-2020"),
            Case.refused("CROSS join", "SELECT t.txn_id FROM txn t CROSS JOIN other o", "PRV-2020"),
            Case.refused(
                    "non-equi join", "SELECT t.txn_id FROM txn t JOIN other o ON t.user_id > o.user_id", "PRV-2020"),

            // --- Sorting, sets, subqueries ----------------------------------------------------
            Case.ok("derived table", "SELECT x.user_id FROM (SELECT user_id FROM txn) x"),
            Case.ok("WITH (common table expression)", "WITH x AS (SELECT user_id FROM txn) SELECT user_id FROM x"),
            Case.ok("SELECT STREAM", "SELECT STREAM txn_id FROM txn"),
            Case.refused("ORDER BY", "SELECT txn_id FROM txn ORDER BY amount", "PRV-2020"),
            Case.refused("LIMIT", "SELECT txn_id FROM txn LIMIT 5", "PRV-2020"),
            Case.refused("OFFSET", "SELECT txn_id FROM txn OFFSET 5 ROWS", "PRV-2020"),
            Case.refused("UNION", "SELECT user_id FROM txn UNION SELECT user_id FROM other", "PRV-2020"),
            Case.refused("UNION ALL", "SELECT user_id FROM txn UNION ALL SELECT user_id FROM other", "PRV-2020"),
            Case.refused("INTERSECT", "SELECT user_id FROM txn INTERSECT SELECT user_id FROM other", "PRV-2020"),
            Case.refused("EXCEPT", "SELECT user_id FROM txn EXCEPT SELECT user_id FROM other", "PRV-2020"),
            Case.refused(
                    "IN (subquery)", "SELECT txn_id FROM txn WHERE user_id IN (SELECT user_id FROM other)", "PRV-2021"),
            Case.refused(
                    "EXISTS",
                    "SELECT txn_id FROM txn WHERE EXISTS (SELECT 1 FROM other WHERE other.user_id = txn.user_id)",
                    "PRV-2021"),
            Case.refused("scalar subquery", "SELECT txn_id, (SELECT COUNT(*) FROM other) FROM txn", "PRV-2021"),
            Case.refused(
                    "window function (OVER)",
                    "SELECT ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY event_time) FROM txn",
                    "PRV-2021"),
            Case.refused("VALUES", "SELECT * FROM (VALUES (1), (2))", "PRV-2020"),

            // --- Not a query engine for writes ------------------------------------------------
            Case.refused(
                    "INSERT",
                    "INSERT INTO other (user_id, region, event_time) VALUES ('a','b', CURRENT_TIMESTAMP)",
                    "PRV-2020"),
            Case.refused("UPDATE", "UPDATE txn SET amount = 1 WHERE amount > 1", "PRV-2020"),
            Case.refused("DELETE", "DELETE FROM txn WHERE amount > 1", "PRV-2020"));

    @Test
    void theSupportMatrixIsWhatTheDocumentationSaysItIs() {
        StringBuilder drifted = new StringBuilder();
        for (Case testCase : MATRIX) {
            String actual = outcomeOf(testCase);
            if (!actual.equals(testCase.expected())) {
                drifted.append("\n  ")
                        .append(testCase.label())
                        .append(": documented as ")
                        .append(testCase.expected())
                        .append(", actually ")
                        .append(actual);
            }
        }
        assertThat(drifted.toString())
                .as(
                        "SQL support has changed. Update docs/SQL_SUPPORT.md to match, then this matrix. "
                                + "A construct that quietly starts or stops working is how a user finds out by "
                                + "trying it in production.%s",
                        drifted)
                .isEmpty();
    }

    @Test
    void everyRefusalCarriesAnErrorCodeAndAnExplanation() {
        for (Case testCase : MATRIX) {
            if (testCase.expected().equals("OK")) {
                continue;
            }
            String message = messageOf(testCase);
            if (!message.startsWith("PRV-")) {
                // One refusal has no PRV code: the self-join check lives in the pipeline builder and
                // throws UnsupportedOperationException. That is a gap worth naming rather than
                // papering over -- every refusal a user can reach should carry a code they can look
                // up -- so it is allowed here by name and nowhere else.
                assertThat(testCase.label())
                        .as("a refusal without a PRV code: '%s'", message)
                        .isEqualTo("self join");
                continue;
            }
            // Not a bare code. A refusal a user cannot act on costs a support call, and the whole
            // point of refusing rather than approximating is that the message says what to do.
            assertThat(message.length())
                    .as("%s is refused with a code and almost no explanation: '%s'", testCase.label(), message)
                    .isGreaterThan(40);
        }
    }

    private static String outcomeOf(Case testCase) {
        String message = messageOf(testCase);
        if (message == null) {
            return "OK";
        }
        return message.startsWith("PRV-") ? message.substring(0, 8) : message;
    }

    /**
     * The failure message, or null if the statement planned, built <em>and</em> compiled into a
     * runnable pipeline.
     *
     * <p>That last step is the one that matters and was once missing here. Planning a statement and
     * being able to run it are different things: a self-join plans perfectly and is refused when the
     * pipeline is built, because both sides would read one stream and a stream name cannot say which
     * side a row belongs to. A matrix that stopped at the planner called that supported, and the
     * documentation it backs repeated the claim. "Supported" has to mean executable.
     */
    private static String messageOf(Case testCase) {
        SqlPlanner planner =
                testCase.lookup() ? SqlPlanner.withLookups(TXN, DIM) : SqlPlanner.withStreams(TXN, OTHER, THIRD);
        try {
            PhysicalOperator plan = new PhysicalPlanBuilder().build(planner.plan(testCase.sql()));
            try (InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, () -> {
                throw new UnsupportedOperationException("the matrix builds pipelines but never runs rows through them");
            })) {
                return null;
            }
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage()).replace('\n', ' ');
        }
    }
}
