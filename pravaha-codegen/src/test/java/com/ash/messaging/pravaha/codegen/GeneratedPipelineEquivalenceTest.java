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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.PravahaType;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.GeneratedChains;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.exec.StageGenerator;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lane's pipeline gives the same answer with the generator installed as without it (C-7).
 *
 * <p>{@link GeneratedStageTest} compares a generated stage with the interpreter's predicate, one
 * fixed schema at a time. This compares what a registered query actually runs: {@link
 * InterpretedPipeline#compile} with and without {@link GeneratedChains} holding a generator, over
 * random schemas of every fixed-width type and text, random nullability, random chains of filters and
 * a projection, and values chosen where comparisons go wrong -- the integer extremes, NaN, both
 * zeros, the infinities, multi-byte text and shared prefixes. What reaches the sink is compared row
 * by row and value by value, doubles by their bits; a failure is compared by its exception.
 *
 * <p>A chain the generator refuses runs interpreted on both sides and agrees trivially, so the
 * property is only worth what it generates. {@link #enoughOfTheChainsAreGenerated} pins that.
 */
class GeneratedPipelineEquivalenceTest {

    private static final TypeName[] TYPES = {
        TypeName.INT8,
        TypeName.INT16,
        TypeName.INT32,
        TypeName.INT64,
        TypeName.DATE,
        TypeName.TIMESTAMP_LTZ,
        TypeName.FLOAT32,
        TypeName.FLOAT64,
        TypeName.BOOLEAN,
        TypeName.STRING
    };

    private static final long[] LONGS = {0, 1, -1, 7, 900, 901, -900, Long.MAX_VALUE, Long.MIN_VALUE};
    private static final int[] INTS = {0, 1, -1, 7, 900, 901, -900, Integer.MAX_VALUE, Integer.MIN_VALUE};
    private static final double[] DOUBLES = {
        0.0, -0.0, 1.5, -1.5, 0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MIN_VALUE
    };
    private static final String[] STRINGS = {"", "COMPLETED", "COMPLETE", "प्रवाह", "🌊", "a"};

    private static final int ROWS = 300;
    private static final int BATCH = 64;

    @AfterEach
    void uninstall() {
        GeneratedChains.install(null);
    }

    @Property(tries = 400)
    void aLanePipelineGivesTheSameAnswerGeneratedAsInterpreted(@ForAll("seeds") long seed) {
        Case c = randomCase(new Random(seed));

        Run interpreted = run(c, null);
        Run generated = run(c, new FilterProjectStageGenerator());

        Statistics.collect(
                generated.paths().stream().anyMatch(p -> p.startsWith("generated:")) ? "generated" : "refused");
        assertThat(interpreted.paths()).allMatch(p -> p.startsWith("interpreted:"));
        assertThat(generated.answer())
                .as("chain %s over %s; paths %s", c.chain().label(), c.schema(), generated.paths())
                .isEqualTo(interpreted.answer());
    }

    @Test
    void enoughOfTheChainsAreGenerated() {
        // A differential property over chains the generator refuses compares the interpreter with
        // itself. This counts, over the property's own case generator, how many chains it compiles.
        int generated = 0;
        int cases = 300;
        for (long seed = 0; seed < cases; seed++) {
            Case c = randomCase(new Random(seed));
            if (run(c, new FilterProjectStageGenerator()).paths().stream().anyMatch(p -> p.startsWith("generated:"))) {
                generated++;
            }
        }
        assertThat(generated)
                .as("%d of %d random chains were generated", generated, cases)
                .isGreaterThan(cases / 3);
    }

    @Test
    void theEquivalenceCatchesAGeneratedStageThatDisagrees() {
        // A differential that cannot fail certifies anything. A generator whose stage passes every
        // row must be seen to disagree on a chain whose filter rejects some.
        StreamSchema schema = StreamSchema.builder("s")
                .field("a", Types.int64())
                .field("b", Types.int64().withNullable(true))
                .build();
        PhysicalOperator chain =
                new FilterOperator(ScanOperator.of("s", schema), new Predicate.CompareLong(0, "a", Predicate.Op.GT, 7));
        Case c = new Case(schema, chain, rows(new Random(3), schema));

        FilterProjectStageGenerator honest = new FilterProjectStageGenerator();
        StageGenerator lying = root -> {
            var outcome = honest.generate(root);
            var stage = outcome.stage();
            return new StageGenerator.Outcome(
                    new com.ash.messaging.pravaha.runtime.exec.GeneratedRowStage() {
                        @Override
                        public boolean test(MemoryRegion region, int row) {
                            return true;
                        }

                        @Override
                        public boolean projects() {
                            return stage.projects();
                        }

                        @Override
                        public void project(MemoryRegion region, int row, MemoryRegion out, int outRow) {
                            stage.project(region, row, out, outRow);
                        }
                    },
                    outcome.reason());
        };

        assertThat(run(c, honest).answer()).isEqualTo(run(c, null).answer());
        assertThat(run(c, lying).answer()).isNotEqualTo(run(c, null).answer());
    }

    @Test
    void thePipelineSaysWhichPathEachChainIsOn() {
        StreamSchema schema = StreamSchema.builder("s")
                .field("a", Types.int64())
                .field("t", Types.string())
                .build();
        PhysicalOperator generable = new ProjectOperator(
                new FilterOperator(ScanOperator.of("s", schema), new Predicate.CompareLong(0, "a", Predicate.Op.GT, 7)),
                StreamSchema.builder("o").field("a", Types.int64()).build(),
                List.of(0));
        PhysicalOperator textOut = new ProjectOperator(
                new FilterOperator(ScanOperator.of("s", schema), new Predicate.CompareLong(0, "a", Predicate.Op.GT, 7)),
                StreamSchema.builder("o").field("t", Types.string()).build(),
                List.of(1));

        GeneratedChains.install(new FilterProjectStageGenerator());
        assertThat(compile(generable, true).executionPaths())
                .singleElement()
                .asString()
                .startsWith("generated: Project[a] <- Filter(a > 7) <- Scan(s)");
        // The projection of text is refused (PRV-3101) and the filter beneath it is generated alone.
        assertThat(compile(textOut, true).executionPaths()).hasSize(2).satisfies(paths -> {
            assertThat(paths.get(0)).startsWith("interpreted: Project[t]").contains("STRING");
            assertThat(paths.get(1)).startsWith("generated: Filter(a > 7) <- Scan(s)");
        });
        assertThat(compile(generable, false).executionPaths())
                .containsExactly("interpreted: this pipeline was not offered to the code generator");
        GeneratedChains.install(null);
        assertThat(compile(generable, true).executionPaths())
                .containsExactly("interpreted: no code generator is installed in this process");
    }

    @Test
    void aSumNettedOverABatchIsTheSameGeneratedAsInterpreted() {
        // TRANSOVF-1 under a generated filter and projection: the aggregate nets a batch in 128 bits
        // whichever path fed it, answers a total that passed 2^63 and came back, and refuses a net
        // total outside 64 bits with the same PRV-3025.
        StreamSchema schema = StreamSchema.builder("s")
                .field("amount", Types.int64())
                .field("keep", Types.bool())
                .build();
        PhysicalOperator chain = new AggregateOperator(
                new ProjectOperator(
                        new FilterOperator(ScanOperator.of("s", schema), new Predicate.CompareBoolean(1, "keep", true)),
                        StreamSchema.builder("p").field("amount", Types.int64()).build(),
                        List.of(0)),
                StreamSchema.builder("o").field("total", Types.int64()).build(),
                List.of(),
                List.of(new AggregateOperator.AggregateCall(AggregateOperator.AggregateCall.Kind.SUM, 0, "total")));
        long max = Long.MAX_VALUE;
        long[][] nets = {{max, 1, max, 1, -max, 1, -max, 1, 5, 1, max, 0}, {max, 1, 7, 0, 1, 1}};

        List<String> generatedAnswer = aggregateRun(chain, schema, nets[0], new FilterProjectStageGenerator());
        assertThat(generatedAnswer).isEqualTo(aggregateRun(chain, schema, nets[0], null));
        assertThat(generatedAnswer).singleElement().asString().endsWith("[l5]");

        List<String> refused = aggregateRun(chain, schema, nets[1], new FilterProjectStageGenerator());
        assertThat(refused).isEqualTo(aggregateRun(chain, schema, nets[1], null));
        assertThat(refused).singleElement().asString().contains("PRV-3025").contains("SUM(amount)");

        GeneratedChains.install(new FilterProjectStageGenerator());
        assertThat(compile(chain, true).executionPaths()).anyMatch(p -> p.startsWith("generated:"));
    }

    /** Runs {@code chain} over (amount, keep) pairs as one batch, then ends the input. */
    private static List<String> aggregateRun(
            PhysicalOperator chain, StreamSchema schema, long[] pairs, StageGenerator generator) {
        GeneratedChains.install(generator);
        List<String> answer = new ArrayList<>();
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        try (InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        chain, () -> new Recorder(chain.outputSchema(), answer), Map.of(), false, true);
                MemoryRegion region = MemoryAccess.best().allocate(1 << 16)) {
            try {
                for (int i = 0; i < pairs.length; i += 2) {
                    writer.begin(region, 0);
                    writer.setLong(0, pairs[i]).setBoolean(1, pairs[i + 1] == 1);
                    writer.weight(1).eventTimestampNanos(i).sequence(i).commit();
                    pipeline.accept("s", view.wrap(region, 0));
                }
                pipeline.endOfBatch();
                pipeline.finish();
            } catch (RuntimeException e) {
                answer.add("FAILED " + e.getClass().getName() + ": " + e.getMessage());
            }
        } finally {
            GeneratedChains.install(null);
        }
        return answer;
    }

    @Property(tries = 200)
    void aGeneratedRowIsTheRowTheWriterWrites(@ForAll("seeds") long seed) {
        // Byte for byte, header included: an operator downstream may hash or compare a row's bytes,
        // so agreeing on values is not enough. The writer's side is RowStages.projector's recipe --
        // a null sets the bit and writes nothing, anything else is written by its type.
        Random random = new Random(seed);
        StreamSchema schema = randomSchema(random);
        ProjectOperator projection = projection(random, ScanOperator.of("s", schema));
        StageGenerator.Outcome outcome = new FilterProjectStageGenerator().generate(projection);
        if (!outcome.generated()) {
            Statistics.collect("refused");
            return;
        }
        Statistics.collect("generated");
        RowLayout in = RowLayout.of(schema);
        RowLayout out = RowLayout.of(projection.outputSchema());
        BinaryRowView view = new BinaryRowView(in);
        BinaryRowWriter writer = new BinaryRowWriter(out);
        try (MemoryRegion region = MemoryAccess.best().allocate(1 << 16);
                MemoryRegion generated = MemoryAccess.best().allocate(1 << 12);
                MemoryRegion written = MemoryAccess.best().allocate(1 << 12)) {
            for (byte[] row : rows(random, schema)) {
                region.putBytes(0, row, 0, row.length);
                view.wrap(region, 0);
                // Stale bytes where the row will go, which the writer clears and so must the stage.
                generated.setMemory(0, out.fixedEnd(), (byte) 0x5A);
                written.setMemory(0, out.fixedEnd(), (byte) 0x5A);
                String generatedFailure = null;
                String writtenFailure = null;
                try {
                    outcome.stage().project(region, 0, generated, 0);
                } catch (IllegalArgumentException e) {
                    generatedFailure = e.getMessage();
                }
                try {
                    writer.begin(written, 0);
                    for (int i = 0; i < projection.sourceOrdinals().size(); i++) {
                        writeLikeCopyField(view, projection.sourceOrdinals().get(i), writer, i, out);
                    }
                    writer.weight(view.weight())
                            .eventTimestampNanos(view.eventTimestampNanos())
                            .sequence(view.sequence());
                    writer.commit();
                } catch (IllegalArgumentException e) {
                    writtenFailure = e.getMessage();
                    writer.abort();
                }
                assertThat(generatedFailure).isEqualTo(writtenFailure);
                if (writtenFailure == null) {
                    byte[] a = new byte[out.fixedEnd()];
                    byte[] b = new byte[out.fixedEnd()];
                    generated.getBytes(0, a, 0, a.length);
                    written.getBytes(0, b, 0, b.length);
                    assertThat(a)
                            .as("projection %s of %s", projection.sourceOrdinals(), schema)
                            .isEqualTo(b);
                }
            }
        }
    }

    private static void writeLikeCopyField(BinaryRowView from, int in, BinaryRowWriter to, int out, RowLayout layout) {
        if (from.isNull(in)) {
            to.setNull(out);
            return;
        }
        switch (layout.schema().field(out).type().typeName()) {
            case BOOLEAN -> to.setBoolean(out, from.getBoolean(in));
            case INT8 -> to.setByte(out, from.getByte(in));
            case INT16 -> to.setShort(out, from.getShort(in));
            case INT32, DATE -> to.setInt(out, from.getInt(in));
            case INT64, TIME, TIMESTAMP_LTZ -> to.setLong(out, from.getLong(in));
            case FLOAT32 -> to.setFloat(out, from.getFloat(in));
            case FLOAT64 -> to.setDouble(out, from.getDouble(in));
            default -> throw new AssertionError("the generator refuses this type, so it is never asked");
        }
    }

    // ------------------------------------------------------------------ harness

    record Case(StreamSchema schema, PhysicalOperator chain, List<byte[]> rows) {}

    record Run(List<String> answer, List<String> paths) {}

    private static InterpretedPipeline compile(PhysicalOperator chain, boolean generate) {
        return InterpretedPipeline.compile(
                chain, () -> new Recorder(chain.outputSchema(), new ArrayList<>()), Map.of(), false, generate);
    }

    private static Run run(Case c, StageGenerator generator) {
        GeneratedChains.install(generator);
        List<String> answer = new ArrayList<>();
        List<String> paths;
        RowOutput sink = () -> new Recorder(c.chain().outputSchema(), answer);
        try (InterpretedPipeline pipeline = InterpretedPipeline.compile(c.chain(), sink, Map.of(), false, true);
                MemoryRegion region = MemoryAccess.best().allocate(1 << 20)) {
            paths = pipeline.executionPaths();
            BinaryRowView view = new BinaryRowView(RowLayout.of(c.schema()));
            int cursor = 0;
            long[] offsets = new long[c.rows().size()];
            for (int i = 0; i < c.rows().size(); i++) {
                byte[] row = c.rows().get(i);
                region.putBytes(cursor, row, 0, row.length);
                offsets[i] = cursor;
                cursor += (row.length + 7) & ~7;
            }
            try {
                for (int i = 0; i < offsets.length; i++) {
                    pipeline.accept(c.schema().name(), view.wrap(region, (int) offsets[i]));
                    if (i % BATCH == BATCH - 1) {
                        pipeline.endOfBatch();
                        pipeline.resetArena();
                    }
                }
            } catch (RuntimeException e) {
                answer.add("FAILED " + e.getClass().getName() + ": " + e.getMessage());
            }
        } finally {
            GeneratedChains.install(null);
        }
        return new Run(answer, paths);
    }

    private static Case randomCase(Random random) {
        StreamSchema schema = randomSchema(random);
        return new Case(schema, randomChain(random, schema), rows(random, schema));
    }

    private static StreamSchema randomSchema(Random random) {
        StreamSchema.Builder builder = StreamSchema.builder("s");
        int fields = 2 + random.nextInt(8);
        for (int i = 0; i < fields; i++) {
            builder.field("c" + i, type(TYPES[random.nextInt(TYPES.length)], random.nextInt(3) == 0));
        }
        return builder.build();
    }

    private static PravahaType type(TypeName name, boolean nullable) {
        PravahaType type =
                switch (name) {
                    case INT8 -> Types.int8();
                    case INT16 -> Types.int16();
                    case INT32 -> Types.int32();
                    case INT64 -> Types.int64();
                    case DATE -> Types.date();
                    case TIMESTAMP_LTZ -> Types.timestamp();
                    case FLOAT32 -> Types.float32();
                    case FLOAT64 -> Types.float64();
                    case BOOLEAN -> Types.bool();
                    default -> Types.string();
                };
        return type.withNullable(nullable);
    }

    private static PhysicalOperator randomChain(Random random, StreamSchema schema) {
        ScanOperator scan = ScanOperator.of("s", schema);
        return switch (random.nextInt(6)) {
            case 0 -> new FilterOperator(scan, predicate(random, schema, 2));
            case 1 -> projection(random, new FilterOperator(scan, predicate(random, schema, 2)));
            case 2 -> projection(random, scan);
            case 3 ->
                projection(
                        random,
                        new FilterOperator(
                                new FilterOperator(scan, predicate(random, schema, 1)), predicate(random, schema, 1)));
            case 4 -> {
                // A filter over a projection: the generator refuses the shape, and the filter
                // reads the projection's ordinals, so it is built against the projected schema.
                ProjectOperator projected = projection(random, scan);
                yield new FilterOperator(projected, predicate(random, projected.outputSchema(), 1));
            }
            default ->
                new FilterOperator(
                        new FilterOperator(scan, predicate(random, schema, 1)), predicate(random, schema, 2));
        };
    }

    private static ProjectOperator projection(Random random, PhysicalOperator input) {
        StreamSchema in = input.outputSchema();
        int columns = 1 + random.nextInt(in.fieldCount() + 1);
        List<Integer> ordinals = new ArrayList<>();
        StreamSchema.Builder out = StreamSchema.builder("p");
        for (int i = 0; i < columns; i++) {
            int ordinal = random.nextInt(in.fieldCount());
            ordinals.add(ordinal);
            PravahaType type = in.field(ordinal).type();
            // Now and then a nullable column projected as NOT NULL, which both paths must refuse
            // the same way when a null arrives.
            if (type.nullable() && random.nextInt(10) == 0) {
                type = type.withNullable(false);
            }
            out.field("p" + i, type);
        }
        return new ProjectOperator(input, out.build(), ordinals);
    }

    private static Predicate predicate(Random random, StreamSchema schema, int depth) {
        int pick = random.nextInt(depth > 0 ? 10 : 7);
        if (pick >= 7) {
            List<Predicate> parts = new ArrayList<>();
            int n = 2 + random.nextInt(2);
            for (int i = 0; i < n; i++) {
                parts.add(predicate(random, schema, depth - 1));
            }
            return pick == 9 ? new Predicate.Or(parts) : new Predicate.And(parts);
        }
        int ordinal = random.nextInt(schema.fieldCount());
        String name = schema.field(ordinal).name();
        if (pick == 0) {
            Predicate isNull = new Predicate.IsNull(ordinal, name, random.nextBoolean());
            return random.nextBoolean() ? new Predicate.Not(isNull) : isNull;
        }
        Predicate.Op op = Predicate.Op.values()[random.nextInt(Predicate.Op.values().length)];
        // The predicate the SQL compiler builds for the column's type (PredicateCompiler), so the
        // narrow types arrive as CompareInt and FLOAT32 as CompareDouble, as they do in production.
        return switch (schema.field(ordinal).type().typeName()) {
            case INT8, INT16, INT32, DATE ->
                new Predicate.CompareInt(ordinal, name, op, INTS[random.nextInt(INTS.length)]);
            case INT64, TIMESTAMP_LTZ ->
                new Predicate.CompareLong(ordinal, name, op, LONGS[random.nextInt(LONGS.length)]);
            case FLOAT32, FLOAT64 ->
                new Predicate.CompareDouble(ordinal, name, op, DOUBLES[random.nextInt(DOUBLES.length)]);
            case BOOLEAN -> new Predicate.CompareBoolean(ordinal, name, random.nextBoolean());
            default ->
                new Predicate.CompareString(
                        ordinal,
                        name,
                        random.nextBoolean() ? Predicate.Op.EQ : Predicate.Op.NE,
                        STRINGS[random.nextInt(STRINGS.length)]);
        };
    }

    private static List<byte[]> rows(Random random, StreamSchema schema) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        List<byte[]> rows = new ArrayList<>(ROWS);
        try (MemoryRegion scratch = MemoryAccess.best().allocate(1 << 16)) {
            for (int i = 0; i < ROWS; i++) {
                writer.begin(scratch, 0);
                for (int f = 0; f < schema.fieldCount(); f++) {
                    if (schema.field(f).type().nullable() && random.nextInt(4) == 0) {
                        writer.setNull(f);
                        continue;
                    }
                    switch (schema.field(f).type().typeName()) {
                        case INT8 -> writer.setByte(f, (byte) INTS[random.nextInt(INTS.length)]);
                        case INT16 -> writer.setShort(f, (short) INTS[random.nextInt(INTS.length)]);
                        case INT32, DATE -> writer.setInt(f, INTS[random.nextInt(INTS.length)]);
                        case INT64, TIMESTAMP_LTZ -> writer.setLong(f, LONGS[random.nextInt(LONGS.length)]);
                        case FLOAT32 -> writer.setFloat(f, (float) DOUBLES[random.nextInt(DOUBLES.length)]);
                        case FLOAT64 -> writer.setDouble(f, DOUBLES[random.nextInt(DOUBLES.length)]);
                        case BOOLEAN -> writer.setBoolean(f, random.nextBoolean());
                        default -> writer.setString(f, STRINGS[random.nextInt(STRINGS.length)]);
                    }
                }
                writer.weight(random.nextBoolean() ? 1 : -1)
                        .eventTimestampNanos(1_000L * i)
                        .sequence(i)
                        .commit();
                byte[] bytes = new byte[writer.sizeSoFar()];
                scratch.getBytes(0, bytes, 0, bytes.length);
                rows.add(bytes);
            }
        }
        return rows;
    }

    /** Writes each committed row as text: every value with its type, doubles by their bits. */
    private static final class Recorder implements RowWriter {
        private final StreamSchema schema;
        private final List<String> into;
        private final String[] values;
        private String header = "";

        Recorder(StreamSchema schema, List<String> into) {
            this.schema = schema;
            this.into = into;
            this.values = new String[schema.fieldCount()];
        }

        @Override
        public StreamSchema schema() {
            return schema;
        }

        private RowWriter set(int ordinal, String value) {
            values[ordinal] = value;
            return this;
        }

        @Override
        public RowWriter setNull(int ordinal) {
            return set(ordinal, "null");
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            return set(ordinal, "z" + value);
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            return set(ordinal, "b" + value);
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            return set(ordinal, "s" + value);
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            return set(ordinal, "i" + value);
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            return set(ordinal, "l" + value);
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            return set(ordinal, "f" + Integer.toHexString(Float.floatToRawIntBits(value)));
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            return set(ordinal, "d" + Long.toHexString(Double.doubleToRawLongBits(value)));
        }

        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            return set(ordinal, "m" + high + "/" + low);
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            return set(ordinal, "y" + Arrays.toString(value));
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            return set(ordinal, "t" + value);
        }

        @Override
        public RowWriter weight(long weight) {
            header += "w" + weight;
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long nanos) {
            header += "e" + nanos;
            return this;
        }

        @Override
        public RowWriter sequence(long sequence) {
            header += "q" + sequence;
            return this;
        }

        @Override
        public int commit() {
            into.add(header + " " + Arrays.toString(values));
            Arrays.fill(values, null);
            header = "";
            return 0;
        }

        @Override
        public void abort() {
            Arrays.fill(values, null);
            header = "";
        }
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }
}
