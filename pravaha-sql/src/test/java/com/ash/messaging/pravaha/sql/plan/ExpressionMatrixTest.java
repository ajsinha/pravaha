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
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.Field;
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
 * The authored QA cases of {@code docs/project/qa/cases/SQLX.md} and {@code TYPE.md}, made executable.
 *
 * <p>Those files hold 2711 and 3384 lines of prose describing what this engine should answer. Prose
 * is read once by one person. Everything here whose expected value is a concrete number or string
 * is now a row in a matrix that runs in every build, against the fixture the case files themselves
 * specify -- so a case's expected value is arithmetic over a table the reader can see rather than a
 * number that has to be trusted.
 *
 * <p>Why a second file rather than more rows in {@link SqlSupportMatrixTest}: that test's fixture is
 * four polite rows with no negative, no zero and no non-ASCII. Half of what SQLX exists to catch --
 * truncation towards zero versus towards negative infinity, the sign of a remainder, unary minus,
 * {@code ROUND} at the half-way point, a code point above the BMP -- cannot be distinguished
 * against it. This file carries SQLX's own Fixture D1 instead, unchanged, so a case can be
 * transplanted either way without a second table to keep in step.
 */
class ExpressionMatrixTest {

    /** SQLX Fixture S. {@code status} is the only nullable column, so it carries the null case. */
    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("txn_id", Types.int64())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("price", Types.float64())
            .field("status", Types.string().withNullable(true))
            .field("flagged", Types.bool())
            .field("event_time", Types.timestamp())
            // Declared, not merely present. A stream whose event time is not declared has no
            // watermark, so no window over it can ever close (TIME-6) -- a fixture that windows
            // has to be a stream a node could really window, or the matrix records the refusals of
            // a configuration nobody should be running.
            .eventTime("event_time")
            .build();

    private static final StreamSchema OTHER = StreamSchema.builder("other")
            .field("user_id", Types.string())
            .field("region", Types.string())
            .field("event_time", Types.timestamp())
            // Declared, not merely present. A stream whose event time is not declared has no
            // watermark, so no window over it can ever close (TIME-6) -- a fixture that windows
            // has to be a stream a node could really window, or the matrix records the refusals of
            // a configuration nobody should be running.
            .eventTime("event_time")
            .build();

    private static final long SECOND = 1_000_000_000L;

    /**
     * SQLX Fixture D1, six rows.
     *
     * <pre>
     * txn_id  user_id   amount  price  status    flagged  event_time
     * 1       u1        100     2.5    ok        true      1s
     * 2       u2        250     4.0    NULL      false     2s
     * 3       u1        -50     1.0    ok        false     3s
     * 4       u3        0       0.5    flagged   true      4s
     * 5       u2        7       1.5    ok        false    12s
     * 6       ünïcødé   7       0.25   ok        true     13s
     * </pre>
     *
     * <p>Hand-computed once, quoted by every case below: {@code COUNT(*) = 6},
     * {@code COUNT(status) = 5}, {@code SUM(amount) = 100 + 250 - 50 + 0 + 7 + 7 = 314},
     * {@code MIN = -50}, {@code MAX = 250}, {@code AVG = 314 / 6 = 52} under integer division.
     */
    private static final List<Consumer<BinaryRowWriter>> D1 = List.of(
            w -> row(w, 1, "u1", 100L, 2.5, "ok", true, 1 * SECOND),
            w -> row(w, 2, "u2", 250L, 4.0, null, false, 2 * SECOND),
            w -> row(w, 3, "u1", -50L, 1.0, "ok", false, 3 * SECOND),
            w -> row(w, 4, "u3", 0L, 0.5, "flagged", true, 4 * SECOND),
            w -> row(w, 5, "u2", 7L, 1.5, "ok", false, 12 * SECOND),
            w -> row(w, 6, "ünïcødé", 7L, 0.25, "ok", true, 13 * SECOND));

    /**
     * One converted case: the QA id it came from, the SQL, and what the case says must happen.
     *
     * <p>Three outcomes, and the difference between them is the point of several cases. A
     * <em>refusal</em> happens before a row is read, so the user is told at plan time and no data
     * moves. A <em>runtime failure</em> is a query that plans, builds, compiles, accepts rows and
     * then throws on one of them -- which is a worse experience and, where the QA case says so, a
     * finding in its own right. An <em>answer</em> is neither: it ran and these are the rows.
     */
    private record Case(
            String id,
            String sql,
            @Nullable List<String> answer,
            @Nullable String refusal,
            @Nullable String runtimeFailure) {

        static Case answers(String id, String sql, String... rows) {
            return new Case(id, sql, List.of(rows), null, null);
        }

        static Case refused(String id, String sql, String code) {
            return new Case(id, sql, null, code, null);
        }

        /** Plans and compiles, then throws while rows are being fed; the fragment must appear. */
        static Case fails(String id, String sql, String messageFragment) {
            return new Case(id, sql, null, null, messageFragment);
        }
    }

    private static final List<Case> MATRIX = List.of(
            // --- SQLX §1, projection ----------------------------------------------------------
            Case.answers(
                    "SQLX-001 SELECT * keeps every column, in schema order",
                    "SELECT * FROM txn",
                    "1|u1|100|2.5|ok|true|1000000000",
                    "2|u2|250|4.0|NULL|false|2000000000",
                    "3|u1|-50|1.0|ok|false|3000000000",
                    "4|u3|0|0.5|flagged|true|4000000000",
                    "5|u2|7|1.5|ok|false|12000000000",
                    "6|ünïcødé|7|0.25|ok|true|13000000000"),
            Case.answers(
                    "SQLX-002 a two-column projection emits exactly those two",
                    "SELECT txn_id, amount FROM txn",
                    "1|100",
                    "2|250",
                    "3|-50",
                    "4|0",
                    "5|7",
                    "6|7"),
            Case.answers(
                    "SQLX-003 the ordinal list follows the SELECT list, not the schema",
                    "SELECT amount, txn_id FROM txn",
                    "100|1",
                    "250|2",
                    "-50|3",
                    "0|4",
                    "7|5",
                    "7|6"),
            Case.answers(
                    "SQLX-008 an unqualified column while an alias is in scope",
                    "SELECT txn_id FROM txn AS t",
                    "1",
                    "2",
                    "3",
                    "4",
                    "5",
                    "6"),
            Case.answers(
                    "SQLX-009 an alias shadowing another stream's name reads txn, not other",
                    "SELECT other.txn_id FROM txn AS other",
                    "1",
                    "2",
                    "3",
                    "4",
                    "5",
                    "6"),
            Case.answers(
                    "SQLX-011 the same column selected twice holds the same value twice",
                    "SELECT amount, amount FROM txn",
                    "100|100",
                    "250|250",
                    "-50|-50",
                    "0|0",
                    "7|7",
                    "7|7"),
            Case.answers(
                    "SQLX-013 an integer literal is one value per row, not one row",
                    "SELECT 1 FROM txn",
                    "1",
                    "1",
                    "1",
                    "1",
                    "1",
                    "1"),
            Case.answers(
                    "SQLX-014 a string literal arrives without its apostrophes",
                    "SELECT 'flagged' FROM txn",
                    "flagged",
                    "flagged",
                    "flagged",
                    "flagged",
                    "flagged",
                    "flagged"),
            Case.answers(
                    "SQLX-017 integer arithmetic, computed per row",
                    "SELECT amount * 2 + 1 FROM txn",
                    "201",
                    "501",
                    "-99",
                    "1",
                    "15",
                    "15"),
            Case.answers(
                    "SQLX-018 floating arithmetic is not promoted to an integer",
                    "SELECT price / 2 FROM txn",
                    "1.25",
                    "2.0",
                    "0.5",
                    "0.25",
                    "0.75",
                    "0.125"),
            Case.answers(
                    "SQLX-019 CAST between numeric types",
                    "SELECT CAST(amount AS DOUBLE) FROM txn",
                    "100.0",
                    "250.0",
                    "-50.0",
                    "0.0",
                    "7.0",
                    "7.0"),
            Case.answers(
                    "SQLX-020/TYPE-146 CAST narrowing a double truncates towards zero",
                    "SELECT CAST(price AS BIGINT) FROM txn",
                    "2",
                    "4",
                    "1",
                    "0",
                    "1",
                    "0"),
            Case.refused(
                    "SQLX-021 CAST to text is refused by name", "SELECT CAST(amount AS VARCHAR) FROM txn", "PRV-2021"),
            Case.refused(
                    "SQLX-023 SELECT DISTINCT over a stream is PRV-2050",
                    "SELECT DISTINCT user_id FROM txn",
                    "PRV-2050"),

            // --- SQLX §2, expressions ---------------------------------------------------------
            Case.answers(
                    "SQLX-029 ABS over positive, negative and zero",
                    "SELECT ABS(amount) FROM txn",
                    "100",
                    "250",
                    "50",
                    "0",
                    "7",
                    "7"),
            Case.answers(
                    "SQLX-031/TYPE-127 FLOOR and CEIL over an integer column are the identity",
                    "SELECT FLOOR(amount), CEIL(amount) FROM txn",
                    "100|100",
                    "250|250",
                    "-50|-50",
                    "0|0",
                    "7|7",
                    "7|7"),
            Case.answers(
                    "SQLX-032/TYPE-128 FLOOR and CEIL over a float column",
                    "SELECT FLOOR(price), CEIL(price) FROM txn",
                    "2.0|3.0",
                    "4.0|4.0",
                    "1.0|1.0",
                    "0.0|1.0",
                    "1.0|2.0",
                    "0.0|1.0"),
            Case.answers(
                    "SQLX-033/TYPE-129 ROUND is half away from zero, not banker's",
                    "SELECT ROUND(price) FROM txn",
                    "3.0",
                    "4.0",
                    "1.0",
                    "1.0",
                    "2.0",
                    "0.0"),
            Case.refused("SQLX-035 ROUND to decimal places is refused", "SELECT ROUND(amount, 2) FROM txn", "PRV-2021"),
            Case.refused("SQLX-036 SQRT is refused", "SELECT SQRT(price) FROM txn", "PRV-2021"),
            Case.refused("SQLX-036 POWER is refused", "SELECT POWER(amount, 2) FROM txn", "PRV-2021"),
            Case.refused("SQLX-036 EXP is refused", "SELECT EXP(price) FROM txn", "PRV-2021"),
            Case.refused("SQLX-036 LN is refused", "SELECT LN(price) FROM txn", "PRV-2021"),
            Case.answers(
                    "SQLX-037/TYPE-112 MOD takes the sign of the dividend",
                    "SELECT MOD(amount, 3) FROM txn",
                    "1",
                    "1",
                    "-2",
                    "0",
                    "1",
                    "1"),
            Case.answers(
                    "SQLX-037 the % spelling agrees with MOD",
                    "SELECT amount % 3 FROM txn", "1", "1", "-2", "0", "1", "1"),
            // TY-1. Calcite casts both operands of MOD to DECIMAL before the planner sees them --
            // unlike + - * /, which keep their floating type -- so every one of these was refused
            // as "DECIMAL arithmetic ... a rounding error in a ledger" over columns that were never
            // declared DECIMAL. Floating modulo was unreachable through SQL entirely. The values
            // are IEEE 754 remainder, which takes the sign of the dividend, same as the integer
            // form two rows above.
            Case.answers(
                    "TY-1 the % spelling over a FLOAT64 column",
                    "SELECT price % 2 FROM txn", "0.5", "0.0", "1.0", "0.5", "1.5", "0.25"),
            Case.answers(
                    "TY-1 the MOD spelling agrees with %",
                    "SELECT MOD(price, 2) FROM txn", "0.5", "0.0", "1.0", "0.5", "1.5", "0.25"),
            Case.answers(
                    "TY-1 both operands floating",
                    "SELECT price % price FROM txn",
                    "0.0",
                    "0.0",
                    "0.0",
                    "0.0",
                    "0.0",
                    "0.0"),
            Case.answers(
                    "TY-1 an integer dividend and a floating divisor, r4 dividing by zero to NaN",
                    "SELECT price % amount FROM txn",
                    "2.5",
                    "4.0",
                    "1.0",
                    "NaN",
                    "1.5",
                    "0.25"),
            // -50 % 1.0 is -0.0, not 0.0: the remainder takes the sign of the dividend and IEEE 754
            // has two zeroes. Pinned rather than smoothed over -- it is the same sign rule the
            // integer MOD row above asserts, and a reader who sees -0.0 in a result should be able
            // to find it stated somewhere.
            Case.answers(
                    "TY-1 a floating dividend and an integer divisor keeps the dividend's sign",
                    "SELECT amount % price FROM txn",
                    "0.0",
                    "2.0",
                    "-0.0",
                    "0.0",
                    "1.0",
                    "0.0"),
            // TY-1's boundary: the coercion is undone, the refusal is not weakened. `amount % 1.5`
            // has no floating operand at all, so it is genuine decimal arithmetic and stays refused
            // -- exactly as `amount * 1.5` (TYPE-110, below) is.
            Case.refused(
                    "TY-1 an integer column modulo a decimal literal is still refused",
                    "SELECT amount % 1.5 FROM txn",
                    "PRV-2021"),
            Case.answers(
                    "TYPE-111 integer division truncates towards zero, not towards -infinity",
                    "SELECT amount / 3 FROM txn",
                    "33",
                    "83",
                    "-16",
                    "0",
                    "2",
                    "2"),
            Case.answers(
                    "SQLX-041 CASE chooses by value, not by row position",
                    "SELECT CASE WHEN amount > 50 THEN 1 ELSE 0 END FROM txn",
                    "1",
                    "1",
                    "0",
                    "0",
                    "0",
                    "0"),
            Case.answers(
                    "SQLX-042a a CASE with several branches takes the first true one",
                    "SELECT CASE WHEN amount > 100 THEN 3 WHEN amount > 10 THEN 2 WHEN amount > 0 THEN 1 ELSE 0 END FROM txn",
                    "2",
                    "3",
                    "0",
                    "0",
                    "1",
                    "1"),
            Case.answers(
                    "SQLX-042b/TYPE-118 a CASE with no ELSE is NULL, not a type default",
                    "SELECT CASE WHEN amount > 100 THEN 3 END FROM txn",
                    "NULL",
                    "3",
                    "NULL",
                    "NULL",
                    "NULL",
                    "NULL"),
            Case.answers(
                    "SQLX-043/TYPE-120 only the branch taken is evaluated",
                    "SELECT CASE WHEN amount = 0 THEN 0 ELSE 100 / amount END FROM txn",
                    "1",
                    "0",
                    "-2",
                    "0",
                    "14",
                    "14"),
            // TY-23. This answered "big"/"0" -- Calcite's validator coerced the 0 into the string
            // '0' before the compiler saw it -- while the same CASE over a numeric *column*
            // (`ELSE amount`) was refused, because a column survives coercion intact. The engine
            // has no number-to-text conversion anywhere, so the literal form was the odd one out
            // and is refused now.
            Case.refused(
                    "SQLX-044/TY-23 a CASE mixing text and a number is refused",
                    "SELECT CASE WHEN amount > 50 THEN 'big' ELSE 0 END FROM txn",
                    "PRV-2021"),
            Case.answers(
                    "SQLX-045 UPPER and LOWER, including above the BMP",
                    "SELECT UPPER(user_id), LOWER(user_id) FROM txn",
                    "U1|u1",
                    "U2|u2",
                    "U1|u1",
                    "U3|u3",
                    "U2|u2",
                    "ÜNÏCØDÉ|ünïcødé"),
            Case.answers(
                    "SQLX-047 NULL concatenated with anything is NULL, not an empty string",
                    "SELECT user_id || '-' || status FROM txn",
                    "u1-ok",
                    "NULL",
                    "u1-ok",
                    "u3-flagged",
                    "u2-ok",
                    "ünïcødé-ok"),
            Case.answers(
                    "SQLX-048 SUBSTRING is 1-based and counts code points",
                    "SELECT SUBSTRING(user_id FROM 1 FOR 1), SUBSTRING(user_id FROM 2) FROM txn",
                    "u|1",
                    "u|2",
                    "u|1",
                    "u|3",
                    "u|2",
                    "ü|nïcødé"),
            Case.refused(
                    "SQLX-046b TRIM of a character other than a space",
                    "SELECT TRIM('x' FROM user_id) FROM txn",
                    "PRV-2021"),
            Case.refused(
                    "SQLX-046c TRIM from one end only", "SELECT TRIM(LEADING ' ' FROM user_id) FROM txn", "PRV-2021"),

            // --- SQLX §3, WHERE ---------------------------------------------------------------
            Case.answers(
                    "SQLX-061 AND of two comparisons", "SELECT txn_id FROM txn WHERE amount > 0 AND flagged", "1", "6"),
            Case.answers(
                    "SQLX-062 OR of two comparisons emits no row twice",
                    "SELECT txn_id FROM txn WHERE amount > 100 OR flagged",
                    "1",
                    "2",
                    "4",
                    "6"),
            Case.answers(
                    "SQLX-063 NOT over a comparison on a non-null column is the complement",
                    "SELECT txn_id FROM txn WHERE NOT (amount > 0)",
                    "3",
                    "4"),
            Case.answers(
                    "SQLX-064 De Morgan over AND",
                    "SELECT txn_id FROM txn WHERE NOT (amount > 0 AND flagged)",
                    "2",
                    "3",
                    "4",
                    "5"),
            Case.answers(
                    "SQLX-065 De Morgan over OR",
                    "SELECT txn_id FROM txn WHERE NOT (amount > 100 OR flagged)",
                    "3",
                    "5"),
            Case.answers(
                    "SQLX-066 NOT NOT is the identity",
                    "SELECT txn_id FROM txn WHERE NOT (NOT (status = 'ok'))",
                    "1",
                    "3",
                    "5",
                    "6"),
            Case.answers(
                    "SQLX-068 a predicate that is always true keeps every row",
                    "SELECT txn_id FROM txn WHERE 1 = 1",
                    "1",
                    "2",
                    "3",
                    "4",
                    "5",
                    "6"),
            Case.answers("SQLX-069 a predicate that is always false keeps none", "SELECT txn_id FROM txn WHERE 1 = 0"),
            Case.answers("SQLX-071 a bare boolean column", "SELECT txn_id FROM txn WHERE flagged", "1", "4", "6"),
            Case.answers(
                    "SQLX-073 column against column, both numeric",
                    "SELECT txn_id FROM txn WHERE amount > txn_id",
                    "1",
                    "2",
                    "5",
                    "6"),
            Case.answers(
                    "SQLX-075 arithmetic inside a predicate",
                    "SELECT txn_id FROM txn WHERE amount * 2 > 100",
                    "1",
                    "2"),
            Case.answers(
                    "SQLX-076 = 'ok' drops the NULL row",
                    "SELECT txn_id FROM txn WHERE status = 'ok'",
                    "1",
                    "3",
                    "5",
                    "6"),
            Case.answers(
                    "SQLX-077 <> 'ok' also drops the NULL row", "SELECT txn_id FROM txn WHERE status <> 'ok'", "4"),
            Case.answers(
                    "SQLX-078 NOT (status = 'ok') is not the complement either",
                    "SELECT txn_id FROM txn WHERE NOT (status = 'ok')",
                    "4"),
            Case.answers("SQLX-087 IS NULL finds the one NULL", "SELECT txn_id FROM txn WHERE status IS NULL", "2"),
            Case.answers(
                    "SQLX-087 IS NOT NULL finds the other five",
                    "SELECT txn_id FROM txn WHERE status IS NOT NULL",
                    "1",
                    "3",
                    "4",
                    "5",
                    "6"),
            Case.answers("SQLX-081 a one-element IN list", "SELECT txn_id FROM txn WHERE user_id IN ('u1')", "1", "3"),
            Case.answers(
                    "SQLX-082 a many-element IN list",
                    "SELECT txn_id FROM txn WHERE user_id IN ('u1','u2','u3','nobody')",
                    "1",
                    "2",
                    "3",
                    "4",
                    "5"),
            Case.answers(
                    "SQLX-083 duplicates in an IN list do not duplicate rows",
                    "SELECT txn_id FROM txn WHERE user_id IN ('u1','u1','u1')",
                    "1",
                    "3"),
            Case.answers(
                    "SQLX-089 BETWEEN is inclusive at both ends",
                    "SELECT txn_id FROM txn WHERE amount BETWEEN -50 AND 100",
                    "1",
                    "3",
                    "4",
                    "5",
                    "6"),
            Case.answers(
                    "SQLX-089b a zero-width BETWEEN", "SELECT txn_id FROM txn WHERE amount BETWEEN 7 AND 7", "5", "6"),
            Case.answers(
                    "SQLX-090 an inverted BETWEEN keeps nothing",
                    "SELECT txn_id FROM txn WHERE amount BETWEEN 100 AND -50"),
            Case.answers(
                    "SQLX-091/TYPE-140 LIKE is anchored at both ends",
                    "SELECT txn_id FROM txn WHERE user_id LIKE 'u1'",
                    "1",
                    "3"),
            Case.answers(
                    "SQLX-091 LIKE with a trailing wildcard",
                    "SELECT txn_id FROM txn WHERE user_id LIKE 'u%'",
                    "1",
                    "2",
                    "3",
                    "4",
                    "5"),
            Case.answers(
                    "SQLX-091 LIKE with a leading wildcard",
                    "SELECT txn_id FROM txn WHERE user_id LIKE '%1'",
                    "1",
                    "3"),
            Case.answers(
                    "SQLX-091 LIKE with a single-character wildcard",
                    "SELECT txn_id FROM txn WHERE user_id LIKE 'u_'",
                    "1",
                    "2",
                    "3",
                    "4",
                    "5"),
            Case.answers(
                    "TYPE-141 a regex metacharacter in a LIKE pattern is literal",
                    "SELECT txn_id FROM txn WHERE user_id LIKE 'u.'"),
            Case.answers(
                    "TYPE-143 NULL is dropped by LIKE and by NOT LIKE alike",
                    "SELECT txn_id FROM txn WHERE status NOT LIKE 'o%'",
                    "4"),
            Case.refused(
                    "SQLX-093/TYPE-144 LIKE with ESCAPE is refused",
                    "SELECT txn_id FROM txn WHERE user_id LIKE 'u!%' ESCAPE '!'",
                    "PRV-2021"),
            Case.refused(
                    "SQLX-094 a LIKE pattern that is not a literal is refused",
                    "SELECT txn_id FROM txn WHERE user_id LIKE status",
                    "PRV-2021"),
            Case.refused(
                    "SQLX-095/TYPE-029 text ordering is refused",
                    "SELECT txn_id FROM txn WHERE status > 'ok'",
                    "PRV-2021"),
            Case.refused(
                    "SQLX-096 comparing text to a number is refused",
                    "SELECT txn_id FROM txn WHERE amount > user_id",
                    "PRV-2021"),
            // TY-5. This was refused -- the compiler required a bare column reference for IS NULL
            // -- and now plans. `amount` is NOT NULL across D1, so the answer is no rows; the CASE
            // shape the finding is actually about is in TypeClusterTest with a nullable operand.
            Case.answers(
                    "SQLX-088/TY-5 IS NULL over an expression", "SELECT txn_id FROM txn WHERE (amount * 2) IS NULL"),

            // --- SQLX §4, aggregation ---------------------------------------------------------
            Case.answers("SQLX-097 global COUNT(*) over a stream", "SELECT COUNT(*) AS n FROM txn", "6"),
            Case.answers(
                    "SQLX-098 COUNT(col) versus COUNT(*), global",
                    "SELECT COUNT(*) AS a, COUNT(status) AS b, COUNT(user_id) AS c FROM txn",
                    "6|5|6"),
            Case.answers(
                    "SQLX-106 AVG over integers is truncating division",
                    "SELECT AVG(amount) AS av, SUM(amount) AS s, COUNT(*) AS n FROM txn",
                    "52|314|6"),
            Case.answers(
                    "SQLX-105 MIN and MAX over a signed column", "SELECT MIN(amount), MAX(amount) FROM txn", "-50|250"),
            Case.refused(
                    "SQLX-113/AGG-033 SUM over a FLOAT64 column is refused at plan time",
                    "SELECT SUM(price) FROM txn",
                    "PRV-2020"),
            Case.refused("SQLX-113/AGG-034 MIN over FLOAT64 is refused", "SELECT MIN(price) FROM txn", "PRV-2020"),
            Case.refused("SQLX-113/AGG-034 MAX over FLOAT64 is refused", "SELECT MAX(price) FROM txn", "PRV-2020"),
            Case.refused("SQLX-113/AGG-034 AVG over FLOAT64 is refused", "SELECT AVG(price) FROM txn", "PRV-2020"),
            Case.refused(
                    "SQLX-115/AGG-004 an unwindowed keyed GROUP BY over a stream is refused",
                    "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id",
                    "PRV-2050"),
            Case.refused(
                    "SQLX-112 GROUPING SETS is refused",
                    "SELECT user_id, COUNT(*) FROM txn GROUP BY GROUPING SETS ((user_id), ())",
                    "PRV-2020"),
            Case.refused(
                    "SQLX-112 CUBE is refused",
                    "SELECT user_id, status, COUNT(*) FROM txn GROUP BY CUBE (user_id, status)",
                    "PRV-2020"),
            Case.refused(
                    "SQLX-112 ROLLUP is refused",
                    "SELECT user_id, status, COUNT(*) FROM txn GROUP BY ROLLUP (user_id, status)",
                    "PRV-2020"),
            Case.refused(
                    "SQLX-120 SESSION windows are refused",
                    "SELECT window_start, window_end, COUNT(*) FROM TABLE(SESSION(TABLE txn, "
                            + "DESCRIPTOR(event_time), DESCRIPTOR(user_id), INTERVAL '5' SECOND)) "
                            + "GROUP BY window_start, window_end",
                    "PRV-2020"),
            Case.refused(
                    "SQLX-103/AGG-006 a windowed GROUP BY that omits the boundaries is refused",
                    "SELECT user_id, COUNT(*) FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), "
                            + "INTERVAL '10' SECOND)) GROUP BY user_id",
                    "PRV-2050"),

            // --- SQLX §5 and §6, sorting and sets ---------------------------------------------
            Case.refused(
                    "SQLX-121 ORDER BY is refused at plan time", "SELECT txn_id FROM txn ORDER BY amount", "PRV-2020"),
            Case.refused("SQLX-123 LIMIT is refused as a sort", "SELECT txn_id FROM txn LIMIT 5", "PRV-2020"),
            Case.refused("SQLX-123 LIMIT 0 is refused too", "SELECT txn_id FROM txn LIMIT 0", "PRV-2020"),
            Case.refused("SQLX-124 OFFSET is refused", "SELECT txn_id FROM txn OFFSET 5 ROWS", "PRV-2020"),
            Case.refused(
                    "SQLX-124 OFFSET with FETCH is refused",
                    "SELECT txn_id FROM txn OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY",
                    "PRV-2020"),
            Case.refused(
                    "SQLX-127 UNION is refused", "SELECT user_id FROM txn UNION SELECT user_id FROM other", "PRV-2020"),
            Case.refused(
                    "SQLX-128 UNION ALL is refused with the same code as UNION",
                    "SELECT user_id FROM txn UNION ALL SELECT user_id FROM other",
                    "PRV-2020"),
            Case.refused(
                    "SQLX-129 INTERSECT is refused",
                    "SELECT user_id FROM txn INTERSECT SELECT user_id FROM other",
                    "PRV-2020"),
            Case.refused(
                    "SQLX-130 EXCEPT is refused",
                    "SELECT user_id FROM txn EXCEPT SELECT user_id FROM other",
                    "PRV-2020"),
            Case.refused(
                    "SQLX-131 a set operation between a stream and itself is refused at build",
                    "SELECT user_id FROM txn UNION ALL SELECT user_id FROM txn",
                    "PRV-2020"),
            Case.refused(
                    "SQLX-134a a set operation nested inside a CTE is still refused",
                    "WITH u AS (SELECT user_id FROM txn UNION SELECT user_id FROM other) SELECT user_id FROM u",
                    "PRV-2020"),

            // --- SQLX §7, derived tables and CTEs ---------------------------------------------
            Case.answers(
                    "SQLX-135 a derived table returns the right rows",
                    "SELECT x.txn_id FROM (SELECT txn_id, amount FROM txn WHERE amount > 0) x WHERE x.amount < 100",
                    "5",
                    "6"),
            Case.answers(
                    "SQLX-136 derived tables nested three deep",
                    "SELECT c.txn_id FROM (SELECT b.txn_id, b.amount FROM "
                            + "(SELECT a.txn_id, a.amount FROM (SELECT txn_id, amount FROM txn WHERE amount > -1) a "
                            + "WHERE a.amount < 250) b WHERE b.amount > 0) c",
                    "1",
                    "5",
                    "6"),
            Case.answers(
                    "SQLX-137 a single WITH clause, and the boundary of >=",
                    "WITH big AS (SELECT txn_id, amount FROM txn WHERE amount >= 100) SELECT txn_id FROM big",
                    "1",
                    "2"),
            Case.answers(
                    "SQLX-138 two CTEs, the second reading the first",
                    "WITH a AS (SELECT txn_id, amount, user_id FROM txn WHERE amount > 0), "
                            + "b AS (SELECT txn_id, amount FROM a WHERE user_id = 'u2') SELECT txn_id FROM b",
                    "2",
                    "5"),
            Case.refused(
                    "SQLX-142 IN (subquery) is refused",
                    "SELECT txn_id FROM txn WHERE user_id IN (SELECT user_id FROM other)",
                    "PRV-2021"),
            Case.refused(
                    "SQLX-143 EXISTS is refused",
                    "SELECT txn_id FROM txn WHERE EXISTS (SELECT 1 FROM other)",
                    "PRV-2021"),
            Case.refused(
                    "SQLX-145 a scalar subquery is refused",
                    "SELECT txn_id, (SELECT COUNT(*) FROM other) FROM txn",
                    "PRV-2021"),
            Case.refused(
                    "SQLX-146 a window function is refused",
                    "SELECT ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY event_time) FROM txn",
                    "PRV-2021"),
            Case.refused("SQLX-141 VALUES is refused", "SELECT * FROM (VALUES (1), (2))", "PRV-2020"),

            // --- SQLX §9, hostile and awkward SQL ---------------------------------------------
            // SQLX-163, the empty query, is not a row here: its message is JIT-dependent. See
            // SqlSupportMatrixTest.theEmptyStatementIsRefusedWithAnExplanation, defect 5.
            Case.refused("SQLX-164 only whitespace", "   ", "PRV-2001"),
            Case.refused("SQLX-165 only a line comment", "-- just a comment", "PRV-2001"),
            Case.refused("SQLX-165 only a block comment", "/* block */", "PRV-2001"),
            Case.refused(
                    "SQLX-168 two statements separated by a semicolon",
                    "SELECT txn_id FROM txn; SELECT user_id FROM txn",
                    "PRV-2001"),
            Case.refused(
                    "SQLX-181 a query naming a stream that does not exist", "SELECT x FROM nosuchstream", "PRV-2002"),
            Case.refused("SQLX-182 an unknown column in the select list", "SELECT nosuchcol FROM txn", "PRV-2002"),
            Case.refused(
                    "SQLX-182 an unknown column in the predicate",
                    "SELECT txn_id FROM txn WHERE nosuchcol > 1",
                    "PRV-2002"),
            Case.refused("SQLX-170 identifier case is significant", "SELECT USER_ID FROM txn", "PRV-2002"),
            Case.answers(
                    "SQLX-169 keyword case is not significant",
                    "select txn_id from txn where amount > 0",
                    "1",
                    "2",
                    "5",
                    "6"),
            Case.answers(
                    "SQLX-166 a block comment inside the statement",
                    "SELECT txn_id /* the id */ FROM txn WHERE amount > 100",
                    "2"),
            Case.answers(
                    "SQLX-170 a quoted identifier in its own case resolves",
                    "SELECT \"user_id\" FROM txn WHERE amount > 100",
                    "u2"),

            // SQLX-016, a boolean literal in the select list, is not here: it throws a raw
            // ClassCastException. See booleanLiteralInTheSelectList below.

            // --- SQLX §3 continued: three-valued logic -----------------------------------------
            Case.answers(
                    "SQLX-072 NOT on a bare boolean column", "SELECT txn_id FROM txn WHERE NOT flagged", "2", "3", "5"),
            Case.answers(
                    "SQLX-070 WHERE TRUE keeps every row",
                    "SELECT txn_id FROM txn WHERE TRUE",
                    "1",
                    "2",
                    "3",
                    "4",
                    "5",
                    "6"),
            Case.answers("SQLX-070 WHERE FALSE keeps none", "SELECT txn_id FROM txn WHERE FALSE"),
            // TYPE-083: `= NULL` is a comparison against UNKNOWN, not a null test. Every row is
            // dropped, including the row whose status really is NULL.
            Case.answers("TYPE-083 status = NULL is not IS NULL", "SELECT txn_id FROM txn WHERE status = NULL"),
            Case.answers("TYPE-083 status <> NULL is not IS NOT NULL", "SELECT txn_id FROM txn WHERE status <> NULL"),
            Case.answers("TYPE-083 a numeric column compared to NULL", "SELECT txn_id FROM txn WHERE amount > NULL"),
            // TYPE-087: UNKNOWN OR TRUE is TRUE, so r2 survives on the strength of the other side;
            // UNKNOWN AND TRUE is UNKNOWN, so it does not.
            Case.answers(
                    "TYPE-087 UNKNOWN OR TRUE is TRUE",
                    "SELECT txn_id FROM txn WHERE status = 'ok' OR amount > 100",
                    "1",
                    "2",
                    "3",
                    "5",
                    "6"),
            Case.answers(
                    "TYPE-087 UNKNOWN AND TRUE is UNKNOWN",
                    "SELECT txn_id FROM txn WHERE status = 'ok' AND amount > -100",
                    "1",
                    "3",
                    "5",
                    "6"),
            // SQLX-086: NOT IN a list containing NULL is UNKNOWN for every row, so the answer is
            // empty -- the classic result that looks like a bug and is the standard.
            Case.answers(
                    "SQLX-086 IN with NULL among the terms",
                    "SELECT txn_id FROM txn WHERE user_id IN ('u1', NULL)",
                    "1",
                    "3"),
            Case.answers(
                    "SQLX-086 NOT IN with NULL among the terms",
                    "SELECT txn_id FROM txn WHERE user_id NOT IN ('u1', NULL)"),
            Case.refused("SQLX-080 an empty IN list", "SELECT txn_id FROM txn WHERE user_id IN ()", "PRV-2001"),
            Case.answers(
                    "SQLX-084 an IN list of 19 terms",
                    "SELECT txn_id FROM txn WHERE user_id IN ('u1','x2','x3','x4','x5','x6','x7','x8','x9','x10',"
                            + "'x11','x12','x13','x14','x15','x16','x17','x18','x19')",
                    "1",
                    "3"),
            Case.answers(
                    "TYPE-124 a CASE used as a WHERE operand",
                    "SELECT txn_id FROM txn WHERE (CASE WHEN flagged THEN 1 ELSE 0 END) = 1",
                    "1",
                    "4",
                    "6"),

            // --- TYPE §17, CASE ----------------------------------------------------------------
            Case.answers(
                    "TYPE-121 text branches",
                    "SELECT CASE WHEN amount > 100 THEN 'big' WHEN amount > 0 THEN 'medium' ELSE 'small' END FROM txn",
                    "medium",
                    "big",
                    "small",
                    "small",
                    "medium",
                    "medium"),
            // TYPE-123: an UNKNOWN condition is not TRUE, so r2 takes the ELSE and gets 0 -- not
            // NULL, which is what a "propagate the null" implementation would produce.
            Case.answers(
                    "TYPE-123 a CASE whose condition is UNKNOWN takes the ELSE",
                    "SELECT CASE WHEN status = 'ok' THEN 1 ELSE 0 END FROM txn",
                    "1",
                    "0",
                    "1",
                    "0",
                    "1",
                    "1"),

            // TY-11. `CASE WHEN c THEN TRUE ELSE FALSE END` is the ordinary way to normalise a
            // condition into a boolean column, and it could not be projected at all: Calcite
            // rewrites it before the planner sees it -- to the bare condition when that cannot be
            // UNKNOWN, to `IS TRUE(c)` when it can -- and neither shape had a compiled path. The
            // same CASE returning 1/0 (SQLX-041, above) always worked, which is what said the CASE
            // was never the problem. Both rewrites are covered here because they are different
            // code, reached by changing nothing but the nullability of the column in the condition.
            Case.answers(
                    "TY-11 a boolean CASE over a NOT NULL condition, which Calcite reduces to the condition",
                    "SELECT CASE WHEN amount > 50 THEN TRUE ELSE FALSE END FROM txn",
                    "true",
                    "true",
                    "false",
                    "false",
                    "false",
                    "false"),
            Case.answers(
                    "TY-11 a boolean CASE over a nullable condition, which Calcite rewrites to IS TRUE",
                    "SELECT CASE WHEN status = 'ok' THEN TRUE ELSE FALSE END FROM txn",
                    "true",
                    "false",
                    "true",
                    "false",
                    "true",
                    "true"),
            // r2's status is NULL, so `status = 'ok'` is UNKNOWN and the CASE takes the ELSE -- the
            // same three-valued rule TYPE-123 asserts for the 1/0 form. Calcite spells this one
            // IS NOT TRUE, which is the total complement rather than SQL's NOT.
            Case.answers(
                    "TY-11 the inverted boolean CASE, which Calcite rewrites to IS NOT TRUE",
                    "SELECT CASE WHEN status = 'ok' THEN FALSE ELSE TRUE END FROM txn",
                    "false",
                    "true",
                    "false",
                    "true",
                    "false",
                    "false"),
            Case.answers(
                    "TY-11 a bare NOT NULL comparison projected directly",
                    "SELECT amount > 50 FROM txn",
                    "true",
                    "true",
                    "false",
                    "false",
                    "false",
                    "false"),
            Case.answers(
                    "TY-11 IS NULL projected directly, which is total and so never UNKNOWN",
                    "SELECT status IS NULL FROM txn",
                    "false",
                    "true",
                    "false",
                    "false",
                    "false",
                    "false"),
            // TY-11's boundary. `status = 'ok'` is UNKNOWN for r2, and a projected column holds two
            // values. Reporting UNKNOWN as false would be a wrong answer under exit 0, so it is
            // refused -- and the refusal names the CASE form that says "collapse it" out loud.
            Case.refused(
                    "TY-11 a nullable comparison is refused rather than flattened to false",
                    "SELECT status = 'ok' FROM txn",
                    "PRV-2021"),

            // --- TYPE §19, CAST ----------------------------------------------------------------
            Case.answers(
                    "TYPE-145 a numeric cast round-trips through DOUBLE",
                    "SELECT CAST(CAST(amount AS DOUBLE) AS BIGINT) FROM txn",
                    "100",
                    "250",
                    "-50",
                    "0",
                    "7",
                    "7"),
            Case.refused("TYPE-149 CAST from text is refused", "SELECT CAST(user_id AS BIGINT) FROM txn", "PRV-2021"),
            Case.refused(
                    "TYPE-149 CAST from a boolean is refused", "SELECT CAST(flagged AS INTEGER) FROM txn", "PRV-2002"),
            // TYPE-110: Calcite types a written 1.5 as DECIMAL. amount * 1.5 is exact decimal
            // arithmetic now (NexmarkDecimalTest); a decimal quotient is not exact at any fixed
            // scale, so it is still refused rather than computed in doubles or rounded.
            Case.refused(
                    "TYPE-110 an integer column divided by a decimal literal",
                    "SELECT amount / 1.5 FROM txn",
                    "PRV-2021"),

            // --- TYPE §16, the arithmetic that has no exception -------------------------------
            // Floating division by zero is Infinity, not an error. price is never zero in D1, so
            // `price - price` is the only way to write a zero of the right type.
            Case.answers(
                    "TYPE-114 floating division by zero is Infinity, not an error",
                    "SELECT price / (price - price) FROM txn",
                    "Infinity",
                    "Infinity",
                    "Infinity",
                    "Infinity",
                    "Infinity",
                    "Infinity"),

            // --- SQLX §4 continued: aggregates over expressions and in bulk -------------------
            Case.answers(
                    "SQLX-110/AGG-103 an aggregate over an expression", "SELECT SUM(amount * 2) AS s FROM txn", "628"),
            Case.answers(
                    "AGG-104 an aggregate over an expression that can be NULL",
                    "SELECT COUNT(amount) AS a, COUNT(amount * 2) AS b, SUM(amount * 2) AS c FROM txn",
                    "6|6|628"),
            Case.answers(
                    "AGG-105 an aggregate over an expression spanning two columns",
                    "SELECT COUNT(amount + txn_id) AS n, SUM(amount + txn_id) AS t FROM txn",
                    "6|335"),
            Case.answers(
                    "AGG-099 five aggregates in one row, five distinct values",
                    "SELECT COUNT(*) AS a, SUM(amount) AS b, MIN(amount) AS c, MAX(amount) AS d, AVG(amount) AS e FROM txn",
                    "6|314|-50|250|52"),
            Case.answers(
                    "AGG-101 the same aggregate written twice",
                    "SELECT COUNT(amount) AS a, COUNT(amount) AS b FROM txn",
                    "6|6"),
            Case.answers(
                    "AGG-102 two COUNTs over different columns are not the same number",
                    "SELECT COUNT(status) AS a, COUNT(user_id) AS b, COUNT(*) AS c FROM txn",
                    "5|6|6"),
            // AGG-048 and TYPE-040 both predicted PRV-2002 from Calcite's validator, and what
            // used to arrive was PRV-2021 about ledgers and 128-bit decimals: Calcite coerces the
            // column to DECIMAL(38,19) for the SUM and Pravaha refused the decimal arithmetic,
            // never mentioning that summing text was the problem. TY-16: the operand is checked at
            // the aggregate now, before the cast underneath it is built, and refused with PRV-2020
            // -- the same code the float-accumulator refusal uses, for the same reason.
            Case.refused("AGG-048/TYPE-040 SUM over a STRING column", "SELECT SUM(user_id) FROM txn", "PRV-2020"),
            // ...and SUM over BOOLEAN is refused one layer earlier, by Calcite, with PRV-2002 and
            // a message that does name the problem. Two spellings of "sum something that is not a
            // number", two codes, two qualities of explanation.
            Case.refused("AGG-048/TYPE-041 SUM over a BOOLEAN column", "SELECT SUM(flagged) FROM txn", "PRV-2002"),
            Case.refused(
                    "SQLX-044c SUM of a CASE that Calcite coerced to text",
                    "SELECT SUM(CASE WHEN amount > 50 THEN 'big' ELSE 0 END) FROM txn",
                    "PRV-2021"),

            // AGG-012: refused at plan time now. It used to live in GlobalAggregate.process and fire
            // on the first row, so a user typing this got a started query that died -- and, because
            // that operator cannot tell a stream from a finite read, the same throw refused the
            // bounded read where the state is bounded by the scan. The refusal belongs where the
            // two can be told apart.
            Case.refused(
                    "AGG-012 global COUNT(DISTINCT) over a stream is refused at plan time",
                    "SELECT COUNT(DISTINCT user_id) FROM txn",
                    "PRV-2050"),

            // --- Queries that plan, compile, accept rows, and then throw -----------------------
            // AGG-047/TYPE-047: MIN over a STRING column plans, and dies at emit naming a schema
            // the user never wrote.
            Case.fails("AGG-047 MIN over a STRING column dies at emit", "SELECT MIN(user_id) FROM txn", "not INT64"),
            // SQLX-039/TYPE-113: r4's amount is 0. Integer division by zero must fail, promptly,
            // naming the cause.
            Case.fails("SQLX-039/TYPE-113 integer division by zero", "SELECT 100 / amount FROM txn", "zero"),
            // AGG-043: MIN/MAX over TIMESTAMP_LTZ is the one non-INT64 type the accumulators handle,
            // because a nanosecond epoch already is a long.
            Case.answers(
                    "AGG-043 MIN and MAX over TIMESTAMP_LTZ",
                    "SELECT MIN(event_time), MAX(event_time) FROM txn",
                    "1000000000|13000000000"),
            // TYPE-150: the workaround the float-aggregate refusal itself recommends. It has to
            // give the right number, or the refusal's advice is worse than the refusal.
            // CAST truncates towards zero: 2, 4, 1, 0, 1, 0 -- which sums to 8, not to 9.75.
            Case.answers(
                    "TYPE-150 SUM(CAST(price AS BIGINT)), the recommended workaround",
                    "SELECT SUM(CAST(price AS BIGINT)) FROM txn",
                    "8"),
            Case.refused(
                    "SQLX-036 a function outside the supported set names the set",
                    "SELECT CHAR_LENGTH(user_id) FROM txn",
                    "PRV-2021"));

    @Test
    void everyConvertedCaseWithAnAnswerProducesIt() {
        List<String> wrong = new ArrayList<>();
        for (Case testCase : MATRIX) {
            if (testCase.answer() == null) {
                continue;
            }
            List<String> actual;
            try {
                actual = answerOf(testCase.sql());
            } catch (RuntimeException e) {
                wrong.add(testCase.id() + ": threw " + e.getMessage());
                continue;
            }
            if (!actual.equals(testCase.answer())) {
                wrong.add(testCase.id() + ":%n    expected %s%n    produced %s".formatted(testCase.answer(), actual));
            }
        }
        assertThat(String.join(System.lineSeparator(), wrong))
                .as("a construct that plans and returns the wrong rows is worse than one that is "
                        + "refused: the refusal is visible and the number is not")
                .isEmpty();
    }

    @Test
    void everyCaseThatMustFailWhileRunningDoesSo() {
        List<String> wrong = new ArrayList<>();
        for (Case testCase : MATRIX) {
            if (testCase.runtimeFailure() == null) {
                continue;
            }
            String planTime = messageOf(testCase.sql());
            if (planTime != null) {
                // Not a failure of the product -- a failure of this case's own claim, which says
                // the query gets as far as running. If it starts refusing at plan time that is an
                // improvement, and the case must be rewritten as a refusal rather than deleted.
                wrong.add(
                        testCase.id() + ": now refused at plan time with '" + planTime + "'; move it to Case.refused");
                continue;
            }
            String thrown;
            try {
                answerOf(testCase.sql());
                thrown = null;
            } catch (RuntimeException e) {
                thrown = String.valueOf(e.getMessage()).replace('\n', ' ');
            }
            if (thrown == null) {
                wrong.add(testCase.id() + ": ran to completion and threw nothing");
            } else if (!thrown.contains(testCase.runtimeFailure())) {
                wrong.add(testCase.id() + ": expected a failure mentioning '" + testCase.runtimeFailure() + "', got '"
                        + thrown + "'");
            }
        }
        assertThat(String.join(System.lineSeparator(), wrong))
                .as("where a query dies at the first row rather than at plan time, that is itself "
                        + "the finding, and it stops being visible the moment nothing asserts it")
                .isEmpty();
    }

    @Test
    void everyConvertedRefusalCarriesItsDocumentedCodeAndAnExplanation() {
        List<String> wrong = new ArrayList<>();
        for (Case testCase : MATRIX) {
            if (testCase.refusal() == null) {
                continue;
            }
            String message = messageOf(testCase.sql());
            if (message == null) {
                wrong.add(testCase.id() + ": documented as " + testCase.refusal() + ", and was accepted");
                continue;
            }
            if (!message.startsWith(testCase.refusal())) {
                wrong.add(testCase.id() + ": documented as " + testCase.refusal() + ", actually '" + message + "'");
                continue;
            }
            // A bare code costs a support call. Refusing rather than approximating is only worth
            // doing if the message says what to do instead.
            if (message.length() <= 40) {
                wrong.add(testCase.id() + ": refused with a code and almost no explanation: '" + message + "'");
            }
        }
        assertThat(String.join(System.lineSeparator(), wrong))
                .as("the refusals are half the contract: a construct that starts refusing with a "
                        + "different code sends a user looking in the wrong place")
                .isEmpty();
    }

    // -------------------------------------------------------------------------------------------
    // Cases whose claim is about the output schema rather than the rows.
    // -------------------------------------------------------------------------------------------

    @Test
    void anAliasReachesTheOutputSchemaWithAndWithoutAs() {
        // SQLX-004 and SQLX-005. The alias is what a client displays and keys on, so it is part of
        // the contract even though no row carries it.
        assertThat(outputNamesOf("SELECT amount AS a FROM txn")).containsExactly("a");
        assertThat(outputNamesOf("SELECT amount a FROM txn")).containsExactly("a");
    }

    @Test
    void aQualifiedColumnProducesABareOutputName() {
        // SQLX-006. CONTINUOUS_QUERIES.md states it in so many words: "SELECT t.amount produces a column
        // called amount, not t.amount". A client keying on the name breaks if this drifts.
        assertThat(outputNamesOf("SELECT t.amount FROM txn AS t")).containsExactly("amount");
    }

    @Test
    void duplicateOutputNamesAreDisambiguatedRatherThanCollapsed() {
        // SQLX-010 and SQLX-011. The document does not say what happens when two output columns
        // claim one name; this is the answer, and the failure it rules out is a two-column SELECT
        // arriving as one column, or as two columns holding the same value.
        assertThat(outputNamesOf("SELECT amount AS a, txn_id AS a FROM txn")).containsExactly("a", "a0");
        assertThat(outputNamesOf("SELECT amount, amount FROM txn")).containsExactly("amount", "amount0");
        assertThat(answerOf("SELECT amount AS a, txn_id AS a FROM txn"))
                .containsExactly("100|1", "250|2", "-50|3", "0|4", "7|5", "7|6");
    }

    // -------------------------------------------------------------------------------------------
    // Cases about size and shape rather than about a value.
    // -------------------------------------------------------------------------------------------

    @Test
    void aThousandNestedParenthesesPlansAndAnswers() {
        // SQLX-173. Round 1 recorded a deeply nested predicate refusing as the literal text
        // "PRV-2001  null" (finding Q-14). At this depth, through the planner directly, it does
        // not: the predicate is flattened and answers. This pins that, so a regression in the
        // parser's depth handling is a failure here rather than a support call.
        String sql = "SELECT txn_id FROM txn WHERE " + "(".repeat(1000) + "amount > 0" + ")".repeat(1000);
        assertThat(answerOf(sql)).containsExactly("1", "2", "5", "6");
    }

    @Test
    void aThousandConjunctsPlansAndTheStrongestOneDecides() {
        // SQLX-174. A thousand terms of `amount > -n`; the strongest is `amount > -1`, which keeps
        // 100, 250, 0, 7 and 7 -- five rows. An implementation that dropped conjuncts past some
        // limit would keep six.
        StringBuilder sql = new StringBuilder("SELECT txn_id FROM txn WHERE ");
        for (int i = 1000; i >= 1; i--) {
            sql.append("amount > -").append(i);
            if (i > 1) {
                sql.append(" AND ");
            }
        }
        assertThat(answerOf(sql.toString())).containsExactly("1", "2", "4", "5", "6");
    }

    @Test
    void aTrailingSemicolonIsRefusedRatherThanSilentlyAccepted() {
        // SQLX-167. Either spelling is fine; what is not fine is one surface accepting it and
        // another not. This pins the behaviour so the three surfaces can be compared against it.
        assertThat(messageOf("SELECT txn_id FROM txn;")).startsWith("PRV-2001").contains("Encountered \";\"");
    }

    // -------------------------------------------------------------------------------------------
    // Defects. Each of these is written as the case says it should behave, and disabled, so the
    // suite stays green and the defect stays visible instead of being quietly deleted.
    // -------------------------------------------------------------------------------------------

    @Test
    void booleanLiteralInTheSelectList() {
        // SQLX-016. Either answer is acceptable to the case -- six rows of `true`, or a PRV-2021
        // saying boolean literals are not supported. A ClassCastException is neither.
        String message = messageOf("SELECT TRUE FROM txn");
        if (message != null) {
            assertThat(message).startsWith("PRV-");
        } else {
            assertThat(answerOf("SELECT TRUE FROM txn"))
                    .containsExactly("true", "true", "true", "true", "true", "true");
        }
    }

    @Test
    void aBareNullInTheSelectList() {
        // SQLX-015. Six empty fields or a coded refusal; anything without a code is a FAIL, and
        // the promise that every refusal carries a code is CONTINUOUS_QUERIES.md's own.
        String message = messageOf("SELECT NULL FROM txn");
        if (message != null) {
            assertThat(message).startsWith("PRV-");
        } else {
            assertThat(answerOf("SELECT NULL FROM txn"))
                    .containsExactly("NULL", "NULL", "NULL", "NULL", "NULL", "NULL");
        }
    }

    @Test
    void aTimestampLiteralInAPredicate() {
        // TYPE-030 and SQLX-060. event_time is 1s..13s, so the literal below is in the future and
        // the right answer is no rows -- but any coded refusal would also pass this case. An
        // AssertionError escapes every `catch (Exception)` on the way out.
        String message = messageOf("SELECT txn_id FROM txn WHERE event_time > TIMESTAMP '2020-01-01 00:00:00'");
        if (message != null) {
            assertThat(message).startsWith("PRV-");
        } else {
            assertThat(answerOf("SELECT txn_id FROM txn WHERE event_time > TIMESTAMP '2020-01-01 00:00:00'"))
                    .isEmpty();
        }
    }

    @Test
    void unaryMinus() {
        // SQLX-038 and TYPE-098. Negating D1's amounts gives -100, -250, 50, 0, -7, -7, which sum
        // to -314: the negation of SUM(amount). Either it works, or the refusal must not claim it
        // does; a message that contradicts itself is worse than a bare code.
        String message = messageOf("SELECT -amount FROM txn");
        if (message != null) {
            assertThat(message)
                    .as("a refusal that names the construct it is refusing as supported")
                    .doesNotContain("unary minus included");
        } else {
            assertThat(answerOf("SELECT -amount FROM txn")).containsExactly("-100", "-250", "50", "0", "-7", "-7");
        }
    }

    private static List<String> outputNamesOf(String sql) {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(TXN, OTHER).plan(sql));
        return plan.outputSchema().fields().stream().map(Field::name).toList();
    }

    // ---------------------------------------------------------------------------------------
    // The harness. SQLX calls this shape H-MTX: plan, build, compile, feed D1, render.
    // ---------------------------------------------------------------------------------------

    @Test
    void theLiteralsThatUsedToEscapeUncodedNowAnswerRatherThanRefuse() {
        // The four cases above each accept an answer *or* a coded refusal, because either was an
        // acceptable outcome for the case. This pins what actually happens, so a later change that
        // quietly downgrades an answer to a refusal is a failure rather than a shrug.
        assertThat(answerOf("SELECT TRUE FROM txn")).allMatch("true"::equals).hasSize(6);
        assertThat(answerOf("SELECT -amount FROM txn")).containsExactly("-100", "-250", "50", "0", "-7", "-7");
        assertThat(answerOf("SELECT txn_id FROM txn WHERE event_time > TIMESTAMP '2020-01-01 00:00:00'"))
                .as("the literal is far in the future of the fixture, so nothing matches")
                .isEmpty();
        // A bare NULL stays refused -- it has no type, so there is no column it could be -- but the
        // refusal carries a code and says what to write instead.
        assertThat(messageOf("SELECT NULL FROM txn")).startsWith("PRV-").contains("CAST(NULL AS BIGINT)");
    }

    @Test
    void aStringLiteralMayContainAnyCharacterTheDataMay() {
        // SQLX-176. Calcite validates string literals against a default charset of ISO-8859-1, and
        // nothing overrode it -- so `WHERE user_id = '日本語'` was refused with "Failed to encode
        // '日本語' in character set 'ISO-8859-1'". The same characters as column data have always
        // worked, which made the gap invisible from the data side: a user could store a name and
        // never write it down in a query.
        //
        // It survived a whole QA campaign because the standing hostile-unicode fixture, ünïcødé, is
        // built from Latin-1-representable accents -- adversarial-looking and, by accident, in
        // agreement with the defect. So the cases below are deliberately above U+00FF.
        for (String literal : List.of("日本語", "✓", "🙂", "Ω", "עברית")) {
            assertThat(messageOf("SELECT txn_id FROM txn WHERE user_id = '" + literal + "'"))
                    .as("a literal containing %s must plan", literal)
                    .isNull();
        }
        // And the accented case that used to pass keeps passing, so this is a widening.
        assertThat(messageOf("SELECT txn_id FROM txn WHERE user_id = 'ünïcødé'"))
                .isNull();
    }

    @Test
    void aPredicateTooDeepToWalkIsRefusedRatherThanCrashingTheProcess() {
        // SQLX-171. Calcite's validator recurses on a predicate, so a long enough boolean chain
        // exhausts the stack. A StackOverflowError is an Error, not an Exception, so the planner's
        // catch never saw it: `pravaha validate` printed a raw stack trace to the console and the
        // process died, in about a second, on a query a client library generates by rewriting a
        // wide IN list into ORs.
        //
        // Six thousand terms is the width the case asks for. The assertion is not that this
        // particular number is refused -- the limit is the JVM's stack and moves with -Xss -- but
        // that whatever happens is a coded refusal and not an Error escaping.
        StringBuilder wide = new StringBuilder("SELECT txn_id FROM txn WHERE amount > 0");
        for (int i = 1; i <= 6_000; i++) {
            wide.append(" OR amount > ").append(i);
        }

        String message;
        try {
            message = messageOf(wide.toString());
        } catch (Error escaped) {
            throw new AssertionError(
                    "an Error escaped the planner instead of being turned into a refusal: " + escaped, escaped);
        }
        if (message != null) {
            assertThat(message)
                    .as("if it is refused, the refusal carries a code and says what to do about it")
                    .startsWith("PRV-");
        }
    }

    private static List<String> answerOf(String sql) {
        PhysicalOperator plan = new PhysicalPlanBuilder()
                .build(SqlPlanner.withStreams(TXN, OTHER).plan(sql));
        List<CapturingRowWriter.Captured> captured = new ArrayList<>();
        RowLayout layout = RowLayout.of(TXN);

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), captured::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (int i = 0; i < D1.size(); i++) {
                long handle = feed.allocate(layout.rowSize(256));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                D1.get(i).accept(writer);
                writer.weight(1L).sequence(i + 1L).commit();
                feed.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return captured.stream().map(ExpressionMatrixTest::render).toList();
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

    /** The refusal message, or null if the statement planned, built and compiled. */
    private static @Nullable String messageOf(String sql) {
        try {
            PhysicalOperator plan = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withStreams(TXN, OTHER).plan(sql));
            try (InterpretedPipeline _ = InterpretedPipeline.compile(plan, () -> {
                throw new UnsupportedOperationException("no row is fed while a refusal is being checked");
            })) {
                return null;
            }
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage()).replace('\n', ' ');
        }
    }

    private static void row(
            BinaryRowWriter w,
            long id,
            String user,
            long amount,
            double price,
            @Nullable String status,
            boolean flagged,
            long eventTime) {
        w.setLong(0, id).setString(1, user).setLong(2, amount).setDouble(3, price);
        if (status == null) {
            w.setNull(4);
        } else {
            w.setString(4, status);
        }
        w.setBoolean(5, flagged).setLong(6, eventTime).eventTimestampNanos(eventTime);
    }
}
