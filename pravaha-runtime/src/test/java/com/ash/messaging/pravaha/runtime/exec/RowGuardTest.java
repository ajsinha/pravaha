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

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.plan.ComputeOperator;
import com.ash.messaging.pravaha.runtime.plan.Expression;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DLQPROJ-1: a row whose evaluation fails before it reaches state goes to the attached sink and the
 * pipeline carries on; with no sink, or once the row has reached state, the failure propagates and
 * stops the query as before.
 */
class RowGuardTest {

    private static final StreamSchema IN = StreamSchema.builder("z")
            .field("id", Types.int64())
            .field("a", Types.int64())
            .field("b", Types.int64())
            .build();

    private static final StreamSchema OUT = StreamSchema.builder("q")
            .field("id", Types.int64())
            .field("r", Types.int64())
            .build();

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);

    @AfterEach
    void close() {
        arena.close();
    }

    private RowView row(long id, long a, long b) {
        RowLayout layout = RowLayout.of(IN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id).setLong(1, a).setLong(2, b);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }

    private static ComputeOperator divide() {
        return new ComputeOperator(
                ScanOperator.of("z", IN),
                OUT,
                List.of(
                        new Expression.Column(0, "id", TypeName.INT64),
                        new Expression.Arithmetic(
                                new Expression.Column(1, "a", TypeName.INT64),
                                Expression.Operator.DIVIDE,
                                new Expression.Column(2, "b", TypeName.INT64),
                                TypeName.INT64)));
    }

    private record Rejected(
            String stream, String row, @Nullable String reason) {}

    @Test
    void aRowThatDividesByZeroIsDeadLetteredAndTheOthersAreAnswered() {
        List<Object[]> out = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        try (InterpretedPipeline pipeline =
                InterpretedPipeline.compile(divide(), () -> new ValueRowWriter(OUT, out::add))) {
            pipeline.deadLetterRowFailures(
                    (stream, schema, row, failure) -> rejected.add(new Rejected(stream, row, failure.getMessage())));
            pipeline.accept("z", row(1, 10, 2));
            pipeline.accept("z", row(2, 10, 0));
            pipeline.accept("z", row(3, 10, 5));
            pipeline.accept("z", row(4, Long.MIN_VALUE, -1));
        }
        assertThat(out).extracting(r -> r[0] + "|" + r[1]).containsExactly("1|5", "3|2");
        assertThat(rejected).hasSize(2);
        assertThat(rejected.get(0).stream()).isEqualTo("z");
        assertThat(rejected.get(0).row()).isEqualTo("{\"id\":\"2\",\"a\":\"10\",\"b\":\"0\"}");
        assertThat(rejected.get(0).reason()).contains("division by zero");
        assertThat(rejected.get(1).reason()).contains("BIGINT overflow");
    }

    @Test
    @SuppressWarnings("NullAway") // nulls on purpose: what a caller outside NullAway may pass
    void withNoSinkTheFailureStopsThePipelineAsBefore() {
        List<Object[]> out = new ArrayList<>();
        try (InterpretedPipeline pipeline =
                InterpretedPipeline.compile(divide(), () -> new ValueRowWriter(OUT, out::add))) {
            pipeline.accept("z", row(1, 10, 2));
            assertThatThrownBy(() -> pipeline.accept("z", row(2, 10, 0)))
                    .isInstanceOf(ArithmeticException.class)
                    .hasMessageContaining("division by zero");
            // Detached again after being attached: the same.
            pipeline.deadLetterRowFailures((stream, schema, row, failure) -> {});
            pipeline.deadLetterRowFailures(null);
            assertThatThrownBy(() -> pipeline.accept("z", row(2, 10, 0))).isInstanceOf(ArithmeticException.class);
        }
    }

    @Test
    void aFailureAfterTheRowReachedStateIsNeverDeadLettered() {
        RowGuard guard = new RowGuard();
        List<String> rejected = new ArrayList<>();
        guard.sendTo((stream, schema, row, failure) -> rejected.add(row));
        List<String> held = new ArrayList<>();
        // A stateful operator that takes the row and then has an overflow above it -- a HAVING or a
        // projection of an aggregate. Dropping the row cannot undo what the state took.
        RowProcessor stateful = guard.boundary(row -> {
            held.add("held");
            throw new ArithmeticException("overflow above the state");
        });
        RowProcessor head = guard.guard("z", IN, row -> stateful.process(row));
        assertThatThrownBy(() -> head.process(row(1, 1, 1))).hasMessageContaining("overflow above the state");
        assertThat(held).hasSize(1);
        assertThat(rejected).isEmpty();
        // The next row starts clean: a failure before the state is dead-lettered again.
        RowProcessor before = guard.guard("z", IN, row -> {
            throw new ArithmeticException("before the state");
        });
        before.process(row(2, 1, 1));
        assertThat(rejected).hasSize(1);
    }

    @Test
    void anythingButAnArithmeticFailureStopsThePipeline() {
        RowGuard guard = new RowGuard();
        guard.sendTo((stream, schema, row, failure) -> {
            throw new AssertionError("not a row failure");
        });
        RowProcessor head = guard.guard("z", IN, row -> {
            throw new IllegalStateException("a bug, not the row");
        });
        assertThatThrownBy(() -> head.process(row(1, 1, 1))).isInstanceOf(IllegalStateException.class);
    }
}
