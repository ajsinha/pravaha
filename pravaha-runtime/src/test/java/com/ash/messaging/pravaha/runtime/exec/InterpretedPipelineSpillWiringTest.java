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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.JoinOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other half of {@code SymmetricHashJoinSpillTest}: not whether the overflow tier works (it
 * does, proven there against {@code SymmetricHashJoin} directly), but whether {@link
 * InterpretedPipeline} -- the thing every real query actually goes through -- reads {@link
 * SpillSettings} and passes it to the joins it builds.
 *
 * <p>{@link InterpretedPipeline#configureSpill} sets process-wide, static state, which every test
 * here resets afterward: a test elsewhere in this module asserting {@code PRV-4001} must not start
 * failing because this class ran first and left spilling turned on.
 */
class InterpretedPipelineSpillWiringTest {

    @AfterEach
    void resetSpillConfiguration() {
        InterpretedPipeline.configureSpill(SpillSettings.DISABLED);
    }

    @Test
    void unconfiguredMeansDisabled() {
        assertThat(InterpretedPipeline.spillSettings()).isEqualTo(SpillSettings.DISABLED);
    }

    @Test
    void configureSpillIsReflectedBySpillSettings(@TempDir Path dir) {
        SpillSettings settings = new SpillSettings(true, dir.toString(), 16);
        InterpretedPipeline.configureSpill(settings);
        assertThat(InterpretedPipeline.spillSettings()).isEqualTo(settings);
    }

    private static StreamSchema left() {
        return StreamSchema.builder("left")
                .field("id", Types.int64())
                .field("key", Types.string())
                .build();
    }

    private static StreamSchema right() {
        return StreamSchema.builder("right")
                .field("key", Types.string())
                .field("value", Types.int64())
                .build();
    }

    private static StreamSchema merged() {
        return StreamSchema.builder("joined")
                .field("l_id", Types.int64())
                .field("l_key", Types.string())
                .field("r_key", Types.string().withNullable(true))
                .field("r_value", Types.int64().withNullable(true))
                .build();
    }

    private static JoinOperator plan() {
        return new JoinOperator(
                ScanOperator.of("left", left()),
                ScanOperator.of("right", right()),
                List.of(1),
                List.of(0),
                merged(),
                10_000_000);
    }

    /**
     * With spilling enabled but nowhere near its ceiling, a join must answer exactly as it always
     * has -- the overflow tier is supposed to be invisible until it is needed, and this is the test
     * that would notice if enabling it broke the ordinary case instead.
     */
    @Test
    void aJoinCompiledWithSpillingEnabledStillAnswersOrdinaryQueriesCorrectly(@TempDir Path dir) {
        InterpretedPipeline.configureSpill(new SpillSettings(true, dir.toString(), 16));

        List<Long> matchedIds = new ArrayList<>();
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);
                InterpretedPipeline pipeline =
                        InterpretedPipeline.compile(plan(), () -> new CapturingWriter(merged(), matchedIds::add))) {
            feed(arena, pipeline, left(), List.of(1L, "alpha"));
            feed(arena, pipeline, left(), List.of(2L, "beta"));
            feed(arena, pipeline, right(), List.of("alpha", 100L));
            feed(arena, pipeline, right(), List.of("gamma", 200L));

            assertThat(matchedIds).as("only the alpha key matches").containsExactly(1L);
            assertThat(pipeline.joinStateBytes()).as("both sides hold rows").isGreaterThan(0);
        }
    }

    private static void feed(RowArena arena, InterpretedPipeline pipeline, StreamSchema schema, List<Object> values) {
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            if (v instanceof String s) {
                writer.setString(i, s);
            } else {
                writer.setLong(i, (Long) v);
            }
        }
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        pipeline.accept(schema.name(), new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
    }

    /** A minimal {@link RowWriter} that reports the joined row's first column (the left id) and
     * discards the rest -- enough to say which pairs matched without a full decode. */
    private static final class CapturingWriter implements RowWriter {
        private final StreamSchema schema;
        private final Consumer<Long> onLeftId;
        private long leftId;

        CapturingWriter(StreamSchema schema, Consumer<Long> onLeftId) {
            this.schema = schema;
            this.onLeftId = onLeftId;
        }

        @Override
        public StreamSchema schema() {
            return schema;
        }

        @Override
        public RowWriter setNull(int ordinal) {
            return this;
        }

        @Override
        public RowWriter setBoolean(int ordinal, boolean value) {
            return this;
        }

        @Override
        public RowWriter setByte(int ordinal, byte value) {
            return this;
        }

        @Override
        public RowWriter setShort(int ordinal, short value) {
            return this;
        }

        @Override
        public RowWriter setInt(int ordinal, int value) {
            return this;
        }

        @Override
        public RowWriter setLong(int ordinal, long value) {
            if (ordinal == 0) {
                leftId = value;
            }
            return this;
        }

        @Override
        public RowWriter setFloat(int ordinal, float value) {
            return this;
        }

        @Override
        public RowWriter setDouble(int ordinal, double value) {
            return this;
        }

        @Override
        public RowWriter setDecimal(int ordinal, long high, long low) {
            return this;
        }

        @Override
        public RowWriter setBytes(int ordinal, byte[] value) {
            return this;
        }

        @Override
        public RowWriter setString(int ordinal, String value) {
            return this;
        }

        @Override
        public RowWriter weight(long weight) {
            return this;
        }

        @Override
        public RowWriter eventTimestampNanos(long nanos) {
            return this;
        }

        @Override
        public RowWriter sequence(long sequence) {
            return this;
        }

        @Override
        public int commit() {
            onLeftId.accept(leftId);
            return 0;
        }

        @Override
        public void abort() {}
    }
}
