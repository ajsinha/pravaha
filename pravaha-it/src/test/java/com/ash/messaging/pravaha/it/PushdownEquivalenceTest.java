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

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee;
import com.ash.messaging.pravaha.api.plugin.PushdownKind;
import com.ash.messaging.pravaha.api.plugin.ReadRequest;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Pushdown;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;
import com.ash.messaging.pravaha.testkit.CapturingRowWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pushing a filter into the source must not change the answer.
 *
 * <p>The property the whole design of pushdown rests on. A predicate the store evaluates is bytes
 * never sent and rows never decoded, which is the largest single lever there is -- and it is only
 * available because the engine keeps its own filter regardless, so a source may honour all, some or
 * none of a request without the result moving.
 *
 * <p>The dangerous direction is one-way. A filter that is <em>not</em> pushed costs bandwidth; a
 * filter pushed that should not have been costs rows, and nothing downstream can tell the difference
 * between a row the source withheld and a row that was never written. So the test does what a
 * too-eager source would do -- applies every pushed filter literally, at the source -- and requires
 * the output to be identical to a run where nothing was pushed at all.
 */
class PushdownEquivalenceTest {

    @SuppressWarnings("NullAway") // nulls passed on purpose
    private static final SourceCapabilities PUSHES_FILTERS = new SourceCapabilities(
            true, true, false, false, DeliveryGuarantee.AT_LEAST_ONCE, java.util.EnumSet.of(PushdownKind.FILTER), null);

    private static StreamSchema schema() {
        return StreamSchema.builder("txn")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .field("status", Types.string())
                .field("note", Types.string().withNullable(true))
                .build();
    }

    private record Row(long id, long amount, String status, String note) {}

    @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
    private static List<Row> data(int count) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Row(i, (long) i * 7 % 200, i % 3 == 0 ? "DONE" : "PENDING", i % 5 == 0 ? null : "note-" + i));
        }
        return rows;
    }

    /** The predicates a source is offered. Chosen to cover every shape the extractor handles. */
    private static final List<String> PREDICATES = List.of(
            "amount > 100",
            "amount >= 100 AND status = 'DONE'",
            "id <> 3",
            "note IS NULL",
            "note IS NOT NULL AND amount < 50",
            "status = 'DONE' OR amount > 150",
            "NOT (amount > 100)",
            "amount * 2 > 100",
            "amount > 100 AND (status = 'DONE' OR id < 10)",
            "id IN (1, 4, 9, 16, 25)",
            "status IN ('DONE', 'LOST') AND amount > 20",
            "id IN (2, 3) AND amount IN (14, 21, 100)",
            "id NOT IN (1, 2, 3)");

    @Property(tries = 80)
    void aSourceThatHonoursEveryPushedFilterReturnsTheSameRows(
            @ForAll @IntRange(min = 0, max = 12) int predicate, @ForAll @IntRange(min = 1, max = 60) int rowCount) {
        String sql = "SELECT id, amount FROM txn WHERE " + PREDICATES.get(predicate);
        List<Row> rows = data(rowCount);

        assertThat(run(sql, rows, true))
                .as("pushing %s changed the answer", PREDICATES.get(predicate))
                .isEqualTo(run(sql, rows, false));
    }

    @Test
    void whatIsPushedIsWhatTheStoreCanActuallyEvaluate() {
        assertThat(describe("amount > 100 AND status = 'DONE'")).containsExactly("amount > 100", "status = 'DONE'");
        assertThat(describe("note IS NULL")).containsExactly("note IS NULL");

        // A disjunction cannot be split into ANDed parts: pushing either half alone drops the rows
        // that satisfy only the other, and pushing the whole thing is not a shape this sends.
        assertThat(describe("status = 'DONE' OR amount > 150")).isEmpty();

        // Arithmetic has no column-and-literal shape to send.
        assertThat(describe("amount * 2 > 100")).isEmpty();

        // A conjunction with a disjunction inside still yields its simple half.
        assertThat(describe("amount > 100 AND (status = 'DONE' OR id < 10)")).containsExactly("amount > 100");
    }

    /**
     * INLIST-1: an {@code IN} list is pushed as the request's one disjunction -- an equality per value
     * -- where it used to reach no source at all. A second list in the same conjunction stays in the
     * engine, and a {@code NOT IN} is not a disjunction of equalities.
     */
    @Test
    void anInListIsPushedAsAnOrOfEqualities() {
        assertThat(alternatives("id IN (1, 4, 9)")).containsExactly("id = 1", "id = 4", "id = 9");
        assertThat(describe("id IN (1, 4, 9)")).isEmpty();
        assertThat(alternatives("status IN ('DONE', 'LOST') AND amount > 20"))
                .containsExactly("status = 'DONE'", "status = 'LOST'");
        assertThat(describe("status IN ('DONE', 'LOST') AND amount > 20")).containsExactly("amount > 20");
        assertThat(alternatives("id IN (2, 3) AND amount IN (14, 21)"))
                .as("one disjunction per request; the other list is the engine's")
                .hasSize(2);
        assertThat(alternatives("id NOT IN (1, 2, 3)")).isEmpty();
        // Different columns OR'd together are not an IN list.
        assertThat(alternatives("status = 'DONE' OR amount > 150")).isEmpty();
    }

    @Test
    void aFilterAboveAJoinIsNotPushedIntoEitherSide() {
        // Built by hand, because Calcite pushes simple conjuncts below a join before Pravaha ever
        // sees the plan -- so the guard against doing it wrongly is not reachable through SQL. It
        // still has to hold: above a join, a column name can belong to either side, and pushing
        // 'amount > 100' into both sources drops left rows that should have joined and right rows
        // that should have matched. The result is a query missing output with nothing to point at.
        StreamSchema left = StreamSchema.builder("left")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .build();
        StreamSchema right = StreamSchema.builder("right")
                .field("id", Types.int64())
                .field("amount", Types.int64())
                .build();
        StreamSchema joined = StreamSchema.builder("joined")
                .field("l_id", Types.int64())
                .field("l_amount", Types.int64())
                .field("r_id", Types.int64())
                .field("r_amount", Types.int64())
                .build();

        PhysicalOperator join = new com.ash.messaging.pravaha.runtime.plan.JoinOperator(
                com.ash.messaging.pravaha.runtime.plan.ScanOperator.of("left", left),
                com.ash.messaging.pravaha.runtime.plan.ScanOperator.of("right", right),
                List.of(0),
                List.of(0),
                joined,
                1000);
        PhysicalOperator filtered = new com.ash.messaging.pravaha.runtime.plan.FilterOperator(
                join,
                new com.ash.messaging.pravaha.runtime.plan.Predicate.CompareLong(
                        3, "amount", com.ash.messaging.pravaha.runtime.plan.Predicate.Op.GT, 100));

        assertThat(Pushdown.requestFor(filtered, "left", PUSHES_FILTERS).isEmpty())
                .isTrue();
        assertThat(Pushdown.requestFor(filtered, "right", PUSHES_FILTERS).isEmpty())
                .isTrue();
    }

    @Test
    void aSourceThatHasNotDeclaredFilterPushdownIsSentNothing() {
        @SuppressWarnings("NullAway") // nulls passed on purpose
        SourceCapabilities silent = new SourceCapabilities(
                true,
                true,
                false,
                false,
                DeliveryGuarantee.AT_LEAST_ONCE,
                java.util.EnumSet.noneOf(PushdownKind.class),
                null);

        assertThat(Pushdown.requestFor(plan("SELECT id FROM txn WHERE amount > 100"), "txn", silent)
                        .isEmpty())
                .as("a request a plugin has not opted into is a request nobody has thought about")
                .isTrue();
    }

    private static List<String> describe(String where) {
        return Pushdown.requestFor(plan("SELECT id, amount FROM txn WHERE " + where), "txn", PUSHES_FILTERS)
                .filters()
                .stream()
                .map(f -> f.column() + " " + f.comparison().sql()
                        + (f.value() == null ? "" : " " + (f.value() instanceof String s ? "'" + s + "'" : f.value())))
                .toList();
    }

    /** The request's disjunction, one alternative per line, each alternative's filters joined. */
    private static List<String> alternatives(String where) {
        return Pushdown.requestFor(plan("SELECT id, amount FROM txn WHERE " + where), "txn", PUSHES_FILTERS)
                .alternatives()
                .stream()
                .map(alternative -> String.join(
                        " AND ",
                        alternative.stream()
                                .map(f -> f.column() + " " + f.comparison().sql() + " "
                                        + (f.value() instanceof String s ? "'" + s + "'" : f.value()))
                                .toList()))
                .toList();
    }

    private static PhysicalOperator plan(String sql) {
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(sql));
    }

    /** Runs the query, optionally filtering at the "source" exactly as a pushed request asks. */
    private static List<String> run(String sql, List<Row> rows, boolean pushDown) {
        PhysicalOperator plan = plan(sql);
        ReadRequest request = pushDown ? Pushdown.requestFor(plan, "txn", PUSHES_FILTERS) : ReadRequest.NOTHING;

        List<CapturingRowWriter.Captured> results = new ArrayList<>();
        RowLayout layout = RowLayout.of(schema());

        try (RowArena feed = new RowArena(MemoryAccess.best(), 1 << 20, 8);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(
                        plan, (RowOutput) () -> new CapturingRowWriter(plan.outputSchema(), results::add))) {
            BinaryRowWriter writer = new BinaryRowWriter(layout);
            BinaryRowView view = new BinaryRowView(layout);
            for (Row row : rows) {
                if (!satisfies(row, request)) {
                    continue;
                }
                long handle = feed.allocate(layout.rowSize(256));
                writer.begin(feed.regionOf(handle), feed.offsetOf(handle));
                writer.setLong(0, row.id()).setLong(1, row.amount()).setString(2, row.status());
                if (row.note() == null) {
                    writer.setNull(3);
                } else {
                    writer.setString(3, row.note());
                }
                writer.weight(1L)
                        .eventTimestampNanos(row.id())
                        .sequence(row.id())
                        .commit();
                feed.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(view.wrap(feed.regionOf(handle), feed.offsetOf(handle)));
            }
            pipeline.finish();
        }
        return results.stream().map(r -> r.asLong(0) + ":" + r.asLong(1)).toList();
    }

    /** A source honouring the request to the letter, which is the worst case for correctness. */
    private static boolean satisfies(Row row, ReadRequest request) {
        if (!request.alternatives().isEmpty()
                && request.alternatives().stream().noneMatch(alternative -> allHold(row, alternative))) {
            return false;
        }
        return allHold(row, request.filters());
    }

    private static boolean allHold(Row row, List<ReadRequest.Filter> filters) {
        for (ReadRequest.Filter filter : filters) {
            Object value =
                    switch (filter.column()) {
                        case "id" -> row.id();
                        case "amount" -> row.amount();
                        case "status" -> row.status();
                        case "note" -> row.note();
                        default -> throw new IllegalStateException("unknown column " + filter.column());
                    };
            if (!matches(value, filter)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
    private static boolean matches(Object value, ReadRequest.Filter filter) {
        return switch (filter.comparison()) {
            case IS_NULL -> value == null;
            case IS_NOT_NULL -> value != null;
            default -> {
                if (value == null) {
                    yield false;
                }
                int comparison = value instanceof Long left
                        ? Long.compare(left, ((Number) filter.value()).longValue())
                        : String.valueOf(value).compareTo(String.valueOf(filter.value()));
                yield switch (filter.comparison()) {
                    case EQ -> comparison == 0;
                    case NE -> comparison != 0;
                    case LT -> comparison < 0;
                    case LE -> comparison <= 0;
                    case GT -> comparison > 0;
                    case GE -> comparison >= 0;
                    default -> throw new IllegalStateException();
                };
            }
        };
    }
}
