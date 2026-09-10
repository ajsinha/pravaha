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

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registering and dropping queries must not grow metaspace.
 *
 * <p>A Gate P2 criterion, and the failure it guards against is R12: every generated stage is a class,
 * classes live in metaspace, and metaspace is not swept by the ordinary collector. A node that
 * registers and drops queries all day -- which is what a node with a console and a thousand users
 * does -- dies of a leak nobody can attribute to anything, hours or days later, with a heap dump
 * that shows nothing wrong because the heap is fine.
 *
 * <p>What makes it not leak is one line in {@link StageCompiler}: a fresh {@code SimpleCompiler},
 * and therefore a fresh classloader, per stage. Sharing one would pin every generated class for the
 * life of the process. This test is what would notice if that line were ever changed for something
 * that looked like an optimisation.
 *
 * <p><strong>It asserts a bound, not equality.</strong> Metaspace does not shrink on demand, class
 * unloading happens when the collector decides, and a JIT compiling in the background allocates
 * there too. So the assertion is that growth across ten thousand cycles stays far below what ten
 * thousand retained classes would cost -- which is the difference between a leak and normal
 * variation, and is a claim that survives on a machine doing other things.
 */
@Timeout(600)
class MetaspaceLeakTest {

    private static final int CYCLES = 10_000;

    /**
     * The ceiling, calibrated by measuring both outcomes rather than by estimating one.
     *
     * <p>The first version of this test guessed 64 MB from "tens of kilobytes per class", and
     * seeding the leak -- retaining every compiled stage, which is what a registry without eviction
     * does -- <em>passed</em>. So the test named a failure it could not detect, which is worse than
     * not testing for it.
     *
     * <p>Measured on this JVM over ten thousand cycles: <strong>266 kB</strong> when stages are
     * dropped, <strong>29.5 MB</strong> when they are retained, or roughly 3 kB per class. 4 MB sits
     * an order of magnitude above the normal figure and nearly an order below the leak, which is
     * where a threshold belongs.
     */
    private static final long GROWTH_CEILING_BYTES = 4L * 1024 * 1024;

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .build();
    }

    private static PhysicalOperator plan() {
        return new ProjectOperator(ScanOperator.of("txn", schema()), schema(), List.of(0, 1));
    }

    private static Optional<MemoryPoolMXBean> metaspace() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getName().contains("Metaspace"))
                .findFirst();
    }

    @Test
    void tenThousandRegisterAndDropCyclesDoNotGrowMetaspace() {
        Optional<MemoryPoolMXBean> pool = metaspace();
        assertThat(pool)
                .as("this JVM exposes no metaspace pool, so the test would pass without measuring anything")
                .isPresent();

        StageCompiler compiler = new StageCompiler();
        FilterProjectGenerator generator = new FilterProjectGenerator();

        // Warm up before the baseline: the first few compilations load Janino itself, and counting
        // that as leakage would make the test fail for the wrong reason.
        for (int i = 0; i < 200; i++) {
            compileAndDrop(generator, compiler, i);
        }
        System.gc();
        long baseline = pool.get().getUsage().getUsed();

        for (int i = 0; i < CYCLES; i++) {
            compileAndDrop(generator, compiler, i);
        }
        System.gc();
        long after = pool.get().getUsage().getUsed();
        long growth = after - baseline;
        // Printed because it is the number the gate evidence pack quotes, and a number nobody can
        // reproduce is not evidence.
        System.out.println("metaspace growth over " + CYCLES + " register/drop cycles: " + growth + " bytes");

        assertThat(growth).as("""
                        metaspace grew by %d bytes across %d register/drop cycles.

                        Every generated stage is a class, and classes are only unloaded when their \
                        classloader is. If StageCompiler stopped creating one per stage, they would \
                        all be pinned for the life of the process -- a leak that shows nothing in a \
                        heap dump, because the heap is fine.""", growth, CYCLES).isLessThan(GROWTH_CEILING_BYTES);
    }

    /**
     * Compiles a stage and drops every reference to it.
     *
     * <p>Dropping the reference is the whole cycle: nothing else is needed for the classloader to
     * become unreachable, and nothing else must be needed, because that is exactly what a dropped
     * query does.
     */
    private static void compileAndDrop(FilterProjectGenerator generator, StageCompiler compiler, int index) {
        FilterProjectGenerator.Fused fused = generator.generate(plan(), "LeakStage" + index);
        GeneratedStage stage = compiler.compileFused("LeakStage" + index, fused.source());
        assertThat(stage.processor()).isNotNull();
    }
}
