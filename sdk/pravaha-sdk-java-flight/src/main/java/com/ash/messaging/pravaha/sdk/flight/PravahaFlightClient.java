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
package com.ash.messaging.pravaha.sdk.flight;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;

import com.ash.messaging.pravaha.api.ErrorCode;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.sdk.ClientErrors;
import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.Endpoint;
import com.ash.messaging.pravaha.sdk.PravahaClientException;

/**
 * Asking Pravaha a question from Java.
 *
 * <pre>{@code
 * try (PravahaFlightClient client = PravahaFlightClient.connect("localhost:9090");
 *         QueryResult result = client.query("SELECT user_id, total FROM user_volume WHERE total > 100")) {
 *     for (Row row : result) {
 *         System.out.println(row.getString("user_id") + " " + row.getLong("total"));
 *     }
 * }
 * }</pre>
 *
 * <p>A thin wrapper, and deliberately so: the protocol is Arrow Flight SQL (ADR-030) and its client
 * is maintained upstream, so what this adds is Pravaha's own vocabulary -- endpoints, options, error
 * codes -- rather than an implementation of the wire. An application that would rather use the
 * Flight SQL client directly, or the JDBC driver, or ADBC, loses nothing by doing so.
 *
 * <p><strong>Reads are consistent.</strong> A query sees the view as of its last committed frontier,
 * which is the mode a person acting on the answer should have and the only one under which two views
 * agree on the same prefix of the input. Per-query consistency is declared on the wire and is the
 * next piece of this work; until it is honoured end to end, a knob here would be a knob that does
 * nothing.
 *
 * <p>Requires {@code --add-opens=java.base/java.nio=ALL-UNNAMED} and
 * {@code --add-opens=java.base/java.lang=ALL-UNNAMED}, because Arrow allocates off-heap through
 * internals the module system closes by default.
 */
public final class PravahaFlightClient implements AutoCloseable {

    private final BufferAllocator allocator;
    private final boolean ownsAllocator;
    private final FlightSqlClient client;

    /**
     * The transport underneath.
     *
     * <p>{@link FlightSqlClient} speaks Flight SQL and nothing else, and registering a continuous
     * query is not Flight SQL -- it is a Flight action. Both are the same connection; this is the
     * lower of the two views of it.
     */
    private final FlightClient transport;

    /**
     * The credential, sent as a header on every call.
     *
     * <p>Held rather than sent once at connect time because Flight has no session: each call is
     * authenticated on its own, which is what lets a server behind a load balancer answer without
     * the balancer having to pin a client to a node.
     */
    private final CallOption[] callOptions;

    /**
     * Subscriptions this client opened and that nobody has closed.
     *
     * <p>Tracked so that closing the client releases the <em>server's</em> side of them. A
     * subscription is a call that does not return: the server is parked holding Arrow buffers and a
     * listener attached to the query, and it learns that nobody is listening from a cancellation.
     * Dropping the transport without one leaves it attached, assembling batches for a client that
     * has gone -- which is a leak on the wrong machine, and the hardest kind to attribute.
     */
    private final java.util.Set<Subscription> subscriptions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The node this client was pointed at, as {@code host:port}.
     *
     * <p>Held only to name it in a failure. E-7: "io exception" is what a dead server used to say,
     * and on a machine talking to three nodes it does not say which one, which is the only thing
     * the operator needs.
     */
    private final String endpoint;

    /**
     * Whether {@link #close()} has run.
     *
     * <p>Volatile because a subscription callback runs on a Flight thread and may outlive the
     * thread that closed the client -- which is how a use-after-close happens in the first place.
     */
    private volatile boolean closed;

    private PravahaFlightClient(
            BufferAllocator allocator,
            boolean ownsAllocator,
            FlightClient transport,
            FlightSqlClient client,
            CallOption[] callOptions,
            String endpoint) {
        this.transport = transport;
        this.allocator = allocator;
        this.ownsAllocator = ownsAllocator;
        this.client = client;
        this.callOptions = callOptions;
        this.endpoint = endpoint;
    }

    /** Connects to {@code host:port}. */
    public static PravahaFlightClient connect(String connectionString) {
        return connect(ClientOptions.builder(connectionString).build());
    }

    /** Connects with the options an application has already configured. */
    public static PravahaFlightClient connect(ClientOptions options) {
        return connect(options, new RootAllocator(Long.MAX_VALUE), true);
    }

    /**
     * Connects on an allocator the caller owns.
     *
     * <p>For an application that already runs Arrow: sharing the allocator puts Pravaha's off-heap
     * usage in the accounting they already watch, rather than in a second pool nobody is looking at.
     */
    public static PravahaFlightClient connect(ClientOptions options, BufferAllocator allocator) {
        return connect(options, allocator, false);
    }

    private static PravahaFlightClient connect(
            ClientOptions options, BufferAllocator allocator, boolean ownsAllocator) {
        Endpoint.HostPort node = options.endpoint().nodes().get(0);
        try {
            Location location = options.endpoint().tls()
                    ? Location.forGrpcTls(node.host(), node.port())
                    : Location.forGrpcInsecure(node.host(), node.port());
            FlightClient transport = FlightClient.builder(allocator, location).build();
            return new PravahaFlightClient(
                    allocator,
                    ownsAllocator,
                    transport,
                    new FlightSqlClient(transport),
                    credentialsOf(options),
                    node.host() + ":" + node.port());
        } catch (RuntimeException e) {
            if (ownsAllocator) {
                allocator.close();
            }
            throw new PravahaClientException(
                    ClientErrors.CONNECT_FAILED,
                    "cannot connect to " + node.host() + ":" + node.port() + ": " + e.getMessage(),
                    // A server that is not up yet may be up shortly; a caller retrying this is not
                    // making a mistake.
                    true,
                    e);
        }
    }

    /**
     * The call options carrying the bearer token, or none when the client was configured without
     * one.
     *
     * <p>A missing token is not an error here. A server that requires authentication says so, with
     * PRV-7001, and that refusal is a better diagnosis than a client-side guess about whether this
     * particular server wanted a credential.
     */
    private static CallOption[] credentialsOf(ClientOptions options) {
        return options.token()
                .map(token -> new CallOption[] {
                    new HeaderCallOption(headersWith(token)),
                })
                .orElse(new CallOption[0]);
    }

    private static FlightCallHeaders headersWith(String token) {
        FlightCallHeaders headers = new FlightCallHeaders();
        headers.insert("authorization", "Bearer " + token);
        return headers;
    }

    /**
     * Runs one query and returns its rows.
     *
     * <p>The result must be closed, and the rows are valid only while iterating it -- both because
     * it streams rather than materialises.
     */
    public QueryResult query(String sql) {
        requireOpen();
        try {
            FlightInfo info = client.execute(sql, callOptions);
            return new QueryResult(client.getStream(info.getEndpoints().get(0).getTicket(), callOptions), this);
        } catch (FlightRuntimeException e) {
            // The server's own diagnosis *and its own code*, rather than a wrapper that keeps the
            // first and discards the second: "PRV-4023 ... this server serves [user_volume]" is
            // actionable, and a caller that wants to branch on it should not have to grep for it.
            // See ServerFailures.
            throw failureOf(e);
        }
    }

    /**
     * Runs a query with values bound to its {@code ?} placeholders (ADR-032).
     *
     * <p>Prefer this to building the SQL yourself, and not only because a value can never be read
     * as SQL this way. The server plans a statement once and reuses the plan, and two callers asking
     * the same question about different users send the same statement -- which is what lets them
     * share the planning work, and what makes a query recognisable in a log as one query rather than
     * a thousand.
     *
     * <p>The types are the server's: it reports what each placeholder needs when the statement is
     * prepared, so nothing here guesses. Any value may be {@code null}, though SQL's three-valued
     * logic means {@code WHERE x = ?} bound to null matches no rows; {@code IS NULL} is what finds
     * the empty ones.
     */
    public QueryResult query(String sql, Object... parameters) {
        if (parameters == null || parameters.length == 0) {
            return query(sql);
        }
        requireOpen();
        try (FlightSqlClient.PreparedStatement statement = client.prepare(sql, callOptions)) {
            try (VectorSchemaRoot bound = VectorSchemaRoot.create(statement.getParameterSchema(), allocator)) {
                Parameters.write(bound, parameters);
                statement.setParameters(bound);
                FlightInfo info = statement.execute(callOptions);
                return new QueryResult(
                        client.getStream(info.getEndpoints().get(0).getTicket(), callOptions), this);
            }
        } catch (FlightRuntimeException e) {
            throw failureOf(e);
        }
    }

    // -------------------------------------------------------------------------------------
    // Continuous queries: registering them, and subscribing to what they produce (ADR-025).
    // -------------------------------------------------------------------------------------

    /**
     * Registers a continuous query and returns what the server made of it.
     *
     * <p>A registration is not a request; it is a computation that keeps running and keeps a view
     * current until somebody drops it. Registering the same question twice -- even worded
     * differently -- gives one computation with two names, because the server matches on the
     * normalised plan rather than the text. The returned fingerprint is how you can tell.
     *
     * @param name the view name this query will maintain, and what SQL will read
     * @param keyColumns output column ordinals the view is keyed by
     */
    public RegisteredQueryInfo register(String name, String sql, List<Integer> keyColumns) {
        String ordinals = keyColumns.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        List<List<String>> results = act(ControlWire.REGISTER, name, sql, ordinals);
        if (results.isEmpty()) {
            throw new PravahaClientException(
                    ClientErrors.QUERY_REFUSED,
                    "the server accepted the registration but said nothing about it",
                    false);
        }
        List<String> row = results.get(0);
        return new RegisteredQueryInfo(field(row, 0), field(row, 1), sql, field(row, 2), 0);
    }

    /** Every continuous query this server is running. */
    public List<RegisteredQueryInfo> queries() {
        List<RegisteredQueryInfo> queries = new java.util.ArrayList<>();
        for (List<String> row : act(ControlWire.LIST)) {
            long rowsIn = 0;
            try {
                rowsIn = Long.parseLong(field(row, 4));
            } catch (NumberFormatException e) {
                // An older server that does not report it. Not worth failing a listing over.
            }
            queries.add(new RegisteredQueryInfo(field(row, 0), field(row, 1), field(row, 2), field(row, 3), rowsIn));
        }
        return queries;
    }

    /** Stops a query without releasing it; its view keeps answering at the frontier it reached. */
    public void pause(String name) {
        act(ControlWire.PAUSE, name);
    }

    public void resume(String name) {
        act(ControlWire.RESUME, name);
    }

    /**
     * Removes a name.
     *
     * <p>The computation goes when its <em>last</em> name goes. If somebody else registered the same
     * question, dropping yours leaves theirs running -- which is the point: neither of you knows the
     * other exists.
     */
    public void drop(String name) {
        act(ControlWire.DROP, name);
    }

    /**
     * Watches a registered query, receiving changes as they are committed.
     *
     * <p>Blocks the calling thread until the returned handle is closed or the query ends, so run it
     * on a thread you are willing to park. Changes arrive per commit, never per row.
     *
     * <p><strong>Rows are valid only inside the callback.</strong> They are flyweights over the
     * Arrow batch that carried them, and that batch is reused for the next commit. Copy anything you
     * intend to keep -- the same rule as {@link QueryResult}, and the reason both are fast.
     *
     * @param filters column/value pairs applied at the tap, so rows you did not ask for never cross
     *     the network. Equality only. A column the view does not have is refused rather than ignored
     */
    public Subscription subscribe(String view, Map<String, String> filters, Consumer<ChangeBatch> onBatch) {
        List<String> pairs = new java.util.ArrayList<>();
        filters.forEach((column, value) -> {
            pairs.add(column);
            pairs.add(value);
        });
        requireOpen();
        FlightStream stream = client.getStream(new Ticket(ControlWire.subscribeTicket(view, pairs)), callOptions);
        Subscription subscription = new Subscription(stream, onBatch, subscriptions::remove);
        subscriptions.add(subscription);
        return subscription;
    }

    /** Watches every row a registered query produces. */
    public Subscription subscribe(String view, Consumer<ChangeBatch> onBatch) {
        return subscribe(view, Map.of(), onBatch);
    }

    private List<List<String>> act(String type, String... fields) {
        requireOpen();
        List<List<String>> results = new java.util.ArrayList<>();
        try {
            transport
                    .doAction(new Action(type, ControlWire.encode(fields)), callOptions)
                    .forEachRemaining(result -> results.add(ControlWire.decode(result.getBody())));
        } catch (FlightRuntimeException e) {
            throw failureOf(e);
        }
        return results;
    }

    /**
     * The Pravaha failure a Flight failure on this connection means.
     *
     * <p>Shared with {@link QueryResult}, so that a server error part way through reading a result
     * is reported exactly as one raised when the call was made -- a caller should not need two
     * handlers for the same refusal arriving at two moments.
     *
     * <p>The closed check comes first and is the reason this is a method on the client rather than a
     * call to {@link ServerFailures} everywhere: gRPC reports a call on a shut-down channel as
     * UNAVAILABLE, indistinguishable from an unreachable server, and only the client knows which it
     * was.
     */
    PravahaClientException failureOf(FlightRuntimeException e) {
        return failureOf(e, ClientErrors.QUERY_REFUSED);
    }

    /** The same, for a caller whose "something else went wrong" code is not QUERY_REFUSED. */
    PravahaClientException failureOf(FlightRuntimeException e, ErrorCode fallback) {
        if (closed) {
            return new PravahaClientException(
                    ClientErrors.CLOSED,
                    "the client that opened this result has been closed; read the rows you need before "
                            + "closing it, or keep it open for as long as the result is in use",
                    false,
                    e);
        }
        return ServerFailures.of(e, endpoint, fallback);
    }

    /**
     * Refuses a call on a client that has been closed.
     *
     * <p>E-7, the second half. gRPC answers a call on a shut-down channel with {@code UNAVAILABLE}
     * and the text "Channel shutdown invoked" -- indistinguishable, from the outside, from a server
     * that is down. Without this guard the connection-failure branch would report a closed client as
     * a retryable {@code PRV-1040}, advising a retry that cannot ever succeed; with it,
     * {@code PRV-1040} keeps one meaning and {@code PRV-1043 CLIENT_CLOSED} -- declared since the
     * SDK was written and never thrown (ERRC-017) -- finally has the site it was declared for.
     */
    private void requireOpen() {
        if (closed) {
            throw new PravahaClientException(
                    ClientErrors.CLOSED,
                    "this client was closed; open another with PravahaFlightClient.connect(...)",
                    false);
        }
    }

    private static String field(List<String> row, int index) {
        return index < row.size() ? row.get(index) : "";
    }

    /**
     * Closes the connection.
     *
     * <p>Teardown noise is not reported as failure. Closing a transport with a finished stream on
     * it produces a cancellation -- {@code RST_STREAM ... CANCEL} -- from gRPC's point of view, and
     * turning that into an exception meant a command that had already done its work and printed its
     * answer then exited non-zero. A caller cannot act on it, and a script cannot tell it apart from
     * the query having failed.
     *
     * <p>The allocator is closed either way, in a finally, because a leak there is a real problem
     * and would otherwise be masked by whatever the transport said on the way out.
     */
    @Override
    public void close() {
        closed = true;
        // Subscriptions first, and this ordering is the point. Each one is cancelled so the server
        // detaches its listener and releases its buffers; dropping the transport underneath them
        // instead leaves the server holding both.
        for (Subscription subscription : java.util.List.copyOf(subscriptions)) {
            subscription.close();
        }
        subscriptions.clear();
        try {
            client.close();
        } catch (Exception e) {
            // Deliberately not rethrown; see above. Anything genuinely wrong with this connection
            // has already shown up as a failed call.
        } finally {
            if (ownsAllocator) {
                allocator.close();
            }
        }
    }
}
