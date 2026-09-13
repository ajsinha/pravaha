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
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

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
    private record Case(String label, String sql, String expected, boolean lookup, List<String> answer) {

        Case(String label, String sql, String expected, boolean lookup) {
            this(label, sql, expected, lookup, null);
        }

        /**
         * A case that also asserts the rows the construct produces.
         *
         * <p>The matrix had no such thing. It planned each statement, built a pipeline, and used a
         * {@code RowOutput} that threw if anything tried to write a row -- so "supported" meant
         * "plans and compiles", and {@code SQL_SUPPORT.md}'s opening claim that every construct is
         * checked by a test was true only of the half that cannot produce a wrong number. None of
         * the wrong-answer defects in this engine would have failed this build.
         *
         * @param answer each output row rendered as its values joined by '|', in order
         */
        static Case answers(String label, String sql, String... answer) {
            return new Case(label, sql, "OK", false, List.of(answer));
        }

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
            // Answers, not just plans. amount is 100, 250, 50, NULL, so amount*2+1 is
            // 201, 501, 101 and NULL -- null propagating rather than becoming 1.
            // Answers, not only plans. amount is 100, 250, 50, 400, so amount*2+1 is
            // 2*100+1=201, 2*250+1=501, 2*50+1=101, 2*400+1=801.
            Case.answers("integer arithmetic", "SELECT amount * 2 + 1 FROM txn", "201", "501", "101", "801"),
            // 100, 250 and 400 exceed 60; 50 does not.
            Case.answers(
                    "filter keeps the matching rows", "SELECT user_id FROM txn WHERE amount > 60", "ann", "bob", "cat"),
            Case.answers(
                    "CASE chooses the branch",
                    "SELECT CASE WHEN amount > 60 THEN 'big' ELSE 'small' END FROM txn",
                    "big",
                    "big",
                    "small",
                    "big"),
            Case.answers("text functions", "SELECT UPPER(user_id) || '!' FROM txn WHERE amount = 250", "BOB!"),
            // status is null for t4, so COUNT(*) is 4 and COUNT(status) is 3. These disagreed in two
            // of the three aggregate operators, each emitting a row that contradicted its own SUM.
            Case.answers("COUNT(*) counts rows", "SELECT COUNT(*) FROM txn", "4"),
            Case.answers("COUNT(col) skips nulls", "SELECT COUNT(status) FROM txn", "3"),
            // 100 + 250 + 50 + 400 = 800, and 800 / 4 = 200.
            Case.answers("SUM over a view", "SELECT SUM(amount) FROM txn", "800"),
            Case.answers("AVG divides", "SELECT AVG(amount) FROM txn", "200"),
            // status is null for cat, and must stay null rather than becoming an empty string.
            Case.answers("null survives a projection", "SELECT status FROM txn WHERE user_id = 'cat'", "NULL"),
            Case.ok("floating arithmetic", "SELECT price / 2 FROM txn"),
            Case.ok("CAST", "SELECT CAST(amount AS DOUBLE) FROM txn"),
            Case.ok("literal", "SELECT 1 FROM txn"),
            Case.ok("string literal", "SELECT 'flagged' FROM txn"),
            Case.ok("UPPER", "SELECT UPPER(user_id) FROM txn"),
            Case.ok("LOWER", "SELECT LOWER(user_id) FROM txn"),
            Case.ok("TRIM", "SELECT TRIM(user_id) FROM txn"),
            Case.refused(
                    "TRIM of a character other than a space", "SELECT TRIM('x' FROM user_id) FROM txn", "PRV-2021"),
            Case.refused("TRIM from one end", "SELECT TRIM(LEADING ' ' FROM user_id) FROM txn", "PRV-2021"),
            Case.ok("string concatenation", "SELECT user_id || 'x' FROM txn"),
            Case.ok("a chain of concatenations", "SELECT user_id || '-' || user_id FROM txn"),
            Case.ok("SUBSTRING with a length", "SELECT SUBSTRING(user_id FROM 1 FOR 3) FROM txn"),
            Case.ok("SUBSTRING to the end", "SELECT SUBSTRING(user_id FROM 2) FROM txn"),
            Case.ok("a text CASE", "SELECT CASE WHEN amount > 5 THEN 'big' ELSE 'small' END FROM txn"),
            Case.ok("text functions nested", "SELECT UPPER(TRIM(user_id)) || '!' FROM txn"),
            // Accepted, and worth knowing why it is not refused: Calcite's validator coerces the 0
            // to the string '0' before Pravaha sees the query, so the branches do agree on a type by
            // the time they arrive. The result is text -- a downstream SUM of it will not plan.
            Case.ok("a CASE mixing text and a number", "SELECT CASE WHEN amount > 5 THEN 'big' ELSE 0 END FROM txn"),
            Case.refused("SELECT DISTINCT (over a stream)", "SELECT DISTINCT user_id FROM txn", "PRV-2050"),

            // --- WHERE ------------------------------------------------------------------------
            Case.ok("comparison", "SELECT txn_id FROM txn WHERE amount > 100"),
            Case.ok("AND, OR, NOT", "SELECT txn_id FROM txn WHERE amount > 1 AND (NOT flagged OR amount < 9)"),
            Case.ok("IN list", "SELECT txn_id FROM txn WHERE user_id IN ('a','b')"),
            Case.ok("BETWEEN", "SELECT txn_id FROM txn WHERE amount BETWEEN 1 AND 9"),
            Case.ok("IS NULL", "SELECT txn_id FROM txn WHERE status IS NULL"),
            Case.ok("arithmetic in a predicate", "SELECT txn_id FROM txn WHERE amount * 2 > 100"),
            Case.ok("boolean column", "SELECT txn_id FROM txn WHERE flagged"),
            Case.ok("LIKE", "SELECT txn_id FROM txn WHERE user_id LIKE 'u%'"),
            Case.ok("NOT LIKE", "SELECT txn_id FROM txn WHERE user_id NOT LIKE 'u%'"),
            Case.refused("LIKE with ESCAPE", "SELECT txn_id FROM txn WHERE user_id LIKE 'u!%' ESCAPE '!'", "PRV-2021"),
            Case.refused(
                    "LIKE against a pattern that is not a literal",
                    "SELECT txn_id FROM txn WHERE user_id LIKE status",
                    "PRV-2021"),
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
            // --- Expressions -----------------------------------------------------------------
            Case.ok("CASE WHEN", "SELECT CASE WHEN amount > 100 THEN 1 ELSE 0 END FROM txn"),
            Case.ok(
                    "CASE with several branches",
                    "SELECT CASE WHEN amount > 100 THEN 2 WHEN amount > 10 THEN 1 ELSE 0 END FROM txn"),
            Case.ok("CASE with no ELSE", "SELECT CASE WHEN amount > 100 THEN 1 END FROM txn"),
            Case.ok("ABS", "SELECT ABS(amount) FROM txn"),
            Case.ok("FLOOR and CEIL", "SELECT FLOOR(amount), CEIL(amount) FROM txn"),
            Case.ok("ROUND", "SELECT ROUND(amount) FROM txn"),
            Case.ok("a function inside arithmetic", "SELECT ABS(amount) * 2 + 1 FROM txn"),
            Case.ok(
                    "a function inside a CASE",
                    "SELECT CASE WHEN ABS(amount) > 5 THEN ABS(amount) ELSE 0 END FROM txn"),
            Case.refused("ROUND to decimal places", "SELECT ROUND(amount, 2) FROM txn", "PRV-2021"),
            Case.ok("inner equi-join", "SELECT t.txn_id FROM txn t JOIN other o ON t.user_id = o.user_id"),
            Case.ok(
                    "multi-column equi-join",
                    "SELECT t.txn_id FROM txn t JOIN other o "
                            + "ON t.user_id = o.user_id AND t.event_time = o.event_time"),
            Case.ok(
                    "equi-join with a time bound",
                    "SELECT t.txn_id FROM txn t JOIN other o ON t.user_id = o.user_id "
                            + "AND t.event_time BETWEEN o.event_time - INTERVAL '5' MINUTE AND o.event_time"),
            Case.ok(
                    "equi-join with a one-sided time bound",
                    "SELECT t.txn_id FROM txn t JOIN other o ON t.user_id = o.user_id "
                            + "AND t.event_time >= o.event_time - INTERVAL '30' SECOND"),
            Case.refused(
                    "time bound with no equality",
                    "SELECT t.txn_id FROM txn t JOIN other o "
                            + "ON t.event_time BETWEEN o.event_time - INTERVAL '5' MINUTE AND o.event_time",
                    "PRV-2020"),
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
            Case.ok(
                    "LEFT join with a time bound",
                    "SELECT t.txn_id FROM txn t LEFT JOIN other o ON t.user_id = o.user_id "
                            + "AND o.event_time BETWEEN t.event_time AND t.event_time + INTERVAL '5' MINUTE"),
            Case.refused(
                    "LEFT join with no time bound",
                    "SELECT t.txn_id FROM txn t LEFT JOIN other o ON t.user_id = o.user_id",
                    "PRV-2020"),
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
            assertThat(message)
                    .as(
                            "%s is in the matrix as refused with %s, and was accepted",
                            testCase.label(), testCase.expected())
                    .isNotNull();
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

    @Test
    void everyConstructWithADocumentedAnswerProducesIt() {
        List<String> wrong = new java.util.ArrayList<>();
        for (Case testCase : MATRIX) {
            if (testCase.answer() == null) {
                continue;
            }
            List<String> actual;
            try {
                actual = answerOf(testCase);
            } catch (RuntimeException e) {
                wrong.add(testCase.label() + ": threw " + e.getMessage());
                continue;
            }
            if (!actual.equals(testCase.answer())) {
                wrong.add(testCase.label() + ": expected " + testCase.answer() + " and produced " + actual);
            }
        }
        assertThat(wrong)
                .as("a construct that plans and returns the wrong rows is worse than one that is refused: "
                        + "the refusal is visible and the number is not")
                .isEmpty();
    }

    /** Runs the case's rows through a real pipeline and renders what came out. */
    private static List<String> answerOf(Case testCase) {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(TXN, OTHER, THIRD).plan(testCase.sql()));
        List<CapturingRowWriter.Captured> captured = new java.util.ArrayList<>();
        RowLayout layout = RowLayout.of(TXN);

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), captured::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (int i = 0; i < FIXTURE.size(); i++) {
                long handle = feed.allocate(layout.rowSize(256));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                FIXTURE.get(i).accept(writer);
                writer.weight(1L).eventTimestampNanos(i + 1L).sequence(i + 1L).commit();
                feed.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return captured.stream().map(SqlSupportMatrixTest::render).toList();
    }

    private static String render(CapturingRowWriter.Captured row) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < row.values().length; i++) {
            if (i > 0) {
                text.append('|');
            }
            text.append(row.isNull(i) ? "NULL" : String.valueOf(row.values()[i]));
        }
        return text.toString();
    }

    /**
     * The rows every answer case runs against.
     *
     * <p>One fixture for all of them, so an expected value is arithmetic over a table the reader can
     * see rather than a number that has to be trusted.
     *
     * <pre>
     * txn_id  user_id  amount  price  status      flagged  event_time
     * t1      ann      100     1.5    COMPLETED   true     1
     * t2      bob      250     2.5    COMPLETED   false    2
     * t3      ann      50      0.5    PENDING     false    3
     * t4      cat      400     4.0    NULL        true     4
     * </pre>
     */
    private static final List<java.util.function.Consumer<BinaryRowWriter>> FIXTURE = List.of(
            w -> row(w, 1, "ann", 100L, 1.5, "COMPLETED", true),
            w -> row(w, 2, "bob", 250L, 2.5, "COMPLETED", false),
            w -> row(w, 3, "ann", 50L, 0.5, "PENDING", false),
            w -> row(w, 4, "cat", 400L, 4.0, null, true));

    private static void row(
            BinaryRowWriter w, long id, String user, long amount, double price, String status, boolean flagged) {
        // txn_id is STRING in this schema, not a number. Writing a long into it failed at the row
        // writer -- which is the mechanism proving itself before it ever compared an answer.
        // status is the only nullable column in this schema, so it carries the null case.
        w.setString(0, "t" + id).setString(1, user).setLong(2, amount).setDouble(3, price);
        if (status == null) {
            w.setNull(4);
        } else {
            w.setString(4, status);
        }
        w.setBoolean(5, flagged).setLong(6, id);
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
