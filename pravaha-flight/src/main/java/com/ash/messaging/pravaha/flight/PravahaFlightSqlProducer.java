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
package com.ash.messaging.pravaha.flight;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.sql.BasicFlightSqlProducer;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.ContinuousQueryStatements;
import com.ash.messaging.pravaha.registry.DeadLetters;
import com.ash.messaging.pravaha.registry.FeedStatus;
import com.ash.messaging.pravaha.registry.QueryListing;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.QueryReplacement;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.ReplacementOptions;
import com.ash.messaging.pravaha.registry.Subscription;
import com.ash.messaging.pravaha.registry.SubscriptionFilter;
import com.ash.messaging.pravaha.registry.SubscriptionOptions;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ReadAdmission;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatement;
import com.ash.messaging.pravaha.sql.ContinuousStatements;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

/**
 * Pravaha as a Flight SQL server (ADR-030).
 *
 * <p>One protocol, both interaction models. This half is request/response: a client sends SQL, gets
 * an Arrow schema, and iterates record batches. The clients are somebody else's work -- Flight SQL
 * ships a JDBC driver, an ADBC driver, and first-party Python, Go and C++ clients -- which is the
 * whole reason for choosing it over writing a driver per language.
 *
 * <p><strong>The query is planned twice and executed once.</strong> {@code getFlightInfo} needs the
 * schema before there are any rows, and planning is what produces a schema; the ticket then carries
 * the SQL rather than a handle to a materialised result, so nothing is held between the two calls.
 * A stateful implementation -- execute at getFlightInfo, cache under a ticket -- would hold every
 * in-flight result in memory and need an expiry policy for clients that never come back for them.
 *
 * <p>Batches are bounded and streamed. The server does not put a whole result on the wire at once,
 * because a client iterating a million rows should not require the server to hold a million rows.
 */
public final class PravahaFlightSqlProducer extends BasicFlightSqlProducer implements AutoCloseable {

    /**
     * Rows per record batch.
     *
     * <p>Large enough that the per-batch overhead disappears against the row cost, small enough that
     * a slow client cannot make the server hold much. Arrow's own guidance is thousands rather than
     * millions, and the difference between 4096 and 65536 is not measurable next to a network hop.
     */
    private static final int BATCH_ROWS = 4096;

    /**
     * How often a running subscription re-proves that it may still be running.
     *
     * <p>Two seconds: short enough that a revocation takes effect in a time an operator would call
     * immediate, long enough that it costs nothing measurable against a stream delivering batches.
     */
    private static final java.time.Duration REAUTHORIZE_EVERY = java.time.Duration.ofSeconds(2);

    private final ViewCatalog catalog;
    private final ViewQuery queries;
    private final SecurityPolicy policy;
    private final AuditSink audit;
    private QueryRegistry registry;

    /** The node's dead-letter files, or an empty store when no directory is configured (B5). */
    private com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore deadLetters =
            com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore.NONE;

    private final BufferAllocator allocator;

    /**
     * The address handed to clients in every {@link FlightEndpoint} (SX-16). Not final: set again
     * by {@link PravahaFlightServer} once the transport is up, because what is built here is the
     * location the server was <em>asked</em> to bind -- and with {@code pravaha.flight.port: 0}
     * that port is literally {@code 0}, so a client following the endpoint dialled a dead port.
     */
    private volatile Location location;

    private final FlightSqlMetadata metadata;

    public PravahaFlightSqlProducer(ViewCatalog catalog, BufferAllocator allocator, Location location) {
        this(
                catalog,
                allocator,
                location,
                SecurityPolicy.PERMISSIVE,
                AuditSink.NONE,
                ReadAdmission.UNLIMITED,
                java.time.Duration.ZERO);
    }

    public PravahaFlightSqlProducer(
            ViewCatalog catalog,
            BufferAllocator allocator,
            Location location,
            SecurityPolicy policy,
            AuditSink audit,
            ReadAdmission admission,
            java.time.Duration readDeadline) {
        this.catalog = catalog;
        this.queries = new ViewQuery(catalog, policy, audit, admission, readDeadline);
        this.policy = policy;
        this.audit = audit;
        this.allocator = allocator;
        this.location = location;
        this.metadata = new FlightSqlMetadata(catalog, policy, allocator);
    }

    /**
     * The address this producer hands to clients, once the transport has actually bound one.
     *
     * <p>SX-16. Called by {@link PravahaFlightServer} after {@code start()}, which is the first
     * moment the real port and the real scheme are both known.
     */
    PravahaFlightSqlProducer servedFrom(Location bound) {
        this.location = bound;
        return this;
    }

    @Override
    protected <T extends Message> List<FlightEndpoint> determineEndpoints(
            T request, FlightDescriptor descriptor, Schema schema) {
        // One endpoint: this server holds the state and there is nowhere else to send the client.
        // A clustered deployment would return the node that owns the view's partitions, which is
        // exactly the indirection Flight's endpoint list exists for.
        //
        // The ticket is the *ticket* message packed as Any, not the descriptor's command. They are
        // different protobufs -- a command says "this is the query", a ticket says "this is how to
        // fetch its rows" -- and handing back the command produces "the defined request is invalid"
        // when the client returns with it, which names neither.
        Ticket ticket = new Ticket(com.google.protobuf.Any.pack(request).toByteArray());
        return Collections.singletonList(new FlightEndpoint(ticket, location));
    }

    /**
     * The caller, as authenticated by {@link PrincipalMiddleware}.
     *
     * <p>Anonymous when no verifier is configured. That is a deliberate two-state design rather than
     * a default that silently downgrades: {@code getMiddleware} returns null only when the server
     * was built without the middleware at all, which is the embedded case, and a server that <em>is</em>
     * authenticating has already refused the call before the producer sees it.
     */
    private static Principal principalOf(CallContext context) {
        PrincipalMiddleware middleware = context.getMiddleware(PrincipalMiddleware.KEY);
        return middleware == null ? Principal.ANONYMOUS : middleware.principal();
    }

    @Override
    public FlightInfo getFlightInfoStatement(
            FlightSql.CommandStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        String sql = command.getQuery();
        Schema schema = schemaOf(sql, context);
        // The ticket carries the query itself, so the server holds nothing between this call and the
        // one that fetches the rows.
        FlightSql.TicketStatementQuery ticket = FlightSql.TicketStatementQuery.newBuilder()
                .setStatementHandle(ByteString.copyFrom(sql, StandardCharsets.UTF_8))
                .build();
        return generateFlightInfo(ticket, descriptor, schema);
    }

    @Override
    public void getStreamStatement(
            FlightSql.TicketStatementQuery ticket, CallContext context, ServerStreamListener listener) {
        String sql = ticket.getStatementHandle().toStringUtf8();
        // A continuous-query statement runs here, at the fetch, like any other statement: the ticket
        // carries the text and getFlightInfo only said what the answer would look like.
        emit(
                listener,
                () -> ContinuousStatements.recognize(sql)
                        .map(statement -> run(statement, context))
                        .orElseGet(() -> queries.execute(sql, principalOf(context))));
    }

    /**
     * The result schema of {@code sql}, whether it is a question or a continuous-query statement.
     *
     * <p>A statement's schema is fixed by what it is, so nothing is planned; but it is refused here,
     * before the fetch, when it is malformed or when this server hosts no registry to run it.
     */
    private Schema schemaOf(String sql, CallContext context) {
        Optional<ContinuousStatement> statement = statementOf(sql);
        if (statement.isEmpty()) {
            return arrowSchemaOf(plan(sql, context));
        }
        try {
            requireRegistry();
        } catch (PravahaException e) {
            throw FlightErrors.failureOf(e).toRuntimeException();
        }
        return arrowSchemaOf(ContinuousQueryStatements.resultSchemaOf(statement.get()));
    }

    /** {@code CREATE}/{@code DROP}/{@code PAUSE}/{@code RESUME CONTINUOUS QUERY} or {@code SHOW}, if it is one. */
    private static Optional<ContinuousStatement> statementOf(String sql) {
        try {
            return ContinuousStatements.recognize(sql);
        } catch (PravahaException e) {
            throw FlightErrors.failureOf(e).toRuntimeException();
        }
    }

    /**
     * Runs a continuous-query statement with this server's policy and audit sink -- the ones its
     * actions use, so the SQL spelling and the action decide identically.
     */
    private ViewQuery.Result run(ContinuousStatement statement, CallContext context) {
        return new ContinuousQueryStatements(requireRegistry(), policy, audit).execute(statement, principalOf(context));
    }

    /**
     * {@code executeUpdate} -- the call a JDBC or ADBC client makes for a statement it expects no
     * rows from -- for the continuous-query statements. Anything else is refused as it always was:
     * this server answers questions and has nothing else to update.
     */
    @Override
    public Runnable acceptPutStatement(
            FlightSql.CommandStatementUpdate command,
            CallContext context,
            FlightStream flightStream,
            StreamListener<PutResult> ackStream) {
        Optional<ContinuousStatement> statement;
        try {
            statement = ContinuousStatements.recognize(command.getQuery());
        } catch (PravahaException e) {
            return () -> ackStream.onError(FlightErrors.failureOf(e).toRuntimeException());
        }
        if (statement.isEmpty()) {
            return super.acceptPutStatement(command, context, flightStream, ackStream);
        }
        return () -> {
            try {
                ViewQuery.Result result = run(statement.get(), context);
                FlightSql.DoPutUpdateResult update = FlightSql.DoPutUpdateResult.newBuilder()
                        .setRecordCount(result.size())
                        .build();
                try (org.apache.arrow.memory.ArrowBuf metadata = allocator.buffer(update.getSerializedSize())) {
                    metadata.writeBytes(update.toByteArray());
                    ackStream.onNext(PutResult.metadata(metadata));
                }
                ackStream.onCompleted();
            } catch (PravahaException e) {
                ackStream.onError(FlightErrors.failureOf(e).toRuntimeException());
            } catch (RuntimeException e) {
                ackStream.onError(CallStatus.INTERNAL
                        .withDescription(String.valueOf(e.getMessage()))
                        .toRuntimeException());
            }
        };
    }

    /**
     * Runs a query and streams its rows, turning any failure into a status the client can act on.
     *
     * <p>Shared by the plain and prepared paths so that the two cannot drift: a batching rule or an
     * error mapping fixed in one and not the other is a difference nobody sees until a client hits
     * exactly the wrong one.
     */
    private void emit(ServerStreamListener listener, java.util.function.Supplier<ViewQuery.Result> query) {
        try {
            ViewQuery.Result result = query.get();
            Schema schema = ArrowSchemas.toArrow(result.schema());
            try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
                listener.start(root);
                int inBatch = 0;
                for (Object[] row : result.rows()) {
                    ArrowSchemas.write(root, inBatch++, row, result.schema());
                    if (inBatch == BATCH_ROWS) {
                        root.setRowCount(inBatch);
                        listener.putNext();
                        root.clear();
                        inBatch = 0;
                    }
                }
                // The last partial batch, and the empty case: a result with no rows still needs one
                // batch of zero rows, or a client waits for data that will never come.
                root.setRowCount(inBatch);
                listener.putNext();
            }
            listener.completed();
        } catch (PravahaException e) {
            // The engine's own diagnosis, with its PRV code, rather than a generic INTERNAL. A
            // client that gets "PRV-4023 ... this server serves [user_volume]" can act on it.
            listener.error(FlightErrors.failureOf(e).toRuntimeException());
        } catch (RuntimeException e) {
            listener.error(CallStatus.INTERNAL
                    .withDescription(String.valueOf(e.getMessage()))
                    .toRuntimeException());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Prepared statements (ADR-032). The server keeps nothing between these calls: the handle
    // carries the statement, and once bound, its values.
    // ---------------------------------------------------------------------------------------

    @Override
    public void createPreparedStatement(
            FlightSql.ActionCreatePreparedStatementRequest request,
            CallContext context,
            StreamListener<Result> listener) {
        try {
            String sql = request.getQuery();
            Optional<ContinuousStatement> statement = ContinuousStatements.recognize(sql);
            if (statement.isPresent()) {
                // A driver that prepares everything -- the Flight SQL JDBC driver does -- prepares a
                // CREATE too. Its answer's shape is fixed, and it has no placeholders to bind.
                requireRegistry();
                listener.onNext(new Result(com.google.protobuf.Any.pack(
                                FlightSql.ActionCreatePreparedStatementResult.newBuilder()
                                        .setPreparedStatementHandle(ByteString.copyFrom(
                                                StatementHandle.unbound(sql).encode()))
                                        .setDatasetSchema(ByteString.copyFrom(
                                                arrowSchemaOf(ContinuousQueryStatements.resultSchemaOf(statement.get()))
                                                        .serializeAsMessage()))
                                        .setParameterSchema(
                                                ByteString.copyFrom(new Schema(List.of()).serializeAsMessage()))
                                        .build())
                        .toByteArray()));
                listener.onCompleted();
                return;
            }
            ViewQuery.Prepared prepared = queries.prepare(sql, principalOf(context));

            // Both schemas go back now, before any value is bound. The dataset schema is what lets a
            // client lay out a grid while the user is still typing; the parameter schema is what
            // tells it which types to send, so it never has to guess -- which is the part JDBC's
            // setObject gets wrong often enough to be a category of bug.
            FlightSql.ActionCreatePreparedStatementResult result =
                    FlightSql.ActionCreatePreparedStatementResult.newBuilder()
                            .setPreparedStatementHandle(ByteString.copyFrom(
                                    StatementHandle.unbound(sql).encode()))
                            .setDatasetSchema(ByteString.copyFrom(
                                    arrowSchemaOf(prepared.resultSchema()).serializeAsMessage()))
                            .setParameterSchema(ByteString.copyFrom(ArrowSchemas.parameterSchema(prepared.parameters())
                                    .serializeAsMessage()))
                            .build();
            listener.onNext(new Result(com.google.protobuf.Any.pack(result).toByteArray()));
            listener.onCompleted();
        } catch (PravahaException e) {
            listener.onError(FlightErrors.failureOf(e).toRuntimeException());
        } catch (RuntimeException e) {
            listener.onError(CallStatus.INTERNAL
                    .withDescription(String.valueOf(e.getMessage()))
                    .toRuntimeException());
        }
    }

    /** A continuous-query statement has no {@code ?} to bind, so a binding is a mistake worth naming. */
    private static PravahaException noParameters(ContinuousStatement statement) {
        return new PravahaException(
                com.ash.messaging.pravaha.sql.SqlErrors.STATEMENT_MALFORMED,
                // HLP-14(e). This sent clients to "the register action, which binds them"; the action
                // has no parameters field, and nothing over Flight binds a registration's parameters.
                statement.verb() + " takes no parameters. Write the values into the statement. A continuous "
                        + "query with bound parameters can only be registered in an embedded engine, through "
                        + "QueryRegistry.register(..., BoundParameters); neither this statement nor the register "
                        + "action binds any.");
    }

    @Override
    public void closePreparedStatement(
            FlightSql.ActionClosePreparedStatementRequest request,
            CallContext context,
            StreamListener<Result> listener) {
        // Nothing to release: there was never anything held. The call is still answered, because a
        // client that closes what it opened should not get an error for doing the right thing.
        listener.onCompleted();
    }

    @Override
    public FlightInfo getFlightInfoPreparedStatement(
            FlightSql.CommandPreparedStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        StatementHandle handle =
                StatementHandle.decode(command.getPreparedStatementHandle().toByteArray());
        try {
            if (ContinuousStatements.isContinuousStatement(handle.sql())) {
                return generateFlightInfo(command, descriptor, schemaOf(handle.sql(), context));
            }
            ViewQuery.Prepared prepared = queries.prepare(handle.sql(), principalOf(context));
            return generateFlightInfo(command, descriptor, arrowSchemaOf(prepared.resultSchema()));
        } catch (PravahaException e) {
            throw FlightErrors.failureOf(e).toRuntimeException();
        }
    }

    @Override
    public void getStreamPreparedStatement(
            FlightSql.CommandPreparedStatementQuery command, CallContext context, ServerStreamListener listener) {
        emit(listener, () -> {
            StatementHandle handle =
                    StatementHandle.decode(command.getPreparedStatementHandle().toByteArray());
            Optional<ContinuousStatement> statement = ContinuousStatements.recognize(handle.sql());
            if (statement.isPresent()) {
                if (handle.boundParameters().isPresent()) {
                    throw noParameters(statement.get());
                }
                return run(statement.get(), context);
            }
            Principal principal = principalOf(context);
            ViewQuery.Prepared prepared = queries.prepare(handle.sql(), principal);
            BoundParameters parameters = handle.boundParameters()
                    .map(bytes -> ArrowParameters.decode(bytes, allocator, prepared.parameters()))
                    .orElse(BoundParameters.none());
            return queries.execute(prepared, parameters, principal);
        });
    }

    @Override
    public Runnable acceptPutPreparedStatementQuery(
            FlightSql.CommandPreparedStatementQuery command,
            CallContext context,
            FlightStream flightStream,
            StreamListener<PutResult> ackStream) {
        return () -> {
            try {
                StatementHandle handle = StatementHandle.decode(
                        command.getPreparedStatementHandle().toByteArray());
                Optional<ContinuousStatement> statement = ContinuousStatements.recognize(handle.sql());
                if (statement.isPresent()) {
                    throw noParameters(statement.get());
                }

                // SX-10. This leg applied no policy check at all. No rows escaped through it -- the
                // follow-on getFlightInfoPreparedStatement re-authorizes and refuses before any
                // batch is produced -- but a handle is not a permission, and a principal who may not
                // read this statement's view had a call here that succeeded: they could bind values
                // into another principal's prepared statement and be told the binding was accepted.
                //
                // Re-authorized through queries.prepare rather than through a check written here, so
                // that the three legs of a prepared statement cannot drift apart: whatever prepare
                // decides -- policy, and the audit event recording it -- is what every leg decides.
                // The plan is cached, so the cost is the policy call the other legs already pay.
                queries.prepare(handle.sql(), principalOf(context));

                byte[] encoded = ArrowParameters.encode(flightStream);
                StatementHandle bound = handle.boundTo(encoded);

                // The updated handle goes back to the client, which uses it for the fetch. This is
                // the mechanism that lets the values live on the client's side of the wire rather
                // than in a table here waiting for a client that may never return.
                FlightSql.DoPutPreparedStatementResult result = FlightSql.DoPutPreparedStatementResult.newBuilder()
                        .setPreparedStatementHandle(ByteString.copyFrom(bound.encode()))
                        .build();
                try (org.apache.arrow.memory.ArrowBuf metadata = allocator.buffer(result.getSerializedSize())) {
                    metadata.writeBytes(result.toByteArray());
                    ackStream.onNext(PutResult.metadata(metadata));
                }
                ackStream.onCompleted();
            } catch (PravahaException e) {
                ackStream.onError(FlightErrors.failureOf(e).toRuntimeException());
            } catch (RuntimeException e) {
                ackStream.onError(CallStatus.INTERNAL
                        .withDescription(String.valueOf(e.getMessage()))
                        .toRuntimeException());
            }
        };
    }

    /**
     * Gives this producer a registry, enabling the register/list/drop actions and subscriptions.
     *
     * <p>Optional. A server that only serves views somebody else maintains has no registry, and its
     * clients get a clear refusal rather than a method that silently does nothing.
     */
    PravahaFlightSqlProducer withRegistry(QueryRegistry registry) {
        this.registry = registry;
        return this;
    }

    /** Gives this producer the node's dead-letter files, enabling the three {@code pravaha.dlq.*} actions. */
    PravahaFlightSqlProducer withDeadLetters(com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore store) {
        this.deadLetters = store == null ? com.ash.messaging.pravaha.runtime.dlq.DeadLetterStore.NONE : store;
        return this;
    }

    /**
     * The three dead-letter actions (B5).
     *
     * <p>Every rule -- who may see that the queue exists, whose entries have their bytes removed,
     * who may replay -- comes from {@link DeadLetters}, which is also what the HTTP endpoints use.
     * A second copy of an authorization rule diverges in the direction of whichever one somebody
     * forgot to change, which is the finding that put the listing rules in one class to begin with.
     */
    private void deadLetterAction(
            String type,
            QueryRegistry required,
            Principal principal,
            List<String> fields,
            StreamListener<Result> listener) {
        requireName(fields, type);
        String name = fields.get(0);
        DeadLetters access = new DeadLetters(required, policy, audit, deadLetters);
        switch (type) {
            case ControlWire.DLQ_LIST -> {
                int offset = intField(fields, 1, 0);
                int limit = intField(fields, 2, com.ash.messaging.pravaha.runtime.dlq.DeadLetterPage.DEFAULT_LIMIT);
                DeadLetters.View view = access.page(principal, name, offset, limit);
                for (DeadLetters.Visible visible : view.entries()) {
                    listener.onNext(new Result(ControlWire.encode(entryFields(visible))));
                }
                // The trailer. '#' as the first field cannot be an id -- ids are UUIDs -- so a
                // client tells the totals from an entry without being told how many to expect.
                com.ash.messaging.pravaha.runtime.dlq.DeadLetterCounts counts = view.counts();
                listener.onNext(new Result(ControlWire.encode(
                        "#",
                        Long.toString(view.page().total()),
                        Long.toString(counts.bytes()),
                        Long.toString(counts.evicted()),
                        Long.toString(counts.evictedBytes()),
                        Long.toString(counts.replayed()),
                        Long.toString(counts.failedAgain()),
                        deadLetters.retention().describe(),
                        Boolean.toString(deadLetters.configured()))));
            }
            case ControlWire.DLQ_SHOW -> {
                if (fields.size() < 2 || fields.get(1).isBlank()) {
                    throw new PravahaException(
                            FlightErrors.BAD_HANDLE, "show needs the id of a dead letter as its second field");
                }
                listener.onNext(
                        new Result(ControlWire.encode(entryFields(access.show(principal, name, fields.get(1))))));
            }
            default -> {
                if (fields.size() < 2) {
                    throw new PravahaException(
                            FlightErrors.BAD_HANDLE,
                            "replay needs at least one dead letter's id after the query's name. Replaying a "
                                    + "whole queue by omission is not offered -- a queue is usually a mix of "
                                    + "causes, and most of it is still malformed.");
                }
                for (String id : fields.subList(1, fields.size())) {
                    if (id.isBlank()) {
                        continue;
                    }
                    DeadLetters.Replayed one = access.replay(principal, name, id);
                    listener.onNext(new Result(
                            ControlWire.encode(one.id(), one.outcome().name(), one.detail(), one.newId())));
                }
            }
        }
    }

    /**
     * One entry as the wire carries it.
     *
     * <p>The record is Base64 like the file's own, and empty when it is withheld -- with field 8
     * saying why, so a client shows "you may not see this record" rather than an empty row.
     */
    private static List<String> entryFields(DeadLetters.Visible visible) {
        com.ash.messaging.pravaha.runtime.dlq.DeadLetterEntry entry = visible.entry();
        return List.of(
                entry.id(),
                Long.toString(entry.sequence()),
                entry.letter().stream(),
                entry.letter().sourceOffset(),
                entry.letter().code(),
                visible.reason(),
                entry.letter().at().map(java.time.Instant::toString).orElse(""),
                Integer.toString(visible.size()),
                java.util.Base64.getEncoder().encodeToString(visible.raw()),
                visible.withheld(),
                entry.replay().name(),
                entry.replayedAt() == null ? "" : entry.replayedAt().toString());
    }

    private static int intField(List<String> fields, int at, int fallback) {
        if (fields.size() <= at || fields.get(at).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(fields.get(at).strip());
        } catch (NumberFormatException e) {
            throw new PravahaException(
                    FlightErrors.BAD_HANDLE, "'" + fields.get(at) + "' is not a number; field " + at + " must be one");
        }
    }

    @Override
    public void doAction(CallContext context, Action action, StreamListener<Result> listener) {
        // Pravaha's own actions first, Flight SQL's afterwards. Registration and subscription have
        // no Flight SQL vocabulary -- the protocol was designed for asking questions, not for
        // standing up computations -- and actions are the extension point it provides for exactly
        // this, which keeps the transport one protocol rather than two.
        String type = action.getType();
        if (!type.startsWith("pravaha.")) {
            super.doAction(context, action, listener);
            return;
        }
        try {
            Principal principal = principalOf(context);
            QueryRegistry required = requireRegistry();
            List<String> fields = ControlWire.decode(action.getBody());
            if (DebugActions.handles(type)) {
                // The debugger's nine verbs, in a class of their own: this switch is already the
                // largest method here and this file is near the repository's size limit.
                DebugActions.act(type, required, principal, fields, encoded -> listener.onNext(new Result(encoded)));
                listener.onCompleted();
                return;
            }
            switch (type) {
                case ControlWire.REGISTER -> {
                    if (fields.size() < 3) {
                        throw new PravahaException(
                                FlightErrors.BAD_HANDLE, "register needs a name, some SQL and key columns");
                    }
                    List<Integer> keys = new ArrayList<>();
                    for (String ordinal : fields.get(2).split(",")) {
                        if (!ordinal.isBlank()) {
                            keys.add(Integer.parseInt(ordinal.strip()));
                        }
                    }
                    // An optional fourth field names a sink (ADR-043). Optional so a client that
                    // predates sinks sends three fields and is answered exactly as before.
                    String sink = fields.size() > 3 && !fields.get(3).isBlank()
                            ? fields.get(3).strip()
                            : null;
                    // An optional fifth field is the view's retention, in event time (ISO-8601, such as
                    // PT24H, or "forever"). Optional for the same reason the sink is; a client with a
                    // retention and no sink sends an empty fourth field.
                    Retention retention = fields.size() > 4 ? retentionOf(fields.get(4)) : null;
                    RegisteredQuery query = ContinuousQueryStatements.register(
                            required, fields.get(0), fields.get(1), keys, principal, sink, retention);
                    listener.onNext(new Result(ControlWire.encode(
                            query.name(),
                            query.state().name(),
                            query.fingerprint().shortForm())));
                }
                case ControlWire.DROP -> {
                    requireName(fields, "drop");
                    requireAdministrable(principal, fields.get(0), "drop");
                    required.drop(fields.get(0));
                    listener.onNext(new Result(ControlWire.encode(fields.get(0), "DROPPED")));
                }
                case ControlWire.PAUSE -> {
                    requireName(fields, "pause");
                    requireAdministrable(principal, fields.get(0), "pause");
                    required.pause(fields.get(0));
                    listener.onNext(new Result(ControlWire.encode(fields.get(0), "PAUSED")));
                }
                case ControlWire.RESUME -> {
                    requireName(fields, "resume");
                    requireAdministrable(principal, fields.get(0), "resume");
                    required.resume(fields.get(0));
                    listener.onNext(new Result(ControlWire.encode(fields.get(0), "RUNNING")));
                }
                case ControlWire.LIST -> {
                    // Filtered, not refused. A listing is how a client finds what it may use, so
                    // showing nothing would be unhelpful and showing everything is a disclosure: the
                    // SQL text carries account numbers and customer ids, which this repository's own
                    // configuration says to permission like data. Which names appear, which are
                    // hidden by what they read (SX-11), which refusals are recorded (SX-8) and which
                    // counts are withheld (SX-18) are decided by QueryListing -- the same rules the
                    // HTTP API's listing applies, written once so the two cannot drift.
                    for (QueryListing.Entry entry : new QueryListing(required, policy, audit).list(principal, "list")) {
                        RegisteredQuery query = entry.query();
                        // Fields 0-4 are the original contract; 5-7 were added after it and are
                        // trailing, so a client that reads five fields reads exactly what it always
                        // did. Key ordinals comma-separated as REGISTER takes them, the sink's binding
                        // name or empty, and the retention as ISO-8601 or "forever". 8-12 are the
                        // feed (FEED-1) and 13-15 the sink's own state (SINK-3), trailing for the
                        // same reason; see feedFields and sinkFields.
                        List<String> row = new java.util.ArrayList<>(List.of(
                                entry.name(),
                                query.state().name(),
                                query.sql(),
                                query.fingerprint().shortForm(),
                                Long.toString(entry.rowsIn()),
                                ordinals(query.view().keyOrdinals()),
                                entry.sink().orElse(""),
                                query.view().retention().toString()));
                        row.addAll(feedFields(entry));
                        row.addAll(sinkFields(entry));
                        listener.onNext(new Result(ControlWire.encode(row.toArray(new String[0]))));
                    }
                }
                case ControlWire.REPLACE -> {
                    // Blue/green replacement (ADR-046): the same fields a registration takes, plus
                    // the options. The name keeps answering the version it answers now; what comes
                    // back is the state the candidate is in and the computation it is.
                    if (fields.size() < 3) {
                        throw new PravahaException(
                                FlightErrors.BAD_HANDLE,
                                "replace needs a name, the new SQL and the key columns of the new version");
                    }
                    ReplacementOptions options = ReplacementOptions.defaults();
                    if (fields.size() > 3 && !fields.get(3).isBlank()) {
                        options = ReplacementOptions.parse(fields.get(3));
                    }
                    QueryReplacement.Status status = required.replacements()
                            .replace(fields.get(0), fields.get(1), keyOrdinals(fields.get(2)), principal, options);
                    listener.onNext(new Result(ControlWire.encode(replacement(status))));
                }
                case ControlWire.CUTOVER -> {
                    requireName(fields, "cutover");
                    listener.onNext(new Result(ControlWire.encode(
                            replacement(required.replacements().cutOver(fields.get(0), principal)))));
                }
                case ControlWire.ROLLBACK -> {
                    requireName(fields, "rollback");
                    listener.onNext(new Result(ControlWire.encode(
                            replacement(required.replacements().rollBack(fields.get(0), principal)))));
                }
                case ControlWire.ABANDON -> {
                    requireName(fields, "abandon");
                    listener.onNext(new Result(ControlWire.encode(
                            replacement(required.replacements().abandon(fields.get(0), principal)))));
                }
                case ControlWire.FINISH -> {
                    requireName(fields, "finish");
                    listener.onNext(new Result(ControlWire.encode(
                            replacement(required.replacements().finish(fields.get(0), principal)))));
                }
                case ControlWire.BACKFILL -> {
                    requireName(fields, "backfill");
                    String verb = fields.size() > 1 ? fields.get(1).strip().toLowerCase(java.util.Locale.ROOT) : "";
                    QueryReplacement.Status status =
                            switch (verb) {
                                case "pause" -> required.replacements().pause(fields.get(0), principal);
                                case "resume" -> required.replacements().resume(fields.get(0), principal);
                                case "throttle" ->
                                    required.replacements().throttle(fields.get(0), rowsPerSecond(fields), principal);
                                default ->
                                    throw new PravahaException(
                                            FlightErrors.BAD_HANDLE,
                                            "a backfill is paused, resumed or throttled; this action asked for '" + verb
                                                    + "'");
                            };
                    listener.onNext(new Result(ControlWire.encode(replacement(status))));
                }
                case ControlWire.REPLACEMENT -> {
                    // Reading the state of a replacement is administering the name, as changing it
                    // is: the candidate's SQL and its progress describe a query somebody may not
                    // read, and an unauthorized caller learns from it that the name exists at all.
                    if (fields.isEmpty() || fields.get(0).isBlank()) {
                        for (QueryReplacement.Status status :
                                required.replacements().all()) {
                            if (policy.mayAdminister(principal, status.name()).allowed()) {
                                listener.onNext(new Result(ControlWire.encode(replacement(status))));
                            }
                        }
                    } else {
                        requireAdministrable(principal, fields.get(0), "replacement");
                        required.replacements()
                                .of(fields.get(0))
                                .ifPresent(
                                        status -> listener.onNext(new Result(ControlWire.encode(replacement(status)))));
                    }
                }

                case ControlWire.DLQ_LIST, ControlWire.DLQ_SHOW, ControlWire.DLQ_REPLAY ->
                    deadLetterAction(type, required, principal, fields, listener);
                default ->
                    throw new PravahaException(
                            FlightErrors.UNSUPPORTED_REQUEST, "this server does not answer the action '" + type + "'");
            }
            listener.onCompleted();
        } catch (PravahaException e) {
            listener.onError(FlightErrors.failureOf(e).toRuntimeException());
        } catch (RuntimeException e) {
            listener.onError(CallStatus.INTERNAL
                    .withDescription(String.valueOf(e.getMessage()))
                    .toRuntimeException());
        }
    }

    /**
     * Refuses a control action whose body carries no query name.
     *
     * <p>API-142. {@code DROP}, {@code PAUSE} and {@code RESUME} read {@code fields.get(0)} without
     * checking there is one, so an empty body reached the client as an {@code INTERNAL} carrying a
     * Java array index -- a stack detail in place of "you did not say which query".
     */
    /**
     * A registration's retention, from the control wire's fifth field.
     *
     * <p>Blank means "this node's default", which is what a client that sends no fifth field gets.
     * Anything else must be an ISO-8601 duration or {@code forever}: a retention that could not be read
     * is refused rather than defaulted, because silently keeping a day of a view somebody asked to keep
     * for an hour changes what the view means.
     */
    static Retention retentionOf(String field) {
        String text = field == null ? "" : field.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.equalsIgnoreCase("forever")) {
            return Retention.forever();
        }
        try {
            java.time.Duration age = java.time.Duration.parse(text.toUpperCase(java.util.Locale.ROOT));
            return Retention.ofAge(age);
        } catch (java.time.format.DateTimeParseException | IllegalArgumentException e) {
            throw new PravahaException(
                    FlightErrors.BAD_HANDLE,
                    "'" + text + "' is not a retention. Give an ISO-8601 duration of event time, such as PT24H "
                            + "or P7D, or 'forever'; the age must be positive.");
        }
    }

    /**
     * The LIST row's feed fields (FEED-1), trailing after the retention.
     *
     * <p>8: the feed's state -- {@code RUNNING}, {@code PAUSED}, {@code STOPPED} or {@code NONE}. 9:
     * the first stopped source's code ({@code PRV-5092} or the source's own), empty while every
     * source reads. 10: its message, or a note that it is withheld from a row-filtered caller -- a
     * source's failure text can quote the row it could not read, which is the reason SX-18 withholds
     * a sink's. 11: where, {@code stream#partition}. 12: when, ISO-8601. A client reading eight
     * fields reads exactly what it always did.
     */
    private static List<String> feedFields(QueryListing.Entry entry) {
        FeedStatus status = entry.query().feedStatus();
        return status.firstStopped()
                .map(source -> List.of(
                        status.state().name(),
                        source.stop().code(),
                        entry.restricted()
                                ? "the message is withheld: your access to this view is row-filtered, and a "
                                        + "failure's text can quote rows outside your entitlement"
                                : source.stop().message(),
                        source.where(),
                        source.stop().at() == null ? "" : source.stop().at().toString()))
                .orElseGet(() -> List.of(status.state().name(), "", "", "", ""));
    }

    /**
     * The LIST row's sink fields (SINK-3), trailing after the feed's.
     *
     * <p>13: whether the sink is still writing -- {@code ATTACHED}, {@code DETACHED}, or {@code
     * NONE} for a query that writes nowhere. 14: the code it was detached with ({@code PRV-8009}),
     * empty while it writes. 15: that failure's message, or a note that it is withheld from a
     * row-filtered caller -- the same rule as a feed's, and for the same reason. The message has
     * already had every configured sink option struck out of it where the failure was recorded
     * ({@code SinkFactory.redact}).
     *
     * <p>Field 6 has carried the sink's <em>name</em> since it was added; what was missing until
     * now was any sign on this wire that the sink had stopped writing, which is the one thing
     * about a sink an operator has to be told without asking. A client reading thirteen fields
     * reads exactly what it always did.
     */
    private static List<String> sinkFields(QueryListing.Entry entry) {
        if (entry.sink().isEmpty()) {
            return List.of("NONE", "", "");
        }
        return entry.sinkFailure()
                .map(failure -> List.of(
                        "DETACHED",
                        failure.errorCode().code(),
                        entry.restricted()
                                ? "the message is withheld: your access to this view is row-filtered, and a "
                                        + "failure's text can quote rows outside your entitlement"
                                : failure.getMessage()))
                .orElseGet(() -> List.of("ATTACHED", "", ""));
    }

    /** A replacement's status as {@link ControlWire#REPLACEMENT_FIELDS} names its fields. */
    private static List<String> replacement(QueryReplacement.Status status) {
        com.ash.messaging.pravaha.backfill.BackfillJob.Progress progress = status.progress();
        return List.of(
                status.name(),
                status.state().name(),
                status.sql(),
                text(status.candidate()),
                text(status.replacing()),
                text(status.sink()),
                status.options().toString(),
                text(status.owner()),
                text(status.startedAt()),
                text(status.cutOverAt()),
                text(status.rollbackUntil()),
                Boolean.toString(status.rollbackAvailable()),
                Long.toString(progress.historyRows()),
                Long.toString(progress.liveRows()),
                Long.toString(Math.round(progress.rowsPerSecond())),
                Integer.toString(progress.partitions()),
                Integer.toString(progress.partitionsLive()),
                Boolean.toString(progress.historyComplete()),
                Long.toString(progress.rateLimit()),
                Boolean.toString(progress.paused()),
                Long.toString(status.lagNanos()),
                text(status.failureCode()),
                text(status.failure()));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** The key columns a replace action carries, in the comma-separated form register takes. */
    private static List<Integer> keyOrdinals(String field) {
        List<Integer> keys = new ArrayList<>();
        for (String ordinal : field.split(",")) {
            if (!ordinal.isBlank()) {
                keys.add(Integer.parseInt(ordinal.strip()));
            }
        }
        return keys;
    }

    private static long rowsPerSecond(List<String> fields) {
        try {
            return Long.parseLong(fields.get(2).strip());
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            throw new PravahaException(
                    FlightErrors.BAD_HANDLE, "throttling a backfill needs a number of records a second");
        }
    }

    private static String ordinals(List<Integer> keys) {
        StringBuilder text = new StringBuilder();
        for (int key : keys) {
            if (!text.isEmpty()) {
                text.append(',');
            }
            text.append(key);
        }
        return text.toString();
    }

    private static void requireName(List<String> fields, String verb) {
        if (fields.isEmpty() || fields.get(0).isBlank()) {
            throw new PravahaException(
                    FlightErrors.BAD_HANDLE,
                    verb + " needs the name of a query, and this request carried none. The control wire "
                            + "sends the name as the first field.");
        }
    }

    /** The one authorization rule for drop, pause and resume, shared with their SQL spellings. */
    private void requireAdministrable(Principal principal, String view, String verb) {
        ContinuousQueryStatements.requireAdministrable(policy, audit, principal, view, verb);
    }

    @Override
    public void getStream(CallContext context, Ticket ticket, ServerStreamListener listener) {
        // A subscription ticket is recognised by its magic rather than by trying to parse it as a
        // Flight SQL protobuf and seeing what happens. Guessing is not telling.
        if (ControlWire.isOurs(ticket.getBytes())) {
            streamSubscription(context, ticket, listener);
            return;
        }
        super.getStream(context, ticket, listener);
    }

    /**
     * Holds a stream open, writing changes as the query commits them.
     *
     * <p>The call does not complete until the client goes away or the query ends. Flight's cancel
     * handler is how the server learns the client has gone -- without it a subscription would
     * outlive its subscriber and the query would keep assembling batches for nobody.
     */
    private void streamSubscription(CallContext context, Ticket ticket, ServerStreamListener listener) {
        try {
            List<String> fields = ControlWire.decode(ticket.getBytes());
            boolean fromSnapshot = fields.size() >= 2 && ControlWire.SUBSCRIBE_FROM_SNAPSHOT.equals(fields.get(0));
            if (fields.size() < 2 || !(fromSnapshot || ControlWire.SUBSCRIBE.equals(fields.get(0)))) {
                throw new PravahaException(FlightErrors.BAD_HANDLE, "this is not a subscription ticket");
            }
            String viewName = fields.get(1);
            Principal principal = principalOf(context);
            QueryRegistry required = requireRegistry();

            // Authorized before resolved. Resolving first meant an unauthorized caller got the
            // registry's answer -- "no query named 'payrol' is registered" for a name that does not
            // exist, versus a policy refusal for one that does -- and the two replies distinguish
            // them. The owner's third constraint is that a user receives only the data they are
            // authorized for, and which names exist is data.
            AccessDecision decision = policy.mayRead(principal, viewName);
            audit.record(AuditEvent.of(principal, "subscribe", viewName, decision, filterText(fields)));
            if (!decision.allowed()) {
                throw new PravahaException(
                        SecurityErrors.FORBIDDEN,
                        principal.id() + " may not subscribe to '" + viewName + "': " + decision.reason());
            }
            RegisteredQuery query = required.require(viewName);
            if (decision.rowFilter().isPresent()) {
                // Refused, because this path cannot enforce it, and an entitlement that is silently
                // discarded is worse than one that is refused.
                //
                // A read through ViewQuery ANDs the policy's predicate into the plan and checks the
                // soundness rule -- the filter may be applied only to a view carrying every column it
                // names. A subscription has no plan to AND into: changes are handed to the subscriber
                // as the view commits them, and the only filter this path can express is equality on
                // a column (SubscriptionFilter). So a principal whose entitlement is
                // "region = 'EU'" would have received every region.
                //
                // Until the predicate can be evaluated per change, this fails closed. A subscriber
                // whose access is unconditional is unaffected; one whose access is conditional is
                // told why rather than quietly over-served.
                throw new PravahaException(
                        SecurityErrors.FORBIDDEN,
                        principal.id() + " may not subscribe to '" + viewName + "' because their access to it "
                                + "is conditional on the row filter '"
                                + decision.rowFilter().get()
                                + "', and a subscription cannot enforce a filter -- it delivers every change the "
                                + "view commits. Read the view instead, where the predicate is applied to the "
                                + "plan, or have the policy grant unconditional access to a view that already "
                                + "carries only the rows this principal may see.");
            }

            // An odd tail is this subscriber's buffer preference, riding last (STRM-16). Filter
            // pairs are alternating column and value, so their count is even; one more field makes
            // it odd, which is why a preference can be added without a version bump and with no
            // chance of a filter column being read as a policy.
            int pairsEnd = fields.size();
            ControlWire.SubscriberPreference preference = null;
            if ((fields.size() - 2) % 2 == 1) {
                preference = ControlWire.SubscriberPreference.decode(fields.get(fields.size() - 1));
                pairsEnd = fields.size() - 1;
            }
            Map<String, Object> equals = new LinkedHashMap<>();
            for (int i = 2; i + 1 < pairsEnd; i += 2) {
                equals.put(fields.get(i), fields.get(i + 1));
            }
            SubscriptionFilter filter = equals.isEmpty()
                    ? SubscriptionFilter.none()
                    : SubscriptionFilter.matching(query.outputSchema(), equals);
            SubscriptionOptions options = SubscriptionHandover.optionsOf(preference);

            StreamSchema schema = query.outputSchema();
            Schema arrow = ArrowSchemas.subscriptionSchema(schema);

            // Committed batches are handed over through a queue, and every Arrow write happens on
            // this call's own thread. Two reasons, and the second is the important one.
            //
            // Arrow's outbound listener is not built to be written from arbitrary threads. And more
            // fundamentally, writing to a socket from the engine's commit thread would make the
            // network part of the query's critical path: one subscriber on a slow link would slow
            // the computation for everybody, which is exactly what Subscription's bounded buffer
            // exists to prevent. The handover must not block, so it does not.
            BlockingQueue<Handed> handover = new LinkedBlockingQueue<>(SubscriptionHandover.BATCHES);
            // Rows waiting, not only batches (STRM-15).
            AtomicLong queuedRows = new AtomicLong();
            AtomicLong droppedBatches = new AtomicLong();
            AtomicBoolean fellBehind = new AtomicBoolean();
            CountDownLatch finished = new CountDownLatch(1);

            try (VectorSchemaRoot root = VectorSchemaRoot.create(arrow, allocator)) {
                listener.start(root);
                listener.setOnCancelHandler(finished::countDown);
                try (Subscription subscription = fromSnapshot
                        ? subscribeFromSnapshot(query, filter, handover, fellBehind)
                        // subscribeAs, not subscribe: the subscription remembers the name this
                        // client asked for, so dropping that name ends this stream even when the
                        // computation survives under another (STRM-14).
                        : query.subscribeAs(viewName, options, filter, changes -> {
                            // offer, never put. A full queue means this subscriber is slower than
                            // the query, and the answer is to lose its batches rather than the
                            // engine's pace.
                            //
                            // Full by either measure: SubscriptionHandover.BATCHES commits, or
                            // SubscriptionHandover.ROWS rows across them (STRM-15). A bound in
                            // batches is not a bound on memory, and memory is what it was for.
                            if (!SubscriptionHandover.hasRoomFor(queuedRows.get(), changes.size())
                                    || !handover.offer(new Handed(changes, null))) {
                                droppedBatches.incrementAndGet();
                            } else {
                                queuedRows.addAndGet(changes.size());
                            }
                        })) {

                    long nextAuthorizationCheck = System.nanoTime() + REAUTHORIZE_EVERY.toNanos();
                    while (!listener.isCancelled()
                            && finished.getCount() > 0
                            && !subscription.isClosed()
                            && !query.state().isTerminal()) {
                        if (fellBehind.get()) {
                            // A snapshot subscriber's promise is every commit; one it cannot be
                            // given ends the stream, and the client resubscribes from a snapshot.
                            audit.record(AuditEvent.of(
                                    principal,
                                    "subscribe.behind",
                                    viewName,
                                    decision,
                                    "more than " + SubscriptionHandover.BATCHES
                                            + " commits waiting for a snapshot subscriber"));
                            listener.error(FlightErrors.failureOf(new PravahaException(
                                            FlightErrors.SUBSCRIBER_BEHIND,
                                            "this subscriber fell more than " + SubscriptionHandover.BATCHES
                                                    + " commits behind '" + viewName + "', and a snapshot "
                                                    + "subscription does not skip one; subscribe again to "
                                                    + "start from a fresh snapshot"))
                                    .toRuntimeException());
                            return;
                        }
                        // Re-authorised while it runs, not only when it opened.
                        //
                        // A subscription was checked once and then delivered for as long as the
                        // client kept the socket. Revoking a credential mid-stream did nothing: a
                        // row committed ten seconds after revocation still arrived, while a fresh
                        // subscribe was correctly refused. The exposure had no bound.
                        //
                        // Both halves are checked, because they fail differently: the credential
                        // can be revoked or expire, and the policy can stop allowing a principal
                        // whose credential is still perfectly good.
                        if (System.nanoTime() >= nextAuthorizationCheck) {
                            nextAuthorizationCheck = System.nanoTime() + REAUTHORIZE_EVERY.toNanos();
                            PrincipalMiddleware middleware = context.getMiddleware(PrincipalMiddleware.KEY);
                            if (middleware != null && !middleware.credentialStillValid()) {
                                audit.record(AuditEvent.of(
                                        principal,
                                        "subscribe.revoked",
                                        viewName,
                                        decision,
                                        "the credential this subscription opened with is no longer accepted"));
                                listener.error(FlightErrors.failureOf(
                                                CallStatus.UNAUTHENTICATED,
                                                SecurityErrors.UNAUTHENTICATED,
                                                "the credential this subscription opened with is no longer "
                                                        + "accepted; open it again with a current one")
                                        .toRuntimeException());
                                return;
                            }
                            AccessDecision now = policy.mayRead(principal, viewName);
                            if (!now.allowed()) {
                                audit.record(AuditEvent.of(principal, "subscribe.withdrawn", viewName, now, ""));
                                listener.error(FlightErrors.failureOf(
                                                CallStatus.UNAUTHORIZED,
                                                SecurityErrors.FORBIDDEN,
                                                principal.id() + " may no longer read '" + viewName + "': "
                                                        + now.reason())
                                        .toRuntimeException());
                                return;
                            }
                        }
                        Handed handed = handover.poll(200, TimeUnit.MILLISECONDS);
                        if (handed == null) {
                            continue;
                        }
                        if (handed.mark() == null) {
                            queuedRows.addAndGet(-handed.changes().size());
                            if (!handed.changes().isEmpty()) {
                                // A mark on a plain subscription's batches too, carrying how many
                                // whole commits this subscriber has lost so far (STRM-10). The
                                // count reached an AuditSink -- once, when the subscription ended,
                                // and only with audit configured -- and reached the subscriber
                                // never, so a dashboard that had lost 98 % of its changes looked
                                // exactly like one that had received everything. Nothing else
                                // about a plain batch changes: the frontier a plain subscription
                                // has never carried is still not carried.
                                writeBatch(
                                        listener,
                                        root,
                                        schema,
                                        handed.changes(),
                                        new ControlWire.BatchMark(
                                                ControlWire.BatchMark.COMMIT, Long.MIN_VALUE, droppedBatches.get()));
                            }
                        } else if (handed.mark().isSnapshot()) {
                            writeSnapshot(
                                    listener,
                                    root,
                                    schema,
                                    handed.changes(),
                                    handed.mark().frontier());
                        } else {
                            writeBatch(listener, root, schema, handed.changes(), handed.mark());
                        }
                    }
                    if (subscription.failure().isPresent()) {
                        // Said, not completed, and for every subscription rather than only a
                        // snapshot's (STRM-12). An administrative drop, a node shutting down and
                        // the client's own close() all used to arrive as listener.completed() --
                        // one signal for three events, only one of which is an end the client
                        // should accept. The reason carries its own code, and FlightErrors maps
                        // each to the status a client acts on before it reads anything:
                        // PRV-8018 NOT_FOUND for a dropped name, PRV-8019 UNAVAILABLE for a node
                        // that is coming back.
                        listener.error(
                                FlightErrors.failureOf(subscription.failure().get())
                                        .toRuntimeException());
                        return;
                    }
                    // Whatever is still queued when the client goes is not worth sending, but it is
                    // worth counting: a subscriber that lost batches should be able to find out.
                    if (droppedBatches.get() > 0) {
                        audit.record(AuditEvent.of(
                                principal,
                                "subscribe.dropped",
                                viewName,
                                decision,
                                droppedBatches.get() + " batches dropped for a slow subscriber"));
                    }
                }
            }
            listener.completed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.error(CallStatus.CANCELLED
                    .withDescription("subscription interrupted")
                    .toRuntimeException());
        } catch (PravahaException e) {
            listener.error(FlightErrors.failureOf(e).toRuntimeException());
        } catch (RuntimeException e) {
            listener.error(CallStatus.INTERNAL
                    .withDescription(String.valueOf(e.getMessage()))
                    .toRuntimeException());
        }
    }

    /** One commit, or the snapshot, on its way from the engine's thread to this call's. */
    private record Handed(List<com.ash.messaging.pravaha.serving.ViewChange> changes, ControlWire.BatchMark mark) {}

    /**
     * Opens a snapshot subscription feeding {@code handover} (SUB-1).
     *
     * <p>Neither conflated nor dropped within a commit -- the buffer is unbounded and FAIL -- because
     * the client is keeping a copy, and a conflated retraction is a copy gone wrong. What is bounded is
     * the handover: a subscriber that falls {@link SubscriptionHandover#BATCHES} commits behind is
     * marked, is offered nothing further (a commit after a gap would be worse than none), and its
     * stream ends with {@link FlightErrors#SUBSCRIBER_BEHIND}. The snapshot is the first thing queued,
     * into an empty queue, so it is never the one refused.
     */
    private static Subscription subscribeFromSnapshot(
            RegisteredQuery query,
            SubscriptionFilter filter,
            BlockingQueue<Handed> handover,
            AtomicBoolean fellBehind) {
        return query.subscribeFromSnapshot(
                SubscriptionOptions.of(Integer.MAX_VALUE, SubscriptionOptions.Overflow.FAIL),
                filter,
                new com.ash.messaging.pravaha.registry.SubscriptionListener() {
                    @Override
                    public void onSnapshot(List<com.ash.messaging.pravaha.serving.ViewChange> rows, long frontier) {
                        hand(rows, new ControlWire.BatchMark(ControlWire.BatchMark.SNAPSHOT_END, frontier));
                    }

                    @Override
                    public void onCommit(List<com.ash.messaging.pravaha.serving.ViewChange> changes, long frontier) {
                        hand(changes, new ControlWire.BatchMark(ControlWire.BatchMark.COMMIT, frontier));
                    }

                    private void hand(
                            List<com.ash.messaging.pravaha.serving.ViewChange> changes, ControlWire.BatchMark mark) {
                        if (!fellBehind.get() && !handover.offer(new Handed(changes, mark))) {
                            fellBehind.set(true);
                        }
                    }
                });
    }

    /**
     * Writes the snapshot in batches of at most {@link #BATCH_ROWS}, each marked, the last one {@link
     * ControlWire.BatchMark#SNAPSHOT_END} -- exactly one, sent even when the snapshot is empty.
     */
    private void writeSnapshot(
            ServerStreamListener listener,
            VectorSchemaRoot root,
            StreamSchema schema,
            List<com.ash.messaging.pravaha.serving.ViewChange> rows,
            long frontier) {
        int from = 0;
        do {
            int to = Math.min(rows.size(), from + BATCH_ROWS);
            String kind = to == rows.size() ? ControlWire.BatchMark.SNAPSHOT_END : ControlWire.BatchMark.SNAPSHOT;
            writeBatch(listener, root, schema, rows.subList(from, to), new ControlWire.BatchMark(kind, frontier));
            from = to;
        } while (from < rows.size());
    }

    /**
     * Writes one commit's changes as one Arrow batch, so a batch boundary is a commit boundary.
     *
     * @param mark what the batch is, sent as its application metadata; null on a plain
     *     subscription, whose batches have never carried any
     */
    private void writeBatch(
            ServerStreamListener listener,
            VectorSchemaRoot root,
            StreamSchema schema,
            List<com.ash.messaging.pravaha.serving.ViewChange> changes,
            ControlWire.BatchMark mark) {
        if (listener.isCancelled()) {
            return;
        }
        root.clear();
        org.apache.arrow.vector.BigIntVector weights = (org.apache.arrow.vector.BigIntVector)
                root.getVector(schema.fields().size());
        int index = 0;
        for (com.ash.messaging.pravaha.serving.ViewChange change : changes) {
            ArrowSchemas.write(root, index, change.values(), schema);
            // The weight, not just the values. A retraction and an insert carry the same bytes in
            // every other column, so without this a subscriber correcting its own total would
            // double-count the correction instead of cancelling it (design section 9.2).
            weights.setSafe(index, change.weight());
            index++;
        }
        root.setRowCount(index);
        if (mark == null) {
            listener.putNext();
            return;
        }
        byte[] encoded = mark.encode();
        // Ownership of the buffer passes to Flight with the call.
        org.apache.arrow.memory.ArrowBuf metadata = allocator.buffer(encoded.length);
        metadata.writeBytes(encoded);
        listener.putNext(metadata);
    }

    private static String filterText(List<String> fields) {
        return fields.size() > 2 ? String.join("=", fields.subList(2, fields.size())) : "no filter";
    }

    private QueryRegistry requireRegistry() {
        if (registry == null) {
            throw new PravahaException(
                    FlightErrors.UNSUPPORTED_REQUEST,
                    "this server serves views but does not host a registry, so it cannot register, drop "
                            + "or subscribe to queries. Start it with a QueryRegistry if it should");
        }
        return registry;
    }

    /** Plans without executing, which is how a schema is known before there are rows. */
    private com.ash.messaging.pravaha.api.data.StreamSchema plan(String sql, CallContext context) {
        try {
            return queries.schemaOf(sql, principalOf(context));
        } catch (PravahaException e) {
            throw FlightErrors.failureOf(e).toRuntimeException();
        }
    }

    /**
     * The Arrow form of a schema, with any refusal turned into a status the client can act on.
     *
     * <p>This conversion sat outside every try/catch, one line after {@code plan(...)} which has
     * one. Planning refusals reached clients cleanly; conversion refusals did not. A DECIMAL column
     * is the case that matters: the SQL is valid and plans, and only then does the wire type turn
     * out to be unmappable -- so PRV-6100 escaped the gRPC service method uncaught and the client
     * received Arrow's own "There was an error servicing your request". No code, no column name,
     * nothing to act on, for a refusal the engine had stated precisely.
     */
    private Schema arrowSchemaOf(com.ash.messaging.pravaha.api.data.StreamSchema schema) {
        try {
            return ArrowSchemas.toArrow(schema);
        } catch (PravahaException e) {
            throw FlightErrors.failureOf(e).toRuntimeException();
        }
    }

    // ---------------------------------------------------------------------------------------
    // The metadata surface (P-6). Everything below answered `getFlightInfo` and then failed its
    // `getStream` with Arrow's default UNIMPLEMENTED, which is why an off-the-shelf SQL client
    // could not get as far as showing a table list -- against a server whose SQL worked.
    //
    // The bodies live in FlightSqlMetadata; these are the protocol's seams and nothing more, so
    // that a change to what a table list contains does not mean editing this class.
    // ---------------------------------------------------------------------------------------

    /**
     * The result schema of a query, without running it.
     *
     * <p>{@code getFlightInfo} already returned a schema and this refused, which is a contradiction
     * a client cannot reason about: the same question, asked over the cheaper of the two calls, was
     * unanswerable. A driver building {@code ResultSetMetaData} takes this path.
     */
    @Override
    public org.apache.arrow.flight.SchemaResult getSchemaStatement(
            FlightSql.CommandStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        return new org.apache.arrow.flight.SchemaResult(schemaOf(command.getQuery(), context));
    }

    @Override
    public org.apache.arrow.flight.SchemaResult getSchemaPreparedStatement(
            FlightSql.CommandPreparedStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        StatementHandle handle =
                StatementHandle.decode(command.getPreparedStatementHandle().toByteArray());
        try {
            if (ContinuousStatements.isContinuousStatement(handle.sql())) {
                return new org.apache.arrow.flight.SchemaResult(schemaOf(handle.sql(), context));
            }
            ViewQuery.Prepared prepared = queries.prepare(handle.sql(), principalOf(context));
            return new org.apache.arrow.flight.SchemaResult(arrowSchemaOf(prepared.resultSchema()));
        } catch (PravahaException e) {
            throw FlightErrors.failureOf(e).toRuntimeException();
        }
    }

    @Override
    public void getStreamCatalogs(CallContext context, ServerStreamListener listener) {
        metadata.catalogs(listener);
    }

    @Override
    public void getStreamSchemas(
            FlightSql.CommandGetDbSchemas command, CallContext context, ServerStreamListener listener) {
        metadata.schemas(listener);
    }

    @Override
    public void getStreamTables(
            FlightSql.CommandGetTables command, CallContext context, ServerStreamListener listener) {
        metadata.tables(command, principalOf(context), listener);
    }

    @Override
    public void getStreamTableTypes(CallContext context, ServerStreamListener listener) {
        metadata.tableTypes(listener);
    }

    @Override
    public void getStreamPrimaryKeys(
            FlightSql.CommandGetPrimaryKeys command, CallContext context, ServerStreamListener listener) {
        metadata.primaryKeys(command, principalOf(context), listener);
    }

    @Override
    public void getStreamExportedKeys(
            FlightSql.CommandGetExportedKeys command, CallContext context, ServerStreamListener listener) {
        metadata.noForeignKeys(listener);
    }

    @Override
    public void getStreamImportedKeys(
            FlightSql.CommandGetImportedKeys command, CallContext context, ServerStreamListener listener) {
        metadata.noForeignKeys(listener);
    }

    @Override
    public void getStreamCrossReference(
            FlightSql.CommandGetCrossReference command, CallContext context, ServerStreamListener listener) {
        metadata.noForeignKeys(listener);
    }

    @Override
    public void getStreamTypeInfo(
            FlightSql.CommandGetXdbcTypeInfo command, CallContext context, ServerStreamListener listener) {
        metadata.typeInfo(command, listener);
    }

    @Override
    public void getStreamSqlInfo(
            FlightSql.CommandGetSqlInfo command, CallContext context, ServerStreamListener listener) {
        metadata.sqlInfo(command, listener);
    }

    /** The transaction verbs. See {@link FlightTransactions} for why they refuse (E-15). */
    @Override
    public void beginTransaction(
            FlightSql.ActionBeginTransactionRequest request,
            CallContext context,
            StreamListener<FlightSql.ActionBeginTransactionResult> listener) {
        listener.onError(FlightTransactions.notATransaction("beginTransaction"));
    }

    @Override
    public void endTransaction(
            FlightSql.ActionEndTransactionRequest request, CallContext context, StreamListener<Result> listener) {
        listener.onError(FlightTransactions.notATransaction("endTransaction"));
    }

    @Override
    public void beginSavepoint(
            FlightSql.ActionBeginSavepointRequest request,
            CallContext context,
            StreamListener<FlightSql.ActionBeginSavepointResult> listener) {
        listener.onError(FlightTransactions.notATransaction("beginSavepoint"));
    }

    @Override
    public void endSavepoint(
            FlightSql.ActionEndSavepointRequest request, CallContext context, StreamListener<Result> listener) {
        listener.onError(FlightTransactions.notATransaction("endSavepoint"));
    }

    /** The views this server serves, for a client browsing the catalogue. */
    public ViewCatalog catalog() {
        return catalog;
    }

    @Override
    public void close() {
        // The allocator belongs to whoever built the server, because it is usually shared with the
        // transport; closing it here would pull it out from under them.
    }
}
