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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
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

    /**
     * How often a running subscription re-proves that it may still be running.
     *
     * <p>Two seconds: short enough that a revocation takes effect in a time an operator would call
     * immediate, long enough that it costs nothing measurable against a stream delivering batches.
     */
    private static final java.time.Duration REAUTHORIZE_EVERY = java.time.Duration.ofSeconds(2);

    /**
     * Committed batches that may wait for a subscriber's socket.
     *
     * <p>Small on purpose. This queue exists to decouple the engine's thread from the network, not
     * to be a buffer -- {@link SubscriptionOptions} already decides how far behind a subscriber may
     * fall, with a policy the subscriber chose. A deep queue here would silently override that
     * choice with a different one.
     */
    private static final int SUBSCRIPTION_HANDOVER_BATCHES = 64;

    private final ViewCatalog catalog;
    private final ViewQuery queries;
    private final SecurityPolicy policy;
    private final AuditSink audit;
    private QueryRegistry registry;
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
        this.policy = policy;
        this.audit = audit;
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
        Schema schema = arrowSchemaOf(plan(sql, context));
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
                            .setDatasetSchema(ByteString.copyFrom(
                                    arrowSchemaOf(prepared.resultSchema()).serializeAsMessage()))
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
            return generateFlightInfo(command, descriptor, arrowSchemaOf(prepared.resultSchema()));
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
                    RegisteredQuery query = required.register(fields.get(0), fields.get(1), keys, principal);
                    listener.onNext(new Result(ControlWire.encode(
                            query.name(),
                            query.state().name(),
                            query.fingerprint().shortForm())));
                }
                case ControlWire.DROP -> {
                    requireAdministrable(principal, fields.get(0), "drop");
                    required.drop(fields.get(0));
                    listener.onNext(new Result(ControlWire.encode(fields.get(0), "DROPPED")));
                }
                case ControlWire.PAUSE -> {
                    requireAdministrable(principal, fields.get(0), "pause");
                    required.pause(fields.get(0));
                    listener.onNext(new Result(ControlWire.encode(fields.get(0), "PAUSED")));
                }
                case ControlWire.RESUME -> {
                    requireAdministrable(principal, fields.get(0), "resume");
                    required.resume(fields.get(0));
                    listener.onNext(new Result(ControlWire.encode(fields.get(0), "RUNNING")));
                }
                case ControlWire.LIST -> {
                    for (String name : required.names()) {
                        // Filtered, not refused. A listing is how a client finds what it may use, so
                        // showing nothing would be unhelpful and showing everything is a disclosure:
                        // the SQL text carries account numbers and customer ids, which this
                        // repository's own configuration says to permission like data. A principal
                        // sees the queries they could read, and does not learn that the others
                        // exist.
                        if (!policy.mayRead(principal, name).allowed()) {
                            continue;
                        }
                        RegisteredQuery query = required.require(name);
                        // SX-11. The name a query was registered under is chosen by whoever
                        // registered it, so deciding on the name alone listed 6 of 8 payroll-reading
                        // views to a principal denied "payroll" -- with their full SQL text, which
                        // carries the account numbers that made this a disclosure rather than an
                        // inconvenience. What the query actually reads decides too.
                        if (!mayReadEverythingBehind(principal, query)) {
                            continue;
                        }
                        listener.onNext(new Result(ControlWire.encode(
                                name,
                                query.state().name(),
                                query.sql(),
                                query.fingerprint().shortForm(),
                                Long.toString(query.rowsIn()))));
                    }
                }
                default ->
                    throw new PravahaException(
                            FlightErrors.UNSUPPORTED_REQUEST, "this server does not answer the action '" + type + "'");
            }
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

    /**
     * Refuses a control verb the principal may not use on this view.
     *
     * <p>These three verbs authorized nothing whatsoever. An unauthenticated caller dropped every
     * continuous query on a node configured to serve only verified callers, and an authenticated but
     * denied principal dropped another principal's payroll query -- destroying its accumulated state
     * and taking the view away from everyone holding a name for it.
     */
    /**
     * Whether this principal may read every base stream the query actually reads.
     *
     * <p>SX-11, on the listing path. {@code ViewQuery} enforces the same rule on the read itself;
     * this is what stops a denied principal learning that the view exists and reading its SQL text,
     * which is the disclosure half of the same finding. Both are needed: closing only the read
     * leaves the account numbers in the listing.
     *
     * <p>A view with no recorded provenance is its own source and is decided by its name alone,
     * exactly as before.
     */
    private boolean mayReadEverythingBehind(Principal principal, RegisteredQuery query) {
        for (String stream : query.view().derivedFrom()) {
            if (!stream.equals(query.view().name())
                    && !policy.mayRead(principal, stream).allowed()) {
                return false;
            }
        }
        return true;
    }

    private void requireAdministrable(Principal principal, String view, String verb) {
        AccessDecision decision = policy.mayAdminister(principal, view);
        audit.record(AuditEvent.of(principal, verb, view, decision, ""));
        if (!decision.allowed()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN,
                    principal.id() + " may not " + verb + " '" + view + "': " + decision.reason());
        }
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
            if (fields.size() < 2 || !"subscribe".equals(fields.get(0))) {
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

            Map<String, Object> equals = new LinkedHashMap<>();
            for (int i = 2; i + 1 < fields.size(); i += 2) {
                equals.put(fields.get(i), fields.get(i + 1));
            }
            SubscriptionFilter filter = equals.isEmpty()
                    ? SubscriptionFilter.none()
                    : SubscriptionFilter.matching(query.outputSchema(), equals);

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
            BlockingQueue<List<com.ash.messaging.pravaha.serving.ViewChange>> handover =
                    new LinkedBlockingQueue<>(SUBSCRIPTION_HANDOVER_BATCHES);
            AtomicLong droppedBatches = new AtomicLong();
            CountDownLatch finished = new CountDownLatch(1);

            try (VectorSchemaRoot root = VectorSchemaRoot.create(arrow, allocator)) {
                listener.start(root);
                listener.setOnCancelHandler(finished::countDown);
                try (Subscription subscription = query.subscribe(SubscriptionOptions.DEFAULT, filter, changes -> {
                    // offer, never put. A full queue means this subscriber is slower than
                    // the query, and the answer is to lose its batches rather than the
                    // engine's pace.
                    if (!handover.offer(changes)) {
                        droppedBatches.incrementAndGet();
                    }
                })) {

                    long nextAuthorizationCheck = System.nanoTime() + REAUTHORIZE_EVERY.toNanos();
                    while (!listener.isCancelled()
                            && finished.getCount() > 0
                            && !subscription.isClosed()
                            && !query.state().isTerminal()) {
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
                                listener.error(CallStatus.UNAUTHENTICATED
                                        .withDescription(SecurityErrors.UNAUTHENTICATED.code()
                                                + "  the credential this subscription opened with is no longer "
                                                + "accepted; open it again with a current one")
                                        .toRuntimeException());
                                return;
                            }
                            AccessDecision now = policy.mayRead(principal, viewName);
                            if (!now.allowed()) {
                                audit.record(AuditEvent.of(principal, "subscribe.withdrawn", viewName, now, ""));
                                listener.error(CallStatus.UNAUTHORIZED
                                        .withDescription(SecurityErrors.FORBIDDEN.code() + "  " + principal.id()
                                                + " may no longer read '" + viewName + "': " + now.reason())
                                        .toRuntimeException());
                                return;
                            }
                        }
                        List<com.ash.messaging.pravaha.serving.ViewChange> batch =
                                handover.poll(200, TimeUnit.MILLISECONDS);
                        if (batch != null && !batch.isEmpty()) {
                            writeBatch(listener, root, schema, batch);
                        }
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
            listener.error(
                    FlightErrors.statusFor(e).withDescription(e.getMessage()).toRuntimeException());
        } catch (RuntimeException e) {
            listener.error(CallStatus.INTERNAL
                    .withDescription(String.valueOf(e.getMessage()))
                    .toRuntimeException());
        }
    }

    /** Writes one commit's changes as one Arrow batch, so a batch boundary is a commit boundary. */
    private void writeBatch(
            ServerStreamListener listener,
            VectorSchemaRoot root,
            StreamSchema schema,
            List<com.ash.messaging.pravaha.serving.ViewChange> changes) {
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
        listener.putNext();
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
            throw FlightErrors.statusFor(e).withDescription(e.getMessage()).toRuntimeException();
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
