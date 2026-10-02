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
package com.ash.messaging.pravaha.sql.plan;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NANNOT-1: the negation of a floating-point comparison is its IEEE complement.
 *
 * <p>Under IEEE 754, which TY-3 chose, {@code NaN > 5} is FALSE, so {@code NOT (NaN > 5)} is TRUE.
 * The negation used to be compiled as the opposite operator, {@code d <= 5}, which is also FALSE
 * for NaN -- so a NaN row was in neither a predicate nor its negation. A NULL row stays out of
 * both, because there the comparison is UNKNOWN and so is its NOT.
 */
class NegatedFloatingComparisonTest {

    private static final StreamSchema S = StreamSchema.builder("s")
            .field("id", Types.int64())
            .field("d", Types.float64().withNullable(true))
            .build();

    /** id 1 NaN, 2 NULL, 3 6.0, 4 4.0, 5 5.0. */
    private static final Double[] D = {Double.NaN, null, 6.0, 4.0, 5.0};

    @Test
    void aNanRowIsInExactlyOneOfAComparisonAndItsNegation() {
        assertThat(ids("SELECT id FROM s WHERE d > 5")).containsExactly(3L);
        assertThat(ids("SELECT id FROM s WHERE NOT (d > 5)")).containsExactly(1L, 4L, 5L);
        assertThat(ids("SELECT id FROM s WHERE NOT (d < 5)")).containsExactly(1L, 3L, 5L);
        assertThat(ids("SELECT id FROM s WHERE NOT (d >= 5)")).containsExactly(1L, 4L);
        assertThat(ids("SELECT id FROM s WHERE NOT (d = 5)")).containsExactly(1L, 3L, 4L);
    }

    @Test
    void theTruthTestsOverAFloatingComparisonFollowTheSameComplement() {
        // NaN > 5 is FALSE: IS FALSE keeps it, IS NOT FALSE drops it. NULL > 5 is UNKNOWN: IS FALSE
        // drops it, IS NOT FALSE keeps it.
        assertThat(ids("SELECT id FROM s WHERE (d > 5) IS FALSE")).containsExactly(1L, 4L, 5L);
        assertThat(ids("SELECT id FROM s WHERE (d > 5) IS NOT FALSE")).containsExactly(2L, 3L);
    }

    @Test
    void anExpressionOperandAndANullOperandAreNegatedThreeValued() {
        assertThat(ids("SELECT id FROM s WHERE NOT (d + 1 > 6)")).containsExactly(1L, 4L, 5L);
        assertThat(ids("SELECT id FROM s WHERE NOT (d > CAST(NULL AS DOUBLE))")).isEmpty();
    }

    private static List<Long> ids(String sql) {
        PhysicalOperator plan =
                new PhysicalPlanBuilder().build(SqlPlanner.withStreams(S).plan(sql));
        List<CapturingRowWriter.Captured> captured = new ArrayList<>();
        RowLayout layout = RowLayout.of(S);
        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 16, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), captured::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (int i = 0; i < D.length; i++) {
                long handle = feed.allocate(layout.rowSize(64));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                writer.setLong(0, i + 1L);
                if (D[i] == null) {
                    writer.setNull(1);
                } else {
                    writer.setDouble(1, D[i]);
                }
                writer.weight(1L).sequence(i + 1L).commit();
                feed.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return captured.stream().map(row -> (Long) row.values()[0]).toList();
    }
}
