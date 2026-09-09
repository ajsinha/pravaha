/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ash.messaging.pravaha.benchmarks;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
