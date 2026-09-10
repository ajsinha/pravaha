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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.runtime.lane.AdaptiveStage;
import com.ash.messaging.pravaha.runtime.lane.LaneProcessor;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;

/**
 * Compiles generated stages in the background, so registration does not wait for a compiler.
 *
 * <p>Ten thousand registrations at ten to thirty milliseconds each is minutes of compilation. Doing
 * it before any query produces a row makes a node restart into an outage of that length, so queries
 * start interpreted and this works through the backlog behind them (design section 13.7).
 *
 * <p><strong>Bounded on purpose, in three ways.</strong> A small pool, because compilation is
 * CPU-bound and stealing cores from the lanes to make them faster later is a poor trade at exactly
 * the moment a node is catching up. A bounded queue, because an unbounded one is the thing every
 * other bound in this system exists to avoid. And <strong>never on a lane thread</strong>: a lane
 * that stops to compile is a lane not draining its inbox, which is backpressure caused by an
 * optimisation.
 *
 * <p>When the queue is full the submission is rejected rather than blocked, and the query simply
 * keeps running interpreted. That is the correct outcome and worth stating plainly: an upgrade is an
 * optimisation, and failing to apply one is never a reason to slow down or fail the thing it was
 * meant to speed up.
 */
public final class StageUpgradeService implements AutoCloseable {

    private final ThreadPoolExecutor executor;
    private final StageCompiler compiler;
    private final GeneratedSourceRegistry sources;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong upgraded = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong notGenerated = new AtomicLong();

    public StageUpgradeService(int threads, int queueDepth, StageCompiler compiler, GeneratedSourceRegistry sources) {
        if (threads < 1) {
            throw new IllegalArgumentException("the compile pool needs at least one thread, got " + threads);
        }
        this.compiler = compiler;
        this.sources = sources;
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueDepth),
                runnable -> {
                    Thread thread = new Thread(runnable, "pravaha-compile");
                    thread.setDaemon(true);
                    // Below normal: a compile thread must never take a core from a lane. The whole
                    // point of compiling in the background is that the foreground keeps working.
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** A pool sized for a node whose cores belong to the lanes. */
    public static StageUpgradeService defaults(GeneratedSourceRegistry sources) {
        return new StageUpgradeService(2, 4096, new StageCompiler(), sources);
    }

    /**
     * Queues a stage for compilation and upgrades it when ready.
     *
     * @param onOutcome told what happened, whether or not it was an upgrade; a caller that only
     *     hears about successes cannot report why a query is still interpreted
     * @return {@code false} if the queue was full or the stage cannot be upgraded, in which case the
     *     query keeps running interpreted and nothing else is affected
     */
    public boolean submit(AdaptiveStage stage, PhysicalOperator plan, Consumer<String> onOutcome) {
        if (!stage.isUpgradable()) {
            onOutcome.accept("not upgraded: " + stage.queryId()
                    + " carries state between rows, and a generated replacement would start empty");
            return false;
        }
        submitted.incrementAndGet();
        try {
            executor.execute(() -> compileAndSwap(stage, plan, onOutcome));
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            rejected.incrementAndGet();
            onOutcome.accept("not upgraded: the compile queue is full, so " + stage.queryId()
                    + " keeps running interpreted. This is a delay, not a failure.");
            return false;
        }
    }

    private void compileAndSwap(AdaptiveStage stage, PhysicalOperator plan, Consumer<String> onOutcome) {
        String className = "Stage_" + stage.queryId().replaceAll("[^A-Za-z0-9_]", "_");
        StageCompilation compilation = StageCompilation.attempt(plan, className, compiler);
        if (compilation.stage().isEmpty()) {
            notGenerated.incrementAndGet();
            onOutcome.accept(compilation.reason());
            return;
        }

        GeneratedStage generated = compilation.stage().get();
        sources.retain(stage.queryId(), generated);
        FusedStage fused = (FusedStage) generated.processor();

        // The generated stage writes into an output region the caller owns; wrapping it as a
        // LaneProcessor is what lets the swap be a single reference assignment rather than a
        // rebuild of the pipeline.
        LaneProcessor asProcessor = new FusedLaneProcessor(fused);
        if (stage.upgradeTo(asProcessor)) {
            upgraded.incrementAndGet();
            onOutcome.accept("upgraded: " + stage.queryId() + " is now generated (" + compilation.reason() + ")");
        }
    }

    public long submittedCount() {
        return submitted.get();
    }

    public long upgradedCount() {
        return upgraded.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    /** Stages that compiled to nothing: an unsupported plan, or a generator bug. */
    public long notGeneratedCount() {
        return notGenerated.get();
    }

    /** Waits for the backlog to clear. For tests and for a node that wants to report readiness. */
    public boolean awaitQuiet(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (executor.getQueue().isEmpty() && executor.getActiveCount() == 0) {
                return true;
            }
            Thread.sleep(1);
        }
        return false;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * Adapts a generated stage to the lane's processor interface.
     *
     * <p>The generated stage needs somewhere to write. This is where the lane's arena would be
     * supplied when the two are wired together; until then it is the seam that makes the swap one
     * assignment, and the output region is the caller's business.
     */
    private record FusedLaneProcessor(FusedStage fused) implements LaneProcessor {
        @Override
        public int onBatch(com.ash.messaging.pravaha.common.memory.MemoryRegion region, long[] offsets, int count) {
            return fused.process(region, offsets, count, region, offsets, 0);
        }
    }
}
