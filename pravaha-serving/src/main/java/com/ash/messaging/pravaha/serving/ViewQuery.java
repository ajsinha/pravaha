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
package com.ash.messaging.pravaha.serving;

import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.TypeName;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.sql.SqlPlanner;
import com.ash.messaging.pravaha.sql.plan.PhysicalPlanBuilder;

/**
 * Answering a question about a maintained view (ADR-030, tiers 1 and 2).
 *
 * <p>The point of this class is how little it does. A view is a table that is already computed and
 * already indexed, so a query against it needs no execution engine of its own -- it needs the
 * engine that is already here, pointed at rows that are already in memory. The SQL is planned by the
 * same planner a continuous query uses, and the plan is executed by the same interpreted pipeline,
 * with the view's rows fed in where a source would normally be.
 *
 * <p>That reuse is worth more than the code it saves. A filter means the same thing in a one-shot
 * query as in a continuous one, because it <em>is</em> the same filter -- the same predicate IR, the
 * same null handling, the same refusals. Two implementations of {@code WHERE} would agree until the
 * day they did not, and the day they did not would be a support call about a number.
 *
 * <p><strong>Bounded by memory, and honest about it.</strong> The rows are materialised: a view is
 * lane-local state that already fits in memory, so a query over it fits too, but a query whose
 * <em>result</em> is unbounded -- a cross join, an unlimited scan of a very large view -- is bounded
 * by a row ceiling rather than allowed to consume the process. Spilling is what a query engine does
 * and Pravaha is not one (ADR-030).
 */
public final class ViewQuery {

    /**
     * How many result rows one query may produce.
     *
     * <p>A ceiling rather than a page size: paging belongs to the transport, which knows what the
     * client asked for. This exists so that a query nobody thought about cannot take the process
     * down, and it fails naming the number rather than being silently truncated -- a truncated
     * answer that looks complete is the worst of both.
     */
    public static final int MAX_RESULT_ROWS = 1_000_000;

    private final ViewCatalog catalog;

    public ViewQuery(ViewCatalog catalog) {
        this.catalog = catalog;
    }

    /** A result: the shape of the rows, and the rows. */
    public record Result(StreamSchema schema, List<Object[]> rows) {

        public Result {
            rows = List.copyOf(rows);
        }

        public int size() {
            return rows.size();
        }
    }

    /**
     * Plans and runs one query over the registered views.
     *
     * <p>Reads the views at their committed frontier, which is what a caller that says nothing
     * should get: a consistent read is the only one under which two views agree on the same prefix
     * of the input, and it is what a person acting on the answer needs.
     */
    public Result execute(String sql) {
        if (catalog.isEmpty()) {
            throw new PravahaException(
                    ServingErrors.NO_SUCH_VIEW,
                    "no views are registered, so there is nothing to query. A view is created by "
                            + "registering a continuous query that serves one.");
        }

        StreamSchema[] schemas = catalog.schemas().values().toArray(new StreamSchema[0]);
        PhysicalOperator plan;
        try {
            plan = new PhysicalPlanBuilder()
                    .build(SqlPlanner.withStreams(schemas).plan(sql));
        } catch (PravahaException e) {
            throw e;
        }

        String source = sourceViewOf(plan);
        ServedView view = catalog.find(source)
                .orElseThrow(() -> new PravahaException(
                        ServingErrors.NO_SUCH_VIEW,
                        "'" + source + "' is not a registered view; this server serves " + catalog.names()));

        List<Object[]> results = new ArrayList<>();
        RowLayout inputLayout = RowLayout.of(view.schema());
        StreamSchema outputSchema = plan.outputSchema();

        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 64);
                InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, (RowOutput) () ->
                        new ValueCollectingWriter(outputSchema, row -> {
                            if (results.size() >= MAX_RESULT_ROWS) {
                                throw new PravahaException(
                                        ServingErrors.RESULT_TOO_LARGE,
                                        "this query has produced " + MAX_RESULT_ROWS + " rows, which is the "
                                                + "ceiling for a single request. Narrow it with a WHERE clause, "
                                                + "or subscribe to it instead -- a subscription streams without "
                                                + "materialising.");
                            }
                            results.add(row);
                        }))) {

            BinaryRowWriter writer = new BinaryRowWriter(inputLayout);
            BinaryRowView cursor = new BinaryRowView(inputLayout);
            for (Object[] row : view.scan()) {
                long handle = arena.allocate(inputLayout.rowSize(1024));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                write(writer, view.schema(), row);
                writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
                arena.trimTo(handle, writer.sizeSoFar());
                pipeline.accept(cursor.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            // A view scan is a bounded source, so the query ends: anything holding state emits what
            // it has rather than waiting for a watermark that will never come.
            pipeline.finish();
        }
        return new Result(outputSchema, results);
    }

    /**
     * Plans without executing, which is how a caller learns the shape of an answer before asking
     * for it.
     *
     * <p>Flight SQL needs exactly this: {@code getFlightInfo} must return a schema, and there are no
     * rows yet. Planning twice -- once for the schema, once when the rows are fetched -- costs a
     * plan and saves holding every in-flight result in memory with an expiry policy for the clients
     * that never come back.
     */
    public StreamSchema schemaOf(String sql) {
        return planFor(sql).outputSchema();
    }

    private PhysicalOperator planFor(String sql) {
        if (catalog.isEmpty()) {
            throw new PravahaException(
                    ServingErrors.NO_SUCH_VIEW,
                    "no views are registered, so there is nothing to query. A view is created by "
                            + "registering a continuous query that serves one.");
        }
        StreamSchema[] schemas = catalog.schemas().values().toArray(new StreamSchema[0]);
        return new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schemas).plan(sql));
    }

    /** The single view a plan reads. */
    private static String sourceViewOf(PhysicalOperator plan) {
        List<String> found = new ArrayList<>();
        collectScans(plan, found);
        if (found.size() != 1) {
            throw new PravahaException(
                    ServingErrors.UNSUPPORTED_QUERY,
                    "a request/response query reads exactly one view; this one reads " + found
                            + ". Joining views in a single request is analytics, which this server "
                            + "deliberately does not do (ADR-030) -- the views are maintained separately and "
                            + "joining them here would compute, not look up.");
        }
        return found.get(0);
    }

    private static void collectScans(PhysicalOperator operator, List<String> into) {
        if (operator instanceof com.ash.messaging.pravaha.runtime.plan.ScanOperator scan) {
            into.add(scan.streamName());
            return;
        }
        operator.inputs().forEach(input -> collectScans(input, into));
    }

    private static void write(BinaryRowWriter writer, StreamSchema schema, Object[] values) {
        for (int ordinal = 0; ordinal < schema.fields().size(); ordinal++) {
            Object value = values[ordinal];
            if (value == null) {
                writer.setNull(ordinal);
                continue;
            }
            TypeName type = schema.field(ordinal).type().typeName();
            switch (type) {
                case BOOLEAN -> writer.setBoolean(ordinal, (Boolean) value);
                case INT8 -> writer.setByte(ordinal, ((Number) value).byteValue());
                case INT16 -> writer.setShort(ordinal, ((Number) value).shortValue());
                case INT32, DATE -> writer.setInt(ordinal, ((Number) value).intValue());
                case INT64, TIME, TIMESTAMP_LTZ -> writer.setLong(ordinal, ((Number) value).longValue());
                case FLOAT32 -> writer.setFloat(ordinal, ((Number) value).floatValue());
                case FLOAT64 -> writer.setDouble(ordinal, ((Number) value).doubleValue());
                case BYTES -> writer.setBytes(ordinal, (byte[]) value);
                default -> writer.setString(ordinal, String.valueOf(value));
            }
        }
    }
}
