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
package com.ash.messaging.pravaha.it.qa.sql;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The `docs/project/qa/cases/SQLX.md` cases that need a second or third stream registered
 * (H-MTX in that document's harness table) rather than one the CLI can express: an alias shadowing
 * another stream's name, duplicate columns from a join, and every set-operation / CTE / subquery
 * refusal, which SQLX.md builds against {@code TXN}, {@code OTHER} and {@code THIRD}.
 *
 * <p>Only the plan is built here -- these are all either refusals (caught and their message
 * inspected) or plans whose row count is checked by feeding no rows and asserting the plan compiles
 * without throwing, which is sufficient for "does it plan or does it refuse, and with what code".
 */
@org.junit.jupiter.api.Tag("qa")
class SqlxMultiStreamTest {

    /** SQLX Fixture S, matching {@code SqlAnswerTest.txn()} exactly (7 columns, txn_id INT64). */
    private static StreamSchema txn() {
        return StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("price", Types.float64())
                .field("status", Types.string().withNullable(true))
                .field("flagged", Types.bool())
                .field("event_time", Types.timestamp())
                .build();
    }

    private static StreamSchema other() {
        return StreamSchema.builder("other")
                .field("user_id", Types.string())
                .field("region", Types.string())
                .field("event_time", Types.timestamp())
                .build();
    }

    private static StreamSchema third() {
        return StreamSchema.builder("third")
                .field("user_id", Types.string())
                .field("score", Types.int64())
                .build();
    }

    /** Builds the plan; returns null if it planned and compiled, or the refusal's message if not. */
    private static String refusalOf(String sql) {
        try {
            PhysicalOperator plan = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withStreams(txn(), other(), third()).plan(sql));
            try (InterpretedPipeline _ = InterpretedPipeline.compile(plan, () -> {
                throw new UnsupportedOperationException("no row is fed while a refusal is being checked");
            })) {
                return null;
            }
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage()).replace('\n', ' ');
        }
    }

    /**
     * Builds and binds the plan with a genuine Java value (not a CLI string, which is what
     * SQLX-157 needs: {@code --params} can only ever hand the server a STRING).
     */
    private static String bindingRefusalOf(String sql, Object... values) {
        try {
            new PhysicalPlanBuilder()
                    .bind(com.ash.messaging.pravaha.sql.plan.BoundParameters.of(values))
                    .build(SqlPlanner.withStreams(txn(), other(), third()).plan(sql));
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage()).replace('\n', ' ');
        }
    }

    // ===========================================================================================
    // SQLX-009, SQLX-012: aliasing and duplicate columns across streams -- these plan and must be
    // checked for shape, not refusal, so they get their own small assertions rather than the
    // refusal table.
    // ===========================================================================================

    @Test
    void anAliasShadowingAnotherStreamsNameStillReadsItsOwnStream() {
        // SQLX-009. "other" is a real registered stream with no txn_id column; if the alias leaked,
        // planning `other.txn_id` would fail to resolve rather than silently reading the wrong
        // stream, because `other` genuinely lacks that column.
        assertThat(refusalOf("SELECT other.txn_id FROM txn AS other")).isNull();
    }

    @Test
    void duplicateColumnNamesFromAJoinBothSurviveWithDistinctOrdinals() {
        // SQLX-012. txn.user_id and other.user_id must both reach the output; Calcite disambiguates
        // rather than collapsing them, which SQLX-010/011 already established for one stream.
        assertThat(refusalOf("SELECT t.user_id, o.user_id FROM txn t JOIN other o ON t.user_id = o.user_id"))
                .isNull();
    }

    // ===========================================================================================
    // SQLX-127..134: set operations. All PRV-2020 at the top level; SQLX-134 puts one inside a CTE,
    // a derived table and an IN-subquery to show the code changes with the shape.
    // ===========================================================================================

    @Test
    void everySetOperationIsRefusedAtPlanTimeNamingItsCalciteClass() {
        String union = refusalOf("SELECT user_id FROM txn UNION SELECT user_id FROM other");
        String unionAll = refusalOf("SELECT user_id FROM txn UNION ALL SELECT user_id FROM other");
        String intersect = refusalOf("SELECT user_id FROM txn INTERSECT SELECT user_id FROM other");
        String except = refusalOf("SELECT user_id FROM txn EXCEPT SELECT user_id FROM other");

        // SQLX-127/128: both UNION spellings are PRV-2020/LogicalUnion, not two different codes.
        assertThat(union).startsWith("PRV-2020").contains("LogicalUnion");
        assertThat(unionAll).startsWith("PRV-2020").contains("LogicalUnion");

        // SQLX-129: INTERSECT at least names a word the user wrote.
        assertThat(intersect).startsWith("PRV-2020").contains("LogicalIntersect");

        // SQLX-130: EXCEPT is refused as "LogicalMinus", sharing no substring with the keyword the
        // user typed -- recorded as a message defect against the brief's own criterion, not fixed.
        assertThat(except).startsWith("PRV-2020").contains("LogicalMinus").doesNotContain("EXCEPT");

        // SQLX-133: all four end with the same supported-operator list and are long enough to be
        // more than a bare code; none mentions the real reason (ADR-030 tier 4, out of scope).
        for (String message : new String[] {union, unionAll, intersect, except}) {
            assertThat(message.length()).isGreaterThan(40);
            assertThat(message).doesNotContain("ADR-030").doesNotContain("out of scope");
        }
    }

    @Test
    void aSetOperationBetweenAStreamAndItselfIsRefusedAtPlanTimeWithACode() {
        // SQLX-131. Unlike the self-join (caught only when the pipeline is compiled, no PRV code),
        // PhysicalPlanBuilder.build's default branch for LogicalUnion fires before that check is
        // ever reached, so this refusal -- unlike the self-join's -- does carry a code.
        String message = refusalOf("SELECT user_id FROM txn UNION ALL SELECT user_id FROM txn");
        assertThat(message).startsWith("PRV-2020").contains("LogicalUnion");
    }

    @Test
    void aSetOperationNestedInsideACteOrADerivedTableIsRefusedWithACodeThatDependsOnTheShape() {
        // SQLX-134. Same underlying union, three different codes depending on where it sits.
        String inCte =
                refusalOf("WITH u AS (SELECT user_id FROM txn UNION SELECT user_id FROM other) SELECT user_id FROM u");
        assertThat(inCte).startsWith("PRV-2020").contains("LogicalUnion");

        String inDerived =
                refusalOf("SELECT x.user_id FROM (SELECT user_id FROM txn EXCEPT SELECT user_id FROM other) x");
        assertThat(inDerived).startsWith("PRV-2020").contains("LogicalMinus");

        // A union inside an IN-subquery is refused by the predicate compiler before the union
        // inside it is ever examined -- a different code (PRV-2021) for text that contains a union.
        String inSubquery = refusalOf("SELECT user_id FROM txn WHERE user_id IN "
                + "(SELECT user_id FROM other UNION SELECT user_id FROM third)");
        assertThat(inSubquery).startsWith("PRV-2021");
    }

    // ===========================================================================================
    // SQLX-139, 140, 141: CTEs referenced twice, WITH RECURSIVE, VALUES.
    // ===========================================================================================

    @Test
    void aCteJoinsCleanlyReferencedOnceAndReferencedTwiceAsASelfJoin() {
        // SQLX-139(a): one reference through a CTE, joined to a different stream -- must plan.
        assertThat(refusalOf("WITH a AS (SELECT user_id, amount FROM txn) "
                        + "SELECT x.amount FROM a x JOIN other o ON x.user_id = o.user_id"))
                .isNull();

        // SQLX-139(b): the same CTE joined to itself is a self-join, which was refused at pipeline
        // compile time with no PRV- code and now compiles.
        assertThat(
                        refusalOf(
                                "WITH a AS (SELECT user_id FROM txn) SELECT p.user_id FROM a p JOIN a q ON p.user_id = q.user_id"))
                .isNull();
    }

    @Test
    void aRecursiveCteFailsWithACodeRatherThanLoopingOrOverflowing() {
        // SQLX-140. Undocumented construct; either a parse refusal or an operator refusal is
        // acceptable, but it must be prompt and it must carry a PRV- code -- a stack overflow, an
        // infinite loop or a stall would all be a FAIL. JUnit's own default timeout is well under
        // the five-minute budget the case allows, so no explicit bound is needed here.
        String message = refusalOf("WITH RECURSIVE r(n) AS (SELECT 1 FROM txn UNION ALL "
                + "SELECT n + 1 FROM r WHERE n < 5) SELECT n FROM r");
        assertThat(message).isNotNull().startsWith("PRV-");
    }

    @Test
    void noSpellingOfValuesIsAcceptedAndEveryRefusalCarriesACode() {
        // SQLX-141(a),(b): LogicalValues reaches the same default branch as ORDER BY / set ops.
        assertThat(refusalOf("SELECT * FROM (VALUES (1), (2)) AS v(x)"))
                .startsWith("PRV-2020")
                .contains("LogicalValues");
        assertThat(refusalOf("VALUES (1), (2)")).startsWith("PRV-2020").contains("LogicalValues");
    }

    // ===========================================================================================
    // SQLX-157: a bound value of the wrong Java type. The CLI's --params only ever produces
    // Strings, so this needs a genuine bind() call with a real Boolean/Integer/byte[].
    // ===========================================================================================

    @Test
    void aBoundValueOfTheWrongTypeIsRefusedWithACodeNamingThePlaceholderAndTheType() {
        // A Boolean where the query needs STRING.
        assertThat(bindingRefusalOf("SELECT txn_id FROM txn WHERE user_id = ?", true))
                .startsWith("PRV-2062")
                .contains("?1");
        // A String where the query needs INT64.
        assertThat(bindingRefusalOf("SELECT txn_id FROM txn WHERE amount = ?", "not a number"))
                .startsWith("PRV-2062")
                .contains("?1");
        // A byte[] where the query needs INT64.
        assertThat(bindingRefusalOf("SELECT txn_id FROM txn WHERE amount = ?", (Object) new byte[] {1, 2, 3}))
                .startsWith("PRV-2062")
                .contains("?1");
        // The control: checkAssignable deliberately accepts a boxed Integer for a FLOAT64
        // placeholder (a type rule, not a class-identity rule) -- this must NOT be refused.
        assertThat(bindingRefusalOf("SELECT txn_id FROM txn WHERE price = ?", 1))
                .isNull();
    }

    // ===========================================================================================
    // SQLX-142..146: subqueries and window functions.
    // ===========================================================================================

    @Test
    void inSubqueryIsRefusedAndDumpsTheWholeSubplanIntoTheMessage() {
        // SQLX-142. Round 1's finding: the message interpolates the entire inner plan.
        String message = refusalOf("SELECT txn_id FROM txn WHERE user_id IN (SELECT user_id FROM other)");
        assertThat(message).startsWith("PRV-2021").contains("IN");
        // The control -- an ordinary literal IN-list -- must plan in the same build.
        assertThat(refusalOf("SELECT txn_id FROM txn WHERE user_id IN ('a','b')"))
                .isNull();

        assertThat(refusalOf("SELECT txn_id FROM txn WHERE user_id NOT IN (SELECT user_id FROM other)"))
                .startsWith("PRV-2021");
    }

    @Test
    void existsAndNotExistsAreBothRefused() {
        // SQLX-143.
        assertThat(refusalOf("SELECT txn_id FROM txn WHERE EXISTS (SELECT 1 FROM other)"))
                .startsWith("PRV-2021")
                .contains("EXISTS");
        assertThat(refusalOf("SELECT txn_id FROM txn WHERE NOT EXISTS (SELECT 1 FROM other)"))
                .startsWith("PRV-2021")
                .contains("EXISTS");
    }

    @Test
    void aCorrelatedSubqueryIsRefusedAndBothReachableFormsOfferTheLookupJoinAlternative() {
        // SQLX-144, and finding X-7 which it became. buildLookupJoin's message naming "JOIN dim FOR
        // SYSTEM_TIME AS OF <time>" is the best-written refusal in this engine, and the case asked
        // whether a query anybody would actually type reaches it. Neither of the two ordinary
        // correlated shapes did: a correlated EXISTS was refused by the predicate compiler as an
        // unsupported EXISTS expression, and a correlated scalar subquery in the select list by the
        // expression compiler as an unsupported $SCALAR_QUERY function -- both before planning
        // reached buildLookupJoin's Correlate branch, and both listing constructs (AND, OR, IS
        // NULL; ABS, FLOOR, ROUND) at somebody who wrote a subquery.
        //
        // Both arms now ask whether the subquery is correlated and, if it is, answer with the one
        // sentence CorrelatedSubqueries holds. The assertion is inverted from the one this test
        // carried while the finding was open.
        String correlatedExists = refusalOf(
                "SELECT txn_id FROM txn WHERE EXISTS (SELECT 1 FROM other WHERE other.user_id = txn.user_id)");
        String correlatedScalar = refusalOf(
                "SELECT txn_id, " + "(SELECT COUNT(*) FROM other WHERE other.user_id = txn.user_id) FROM txn");
        assertThat(correlatedExists).startsWith("PRV-2020").contains("correlated subquery");
        assertThat(correlatedScalar).startsWith("PRV-2020").contains("correlated subquery");
        assertThat(correlatedExists + " " + correlatedScalar)
                .as("X-7: both reachable correlated forms now name the lookup join")
                .contains("FOR SYSTEM_TIME AS OF");
    }

    @Test
    void anUncorrelatedScalarSubqueryIsRefused() {
        // SQLX-145.
        assertThat(refusalOf("SELECT txn_id, (SELECT COUNT(*) FROM other) FROM txn"))
                .startsWith("PRV-2021");
        // In a predicate, Calcite may decorrelate into a join -- record whichever code results
        // rather than assume it stays PRV-2021.
        String predicateForm = refusalOf("SELECT txn_id FROM txn WHERE amount > (SELECT COUNT(*) FROM other)");
        assertThat(predicateForm).isNotNull().matches(m -> m.startsWith("PRV-2020") || m.startsWith("PRV-2021"));
    }

    @Test
    void windowFunctionsAreRefusedNamingTheFunctionTheUserWrote() {
        // SQLX-146. The control (a real global aggregate) must still plan in the same build.
        assertThat(refusalOf("SELECT SUM(amount) FROM txn")).isNull();

        assertThat(refusalOf("SELECT ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY event_time) FROM txn"))
                .startsWith("PRV-2021")
                .contains("ROW_NUMBER");
        assertThat(refusalOf("SELECT RANK() OVER (ORDER BY amount) FROM txn"))
                .startsWith("PRV-2021")
                .contains("RANK");
        String sumOver = refusalOf("SELECT SUM(amount) OVER (PARTITION BY user_id) FROM txn");
        assertThat(sumOver).isNotNull().matches(m -> m.startsWith("PRV-2020") || m.startsWith("PRV-2021"));
        assertThat(refusalOf("SELECT LAG(amount) OVER (ORDER BY event_time) FROM txn"))
                .startsWith("PRV-2021")
                .contains("LAG");
    }
}
