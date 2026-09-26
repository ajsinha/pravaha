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

import java.util.LinkedHashMap;
import java.util.Map;

import com.ash.messaging.pravaha.runtime.exec.GeneratedChains;
import com.ash.messaging.pravaha.runtime.exec.StageGenerator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;

/**
 * The generator a node installs so that registered queries run generated filters and projections
 * (finding C-7).
 *
 * <p>Each lane of a query compiles its own pipeline from the same plan, so the same chain arrives
 * once per lane. A generated stage holds no state -- its literals are static constants and it writes
 * only where it is told -- so one compiled instance serves every lane, and a chain is compiled once
 * rather than once per lane. The cache is keyed by the chain itself, which is a tree of records:
 * two registrations of the same query share their stage, and two different queries cannot.
 *
 * <p>Bounded, because every compiled stage is a class and a class loader: an unbounded cache over a
 * workload that registers and drops queries would grow metaspace without limit. An evicted stage is
 * still referenced by the pipelines running it and is collected when they close.
 */
public final class FilterProjectStageGenerator implements StageGenerator {

    /** Chains kept compiled. A node running more distinct chains than this recompiles on eviction. */
    static final int MAX_CACHED = 512;

    private final StageCompiler compiler;
    private final Map<PhysicalOperator, Outcome> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PhysicalOperator, Outcome> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private long compiled;

    public FilterProjectStageGenerator() {
        this(new StageCompiler());
    }

    FilterProjectStageGenerator(StageCompiler compiler) {
        this.compiler = compiler;
    }

    /** Installs a new generator process-wide; every lane pipeline compiled afterwards is offered to it. */
    public static FilterProjectStageGenerator install() {
        FilterProjectStageGenerator generator = new FilterProjectStageGenerator();
        GeneratedChains.install(generator);
        return generator;
    }

    @Override
    public synchronized Outcome generate(PhysicalOperator root) {
        Outcome cached = cache.get(root);
        if (cached != null) {
            return cached;
        }
        StageCompilation compilation = StageCompilation.attempt(root, "GeneratedStage", compiler);
        Outcome outcome = compilation
                .stage()
                .map(stage -> new Outcome((FusedStage) stage.processor(), compilation.reason()))
                .orElseGet(() -> Outcome.refused(compilation.reason()));
        if (outcome.generated()) {
            compiled++;
        }
        cache.put(root, outcome);
        return outcome;
    }

    /** Chains compiled since this generator was made; a chain served from the cache is not counted. */
    public synchronized long compiledCount() {
        return compiled;
    }
}
