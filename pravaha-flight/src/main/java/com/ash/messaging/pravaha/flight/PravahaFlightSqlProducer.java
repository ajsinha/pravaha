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
import java.util.Collections;
import java.util.List;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
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
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ReadAdmission;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;
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

    private final ViewCatalog catalog;
    private final ViewQuery queries;
    private final BufferAllocator allocator;
    private final Location location;

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
        this.allocator = allocator;
        this.location = location;
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
        Schema schema = ArrowSchemas.toArrow(plan(sql, context));
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
        emit(listener, () -> queries.execute(sql, principalOf(context)));
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
            listener.error(
                    FlightErrors.statusFor(e).withDescription(e.getMessage()).toRuntimeException());
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
            ViewQuery.Prepared prepared = queries.prepare(sql, principalOf(context));

            // Both schemas go back now, before any value is bound. The dataset schema is what lets a
            // client lay out a grid while the user is still typing; the parameter schema is what
            // tells it which types to send, so it never has to guess -- which is the part JDBC's
            // setObject gets wrong often enough to be a category of bug.
            FlightSql.ActionCreatePreparedStatementResult result =
                    FlightSql.ActionCreatePreparedStatementResult.newBuilder()
                            .setPreparedStatementHandle(ByteString.copyFrom(
                                    StatementHandle.unbound(sql).encode()))
                            .setDatasetSchema(ByteString.copyFrom(ArrowSchemas.toArrow(prepared.resultSchema())
                                    .serializeAsMessage()))
                            .setParameterSchema(ByteString.copyFrom(ArrowSchemas.parameterSchema(prepared.parameters())
                                    .serializeAsMessage()))
                            .build();
            listener.onNext(new Result(com.google.protobuf.Any.pack(result).toByteArray()));
            listener.onCompleted();
        } catch (PravahaException e) {
            listener.onError(
                    FlightErrors.statusFor(e).withDescription(e.getMessage()).toRuntimeException());
        } catch (RuntimeException e) {
            listener.onError(CallStatus.INTERNAL
                    .withDescription(String.valueOf(e.getMessage()))
                    .toRuntimeException());
        }
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
            ViewQuery.Prepared prepared = queries.prepare(handle.sql(), principalOf(context));
            return generateFlightInfo(command, descriptor, ArrowSchemas.toArrow(prepared.resultSchema()));
        } catch (PravahaException e) {
            throw FlightErrors.statusFor(e).withDescription(e.getMessage()).toRuntimeException();
        }
    }

    @Override
    public void getStreamPreparedStatement(
            FlightSql.CommandPreparedStatementQuery command, CallContext context, ServerStreamListener listener) {
        emit(listener, () -> {
            StatementHandle handle =
                    StatementHandle.decode(command.getPreparedStatementHandle().toByteArray());
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
                ackStream.onError(FlightErrors.statusFor(e)
                        .withDescription(e.getMessage())
                        .toRuntimeException());
            } catch (RuntimeException e) {
                ackStream.onError(CallStatus.INTERNAL
                        .withDescription(String.valueOf(e.getMessage()))
                        .toRuntimeException());
            }
        };
    }

    /** Plans without executing, which is how a schema is known before there are rows. */
    private com.ash.messaging.pravaha.api.data.StreamSchema plan(String sql, CallContext context) {
        try {
            return queries.schemaOf(sql, principalOf(context));
        } catch (PravahaException e) {
            throw FlightErrors.statusFor(e).withDescription(e.getMessage()).toRuntimeException();
        }
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
