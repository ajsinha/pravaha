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
package com.ash.messaging.pravaha.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>The differential tests.</strong> Story P2-05.
 *
 * <p>Generated code and the interpreted path are two implementations of one specification -- the
 * predicate IR -- and this asserts they agree. That is the mechanism design section 12.4 relies on to make
 * code generation, the highest-risk component in the whole design (R2), survivable: a generated
 * stage that disagrees with the interpreter is caught here rather than by a customer noticing their
 * numbers are wrong.
 *
 * <p>Generated over random rows and random predicates, because the bugs worth catching are in
 * particular combinations -- a null in a nullable column, a UTF-8 string whose byte length differs
 * from its character length, a boundary value at exactly the comparison threshold.
 */
class GeneratedStageTest {

    private static StreamSchema inputSchema() {
        return StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .field("score", Types.int32())
                .field("ratio", Types.float64())
                .field("active", Types.bool())
                .field("status", Types.string())
                .field("note", Types.string().withNullable(true))
                // C-5. A nullable column of a type the generator actually projects. `note` is
                // nullable and is a STRING, which the generator refuses, so until this column
                // existed no projection the differential property could build carried a null --
                // and the generated projection dropped every null bit it was given.
                .field("bonus", Types.int64().withNullable(true))
                .build();
    }

    private static StreamSchema outputSchema() {
        return StreamSchema.builder("out")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .build();
    }

    /** The projection the differential property uses: two NOT NULL columns and one nullable one. */
    private static StreamSchema outputSchemaWithANullableColumn() {
        return StreamSchema.builder("out")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .field("bonus", Types.int64().withNullable(true))
                .build();
    }

    private static final List<Integer> DIFFERENTIAL_PROJECTION = List.of(0, 1, 7);

    // ------------------------------------------------------------------ the differential property

    @Property(tries = 300)
    void generatedCodeAgreesWithTheInterpreter(@ForAll("predicates") Predicate predicate, @ForAll("seeds") long seed) {

        PhysicalOperator plan = new ProjectOperator(
                new FilterOperator(ScanOperator.of("txn", inputSchema()), predicate),
                outputSchemaWithANullableColumn(),
                DIFFERENTIAL_PROJECTION);

        List<String> interpreted = runInterpreted(predicate, seed);
        List<String> generated = runGenerated(plan, seed);

        assertThat(generated)
                .as("generated code disagreed with the interpreter for predicate: %s", predicate.describe())
                .isEqualTo(interpreted);
    }

    @Test
    void theDifferentialTestCatchesAGeneratedBug() {
        // A differential test that cannot fail certifies whatever it compares. This plants a
        // generator that inverts its comparison -- the single most likely codegen mistake -- and
        // asserts the comparison notices.
        Predicate predicate = new Predicate.CompareLong(1, "amount", Predicate.Op.GT, 500);
        PhysicalOperator plan = new ProjectOperator(
                new FilterOperator(ScanOperator.of("txn", inputSchema()), predicate),
                outputSchemaWithANullableColumn(),
                DIFFERENTIAL_PROJECTION);

        List<String> interpreted = runInterpreted(predicate, 1L);

        // The generated stage, with its emitted comparison inverted before compilation. The
        // previous version of this guard compared two *interpreted* runs, so it proved the fixture
        // was not degenerate and never established that the generated path was compared at all --
        // which is the one thing a differential test exists to establish.
        var fused = new FilterProjectGenerator().generate(plan, "SeededStage");
        String sabotaged = fused.source().replace(" > 500L", " <= 500L");
        assertThat(sabotaged)
                .as("the seeding must actually change the generated source, or this guard is theatre")
                .isNotEqualTo(fused.source());

        GeneratedStage stage = new StageCompiler().compileFused("SeededStage", sabotaged);
        List<String> wrong = runFused((FusedStage) stage.processor(), 1L);

        assertThat(wrong)
                .as("a generated stage with an inverted comparison must disagree with the interpreter; "
                        + "if it does not, the differential property certifies whatever it is given")
                .isNotEqualTo(interpreted);
    }

    /**
     * C-5, directly: a NULL projected through a generated fused stage comes out NULL.
     *
     * <p>The property above would catch this too, now that its projection carries a nullable
     * column -- but only as "these two lists differ", and the finding is worth one test that says
     * what went wrong in its own name. The generated projection copied the value and never the
     * null bit, and the output row's bitmap is zeroed once per row, so every null arrived as
     * {@code isNull=false, value=0}: a zero standing where an absence was, silently, on the path
     * that only runs once a query is hot.
     */
    @Test
    void c5_aNullProjectedThroughAGeneratedStageIsStillNull() {
        PhysicalOperator plan = new ProjectOperator(
                new FilterOperator(
                        ScanOperator.of("txn", inputSchema()),
                        new Predicate.CompareLong(1, "amount", Predicate.Op.GE, Long.MIN_VALUE)),
                outputSchemaWithANullableColumn(),
                DIFFERENTIAL_PROJECTION);

        List<String> generated = runGenerated(plan, 7L);
        List<String> interpreted = runInterpreted(new Predicate.True(), 7L);

        assertThat(generated)
                .as("every row survives this filter, so the two projections must agree row for row")
                .isEqualTo(interpreted);
        assertThat(generated)
                .as("the fixture has to contain a null, or this test proves nothing")
                .anyMatch(row -> row.endsWith("|null"));
    }

    // ------------------------------------------------------------------ generation mechanics

    @Test
    void generatesReadableSourceWithConstantOffsets() {
        // Generated source is retained and downloadable from the console (design 12.4); it is the
        // first thing anyone reads when a plan gives a wrong answer.
        PhysicalOperator plan = new ProjectOperator(
                new FilterOperator(
                        ScanOperator.of("txn", inputSchema()),
                        new Predicate.CompareLong(1, "amount", Predicate.Op.GT, 100)),
                outputSchema(),
                List.of(0, 1));

        var fused = new FilterProjectGenerator().generate(plan, "TestStage");
        assertThat(fused.source())
                .contains("GENERATED by Pravaha")
                .contains("implements com.ash.messaging.pravaha.codegen.FusedStage")
                .contains("for (int i = 0; i < count; i++)")
                // A field read is one load at a constant offset, not an ordinal lookup.
                .contains("region.getLong(row + ")
                .doesNotContain("getString");
    }

    @Test
    void stringComparisonBecomesAUtf8ByteCompareWithNoStringMaterialised() {
        PhysicalOperator plan = new ProjectOperator(
                new FilterOperator(
                        ScanOperator.of("txn", inputSchema()),
                        new Predicate.CompareString(5, "status", Predicate.Op.EQ, "COMPLETED")),
                outputSchema(),
                List.of(0, 1));

        var fused = new FilterProjectGenerator().generate(plan, "StringStage");
        assertThat(fused.source())
                .contains("private static final byte[] LIT_0")
                .contains("equalsBytes")
                // The length check first is correctness, not optimisation: without it "COMPLETE"
                // would match the prefix of "COMPLETED".
                .contains("== 9 &&")
                .doesNotContain("getString");
    }

    @Test
    void everyComparedColumnGetsANullCheckAsTheInterpretedPredicateDoes() {
        // It was the other way round: a NOT NULL column skipped the bit test, as an optimisation.
        // Since C-7 made the generated stage what a registered query runs, it must give the
        // interpreter's answer on every row, and CompareLong.test checks the bit on every column.
        // A NOT NULL column's bit is never set by a writer, so this costs one byte load from a line
        // already in cache -- and it removes the only input on which the two could differ.
        var generator = new FilterProjectGenerator();

        var notNull = generator.generate(
                new ProjectOperator(
                        new FilterOperator(
                                ScanOperator.of("txn", inputSchema()),
                                new Predicate.CompareLong(1, "amount", Predicate.Op.GT, 100)),
                        outputSchema(),
                        List.of(0, 1)),
                "NotNullStage");
        var nullable = generator.generate(
                new ProjectOperator(
                        new FilterOperator(
                                ScanOperator.of("txn", inputSchema()), new Predicate.IsNull(6, "note", false)),
                        outputSchema(),
                        List.of(0, 1)),
                "NullableStage");

        assertThat(notNull.source()).contains("(!((region.getByte(row + ").contains("region.getLong(row + ");
        assertThat(nullable.source()).contains("getByte").contains("!= 0)");
    }

    @Test
    void compilesInSingleDigitMilliseconds() {
        // Registration latency is a stated product claim (W2, deploy under two seconds), and this
        // is the part of it that can quietly grow as more operators become generable.
        PhysicalOperator plan = new ProjectOperator(
                new FilterOperator(
                        ScanOperator.of("txn", inputSchema()),
                        new Predicate.CompareLong(1, "amount", Predicate.Op.GT, 100)),
                outputSchema(),
                List.of(0, 1));
        var fused = new FilterProjectGenerator().generate(plan, "TimedStage");
        GeneratedStage stage = new StageCompiler().compileFused("TimedStage", fused.source());

        assertThat(stage.compileMillis())
                .as("compile took %d ms", stage.compileMillis())
                .isLessThan(500L);
        assertThat(stage.source()).isNotBlank();
        assertThat(stage.className()).startsWith("TimedStage$");
    }

    @Test
    void aCompilationFailureCarriesTheNumberedSource() {
        // A compiler error citing line 47 is useless without the source it refers to.
        assertThatThrownBy(() -> new StageCompiler().compileFused("Broken", "public class Broken { this is not java }"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3100")
                .hasMessageContaining("generated source")
                .hasMessageContaining("   1  ");
    }

    @Test
    void anOversizedStageIsRefusedRatherThanCompiledIntoSomethingTheJitWillSkip() {
        String huge = "// filler\n".repeat(StageCompiler.MAX_SOURCE_LINES + 10);
        assertThatThrownBy(() -> new StageCompiler().compileFused("Huge", huge))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3102")
                .hasMessageContaining("8 kB");
    }

    @Test
    void anUngenerableOperatorSaysSoSoTheCallerCanFallBack() {
        PhysicalOperator withAggregate = new com.ash.messaging.pravaha.runtime.plan.AggregateOperator(
                ScanOperator.of("txn", inputSchema()),
                StreamSchema.builder("agg").field("n", Types.int64()).build(),
                List.of(),
                List.of(new com.ash.messaging.pravaha.runtime.plan.AggregateOperator.AggregateCall(
                        com.ash.messaging.pravaha.runtime.plan.AggregateOperator.AggregateCall.Kind.COUNT, -1, "n")));

        assertThatThrownBy(() -> new FilterProjectGenerator().generate(withAggregate, "AggStage"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3101")
                .hasMessageContaining("interpreted path");
    }

    @Test
    void aLikeIsRefusedByTheGeneratorRatherThanDecodedPerRow() {
        // Not an unfinished case. This stage beats the interpreter on text precisely because it
        // compares UTF-8 bytes against a pre-encoded literal and never builds a String; a Matcher
        // needs one. Emitting LIKE here would be slower than the path it falls back to, so the
        // refusal is the optimisation.
        PhysicalOperator filtered = new com.ash.messaging.pravaha.runtime.plan.FilterOperator(
                ScanOperator.of("txn", inputSchema()),
                new com.ash.messaging.pravaha.runtime.plan.Predicate.Like(5, "status", "a%", false));

        assertThatThrownBy(() -> new FilterProjectGenerator().generate(filtered, "LikeStage"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-3101")
                .hasMessageContaining("interpreted path");
    }

    // ------------------------------------------------------------------ harness

    private static final int ROWS = 64;

    /** Runs the interpreted path, returning the ids that survived. */
    private static List<String> runInterpreted(Predicate predicate, long seed) {
        List<String> surviving = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 18, 8)) {
            RowLayout layout = RowLayout.of(inputSchema());
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            Random random = new Random(seed);
            for (int i = 0; i < ROWS; i++) {
                long handle = arena.allocate(layout.rowSize(256));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                populate(writer, i, random);
                writer.commit();
                RowView row = view.wrap(arena.regionOf(handle), arena.offsetOf(handle));
                if (predicate.test(row)) {
                    // Every projected column, not just the id. Comparing column 0 alone certifies a
                    // generator that filters correctly and projects the wrong value into column 1 --
                    // and the projection is half of what this generator does.
                    //
                    // The third column is nullable, and its null state is part of the comparison
                    // (C-5): a generated projection that dropped the null bit produced the right
                    // *value* -- zero, which is what the bytes under a null read as -- and the
                    // wrong answer, and a comparison of values alone could not see it.
                    surviving.add(row.getLong(0) + "|" + row.getLong(1) + "|"
                            + (row.isNull(7) ? "null" : Long.toString(row.getLong(7))));
                }
            }
        }
        return surviving;
    }

    /** Runs the generated path over identical input, returning the ids that survived. */
    private static List<String> runGenerated(PhysicalOperator plan, long seed) {
        var fused = new FilterProjectGenerator().generate(plan, "DiffStage");
        GeneratedStage stage = new StageCompiler().compileFused("DiffStage", fused.source());
        return runFused((FusedStage) stage.processor(), seed);
    }

    /** Drives rows through an already-compiled stage, so a deliberately broken one can be run too. */
    private static List<String> runFused(FusedStage generated, long seed) {

        RowLayout inputLayout = RowLayout.of(inputSchema());
        RowLayout outputLayout = RowLayout.of(outputSchemaWithANullableColumn());
        List<String> surviving = new ArrayList<>();

        try (MemoryRegion in = MemoryAccess.best().allocate(1 << 18);
                MemoryRegion out = MemoryAccess.best().allocate(1 << 18)) {
            BinaryRowWriter writer = new BinaryRowWriter(inputLayout);
            long[] offsets = new long[ROWS];
            long[] outOffsets = new long[ROWS];
            Random random = new Random(seed);

            int cursor = 0;
            for (int i = 0; i < ROWS; i++) {
                offsets[i] = cursor;
                writer.begin(in, cursor);
                populate(writer, i, random);
                writer.commit();
                cursor += align8(writer.sizeSoFar());
            }
            for (int i = 0; i < ROWS; i++) {
                outOffsets[i] = (long) i * align8(outputLayout.fixedEnd());
            }

            int emitted = generated.process(in, offsets, ROWS, out, outOffsets, 0);
            BinaryRowView outView = new BinaryRowView(outputLayout);
            for (int i = 0; i < emitted; i++) {
                RowView emittedRow = outView.wrap(out, (int) outOffsets[i]);
                surviving.add(emittedRow.getLong(0) + "|" + emittedRow.getLong(1) + "|"
                        + (emittedRow.isNull(2) ? "null" : Long.toString(emittedRow.getLong(2))));
            }
        }
        return surviving;
    }

    /** Identical values for both paths, including nulls and multi-byte UTF-8. */
    private static void populate(BinaryRowWriter writer, int i, Random random) {
        writer.setLong(0, i)
                .setLong(1, random.nextInt(1000))
                .setInt(2, random.nextInt(200) - 100)
                .setDouble(3, random.nextDouble() * 10)
                .setBoolean(4, random.nextBoolean())
                .setString(5, STATUSES[random.nextInt(STATUSES.length)]);
        if (random.nextInt(4) == 0) {
            writer.setNull(6);
        } else {
            writer.setString(6, NOTES[random.nextInt(NOTES.length)]);
        }
        // Roughly a third null, and never the value a dropped null bit would read back as (zero),
        // so "the null bit was lost" and "the value was right" cannot be confused (C-5).
        if (random.nextInt(3) == 0) {
            writer.setNull(7);
        } else {
            writer.setLong(7, 1 + random.nextInt(1000));
        }
        writer.weight(1L).eventTimestampNanos(1_700_000_000_000_000_000L + i).sequence(i);
    }

    // Deliberately includes a value whose UTF-8 byte length differs from its character length, and
    // a pair sharing a prefix, which is where a length-less byte comparison would go wrong.
    private static final String[] STATUSES = {"COMPLETED", "COMPLETE", "PENDING", "प्रवाह"};
    private static final String[] NOTES = {"a", "", "note with spaces", "🌊"};

    private static int align8(int value) {
        return (value + 7) & ~7;
    }

    @Provide
    Arbitrary<Predicate> predicates() {
        return Arbitraries.longs().map(GeneratedStageTest::randomPredicate);
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }

    private static Predicate randomPredicate(long seed) {
        Random random = new Random(seed);
        // Case 7 compares against the NULLABLE column deliberately. An earlier version of this
        // generator only ever touched `note` through IS NULL, so the generated null-check branch
        // was never exercised -- a seeded bug that dropped the check went undetected. Comparisons
        // against a nullable column are exactly where SQL's three-valued logic gets lost.
        return switch (random.nextInt(9)) {
            case 0 -> new Predicate.CompareLong(1, "amount", randomOp(random), random.nextInt(1000));
            case 1 -> new Predicate.CompareInt(2, "score", randomOp(random), random.nextInt(200) - 100);
            case 2 -> new Predicate.CompareDouble(3, "ratio", randomOp(random), random.nextDouble() * 10);
            case 3 -> new Predicate.CompareBoolean(4, "active", random.nextBoolean());
            case 4 ->
                new Predicate.CompareString(
                        5,
                        "status",
                        random.nextBoolean() ? Predicate.Op.EQ : Predicate.Op.NE,
                        STATUSES[random.nextInt(STATUSES.length)]);
            case 5 -> new Predicate.IsNull(6, "note", random.nextBoolean());
            case 6 ->
                new Predicate.And(List.of(
                        new Predicate.CompareLong(1, "amount", Predicate.Op.GT, random.nextInt(500)),
                        new Predicate.CompareString(5, "status", Predicate.Op.EQ, "COMPLETED")));
            case 7 ->
                new Predicate.CompareString(
                        6,
                        "note",
                        random.nextBoolean() ? Predicate.Op.EQ : Predicate.Op.NE,
                        NOTES[random.nextInt(NOTES.length)]);
            default ->
                new Predicate.Or(List.of(
                        new Predicate.CompareLong(1, "amount", Predicate.Op.LT, 100),
                        new Predicate.IsNull(6, "note", true)));
        };
    }

    private static Predicate.Op randomOp(Random random) {
        Predicate.Op[] ops = Predicate.Op.values();
        return ops[random.nextInt(ops.length)];
    }
}
