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
package com.ash.messaging.pravaha.it;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.ComputeOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Computed columns.
 *
 * <p>{@code SELECT amount * 2 FROM txn} was refused until now, which was the right call while there
 * was no way to evaluate it -- a projection that silently produced the wrong column would be far
 * worse -- and a large hole in a SQL surface. "Compute it in the source query" is not an answer when
 * the source is a Kafka topic.
 *
 * <p>Two behaviours are worth more than the arithmetic. Null propagates the way SQL says rather than
 * the way Java does, because writing a zero where a null belongs makes a downstream {@code SUM}
 * produce a number that looks entirely reasonable. And DECIMAL is refused rather than approximated
 * in {@code double}, which would pass every test anybody writes and be wrong in a ledger.
 */
class ComputedProjectionTest {

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .field("rate", Types.float64())
                .field("bonus", Types.int64().withNullable(true))
                .build();
    }

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(sql));
    }

    /** One input row: id, amount, rate, and a bonus that may be null. */
    private record Row(long id, long amount, double rate, Long bonus) {}

    @Test
    void arithmeticOnAColumnIsComputedPerRow() {
        List<CapturingRowWriter.Captured> out = run(
                "SELECT id, amount * 2 + 1 FROM txn", List.of(new Row(1, 50, 1.0, null), new Row(2, 10, 1.0, null)));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).asLong(1)).isEqualTo(101);
        assertThat(out.get(1).asLong(1)).isEqualTo(21);
    }

    @Test
    void floatingPointArithmeticStaysFloatingPoint() {
        List<CapturingRowWriter.Captured> out = run("SELECT rate * 2.5 FROM txn", List.of(new Row(1, 0, 4.0, null)));

        assertThat((Double) out.get(0).values()[0]).isEqualTo(10.0);
    }

    @Test
    void nullPropagatesTheWaySqlSaysRatherThanTheWayJavaDoes() {
        // Java would make this zero. SQL makes it null, and a downstream SUM over zeroes that should
        // have been nulls produces a number nobody questions.
        List<CapturingRowWriter.Captured> out =
                run("SELECT bonus + 10 FROM txn", List.of(new Row(1, 0, 0, null), new Row(2, 0, 0, 5L)));

        assertThat(out.get(0).isNull(0)).as("null + 10 is null, not 10").isTrue();
        assertThat(out.get(1).asLong(0)).isEqualTo(15);
    }

    @Test
    void aPlainColumnProjectionStaysAProjection() {
        // The generated form of a column copy is a load and a store at constant offsets. Routing it
        // through an expression tree would cost a branch per column per row to answer a question the
        // plan already knew the answer to.
        assertThat(plan("SELECT id, amount FROM txn")).isInstanceOf(ProjectOperator.class);
        assertThat(plan("SELECT id, amount * 2 FROM txn")).isInstanceOf(ComputeOperator.class);
    }

    @Test
    void theExpressionIsReadableInThePlan() {
        // Sealed and inspectable, like Predicate and for the same reason: a lambda can be evaluated
        // but not read, and the code generator has to read it. That was learned once already.
        ComputeOperator compute = (ComputeOperator) plan("SELECT amount * 2 + 1 FROM txn");

        assertThat(compute.expressions()).hasSize(1);
        assertThat(compute.expressions().get(0).describe()).isEqualTo("((amount * 2) + 1)");
        assertThat(compute.label()).contains("Compute");
    }

    @Test
    void decimalArithmeticIsRefusedRatherThanApproximated() {
        StreamSchema money = StreamSchema.builder("ledger")
                .field("id", Types.int64())
                .field("amount", Types.decimal(18, 4))
                .build();

        assertThatThrownBy(() -> new PhysicalPlanBuilder()
                        .build(SqlPlanner.withStreams(money).plan("SELECT amount * 2 FROM ledger")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("rounding error in a ledger")
                .hasMessageContaining("Cast to DOUBLE explicitly");
    }

    @Test
    void anUnsupportedFunctionSaysWhatIsSupported() {
        assertThatThrownBy(() -> plan("SELECT ABS(amount) FROM txn"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("not supported in a projection yet");
    }

    /** Plans, runs, and copies the output rows out. */
    private static List<CapturingRowWriter.Captured> run(String sql, List<Row> rows) {
        PhysicalOperator plan = plan(sql);
        List<CapturingRowWriter.Captured> results = new ArrayList<>();
        RowLayout layout = RowLayout.of(schema());

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 4);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (Row row : rows) {
                long handle = feed.allocate(layout.rowSize(128));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                writer.setLong(0, row.id()).setLong(1, row.amount()).setDouble(2, row.rate());
                if (row.bonus() == null) {
                    writer.setNull(3);
                } else {
                    writer.setLong(3, row.bonus());
                }
                writer.weight(1L)
                        .eventTimestampNanos(row.id())
                        .sequence(row.id())
                        .commit();
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return results;
    }
}
