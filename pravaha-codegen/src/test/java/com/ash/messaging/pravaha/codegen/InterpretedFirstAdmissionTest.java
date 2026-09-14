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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.lane.AdaptiveStage;
import com.ash.messaging.pravaha.runtime.lane.LaneProcessor;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Queries start interpreted and become generated behind the scenes.
 *
 * <p>The point is a node restart. Ten thousand registrations at ten to thirty milliseconds of
 * compilation each is minutes of a node being up and answering nothing -- at exactly the moment when
 * that is most expensive. Starting on the interpreted path makes the compile time invisible, and it
 * makes the interpreted path load-bearing for a second, independent reason beyond correctness.
 *
 * <p>{@link #theSwapLosesNoRows} is the one that matters. A swap that dropped or doubled a row under
 * load would be a wrong answer produced by an optimisation, which is the worst category of bug this
 * system can have.
 */
@Timeout(120)
class InterpretedFirstAdmissionTest {

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .build();
    }

    private static PhysicalOperator plan() {
        return new ProjectOperator(ScanOperator.of("txn", schema()), schema(), List.of(0, 1));
    }

    private static LaneProcessor countingInterpreted(AtomicLong rows) {
        return (region, offsets, count) -> {
            rows.addAndGet(count);
            return count;
        };
    }

    @Test
    void aQueryProducesOutputBeforeItsStageIsCompiled() throws Exception {
        AtomicLong interpretedRows = new AtomicLong();
        AdaptiveStage stage = AdaptiveStage.stateless("q-1", countingInterpreted(interpretedRows));

        MemoryAccess access = MemoryAccess.best();
        try (MemoryRegion region = access.allocate(4096);
                StageUpgradeService service = StageUpgradeService.defaults(new GeneratedSourceRegistry(true, 100))) {
            long[] offsets = {0L};
            region.setMemory(0, 64, (byte) 0);

            // Before any compilation has been asked for, the query already works.
            assertThat(stage.onBatch(region, offsets, 1)).isEqualTo(1);
            assertThat(stage.isGenerated()).isFalse();
            assertThat(interpretedRows.get()).isEqualTo(1);

            List<String> outcomes = new CopyOnWriteArrayList<>();
            assertThat(service.submit(stage, plan(), outcomes::add)).isTrue();
            assertThat(service.awaitQuiet(30, TimeUnit.SECONDS)).isTrue();

            assertThat(stage.isGenerated()).as("%s", outcomes).isTrue();
            assertThat(outcomes).anyMatch(outcome -> outcome.startsWith("upgraded:"));
        }
    }

    @Test
    void theSwapLosesNoRows() throws Exception {
        // The property that makes this safe: the lane thread reads the active processor once per
        // batch, so a batch runs entirely on one side of the swap or entirely on the other. Feeding
        // continuously while the upgrade lands is the only way to test it.
        AtomicLong interpretedRows = new AtomicLong();
        AdaptiveStage stage = AdaptiveStage.stateless("q-swap", countingInterpreted(interpretedRows));
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong fed = new AtomicLong();

        MemoryAccess access = MemoryAccess.best();
        try (MemoryRegion region = access.allocate(8192);
                StageUpgradeService service = StageUpgradeService.defaults(new GeneratedSourceRegistry(true, 100))) {
            RowLayout layout = RowLayout.of(schema());
            long[] offsets = new long[4];
            for (int i = 0; i < 4; i++) {
                offsets[i] = (long) i * 128;
                region.setMemory((int) offsets[i], 128, (byte) 0);
                region.putInt((int) offsets[i] + RowLayout.OFFSET_TOTAL_LENGTH, layout.fixedEnd());
            }

            Thread feeder = new Thread(
                    () -> {
                        while (running.get()) {
                            stage.onBatch(region, offsets, 4);
                            fed.addAndGet(4);
                        }
                    },
                    "swap-feeder");
            feeder.start();

            service.submit(stage, plan(), outcome -> {});
            assertThat(service.awaitQuiet(30, TimeUnit.SECONDS)).isTrue();
            // Keep feeding after the swap so both sides have real traffic.
            Thread.sleep(50);
            running.set(false);
            feeder.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(stage.isGenerated()).isTrue();
            assertThat(stage.rowsInterpreted() + stage.rowsGenerated())
                    .as("every row fed was counted exactly once, on one side of the swap or the other")
                    .isEqualTo(fed.get());
            assertThat(stage.rowsInterpreted())
                    .as("some rows ran before the swap")
                    .isPositive();
            assertThat(stage.rowsGenerated()).as("and some after it").isPositive();
        }
    }

    @Test
    void everyBatchIsAttributedToTheProcessorThatRanIt() throws Exception {
        // Attribution is the evidence that an upgrade happened, so it must hold under a concurrent
        // feed rather than only when quiet -- this catches a systematically wrong attribution, such
        // as counting every batch as generated.
        //
        // What it deliberately does not claim to catch: reading the active reference twice per batch.
        // That mis-attributes at most one batch, once, in a window a few nanoseconds wide, and
        // seeding it passes both this test and the row-total one. The single read in AdaptiveStage
        // is discipline, not something asserted here, and saying so beats implying a guard that does
        // not exist.
        AtomicLong interpretedActual = new AtomicLong();
        AtomicLong generatedActual = new AtomicLong();
        AdaptiveStage stage = AdaptiveStage.stateless("q-attrib", (region, offsets, count) -> {
            interpretedActual.addAndGet(count);
            return count;
        });
        LaneProcessor pretendGenerated = (region, offsets, count) -> {
            generatedActual.addAndGet(count);
            return count;
        };

        AtomicBoolean running = new AtomicBoolean(true);
        MemoryAccess access = MemoryAccess.best();
        try (MemoryRegion region = access.allocate(4096)) {
            region.setMemory(0, 128, (byte) 0);
            long[] offsets = {0L};

            Thread feeder = new Thread(
                    () -> {
                        while (running.get()) {
                            stage.onBatch(region, offsets, 1);
                        }
                    },
                    "attribution-feeder");
            feeder.start();
            Thread.sleep(20);
            stage.upgradeTo(pretendGenerated);
            Thread.sleep(20);
            running.set(false);
            feeder.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(stage.rowsInterpreted())
                    .as("the stage's own count of interpreted rows must match what the interpreted "
                            + "processor actually saw")
                    .isEqualTo(interpretedActual.get());
            assertThat(stage.rowsGenerated()).isEqualTo(generatedActual.get());
            assertThat(generatedActual.get()).isPositive();
        }
    }

    @Test
    void aStatefulStageIsNeverSwapped() {
        // An aggregate carries its accumulators. A generated replacement starts empty, which would
        // silently reset the query's answer to whatever arrived after the swap -- a wrong answer
        // produced by an optimisation. Refused, with the reason, rather than attempted carefully.
        AtomicLong rows = new AtomicLong();
        AdaptiveStage stage = AdaptiveStage.stateful("q-agg", countingInterpreted(rows));
        List<String> outcomes = new ArrayList<>();

        try (StageUpgradeService service = StageUpgradeService.defaults(GeneratedSourceRegistry.disabled())) {
            assertThat(service.submit(stage, plan(), outcomes::add)).isFalse();
            assertThat(stage.isGenerated()).isFalse();
            assertThat(outcomes).singleElement().asString().contains("carries state between rows");
        }
    }

    @Test
    void aPlanWithNoGeneratedFormKeepsRunningInterpreted() throws Exception {
        // Not a failure. The query is correct and slower, which is the design's guarantee, and the
        // caller is told which of the reasons applies.
        StreamSchema stringy = StreamSchema.builder("s")
                .field("id", Types.int64())
                .field("name", Types.string())
                .build();
        PhysicalOperator plan = new ProjectOperator(ScanOperator.of("s", stringy), stringy, List.of(0, 1));

        AdaptiveStage stage = AdaptiveStage.stateless("q-str", (region, offsets, count) -> count);
        List<String> outcomes = new CopyOnWriteArrayList<>();

        try (StageUpgradeService service = StageUpgradeService.defaults(GeneratedSourceRegistry.disabled())) {
            service.submit(stage, plan, outcomes::add);
            assertThat(service.awaitQuiet(30, TimeUnit.SECONDS)).isTrue();

            assertThat(stage.isGenerated()).isFalse();
            assertThat(service.notGeneratedCount()).isEqualTo(1);
            assertThat(outcomes).singleElement().asString().contains("not generated");
        }
    }

    @Test
    void aFullCompileQueueDelaysAnUpgradeRatherThanFailingAQuery() {
        // An upgrade is an optimisation. Failing to apply one must never slow down or break the
        // thing it was meant to speed up, so a full queue is a rejection the query survives.
        try (StageUpgradeService service =
                new StageUpgradeService(1, 1, new StageCompiler(), GeneratedSourceRegistry.disabled())) {
            List<String> outcomes = new CopyOnWriteArrayList<>();
            int accepted = 0;
            for (int i = 0; i < 200; i++) {
                AdaptiveStage stage = AdaptiveStage.stateless("q-" + i, (region, offsets, count) -> count);
                if (service.submit(stage, plan(), outcomes::add)) {
                    accepted++;
                }
            }

            assertThat(accepted)
                    .as("a one-deep queue cannot have taken all two hundred")
                    .isLessThan(200);
            assertThat(service.rejectedCount()).isPositive();
            assertThat(outcomes).anyMatch(outcome -> outcome.contains("a delay, not a failure"));
        }
    }

    @Test
    void aThousandQueriesAreAdmittedImmediatelyAndUpgradedBehindThem() throws Exception {
        // The acceptance criterion. What matters is not how fast the compilations finish but that
        // admission does not wait for them: every query answers from the moment it is registered.
        MemoryAccess access = MemoryAccess.best();
        List<AdaptiveStage> stages = new ArrayList<>();
        AtomicLong interpretedRows = new AtomicLong();

        try (MemoryRegion region = access.allocate(4096);
                StageUpgradeService service =
                        new StageUpgradeService(2, 8192, new StageCompiler(), new GeneratedSourceRegistry(true, 64))) {
            region.setMemory(0, 128, (byte) 0);
            long[] offsets = {0L};

            long start = System.nanoTime();
            long emitted = 0;
            for (int i = 0; i < 1_000; i++) {
                AdaptiveStage stage = AdaptiveStage.stateless("q-" + i, countingInterpreted(interpretedRows));
                stages.add(stage);
                service.submit(stage, plan(), outcome -> {});
                emitted += stage.onBatch(region, offsets, 1); // it answers immediately
            }
            long admissionMillis = (System.nanoTime() - start) / 1_000_000L;

            // Counted as rows out, not as interpreted invocations. A compiler thread can land an
            // upgrade in the microseconds between submit and onBatch, and that query answered --
            // it answered from generated code. Scoring it as a miss tests the swap, not admission.
            assertThat(emitted)
                    .as("all thousand produced output during registration, without waiting for a compiler")
                    .isEqualTo(1_000L);
            assertThat(interpretedRows.get())
                    .as(
                            "and the interpreter served them: only %d of 1000 ran interpreted, which is"
                                    + " what gating admission on compilation would look like",
                            interpretedRows.get())
                    .isGreaterThan(500L);
            assertThat(admissionMillis)
                    .as("admission is not gated on compilation: it took %d ms", admissionMillis)
                    .isLessThan(5_000L);

            // The upgrades then land behind them. Not asserted to completion within a deadline --
            // that would be a timing test on a shared machine -- but they must be progressing.
            service.awaitQuiet(60, TimeUnit.SECONDS);
            assertThat(service.upgradedCount())
                    .as("upgrades are happening behind the queries")
                    .isPositive();
        }
    }
}
