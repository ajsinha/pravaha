/*
 * Copyright the Pravaha authors.
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
