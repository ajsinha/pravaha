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
package com.ash.messaging.pravaha.runtime.exec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator.AggregateCall.Kind;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.runtime.window.GroupDoubles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NANGROUP-1: a GROUP BY or a COUNT(DISTINCT) over a DOUBLE treats -0.0 and 0.0 as one value, as SQL
 * equality does, and every NaN as one value whatever its bits -- and publishes the canonical one.
 */
class DoubleGroupingTest {

    private static final double OTHER_NAN = Double.longBitsToDouble(0x7ff8000000000001L);

    private static final StreamSchema IN =
            StreamSchema.builder("w").field("d", Types.float64()).build();

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);

    private void feed(InterpretedPipeline pipeline, double d) {
        RowLayout layout = RowLayout.of(IN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(16));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setDouble(0, d);
        writer.weight(1).eventTimestampNanos(0).sequence(0).commit();
        pipeline.accept("w", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    @Test
    void eitherZeroIsOneGroupAndEveryNanIsOne() {
        StreamSchema out = StreamSchema.builder("g")
                .field("d", Types.float64())
                .field("c", Types.int64())
                .build();
        AggregateOperator grouped = new AggregateOperator(
                ScanOperator.of("w", IN),
                out,
                List.of(0),
                List.of(new AggregateOperator.AggregateCall(Kind.COUNT, -1, "c")));
        List<Object[]> rows = new ArrayList<>();
        try (InterpretedPipeline pipeline =
                InterpretedPipeline.compile(grouped, () -> new ValueRowWriter(out, rows::add))) {
            for (double d : new double[] {-0.0, 0.0, Double.NaN, OTHER_NAN, 1.0}) {
                feed(pipeline, d);
            }
            pipeline.finish();
        }
        List<String> groups = rows.stream().map(r -> r[0] + "|" + r[1]).sorted().toList();
        assertThat(groups).containsExactly("0.0|2", "1.0|1", "NaN|2");
        assertThat(rows)
                .as("the canonical zero is published, never -0.0")
                .noneMatch(r -> Double.doubleToRawLongBits((Double) r[0]) == Double.doubleToRawLongBits(-0.0));
    }

    @Test
    void countDistinctCountsEitherZeroOnceAndEveryNanOnce() {
        StreamSchema out = StreamSchema.builder("g").field("c", Types.int64()).build();
        AggregateOperator distinct = new AggregateOperator(
                ScanOperator.of("w", IN),
                out,
                List.of(),
                List.of(new AggregateOperator.AggregateCall(Kind.COUNT_DISTINCT, 0, "c")));
        List<Object[]> rows = new ArrayList<>();
        try (InterpretedPipeline pipeline =
                InterpretedPipeline.compile(distinct, () -> new ValueRowWriter(out, rows::add))) {
            for (double d : new double[] {-0.0, 0.0, Double.NaN, OTHER_NAN, 1.0}) {
                feed(pipeline, d);
            }
            pipeline.finish();
        }
        assertThat(rows).extracting(r -> r[0]).containsExactly(3L);
    }

    @Test
    void aCheckpointedValueFromBeforeIsRefusedSoTheQueryRebuilds() throws Exception {
        // A 2.0.0 checkpoint can hold -0.0 or a NaN payload as a key or distinct value. Restored, it
        // would stay a group apart from the canonical one; refused, the restore falls back to a rebuild.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(2);
            out.writeByte(6);
            out.writeDouble(-0.0);
            out.writeByte(6);
            out.writeDouble(1.0);
        }
        assertThatThrownBy(() -> GlobalAggregate.readDistinct(
                        new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("NANGROUP-1");
        assertThat(GroupDoubles.isCanonical(0.0)).isTrue();
        assertThat(GroupDoubles.isCanonical(Double.NaN)).isTrue();
        assertThat(GroupDoubles.isCanonical(OTHER_NAN)).isFalse();
        assertThat(GroupDoubles.isCanonical(-0.0)).isFalse();
        assertThat(GroupDoubles.canonical(-0.0f)).isEqualTo(0.0f);
    }
}
