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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Benchmarks are code too, and a benchmark that silently measures nothing is worse than none.
 *
 * <p>These run the benchmark bodies once, outside JMH, purely to check they are wired correctly --
 * the region is allocated, the stride stays in bounds, and the UTF-8 comparison actually matches.
 */
class MemoryAccessBenchmarkTest {

    @Test
    void benchmarkBodiesRunAndStayInBounds() {
        MemoryAccessBenchmark b = new MemoryAccessBenchmark();
        b.implementation = "bytebuffer";
        b.setUp();
        try {
            for (int i = 0; i < 10_000; i++) {
                b.putLong();
                b.getLong();
            }
            assertThat(b.equalsUtf8Literal())
                    .as("the literal comparison must actually match, or the benchmark measures a miss")
                    .isTrue();
        } finally {
            b.tearDown();
        }
    }

    @Test
    void rejectsAnUnknownImplementationRatherThanSilentlyMeasuringTheDefault() {
        MemoryAccessBenchmark b = new MemoryAccessBenchmark();
        b.implementation = "nonsense";
        org.assertj.core.api.Assertions.assertThatThrownBy(b::setUp).isInstanceOf(IllegalArgumentException.class);
    }
}
