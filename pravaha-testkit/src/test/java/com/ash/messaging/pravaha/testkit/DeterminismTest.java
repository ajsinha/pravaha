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
package com.ash.messaging.pravaha.testkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate P0's demonstration: a two-stage pipeline produces byte-identical output across a thousand
 * runs with a thousand different interleavings.
 *
 * <p>This is the property every later correctness claim rests on. Replay safety, the incremental
 * oracle, differential testing of generated code against interpreted operators -- none of them mean
 * anything if the same input can produce different output depending on how threads happened to be
 * scheduled. Establishing it first, on the real row, arena and ring machinery, is why the testkit is
 * a Wave 1 deliverable rather than something added when it is needed.
 */
class DeterminismTest {

    private static final byte[] COMPLETED = "COMPLETED".getBytes(StandardCharsets.UTF_8);

    private static StreamSchema inputSchema() {
        return StreamSchema.builder("txn")
                .field("txn_id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .field("status", Types.string())
                .build();
    }

    private static StreamSchema projectedSchema() {
        return StreamSchema.builder("txn_projected")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
    }

    /** WHERE status = 'COMPLETED' AND amount > 100 -- a UTF-8 compare, no String materialised. */
    private static RowOperator filter() {
        return (in, out) -> {
            if (!in.utf8Equals(3, COMPLETED)) {
                return;
            }
            if (in.getLong(2) <= 100) {
                return;
            }
            var w = out.begin();
            w.setString(0, in.getString(1))
                    .setLong(1, in.getLong(2))
                    .weight(in.weight())
                    .eventTimestampNanos(in.eventTimestampNanos())
                    .sequence(in.sequence())
                    .commit();
        };
    }

    /** A second stage that doubles the amount, so the two stages are distinguishable in output. */
    private static RowOperator doubleAmount() {
        return (in, out) -> {
            var w = out.begin();
            w.setString(0, in.getString(0))
                    .setLong(1, in.getLong(1) * 2)
                    .weight(in.weight())
                    .eventTimestampNanos(in.eventTimestampNanos())
                    .sequence(in.sequence())
                    .commit();
        };
    }

    private static List<byte[]> runOnce(long seed, int records) {
        try (Pipeline pipeline = Pipeline.builder(inputSchema())
                .stage("filter", projectedSchema(), filter())
                .stage("double", projectedSchema(), doubleAmount())
                .ringCapacity(4096)
                .arena(1 << 20, 32)
                .build()) {

            for (int i = 0; i < records; i++) {
                final int n = i;
                boolean accepted = pipeline.feed(w -> w.setLong(0, n)
                        .setString(1, "user-" + (n % 7))
                        .setLong(2, n * 13L % 500)
                        .setString(3, n % 3 == 0 ? "COMPLETED" : "PENDING")
                        .weight(1L)
                        .eventTimestampNanos(1_700_000_000_000_000_000L + n)
                        .sequence(n));
                assertThat(accepted).as("record %d should be accepted", n).isTrue();
            }

            DeterministicScheduler scheduler = new DeterministicScheduler(seed);
            pipeline.registerWith(scheduler);
            scheduler.runToCompletion();
            return pipeline.output();
        }
    }

    @Test
    void aThousandInterleavingsProduceByteIdenticalOutput() {
        List<byte[]> reference = runOnce(0L, 200);
        assertThat(reference).as("the pipeline must actually emit something").isNotEmpty();

        for (long seed = 1; seed < 1_000; seed++) {
            List<byte[]> actual = runOnce(seed, 200);
            assertThat(actual)
                    .as("seed %d produced different output; the pipeline is not deterministic", seed)
                    .hasSameSizeAs(reference);
            for (int i = 0; i < reference.size(); i++) {
                assertThat(actual.get(i))
                        .as("seed %d, row %d differs from the reference run", seed, i)
                        .isEqualTo(reference.get(i));
            }
        }
    }

    @Test
    void differentSeedsGenuinelyProduceDifferentInterleavings() {
        // Without this the test above could pass vacuously: if every seed produced the same
        // schedule, "identical output across a thousand interleavings" would be one interleaving
        // run a thousand times.
        Set<String> distinctTraces = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            try (Pipeline pipeline = Pipeline.builder(inputSchema())
                    .stage("filter", projectedSchema(), filter())
                    .stage("double", projectedSchema(), doubleAmount())
                    .build()) {
                for (int i = 0; i < 30; i++) {
                    final int n = i;
                    pipeline.feed(w -> w.setLong(0, n)
                            .setString(1, "u")
                            .setLong(2, 500)
                            .setString(3, "COMPLETED")
                            .weight(1L)
                            .sequence(n));
                }
                DeterministicScheduler scheduler = new DeterministicScheduler(seed).recordTrace();
                pipeline.registerWith(scheduler);
                scheduler.runToCompletion();
                distinctTraces.add(String.join(",", scheduler.trace()));
            }
        }
        assertThat(distinctTraces)
                .as("seeds must produce varied schedules, or the determinism test proves nothing")
                .hasSizeGreaterThan(20);
    }

    @Test
    void theSameSeedAlwaysReproducesTheSameSchedule() {
        // The other half of the contract: a failure found at seed N must recur at seed N.
        List<String> first = traceFor(12345L);
        List<String> second = traceFor(12345L);
        assertThat(second).isEqualTo(first).isNotEmpty();
    }

    private static List<String> traceFor(long seed) {
        try (Pipeline pipeline = Pipeline.builder(inputSchema())
                .stage("filter", projectedSchema(), filter())
                .stage("double", projectedSchema(), doubleAmount())
                .build()) {
            for (int i = 0; i < 50; i++) {
                final int n = i;
                pipeline.feed(w -> w.setLong(0, n)
                        .setString(1, "u")
                        .setLong(2, 500)
                        .setString(3, "COMPLETED")
                        .weight(1L)
                        .sequence(n));
            }
            DeterministicScheduler scheduler = new DeterministicScheduler(seed).recordTrace();
            pipeline.registerWith(scheduler);
            scheduler.runToCompletion();
            return new ArrayList<>(scheduler.trace());
        }
    }

    @Test
    void thePipelineFiltersAndTransformsAsWritten() {
        // Determinism is worthless if the output is deterministically wrong.
        List<byte[]> out = runOnce(0L, 30);
        int expected = 0;
        for (int n = 0; n < 30; n++) {
            if (n % 3 == 0 && (n * 13L % 500) > 100) {
                expected++;
            }
        }
        assertThat(out).hasSize(expected);
    }
}
