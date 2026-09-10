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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.memory.MemoryRegion;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wide stages, and what happens when they get too wide.
 *
 * <p>The JVM refuses to JIT a method over 8 kB of bytecode. A projection emits a few instructions
 * per column, so a wide enough one pushes {@code process} past that limit -- and the failure is
 * perverse: the generated stage runs <em>interpreted by the JVM</em>, which is slower than the
 * interpreter it was generated to replace, with nothing at all to indicate why. Splitting the
 * projection into bounded methods is what stops that, and this is where it is checked.
 */
class StageSplittingTest {

    private static StreamSchema wideSchema(int columns) {
        StreamSchema.Builder builder = StreamSchema.builder("wide");
        for (int i = 0; i < columns; i++) {
            builder.field("c" + i, Types.int64());
        }
        return builder.build();
    }

    private static PhysicalOperator projectAll(int columns) {
        StreamSchema schema = wideSchema(columns);
        List<Integer> ordinals = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            ordinals.add(i);
        }
        return new ProjectOperator(ScanOperator.of("wide", schema), schema, ordinals);
    }

    @Test
    void aWideProjectionIsSplitIntoBoundedMethods() {
        // Hard-coded expectations, deliberately. The first version of this test computed how many
        // methods to expect from COLUMNS_PER_METHOD -- so raising that constant raised the
        // expectation with it, and seeding "stop splitting" by setting it to a million passed. A
        // test that derives its expectation from the thing it is testing asserts nothing at all.
        assertThat(FilterProjectGenerator.COLUMNS_PER_METHOD)
                .as("the split has to stay well inside the JVM's 8 kB bytecode limit for a method")
                .isBetween(8, 128);

        FilterProjectGenerator.Fused fused = new FilterProjectGenerator().generate(projectAll(300), "WideStage");

        assertThat(fused.source())
                .as("300 columns cannot fit in one method whatever the chunk size is set to")
                .contains("private void project0(")
                .contains("private void project1(")
                .contains("private void project2(");
    }

    @Test
    void aNarrowProjectionIsSplitTheSameWay() {
        // Always split, never split-when-large. A conditional split means the wide case takes a path
        // the narrow case never exercises, so its first run is on somebody's thousand-column table.
        // The JIT inlines a small private method called once per row without being asked, so the
        // narrow case pays nothing for the uniformity.
        FilterProjectGenerator.Fused fused = new FilterProjectGenerator().generate(projectAll(3), "NarrowStage");

        assertThat(fused.source()).contains("private void project0(");
        assertThat(fused.source()).doesNotContain("private void project1(");
    }

    @Test
    void aSplitStageCompilesAndProducesTheSameRowsAsBefore() {
        int columns = 200;
        StageCompilation compilation = StageCompilation.attempt(projectAll(columns), "SplitStage", new StageCompiler());

        assertThat(compilation.isGenerated())
                .as("a 200-column projection must still generate: %s", compilation.reason())
                .isTrue();
        assertThat(compilation.reason()).contains("generated:");

        // And it actually runs: split methods that compile but copy the wrong columns would pass
        // every structural assertion above.
        //
        // The input row is built by hand rather than with BinaryRowWriter, and that is a finding
        // rather than a convenience. The writer tracks written fields in a long bitmask, so it
        // refuses schemas wider than 64 columns -- while the generated stage, which addresses fields
        // by constant offset, has no such limit. Today that means a 200-column projection can be
        // generated but not written by the engine's own writer, so the ceiling on schema width is
        // the writer's, not the generator's. Worth knowing before somebody meets it from the other
        // direction.
        FusedStage stage = (FusedStage) compilation.stage().orElseThrow().processor();
        RowLayout layout = RowLayout.of(wideSchema(columns));
        MemoryAccess access = MemoryAccess.best();
        try (MemoryRegion input = access.allocate(64 * 1024);
                MemoryRegion output = access.allocate(64 * 1024)) {
            input.setMemory(0, layout.fixedEnd(), (byte) 0);
            for (int i = 0; i < columns; i++) {
                input.putLong(layout.offsetOf(i), 1000L + i);
            }
            input.putLong(RowLayout.OFFSET_WEIGHT, 1L);
            input.putLong(RowLayout.OFFSET_EVENT_TIME, 7L);
            input.putLong(RowLayout.OFFSET_SEQUENCE, 3L);
            input.putInt(RowLayout.OFFSET_TOTAL_LENGTH, layout.fixedEnd());

            long[] offsets = {0L};
            long[] outOffsets = {0L};
            assertThat(stage.process(input, offsets, 1, output, outOffsets, 0)).isEqualTo(1);

            for (int i = 0; i < columns; i++) {
                assertThat(output.getLong(layout.offsetOf(i)))
                        .as("column %d survived the split", i)
                        .isEqualTo(1000L + i);
            }
            assertThat(output.getLong(RowLayout.OFFSET_WEIGHT)).isEqualTo(1L);
        }
    }

    @Test
    void aPlanTheGeneratorCannotCoverFallsBackWithAReason() {
        // Correctness never depends on generation succeeding (design 12.4), so this is an answer
        // rather than an exception -- and the reason is written for whoever is asking why their
        // query is slow, not for a log parser.
        StreamSchema schema = StreamSchema.builder("s")
                .field("id", Types.int64())
                .field("name", Types.string())
                .build();
        PhysicalOperator plan = new ProjectOperator(ScanOperator.of("s", schema), schema, List.of(0, 1));

        StageCompilation compilation = StageCompilation.attempt(plan, "StringStage", new StageCompiler());

        assertThat(compilation.isGenerated()).isFalse();
        assertThat(compilation.stage()).isEmpty();
        assertThat(compilation.reason())
                .contains("not generated")
                .contains("interpreted path runs this correctly and more slowly");
    }

    @Test
    void aStageTooLargeEvenAfterSplittingSaysSo() {
        // The source-length guard still applies to the whole class. Splitting bounds each method;
        // it does not bound the total, and a query wide enough to exceed it should be told that
        // rather than left to discover a stage the JIT will refuse.
        StageCompilation compilation =
                StageCompilation.attempt(projectAll(20_000), "EnormousStage", new StageCompiler());

        assertThat(compilation.isGenerated()).isFalse();
        assertThat(compilation.reason())
                .contains("too large even after splitting")
                .contains("interpreted path runs it correctly");
    }
}
