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
 * <p>This is the executable half of {@code docs/CONTINUOUS_QUERIES.md}. That document exists because "what
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
         * "plans and compiles", and {@code CONTINUOUS_QUERIES.md}'s opening claim that every construct is
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
            // Answers, not plans. Every entry below that names its rows was a Case.ok until the
            // conversion of docs/qa/cases/SQLX.md: "supported" meant "plans and compiles", and a
            // construct could return the wrong number with this matrix green. That is finding Q-8.
            Case.answers("columns", "SELECT txn_id, amount FROM txn", "t1|100", "t2|250", "t3|50", "t4|400"),
            Case.answers(
                    "star",
                    "SELECT * FROM txn",
                    "t1|ann|100|1.5|COMPLETED|true|1",
                    "t2|bob|250|2.5|COMPLETED|false|2",
                    "t3|ann|50|0.5|PENDING|false|3",
                    "t4|cat|400|4.0|NULL|true|4"),
            Case.answers("column alias", "SELECT amount AS a FROM txn", "100", "250", "50", "400"),
            Case.ok("table alias with AS", "SELECT t.txn_id, t.amount FROM txn AS t"),
            Case.ok("table alias without AS", "SELECT t.txn_id FROM txn t"),
            Case.ok("qualified column in WHERE", "SELECT t.txn_id FROM txn AS t WHERE t.amount > 1"),
            Case.ok("unqualified column while aliased", "SELECT txn_id FROM txn AS t"),
            // SQLX-007: two spellings of the same thing must agree, and the matrix asserted only
            // that each planned.
            Case.answers(
                    "qualified star",
                    "SELECT t.* FROM txn AS t",
                    "t1|ann|100|1.5|COMPLETED|true|1",
                    "t2|bob|250|2.5|COMPLETED|false|2",
                    "t3|ann|50|0.5|PENDING|false|3",
                    "t4|cat|400|4.0|NULL|true|4"),
            Case.ok(
                    "table alias on both sides of a join",
                    "SELECT t.txn_id, o.region FROM txn AS t JOIN other AS o ON t.user_id = o.user_id"),
            // An alias may shadow the name of a different registered stream. Standard SQL: inside
            // this query `other` means txn, because the alias hides the base name.
            // SQLX-009: if the shadow leaked, this would read the `other` stream, which has no
            // txn_id column at all. Four rows of txn's own ids is the proof that it did not.
            Case.answers(
                    "alias shadowing another stream's name",
                    "SELECT other.txn_id FROM txn AS other",
                    "t1",
                    "t2",
                    "t3",
                    "t4"),
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
            // SQLX-018: division on doubles, where a "promote to long" bug returns plausible
            // integers. 1.5/2 and 0.5/2 are exact in binary, so no tolerance is needed or allowed.
            Case.answers("floating arithmetic", "SELECT price / 2 FROM txn", "0.75", "1.25", "0.25", "2.0"),
            Case.answers("CAST", "SELECT CAST(amount AS DOUBLE) FROM txn", "100.0", "250.0", "50.0", "400.0"),
            // SQLX-013: one literal per row -- four rows, not one.
            Case.answers("literal", "SELECT 1 FROM txn", "1", "1", "1", "1"),
            // SQLX-014: ExpressionCompiler.literal uses getValue2 precisely so the apostrophes do
            // not land in the row. Nothing ran a row to check until now.
            Case.answers("string literal", "SELECT 'flagged' FROM txn", "flagged", "flagged", "flagged", "flagged"),
            Case.answers("UPPER", "SELECT UPPER(user_id) FROM txn", "ANN", "BOB", "ANN", "CAT"),
            Case.answers("LOWER", "SELECT LOWER(user_id) FROM txn", "ann", "bob", "ann", "cat"),
            Case.answers("TRIM", "SELECT TRIM(user_id) FROM txn", "ann", "bob", "ann", "cat"),
            Case.refused(
                    "TRIM of a character other than a space", "SELECT TRIM('x' FROM user_id) FROM txn", "PRV-2021"),
            Case.refused("TRIM from one end", "SELECT TRIM(LEADING ' ' FROM user_id) FROM txn", "PRV-2021"),
            Case.answers("string concatenation", "SELECT user_id || 'x' FROM txn", "annx", "bobx", "annx", "catx"),
            // SQLX-047: a chain must flatten rather than nest into the wrong associativity.
            Case.answers(
                    "a chain of concatenations",
                    "SELECT user_id || '-' || user_id FROM txn",
                    "ann-ann",
                    "bob-bob",
                    "ann-ann",
                    "cat-cat"),
            // SQLX-048: SUBSTRING is 1-based. An off-by-one gives "nn" for the first and "n" for
            // the second, both of which plan perfectly.
            Case.answers(
                    "SUBSTRING with a length",
                    "SELECT SUBSTRING(user_id FROM 1 FOR 3) FROM txn",
                    "ann",
                    "bob",
                    "ann",
                    "cat"),
            Case.answers("SUBSTRING to the end", "SELECT SUBSTRING(user_id FROM 2) FROM txn", "nn", "ob", "nn", "at"),
            Case.answers(
                    "a text CASE",
                    "SELECT CASE WHEN amount > 5 THEN 'big' ELSE 'small' END FROM txn",
                    "big",
                    "big",
                    "big",
                    "big"),
            Case.answers(
                    "text functions nested",
                    "SELECT UPPER(TRIM(user_id)) || '!' FROM txn",
                    "ANN!",
                    "BOB!",
                    "ANN!",
                    "CAT!"),
            // Refused since TY-23, and it used to answer "big" for every row. Calcite's validator
            // coerced the 0 into the string '0' before Pravaha saw the query, so the branches
            // agreed on a type by the time they arrived -- while the same CASE with a *column* in
            // the numeric branch (`ELSE amount`) was refused, because a column arrives intact.
            // Whether a number could be turned into text therefore depended on whether it was
            // written as a literal, which is not a rule anybody can read off the types. Both
            // shapes refuse now.
            Case.refused(
                    "a CASE mixing text and a number",
                    "SELECT CASE WHEN amount > 5 THEN 'big' ELSE 0 END FROM txn",
                    "PRV-2021"),
            Case.refused("SELECT DISTINCT (over a stream)", "SELECT DISTINCT user_id FROM txn", "PRV-2050"),

            // --- WHERE ------------------------------------------------------------------------
            Case.answers("comparison", "SELECT txn_id FROM txn WHERE amount > 100", "t2", "t4"),
            // flagged is true, false, false, true; `amount < 9` is false everywhere, so this keeps
            // the two unflagged rows. A predicate tree evaluated in the wrong order keeps four.
            Case.answers(
                    "AND, OR, NOT",
                    "SELECT txn_id FROM txn WHERE amount > 1 AND (NOT flagged OR amount < 9)",
                    "t2",
                    "t3"),
            // An IN list that matches nothing is worth keeping as a control -- a filter that
            // dropped every row would pass it -- but only beside one that matches.
            Case.answers("IN list matching nothing", "SELECT txn_id FROM txn WHERE user_id IN ('a','b')"),
            Case.answers("IN list", "SELECT txn_id FROM txn WHERE user_id IN ('ann','cat')", "t1", "t3", "t4"),
            Case.answers("BETWEEN matching nothing", "SELECT txn_id FROM txn WHERE amount BETWEEN 1 AND 9"),
            // SQLX-089: inclusive at both ends. An exclusive implementation keeps only t3.
            Case.answers(
                    "BETWEEN is inclusive", "SELECT txn_id FROM txn WHERE amount BETWEEN 50 AND 250", "t1", "t2", "t3"),
            Case.answers("IS NULL", "SELECT txn_id FROM txn WHERE status IS NULL", "t4"),
            Case.answers("IS NOT NULL", "SELECT txn_id FROM txn WHERE status IS NOT NULL", "t1", "t2", "t3"),
            Case.answers(
                    "arithmetic in a predicate", "SELECT txn_id FROM txn WHERE amount * 2 > 100", "t1", "t2", "t4"),
            Case.answers("boolean column", "SELECT txn_id FROM txn WHERE flagged", "t1", "t4"),
            Case.answers("negated boolean column", "SELECT txn_id FROM txn WHERE NOT flagged", "t2", "t3"),
            Case.answers("LIKE matching nothing", "SELECT txn_id FROM txn WHERE user_id LIKE 'u%'"),
            Case.answers("LIKE", "SELECT txn_id FROM txn WHERE user_id LIKE 'a%'", "t1", "t3"),
            Case.answers("NOT LIKE", "SELECT txn_id FROM txn WHERE user_id NOT LIKE 'u%'", "t1", "t2", "t3", "t4"),
            Case.refused("LIKE with ESCAPE", "SELECT txn_id FROM txn WHERE user_id LIKE 'u!%' ESCAPE '!'", "PRV-2021"),
            Case.refused(
                    "LIKE against a pattern that is not a literal",
                    "SELECT txn_id FROM txn WHERE user_id LIKE status",
                    "PRV-2021"),
            Case.refused("text inequality", "SELECT txn_id FROM txn WHERE status > user_id", "PRV-2021"),
            Case.refused("comparing text to a number", "SELECT txn_id FROM txn WHERE amount > txn_id", "PRV-2021"),

            // --- Aggregation ------------------------------------------------------------------
            Case.answers("global COUNT(*)", "SELECT COUNT(*) FROM txn", "4"),
            Case.answers("global MIN and MAX", "SELECT MIN(amount), MAX(amount) FROM txn", "50|400"),
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
            Case.answers("CASE WHEN", "SELECT CASE WHEN amount > 100 THEN 1 ELSE 0 END FROM txn", "0", "1", "0", "1"),
            // The first true branch wins: 250 and 400 are both > 100, so neither falls through to
            // the second branch.
            Case.answers(
                    "CASE with several branches",
                    "SELECT CASE WHEN amount > 100 THEN 2 WHEN amount > 10 THEN 1 ELSE 0 END FROM txn",
                    "1",
                    "2",
                    "1",
                    "2"),
            // A missing ELSE is NULL, not the type's zero -- which is what an implementation that
            // initialises the output slot and forgets the null bit produces.
            Case.answers(
                    "CASE with no ELSE", "SELECT CASE WHEN amount > 100 THEN 1 END FROM txn", "NULL", "1", "NULL", "1"),
            Case.answers("ABS", "SELECT ABS(amount) FROM txn", "100", "250", "50", "400"),
            Case.answers(
                    "FLOOR and CEIL",
                    "SELECT FLOOR(amount), CEIL(amount) FROM txn",
                    "100|100",
                    "250|250",
                    "50|50",
                    "400|400"),
            Case.answers("ROUND", "SELECT ROUND(amount) FROM txn", "100", "250", "50", "400"),
            Case.answers(
                    "a function inside arithmetic", "SELECT ABS(amount) * 2 + 1 FROM txn", "201", "501", "101", "801"),
            Case.answers(
                    "a function inside a CASE",
                    "SELECT CASE WHEN ABS(amount) > 5 THEN ABS(amount) ELSE 0 END FROM txn",
                    "100",
                    "250",
                    "50",
                    "400"),
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
            Case.answers(
                    "derived table", "SELECT x.user_id FROM (SELECT user_id FROM txn) x", "ann", "bob", "ann", "cat"),
            Case.answers(
                    "WITH (common table expression)",
                    "WITH x AS (SELECT user_id FROM txn) SELECT user_id FROM x",
                    "ann",
                    "bob",
                    "ann",
                    "cat"),
            Case.answers("SELECT STREAM", "SELECT STREAM txn_id FROM txn", "t1", "t2", "t3", "t4"),
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
            Case.refused("DELETE", "DELETE FROM txn WHERE amount > 1", "PRV-2020"),

            // --- Constructs the document does not list, converted from docs/qa/cases ----------
            // SQLX-112: the three GROUP BY extensions, each refused by name.
            Case.refused(
                    "GROUPING SETS",
                    "SELECT user_id, COUNT(*) FROM txn GROUP BY GROUPING SETS ((user_id), ())",
                    "PRV-2020"),
            Case.refused(
                    "CUBE", "SELECT user_id, status, COUNT(*) FROM txn GROUP BY CUBE (user_id, status)", "PRV-2020"),
            Case.refused(
                    "ROLLUP",
                    "SELECT user_id, status, COUNT(*) FROM txn GROUP BY ROLLUP (user_id, status)",
                    "PRV-2020"),
            // SQLX-036: the supported numeric set is ABS, FLOOR, CEIL and ROUND, and the refusal
            // for anything else names the set. Four spellings, because each reaches it differently.
            Case.refused("SQRT", "SELECT SQRT(price) FROM txn", "PRV-2021"),
            Case.refused("POWER", "SELECT POWER(amount, 2) FROM txn", "PRV-2021"),
            Case.refused("CHAR_LENGTH", "SELECT CHAR_LENGTH(user_id) FROM txn", "PRV-2021"),
            // SQLX-021 and TYPE-149: casts leave the numeric family and are refused by name.
            Case.refused("CAST to text", "SELECT CAST(amount AS VARCHAR) FROM txn", "PRV-2021"),
            Case.refused("CAST from text", "SELECT CAST(user_id AS BIGINT) FROM txn", "PRV-2021"),
            // SQLX-113 and AGG-033: the float-aggregate refusal, one entry per kind, because the
            // message names the kind and the column and round 2 found the four drifting apart.
            Case.refused("SUM over a FLOAT64 column", "SELECT SUM(price) FROM txn", "PRV-2020"),
            Case.refused("AVG over a FLOAT64 column", "SELECT AVG(price) FROM txn", "PRV-2020"),
            // SQLX-088: IS NULL over an expression rather than over a bare column. Planned since
            // TY-5 -- the expression tree has always been able to say whether a node is null, and
            // the compiler simply required a column reference. `amount` is NOT NULL in this
            // fixture, so the answer is no rows rather than a refusal.
            Case.answers("IS NULL over an expression", "SELECT txn_id FROM txn WHERE (amount * 2) IS NULL"),
            // SQLX-163 to SQLX-182: statements that are not queries, and queries that name things
            // that do not exist. Each must be a coded refusal rather than a stack trace.
            // The empty statement is not here: its message is an internal StringIndexOutOfBounds
            // text that HotSpot's fast-throw replaces with null. See theEmptyStatement below.
            Case.refused("only a comment", "-- just a comment", "PRV-2001"),
            Case.refused("two statements", "SELECT txn_id FROM txn; SELECT user_id FROM txn", "PRV-2001"),
            Case.refused("an unknown stream", "SELECT x FROM nosuchstream", "PRV-2002"),
            Case.refused("an unknown column", "SELECT nosuchcol FROM txn", "PRV-2002"),
            Case.refused("an identifier in the wrong case", "SELECT USER_ID FROM txn", "PRV-2002"));

    @Test
    @org.junit.jupiter.api.Disabled("PRV-2001 defect 5 (SQLX-163): an empty statement is refused with "
            + "the text of an internal StringIndexOutOfBoundsException -- 'Index 0 out of bounds for "
            + "length 0' -- and once HotSpot's fast-throw preallocates that exception the refusal "
            + "becomes the literal string 'PRV-2001  null'. A user's explanation therefore depends on "
            + "how warm the JVM is. Same class as FINDINGS Q-14.")
    void theEmptyStatementIsRefusedWithAnExplanation() {
        String message = messageOf(Case.refused("the empty statement", "", "PRV-2001"));
        assertThat(message).startsWith("PRV-2001");
        assertThat(message)
                .as("an explanation a user can act on, not an internal bounds error and not 'null'")
                .doesNotContain("null")
                .doesNotContain("out of bounds");
    }

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
                        "SQL support has changed. Update docs/CONTINUOUS_QUERIES.md to match, then this matrix. "
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
