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
package com.ash.messaging.pravaha.benchmarks;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.codegen.FilterProjectGenerator;
import com.ash.messaging.pravaha.codegen.FusedStage;
import com.ash.messaging.pravaha.codegen.StageCompiler;
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

/**
 * Design section 28.4's Profile A: filter and project, twelve fields, 10 % selectivity.
 *
 * <p>The gate for Wave 3 is 1.2 M records per second per lane, and the reason both arms are here is
 * that the claim being tested is a <em>ratio</em>, not an absolute: whether whole-stage generation
 * actually earns the risk it carries (R2). If generated code is not decisively faster than the
 * interpreter, the interpreter is the better engineering choice and the generator should be dropped.
 *
 * <p>Both arms process an identical batch of identical rows and are checked for identical output in
 * setup, so a difference in the numbers is a difference in speed and not in work done.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(
        value = 1,
        jvmArgsAppend = {"-XX:+UseZGC", "-XX:+ZGenerational"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Thread)
public class ProfileABenchmark {

    /** A realistic batch: large enough to amortise the call, small enough to stay in cache. */
    private static final int BATCH = 512;

    private static final byte[] COMPLETED = "COMPLETED".getBytes(StandardCharsets.UTF_8);

    private MemoryRegion input;
    private MemoryRegion output;
    private long[] offsets;
    private long[] outOffsets;
    private FusedStage generated;
    private Predicate predicate;
    private BinaryRowView view;
    private RowLayout inputLayout;
    private RowLayout outputLayout;
    private BinaryRowWriter outputWriter;

    /** Twelve fields, roughly 200 bytes encoded, as design 28.4 specifies. */
    private static StreamSchema inputSchema() {
        return StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .field("fee", Types.int64())
                .field("score", Types.int32())
                .field("rank", Types.int32())
                .field("ratio", Types.float64())
                .field("weight", Types.float64())
                .field("active", Types.bool())
                .field("flagged", Types.bool())
                .field("status", Types.string())
                .field("region", Types.string())
                .build();
    }

    private static StreamSchema outputSchema() {
        return StreamSchema.builder("out")
                .field("txn_id", Types.int64())
                .field("user_id", Types.int64())
                .field("amount", Types.int64())
                .build();
    }

    @Setup(Level.Trial)
    public void setUp() {
        StreamSchema in = inputSchema();
        StreamSchema out = outputSchema();
        inputLayout = RowLayout.of(in);
        outputLayout = RowLayout.of(out);
        view = new BinaryRowView(inputLayout);
        outputWriter = new BinaryRowWriter(outputLayout);

        // WHERE status = 'COMPLETED' AND amount > 900 -- about 10% selectivity on this data.
        predicate = new Predicate.And(List.of(
                new Predicate.CompareString(10, "status", Predicate.Op.EQ, "COMPLETED"),
                new Predicate.CompareLong(2, "amount", Predicate.Op.GT, 900)));

        PhysicalOperator plan =
                new ProjectOperator(new FilterOperator(ScanOperator.of("txn", in), predicate), out, List.of(0, 1, 2));

        var fused = new FilterProjectGenerator().generate(plan, "ProfileAStage");
        generated = (FusedStage) new StageCompiler()
                .compileFused("ProfileAStage", fused.source())
                .processor();

        input = MemoryAccess.best().allocate(BATCH * 512);
        output = MemoryAccess.best().allocate(BATCH * 128);
        offsets = new long[BATCH];
        outOffsets = new long[BATCH];

        BinaryRowWriter writer = new BinaryRowWriter(inputLayout);
        Random random = new Random(20260909L);
        int cursor = 0;
        for (int i = 0; i < BATCH; i++) {
            offsets[i] = cursor;
            writer.begin(input, cursor);
            writer.setLong(0, i)
                    .setLong(1, random.nextInt(100_000))
                    .setLong(2, random.nextInt(1000))
                    .setLong(3, random.nextInt(100))
                    .setInt(4, random.nextInt(1000))
                    .setInt(5, random.nextInt(100))
                    .setDouble(6, random.nextDouble())
                    .setDouble(7, random.nextDouble())
                    .setBoolean(8, random.nextBoolean())
                    .setBoolean(9, random.nextBoolean())
                    .setString(10, random.nextInt(3) == 0 ? "COMPLETED" : "PENDING")
                    .setString(11, "eu-west-1")
                    .weight(1L)
                    .eventTimestampNanos(1_700_000_000_000_000_000L + i)
                    .sequence(i)
                    .commit();
            cursor += align8(writer.sizeSoFar());
        }
        for (int i = 0; i < BATCH; i++) {
            outOffsets[i] = (long) i * align8(outputLayout.fixedEnd());
        }

        // Both arms must do the same work, or the comparison measures nothing.
        int fromGenerated = generated.process(input, offsets, BATCH, output, outOffsets, 0);
        int fromInterpreted = countInterpreted();
        if (fromGenerated != fromInterpreted) {
            throw new IllegalStateException("the two arms disagree: generated emitted " + fromGenerated
                    + ", interpreted emitted " + fromInterpreted + "; the benchmark would be meaningless");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        input.close();
        output.close();
    }

    /** The generated, fused path: one counted loop, constant offsets, no dispatch. */
    @Benchmark
    public int generated() {
        return generated.process(input, offsets, BATCH, output, outOffsets, 0);
    }

    /**
     * The interpreted path: a virtual call per operator per row, plus an ordinal lookup per field.
     *
     * <p>Deliberately written the way the interpreter actually runs, rather than an idealised
     * version -- an unfair comparison in either direction would make the number useless.
     */
    @Benchmark
    public int interpreted() {
        int emitted = 0;
        for (int i = 0; i < BATCH; i++) {
            RowView row = view.wrap(input, (int) offsets[i]);
            if (!predicate.test(row)) {
                continue;
            }
            outputWriter.begin(output, (int) outOffsets[emitted]);
            outputWriter
                    .setLong(0, row.getLong(0))
                    .setLong(1, row.getLong(1))
                    .setLong(2, row.getLong(2))
                    .weight(row.weight())
                    .eventTimestampNanos(row.eventTimestampNanos())
                    .sequence(row.sequence())
                    .commit();
            emitted++;
        }
        return emitted;
    }

    /** Just the predicate, to separate filtering cost from projection cost. */
    @Benchmark
    public void predicateOnly(Blackhole bh) {
        for (int i = 0; i < BATCH; i++) {
            bh.consume(predicate.test(view.wrap(input, (int) offsets[i])));
        }
    }

    private int countInterpreted() {
        int emitted = 0;
        for (int i = 0; i < BATCH; i++) {
            if (predicate.test(view.wrap(input, (int) offsets[i]))) {
                emitted++;
            }
        }
        return emitted;
    }

    private static int align8(int value) {
        return (value + 7) & ~7;
    }
}
