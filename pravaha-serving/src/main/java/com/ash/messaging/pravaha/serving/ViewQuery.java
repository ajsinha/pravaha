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

import org.apache.calcite.rel.RelNode;

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
import com.ash.messaging.pravaha.runtime.plan.AggregateOperator;
import com.ash.messaging.pravaha.runtime.plan.ComputeOperator;
import com.ash.messaging.pravaha.runtime.plan.FilterOperator;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.Predicate;
import com.ash.messaging.pravaha.runtime.plan.ProjectOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
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
    private final SecurityPolicy policy;
    private final AuditSink audit;

    /** A query path with no authorization, for an engine embedded behind its own wall. */
    public ViewQuery(ViewCatalog catalog) {
        this(catalog, SecurityPolicy.PERMISSIVE, AuditSink.NONE);
    }

    /**
     * A query path that enforces a policy and records what it decided.
     *
     * <p>Enforcement lives here rather than in the transport on purpose. A second transport -- REST,
     * an embedded call, the console -- would otherwise need its own copy of these checks, and the
     * copies would diverge; the one that diverged would be the one somebody exploited.
     */
    public ViewQuery(ViewCatalog catalog, SecurityPolicy policy, AuditSink audit) {
        this.catalog = catalog;
        this.policy = policy;
        this.audit = audit;
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
        return execute(sql, Principal.ANONYMOUS);
    }

    /**
     * Plans and runs one query as {@code principal}, enforcing the policy.
     *
     * <p>The row filter is ANDed into the <em>plan</em>, above the scan, rather than concatenated
     * into the SQL. Text concatenation is how a filter gets removed by a caller who understands
     * operator precedence better than whoever wrote the concatenation; a filter in the plan has no
     * syntax for the caller to reach (section 25).
     */
    public Result execute(String sql, Principal principal) {
        PhysicalOperator plan = planFor(sql);
        String source = sourceViewOf(plan);
        ServedView view = catalog.find(source)
                .orElseThrow(() -> new PravahaException(
                        ServingErrors.NO_SUCH_VIEW,
                        "'" + source + "' is not a registered view; this server serves " + catalog.names()));

        AccessDecision decision = policy.mayRead(principal, source);
        // Recorded whether allowed or denied: an audit log holding only refusals answers "who was
        // stopped" and not "who read the salary view", which is the question that gets asked.
        audit.record(AuditEvent.of(principal, "query", source, decision, sql));
        if (!decision.allowed()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN, principal.id() + " may not read '" + source + "': " + decision.reason());
        }
        if (decision.rowFilter().isPresent()) {
            plan = withRowFilter(plan, view, decision.rowFilter().get(), source);
        }

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
        return schemaOf(sql, Principal.ANONYMOUS);
    }

    /**
     * The shape of an answer, for a principal allowed to have one.
     *
     * <p>Authorized as strictly as the read itself. A schema is the list of columns an organisation
     * keeps about its customers; answering "what would this query return" for someone who may not
     * run it hands them that list for free.
     */
    public StreamSchema schemaOf(String sql, Principal principal) {
        PhysicalOperator plan = planFor(sql);
        String source = sourceViewOf(plan);
        AccessDecision decision = policy.mayRead(principal, source);
        audit.record(AuditEvent.of(principal, "schema", source, decision, sql));
        if (!decision.allowed()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN, principal.id() + " may not read '" + source + "': " + decision.reason());
        }
        return plan.outputSchema();
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

    /**
     * ANDs the policy's row filter into the plan, or refuses because it cannot be enforced.
     *
     * <p>The soundness rule of ADR-031, in one check. A filter can be applied to a view only if
     * every column it names is in that view. When a column is missing it is not merely
     * inconvenient: the column was aggregated away by the continuous query, so each row of the view
     * already <em>mixes</em> values this principal may and may not see, and no filter applied
     * afterwards can separate them. Serving such a row would leak exactly what the policy exists to
     * prevent, so the read is refused and the message says what would fix it -- a view registered
     * with the filter already applied, which is a different query with its own state (ADR-025's
     * fingerprint makes them different queries by construction).
     */
    private PhysicalOperator withRowFilter(PhysicalOperator plan, ServedView view, String filterSql, String source) {
        StreamSchema schema = view.schema();
        Predicate predicate;
        try {
            // Parsed against the view's own schema, so a filter naming a column the view does not
            // have fails here rather than being silently dropped.
            RelNode filterPlan = SqlPlanner.withStreams(schema).plan("SELECT * FROM " + source + " WHERE " + filterSql);
            predicate = predicateOf(new PhysicalPlanBuilder().build(filterPlan));
        } catch (PravahaException e) {
            throw new PravahaException(
                    SecurityErrors.FILTER_NOT_ENFORCEABLE,
                    "the row filter for " + view.name() + " (" + filterSql + ") cannot be applied to it: "
                            + e.getMessage()
                            + ". A filter naming a column this view does not carry cannot be enforced on it -- "
                            + "the column was aggregated away, so each row already mixes values this principal "
                            + "may and may not see. Register a view that applies the filter before aggregating.",
                    e);
        }
        return injectAboveScan(plan, predicate);
    }

    private static Predicate predicateOf(PhysicalOperator plan) {
        if (plan instanceof FilterOperator filter) {
            return filter.predicate();
        }
        for (PhysicalOperator input : plan.inputs()) {
            Predicate found = predicateOf(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Rebuilds the plan with the security filter immediately above the scan. */
    private static PhysicalOperator injectAboveScan(PhysicalOperator operator, Predicate predicate) {
        if (predicate == null) {
            return operator;
        }
        if (operator instanceof ScanOperator) {
            return new FilterOperator(operator, predicate);
        }
        if (operator instanceof FilterOperator filter) {
            return new FilterOperator(injectAboveScan(filter.input(), predicate), filter.predicate());
        }
        if (operator instanceof ProjectOperator project) {
            return new ProjectOperator(
                    injectAboveScan(project.input(), predicate), project.outputSchema(), project.sourceOrdinals());
        }
        if (operator instanceof ComputeOperator compute) {
            return new ComputeOperator(
                    injectAboveScan(compute.input(), predicate), compute.outputSchema(), compute.expressions());
        }
        if (operator instanceof AggregateOperator aggregate) {
            return new AggregateOperator(
                    injectAboveScan(aggregate.input(), predicate),
                    aggregate.outputSchema(),
                    aggregate.groupKeyOrdinals(),
                    aggregate.aggregates());
        }
        throw new PravahaException(
                SecurityErrors.FILTER_NOT_ENFORCEABLE,
                "cannot place a row filter under " + operator.label()
                        + "; refusing rather than running the query without it");
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
