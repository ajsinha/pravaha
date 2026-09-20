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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The TY cluster: the type system's own defects, one test per finding.
 *
 * <p>Named so that the finding is findable from the test and the test from the finding, because
 * the register and the code are read by different people at different times. Each method says what
 * the defect was, not only what the behaviour is now -- a test that pins the right answer without
 * saying which wrong answer it is holding back gets deleted by whoever finds it inconvenient.
 *
 * <p>Every finding here is a correctness question first. A type accepted and mishandled, a
 * conversion that rounds where nobody asked, an aggregate over something that is not a number, a
 * literal compared by different rules from the column beside it: all of them can produce an answer
 * that is wrong and a status that says fine, which is the failure this engine exists to not have.
 * Where the answer was only badly explained, the test says so.
 */
class TypeClusterTest {

    /** One column of every type the cluster touches, with a nullable text column for the null work. */
    private static final StreamSchema TYPES = StreamSchema.builder("types")
            .field("id", Types.int64())
            .field("n", Types.int64())
            .field("r", Types.float32())
            .field("f", Types.float64())
            .field("s", Types.string())
            .field("tag", Types.string().withNullable(true))
            .field("bin", Types.bytes())
            .field("tags", Types.array(Types.string()).withNullable(true))
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();

    private static final long SECOND = 1_000_000_000L;

    /**
     * Three rows.
     *
     * <pre>
     * id  n     r      f     s        tag     bin       event_time
     * 1   3     1.5    2.5   alpha    ok      {1,2}      1s
     * 2   7    -0.5    0.0   beta     NULL    {}         2s
     * 3   0     3.25   1.0   gamma    ok      {9}        3s
     * </pre>
     */
    private static final List<Consumer<BinaryRowWriter>> ROWS = List.of(
            w -> row(w, 1, 3, 1.5f, 2.5, "alpha", "ok", new byte[] {1, 2}, SECOND),
            w -> row(w, 2, 7, -0.5f, 0.0, "beta", null, new byte[] {}, 2 * SECOND),
            w -> row(w, 3, 0, 3.25f, 1.0, "gamma", "ok", new byte[] {9}, 3 * SECOND));

    // ---------------------------------------------------------------------------------------
    // TY-4 -- two ordinary expression shapes crashed with a raw, uncoded Java exception
    // ---------------------------------------------------------------------------------------

    @Test
    void ty4_aDoubleLiteralWrittenWithAnExponentDividesRatherThanThrowingAClassCastException() {
        // `r / 3.0E0` threw `ClassCastException: java.lang.Double cannot be cast to
        // java.math.BigDecimal` out of ExpressionCompiler.literal -- no code, no sentence, and
        // reachable from an ordinary division a person would write without thinking about it.
        // Calcite carries `3.0E0` as a Double and `3.0` as a BigDecimal, and the compiler assumed
        // the second.
        assertThat(answerOf("SELECT r / 3.0E0 FROM types"))
                .containsExactly(String.valueOf(1.5f / 3.0), String.valueOf(-0.5f / 3.0), String.valueOf(3.25f / 3.0));
        // The value is the point, not merely that it planned: the two spellings of the same
        // literal have to divide to the same number.
        assertThat(answerOf("SELECT r / 3.0E0 FROM types"))
                .isEqualTo(answerOf("SELECT r / CAST(3.0 AS DOUBLE) FROM types"));
        // And the same literal inside a predicate, which the finding's update added.
        assertThat(answerOf("SELECT id FROM types WHERE f > 1.5E0")).containsExactly("1");
    }

    @Test
    void ty4_aCaseWhoseBranchesDisagreeIsRefusedWithACodeRatherThanAnIllegalArgumentException() {
        // `CASE WHEN n > 5 THEN 1 ELSE 1.5 END` threw IllegalArgumentException straight out of
        // Expression.Case's constructor. Calcite types that CASE DECIMAL, exactly as it types
        // `amount * 1.5`, so the refusal it deserves is the decimal one -- and it is a refusal
        // rather than a widening because a CASE that chose FLOAT64 would round an INT64 branch.
        PravahaException refusal = refusalOf("SELECT CASE WHEN n > 5 THEN 1 ELSE 1.5 END FROM types");
        assertThat(refusal).hasMessageContaining("PRV-2021").hasMessageContaining("DECIMAL arithmetic");
        assertThat(refusal.getCause()).isNull();
        // The sibling that must keep working: branches that agree need no refusal.
        assertThat(answerOf("SELECT CASE WHEN n > 5 THEN 1 ELSE 2 END FROM types"))
                .containsExactly("2", "1", "2");
        assertThat(answerOf("SELECT CASE WHEN n > 5 THEN f ELSE 0 END FROM types"))
                .containsExactly("0.0", "0.0", "0.0");
    }

    // ---------------------------------------------------------------------------------------
    // TY-5 -- IS NULL over a computed CASE was refused
    // ---------------------------------------------------------------------------------------

    @Test
    void ty5_isNullOverAComputedCaseIsAnsweredRatherThanRefused() {
        // `PRV-2021 cannot compile the expression 'IS NULL(CASE(...))'`: the compiler required a
        // bare column reference, so the ordinary way to ask whether a computed value came out
        // empty was refused while the same CASE in the SELECT list compiled.
        //
        // Row 2 has a null tag, so the CASE is null there and only there.
        assertThat(answerOf("SELECT id FROM types WHERE (CASE WHEN n > 0 THEN tag ELSE 'x' END) IS NULL"))
                .containsExactly("2");
        assertThat(answerOf("SELECT id FROM types WHERE (CASE WHEN n > 0 THEN tag ELSE 'x' END) IS NOT NULL"))
                .as("the two halves must partition the rows; a null-check that is not total is a wrong answer")
                .containsExactly("1", "3");
        // Over arithmetic as well, not only over a CASE.
        assertThat(answerOf("SELECT id FROM types WHERE (n * 2) IS NOT NULL")).containsExactly("1", "2", "3");
    }

    // ---------------------------------------------------------------------------------------
    // TY-10 -- the ARRAY/MAP/ROW refusal named neither the column nor the type
    // ---------------------------------------------------------------------------------------

    @Test
    void ty10_projectingAnArrayColumnNamesTheColumnAndSaysWhichTypesArriveAsAny() {
        // The refusal was `no Pravaha type for SQL type ANY; the supported set is in TypeMapping`
        // -- a Calcite word the person never wrote, about a column they were not told the name of.
        assertThat(refusalOf("SELECT tags FROM types"))
                .hasMessageContaining("PRV-2021")
                .hasMessageContaining("column 'tags'")
                .hasMessageContaining("ARRAY, MAP or ROW");
        // The columns beside it still plan, which is what makes naming the column worth anything.
        assertThatCode(() -> plan("SELECT id, s FROM types")).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------
    // TY-14 -- a BYTES comparison refused without naming the column
    // ---------------------------------------------------------------------------------------

    @Test
    void ty14_comparingABytesColumnNamesTheColumnAsTheArrayCaseAlreadyDid() {
        // `WHERE bin = 'cafe'` answered `'CAST('cafe'):VARBINARY NOT NULL' has SQL type VARBINARY`
        // -- Calcite's rendering of a cast the person did not write -- because BYTES maps to a real
        // VARBINARY, takes an implicit cast, and so missed the column-naming path that ARRAY
        // reached. Two type families refusing the same mistake two different ways.
        for (String sql : List.of(
                "SELECT id FROM types WHERE bin = 'cafe'",
                "SELECT id FROM types WHERE bin <> 'cafe'",
                "SELECT id FROM types WHERE 'cafe' = bin")) {
            assertThat(refusalOf(sql))
                    .as("%s", sql)
                    .hasMessageContaining("PRV-2021")
                    .hasMessageContaining("column 'bin'")
                    .hasMessageContaining("BYTES");
        }
        // The ARRAY spelling that was already right stays right, so this is one message not two.
        assertThat(refusalOf("SELECT id FROM types WHERE tags = tags")).hasMessageContaining("column 'tags'");
    }

    // ---------------------------------------------------------------------------------------
    // TY-16 -- SUM/AVG over text was refused by the DECIMAL guard
    // ---------------------------------------------------------------------------------------

    @Test
    void ty16_sumOverTextIsRefusedAsAnAggregateOverTextRatherThanAsDecimalArithmetic() {
        // Calcite does not reject a text operand to SUM: it inserts CAST(s AS DECIMAL(38,19))
        // underneath. So the person who asked for the sum of a text column was answered with a
        // paragraph about 128-bit decimals and rounding errors in ledgers, naming a cast they
        // never wrote. PRV-2020 now, the same code the float-accumulator refusal uses.
        for (String sql : List.of("SELECT SUM(s) FROM types", "SELECT AVG(s) FROM types")) {
            assertThat(refusalOf(sql))
                    .as("%s", sql)
                    .hasMessageContaining("PRV-2020")
                    .hasMessageContaining("(s)")
                    .hasMessageNotContaining("DECIMAL arithmetic")
                    .hasMessageNotContaining("ledger");
        }
        // The refusal must not have widened into "aggregates are refused": the numeric ones work.
        assertThat(answerOf("SELECT SUM(n) FROM types")).containsExactly("10");
        assertThat(answerOf("SELECT COUNT(s) FROM types"))
                .as("COUNT reads no value, so it is not refused over text")
                .containsExactly("3");
        assertThat(answerOf("SELECT MIN(n), MAX(n) FROM types")).containsExactly("0|7");
        // And the float-accumulator refusal it was made to match is untouched.
        assertThat(refusalOf("SELECT SUM(f) FROM types"))
                .hasMessageContaining("PRV-2020")
                .hasMessageContaining("64-bit integers");
    }

    // ---------------------------------------------------------------------------------------
    // TY-20 -- ORDER BY inside a derived table planned and ran
    // ---------------------------------------------------------------------------------------

    @Test
    void ty20_orderByIsRefusedWhicheverShapeItIsWrittenIn() {
        // `SELECT * FROM (SELECT id FROM types ORDER BY id) x` planned and ran, exit 0, while every
        // other ORDER BY was refused with PRV-2020: the optimiser drops a sort inside a derived
        // table with no FETCH before a Sort node ever reaches the plan builder, so the refusal was
        // a property of the plan's shape rather than a promise about SQL.
        // The finding's own shape first: it is the one that planned and ran, and the ones after it
        // were refused all along -- by a message that did not say ORDER BY, which is why they are
        // here too.
        for (String sql : List.of(
                "SELECT * FROM (SELECT id FROM types ORDER BY id) x",
                "SELECT COUNT(*) FROM (SELECT id FROM types ORDER BY id) x",
                "SELECT * FROM (SELECT id FROM types ORDER BY id FETCH FIRST 2 ROWS ONLY) x",
                "SELECT id FROM types ORDER BY id",
                "SELECT id FROM types ORDER BY id DESC",
                "SELECT id FROM types ORDER BY id FETCH FIRST 3 ROWS ONLY")) {
            assertThat(refusalOf(sql))
                    .as("%s", sql)
                    .hasMessageContaining("PRV-2020")
                    .hasMessageContaining("ORDER BY");
        }
        // A window's own ORDER BY is a different clause with a different meaning, and is refused
        // for its own reason rather than by this one.
        assertThat(refusalOf("SELECT ROW_NUMBER() OVER (ORDER BY event_time) FROM types"))
                .hasMessageNotContaining("no row order to maintain");
        // The same queries without the sort still plan, so nothing was refused by association.
        assertThatCode(() -> plan("SELECT * FROM (SELECT id FROM types) x")).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------
    // TY-23 -- `||` accepted a number written as a literal and refused it written as a column
    // ---------------------------------------------------------------------------------------

    @Test
    void ty23_aNumberIsRefusedInTextWhetherItIsWrittenAsALiteralOrAsAColumn() {
        // `s || 5` and `s || CAST(5 AS VARCHAR)` both succeeded -- SQL's coercion inserts the cast
        // and the optimiser folds it away before anything Pravaha owns runs -- while `s || n` was
        // correctly refused. Whether a number could become text depended on how it was spelled.
        String columnRefusal = refusalOf("SELECT s || n FROM types").getMessage();
        assertThat(columnRefusal).contains("PRV-2021");
        for (String sql : List.of(
                "SELECT s || 5 FROM types",
                "SELECT 5 || s FROM types",
                "SELECT s || CAST(5 AS VARCHAR) FROM types",
                "SELECT s || TRUE FROM types",
                "SELECT CASE WHEN n > 5 THEN 'big' ELSE 0 END FROM types")) {
            assertThat(refusalOf(sql))
                    .as("%s -- the column form is refused, so the literal form must be too", sql)
                    .hasMessageContaining("PRV-2021");
        }
        // Text concatenation itself is untouched, literal or column.
        assertThat(answerOf("SELECT s || '!' FROM types")).containsExactly("alpha!", "beta!", "gamma!");
        assertThat(answerOf("SELECT s || s FROM types")).containsExactly("alphaalpha", "betabeta", "gammagamma");
        // And CAST(NULL AS VARCHAR) keeps working: it converts nothing, and it is the rewrite the
        // bare-NULL refusal itself recommends, so refusing it would be advice that fails.
        assertThatCode(() -> plan("SELECT CAST(NULL AS VARCHAR) FROM types")).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------
    // TY-24 -- Calcite's validator answers first, with a less specific message
    // ---------------------------------------------------------------------------------------

    @Test
    void ty24_aRefusalCalciteMakesFirstStillSaysWhatThisEngineEvaluates() {
        // Five shapes never reach Pravaha's own message: an unknown function, a function given the
        // wrong number of arguments, a cast between types that do not convert. Winning that race
        // means replacing Calcite's operator table and cast checker, which is a batch of its own;
        // what is here is the engine's own sentence appended to the validator's, so the reader is
        // told what *is* evaluated whichever refusal won.
        for (String sql : List.of(
                "SELECT LTRIM(s) FROM types",
                "SELECT RTRIM(s) FROM types",
                "SELECT CONCAT(s, s) FROM types",
                "SELECT ABS(f, 1) FROM types",
                "SELECT ROUND(f, 2, 1) FROM types")) {
            assertThat(refusalOf(sql))
                    .as("%s", sql)
                    .hasMessageContaining("PRV-2002")
                    .hasMessageContaining("Pravaha evaluates")
                    .hasMessageContaining("TRIM")
                    .hasMessageContaining("CONTINUOUS_QUERIES.md");
        }
        assertThat(refusalOf("SELECT CAST(n > 1 AS INTEGER) FROM types"))
                .hasMessageContaining("PRV-2002")
                .hasMessageContaining("conversions between numbers")
                .hasMessageContaining("CASE WHEN");
        // FLOOR(x, n) is the sub-case that cannot be improved: multi-argument FLOOR is reserved SQL
        // syntax, so the parser stops before any validator or any engine sees a function call.
        assertThat(refusalOf("SELECT FLOOR(f, 1) FROM types")).hasMessageContaining("PRV-2001");
        // CAST(<int> AS BOOLEAN) no longer refuses at all: Calcite rewrites it to `n <> 0` and the
        // TY-11 fix gave a boolean-valued call a compiled path. Pinned as an answer, because a
        // sub-case that silently changed from a refusal to a result is worth a test either way.
        assertThat(answerOf("SELECT CAST(n AS BOOLEAN) FROM types")).containsExactly("true", "true", "false");
    }

    // ---------------------------------------------------------------------------------------
    // The harness
    // ---------------------------------------------------------------------------------------

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(TYPES).plan(sql));
    }

    private static PravahaException refusalOf(String sql) {
        try {
            plan(sql);
        } catch (PravahaException refused) {
            return refused;
        }
        throw new AssertionError("expected a coded refusal, and the statement planned: " + sql);
    }

    private static List<String> answerOf(String sql) {
        PhysicalOperator plan = plan(sql);
        List<CapturingRowWriter.Captured> captured = new ArrayList<>();
        RowLayout layout = RowLayout.of(TYPES);

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), captured::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (int i = 0; i < ROWS.size(); i++) {
                long handle = feed.allocate(layout.rowSize(256));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                ROWS.get(i).accept(writer);
                writer.weight(1L).sequence(i + 1L).commit();
                feed.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return captured.stream().map(TypeClusterTest::render).toList();
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

    private static void row(
            BinaryRowWriter w, long id, long n, float r, double f, String s, String tag, byte[] bin, long eventTime) {
        w.setLong(0, id).setLong(1, n).setFloat(2, r).setDouble(3, f).setString(4, s);
        if (tag == null) {
            w.setNull(5);
        } else {
            w.setString(5, tag);
        }
        w.setBytes(6, bin);
        // Nullable, and always null: a nested value cannot be written through this writer at all,
        // which is itself why ARRAY/MAP/ROW are declarable and not selectable.
        w.setNull(7);
        w.setLong(8, eventTime).eventTimestampNanos(eventTime);
    }
}
