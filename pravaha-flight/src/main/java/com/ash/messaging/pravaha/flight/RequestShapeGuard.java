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

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.ActionType;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightProducer;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.PollInfo;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.Ticket;

import com.ash.messaging.pravaha.api.wire.ControlWire;

/**
 * Refuses, by name, the Flight requests whose shape the producer cannot read (FLIGHTTICKET-1).
 *
 * <p>Two shapes reached Flight SQL's own dispatch and failed there with gRPC {@code INTERNAL} and
 * "There was an error servicing your request" -- no code, and a status that tells a client the server
 * is broken rather than that the request was:
 *
 * <ul>
 *   <li>a {@code DoGet} ticket that is neither a Pravaha subscription ticket nor a Flight SQL ticket
 *       ({@code \x00\xff...}, {@code NOPE:x}, {@code LIST}): now {@code INVALID_ARGUMENT}, {@link
 *       FlightErrors#UNREADABLE_TICKET};
 *   <li>a <em>path</em> descriptor on {@code GetFlightInfo}, {@code PollFlightInfo}, {@code GetSchema}
 *       or {@code DoPut}: Flight SQL speaks only command descriptors, and a path descriptor has no
 *       command to read. Now {@code UNIMPLEMENTED}, {@link FlightErrors#UNSUPPORTED_REQUEST}.
 * </ul>
 *
 * <p>A decorator, like {@link ObservedFlightProducer} and for the same reason: {@link
 * PravahaFlightSqlProducer} is at the project's file-size limit, and the question "can this request
 * be read at all" comes before anything the producer does. Everything else is passed through as is.
 */
final class RequestShapeGuard implements FlightProducer {

    /** The type-URL prefix of every Flight SQL command and ticket message. */
    private static final String FLIGHT_SQL_TYPE = "type.googleapis.com/arrow.flight.protocol.sql.";

    private final FlightProducer delegate;

    RequestShapeGuard(FlightProducer delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void getStream(CallContext context, Ticket ticket, ServerStreamListener listener) {
        if (!readable(ticket.getBytes())) {
            listener.error(unreadableTicket());
            return;
        }
        delegate.getStream(context, ticket, listener);
    }

    @Override
    public void listFlights(CallContext context, Criteria criteria, StreamListener<FlightInfo> listener) {
        delegate.listFlights(context, criteria, listener);
    }

    @Override
    public FlightInfo getFlightInfo(CallContext context, FlightDescriptor descriptor) {
        requireCommand(descriptor, "GetFlightInfo");
        return delegate.getFlightInfo(context, descriptor);
    }

    @Override
    public PollInfo pollFlightInfo(CallContext context, FlightDescriptor descriptor) {
        requireCommand(descriptor, "PollFlightInfo");
        return delegate.pollFlightInfo(context, descriptor);
    }

    @Override
    public SchemaResult getSchema(CallContext context, FlightDescriptor descriptor) {
        requireCommand(descriptor, "GetSchema");
        return delegate.getSchema(context, descriptor);
    }

    @Override
    public Runnable acceptPut(CallContext context, FlightStream flightStream, StreamListener<PutResult> ackStream) {
        // Read where Flight SQL's own acceptPut reads it, before any work is handed back: a path
        // descriptor has no command, and Flight SQL's parse of the missing one was the INTERNAL.
        FlightDescriptor descriptor = flightStream.getDescriptor();
        if (descriptor == null || !descriptor.isCommand()) {
            return () -> ackStream.onError(pathDescriptor("DoPut"));
        }
        return delegate.acceptPut(context, flightStream, ackStream);
    }

    @Override
    public void doExchange(CallContext context, FlightStream reader, ServerStreamListener writer) {
        delegate.doExchange(context, reader, writer);
    }

    @Override
    public void doAction(CallContext context, Action action, StreamListener<Result> listener) {
        delegate.doAction(context, action, listener);
    }

    @Override
    public void listActions(CallContext context, StreamListener<ActionType> listener) {
        delegate.listActions(context, listener);
    }

    /**
     * Whether {@code bytes} is a ticket the producer can read: a Pravaha subscription ticket (by its
     * magic), or a protobuf {@code Any} carrying a Flight SQL message.
     */
    static boolean readable(byte[] bytes) {
        if (ControlWire.isOurs(bytes)) {
            return true;
        }
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        try {
            return com.google.protobuf.Any.parseFrom(bytes).getTypeUrl().startsWith(FLIGHT_SQL_TYPE);
        } catch (com.google.protobuf.InvalidProtocolBufferException notProtobuf) {
            return false;
        }
    }

    private static FlightRuntimeException unreadableTicket() {
        return FlightErrors.failureOf(
                        CallStatus.INVALID_ARGUMENT,
                        FlightErrors.UNREADABLE_TICKET,
                        "this ticket is neither a Flight SQL ticket this server issued nor a Pravaha "
                                + "subscription ticket. Take the ticket from GetFlightInfo's endpoint, or "
                                + "subscribe through an SDK's subscribe() or `pravaha subscribe`.")
                .toRuntimeException();
    }

    private static FlightRuntimeException pathDescriptor(String call) {
        return FlightErrors.failureOf(
                        CallStatus.UNIMPLEMENTED,
                        FlightErrors.UNSUPPORTED_REQUEST,
                        call + " with a path descriptor is not supported: this server speaks Flight SQL, "
                                + "whose requests are command descriptors. Send the query as a Flight SQL "
                                + "statement: an SDK's query(), or `pravaha query --sql`.")
                .toRuntimeException();
    }

    private static void requireCommand(FlightDescriptor descriptor, String call) {
        if (descriptor == null || !descriptor.isCommand()) {
            throw pathDescriptor(call);
        }
    }
}
