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
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.sql.BasicFlightSqlProducer;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

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
        this.catalog = catalog;
        this.queries = new ViewQuery(catalog);
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

    @Override
    public FlightInfo getFlightInfoStatement(
            FlightSql.CommandStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        String sql = command.getQuery();
        Schema schema = ArrowSchemas.toArrow(plan(sql));
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
        try {
            ViewQuery.Result result = queries.execute(sql);
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
                    CallStatus.INVALID_ARGUMENT.withDescription(e.getMessage()).toRuntimeException());
        } catch (RuntimeException e) {
            listener.error(CallStatus.INTERNAL
                    .withDescription(String.valueOf(e.getMessage()))
                    .toRuntimeException());
        }
    }

    /** Plans without executing, which is how a schema is known before there are rows. */
    private com.ash.messaging.pravaha.api.data.StreamSchema plan(String sql) {
        try {
            return queries.schemaOf(sql);
        } catch (PravahaException e) {
            throw CallStatus.INVALID_ARGUMENT.withDescription(e.getMessage()).toRuntimeException();
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
