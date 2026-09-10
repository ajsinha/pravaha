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

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What the padding is worth.
 *
 * <p>{@code FalseSharingAuditTest} fails if the padding fields are deleted; it cannot say whether
 * they are still doing anything on this JVM, because declared padding is not laid-out padding and
 * only the hardware can answer that. This does: two threads write two cursors, once on the same
 * cache line and once a line apart, and the ratio between the arms is the cost of the sharing.
 *
 * <p>Expect the padded arm to be several times faster on any real multicore machine. If the two
 * arms ever converge, either HotSpot has started laying the fields out differently or the padding
 * has stopped separating them -- both of which are worth knowing before they show up as a lane count
 * that stops scaling.
 *
 * <p>Two threads per group, one per cursor, which is the arrangement the rings actually have: a
 * producer advancing one cursor while a consumer advances the other.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
public class FalseSharingBenchmark {

    /** Two cursors on one cache line, which is what the padding exists to prevent. */
    @State(Scope.Group)
    public static class Shared {
        volatile long producer;
        volatile long consumer;
    }

    /** The same two cursors, a cache line apart. */
    @State(Scope.Group)
    public static class Padded {
        volatile long producer;

        @SuppressWarnings("unused")
        long p1, p2, p3, p4, p5, p6, p7;

        volatile long consumer;

        @SuppressWarnings("unused")
        long p8, p9, p10, p11, p12, p13, p14;
    }

    @Benchmark
    @Group("shared")
    @GroupThreads(1)
    public void sharedProducer(Shared state) {
        state.producer++;
    }

    @Benchmark
    @Group("shared")
    @GroupThreads(1)
    public long sharedConsumer(Shared state) {
        state.consumer++;
        return state.consumer;
    }

    @Benchmark
    @Group("padded")
    @GroupThreads(1)
    public void paddedProducer(Padded state) {
        state.producer++;
    }

    @Benchmark
    @Group("padded")
    @GroupThreads(1)
    public long paddedConsumer(Padded state) {
        state.consumer++;
        return state.consumer;
    }
}
