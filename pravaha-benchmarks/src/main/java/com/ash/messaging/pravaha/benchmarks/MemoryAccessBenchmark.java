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
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import com.ash.messaging.pravaha.common.memory.AgronaMemoryAccess;
import com.ash.messaging.pravaha.common.memory.ByteBufferMemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.observe.CoverageAgent;

/**
 * Decides {@code MemoryAccess} selection with numbers instead of argument (implementation plan
 * spike S2).
 *
 * <p>The question this answers is narrow and consequential: the default has to be
 * {@code bytebuffer} because it is the only flag-free implementation, so what matters is how much
 * throughput that costs. If the answer is "nothing measurable", the Agrona path can be dropped
 * entirely and the seam gets simpler.
 *
 * <p>Run the Agrona arm with:
 * {@code --add-exports java.base/jdk.internal.misc=ALL-UNNAMED}
 *
 * <p>Acceptance for P0-05: single accessor at or under 2 ns, zero bytes allocated per operation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(
        value = 2,
        jvmArgsAppend = {"-XX:+UseZGC", "-XX:+ZGenerational"})
@Measurement(iterations = 5, time = 2)
@State(Scope.Thread)
public class MemoryAccessBenchmark {

    private static final int CAPACITY = 1 << 20;
    private static final byte[] LITERAL = "COMPLETED".getBytes(StandardCharsets.UTF_8);

    @Param({"bytebuffer", "agrona"})
    public String implementation;

    private MemoryRegion region;
    private int index;

    /** PERF-1: a benchmark run under a coverage agent measures the agent; refused, by name. */
    @Setup(Level.Trial)
    public void declineUnderACoverageAgent() {
        CoverageAgent.refuseToMeasure("MemoryAccessBenchmark");
    }

    @Setup
    public void setUp() {
        MemoryAccess access =
                switch (implementation) {
                    case "agrona" -> AgronaMemoryAccess.INSTANCE;
                    case "bytebuffer" -> ByteBufferMemoryAccess.INSTANCE;
                    default -> throw new IllegalArgumentException(implementation);
                };
        region = access.allocate(CAPACITY);
        region.putBytes(4096, LITERAL, 0, LITERAL.length);
    }

    @TearDown
    public void tearDown() {
        region.close();
    }

    /** Sequential stride keeps the access pattern realistic rather than a single hot cache line. */
    private int nextIndex() {
        index = (index + 64) & (CAPACITY - 1024);
        return index;
    }

    @Benchmark
    public long getLong() {
        return region.getLong(nextIndex());
    }

    @Benchmark
    public void putLong() {
        region.putLong(nextIndex(), 0x0123456789ABCDEFL);
    }

    @Benchmark
    public void readRowOfTwelveFields(Blackhole bh) {
        // Approximates the design's reference payload: twelve fields, mixed widths.
        int base = nextIndex();
        bh.consume(region.getLong(base));
        bh.consume(region.getLong(base + 8));
        bh.consume(region.getInt(base + 16));
        bh.consume(region.getInt(base + 20));
        bh.consume(region.getDouble(base + 24));
        bh.consume(region.getLong(base + 32));
        bh.consume(region.getShort(base + 40));
        bh.consume(region.getByte(base + 42));
        bh.consume(region.getBoolean(base + 43));
        bh.consume(region.getInt(base + 44));
        bh.consume(region.getLong(base + 48));
        bh.consume(region.getFloat(base + 56));
    }

    /** The comparison generated code actually emits for {@code WHERE status = 'COMPLETED'}. */
    @Benchmark
    public boolean equalsUtf8Literal() {
        return region.equalsBytes(4096, LITERAL);
    }
}
