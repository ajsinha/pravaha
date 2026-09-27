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

import java.io.InputStream;
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
import com.ash.messaging.pravaha.sdk.TlsOptions;

/**
 * Asking Pravaha a question from Java.
 *
 * <pre>{@code
 * try (PravahaFlightClient client = PravahaFlightClient.connect("localhost:19090");
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

    /**
     * What this client's options ask its server-side subscription buffer to do (STRM-16).
     *
     * <p>{@code ClientOptions.subscriberBufferRows} and {@code conflateOnOverflow} had <strong>no
     * reader</strong> anywhere in this SDK or in the gateway: a subscription ticket carried a view
     * name and filter pairs and nothing else, so every remote subscriber was
     * {@code (10 000, CONFLATE)} whatever it set, and a client that called
     * {@code subscriberBufferRows(1)} had configured a setting with no reachable effect. The two
     * options are not new; carrying them is.
     *
     * <p>{@code conflateOnOverflow(false)} means {@code FAIL}, not {@code DROP_OLDEST}. A client
     * that says "do not conflate" is one keeping its own total from the weights, and dropping the
     * oldest change corrupts that total in exactly the way conflating it does; being told is the
     * whole difference.
     */
    private final com.ash.messaging.pravaha.api.wire.ControlWire.SubscriberPreference preference;

    private PravahaFlightClient(
            BufferAllocator allocator,
            boolean ownsAllocator,
            FlightClient transport,
            FlightSqlClient client,
            CallOption[] callOptions,
            String endpoint,
            com.ash.messaging.pravaha.api.wire.ControlWire.SubscriberPreference preference) {
        this.transport = transport;
        this.allocator = allocator;
        this.ownsAllocator = ownsAllocator;
        this.client = client;
        this.callOptions = callOptions;
        this.endpoint = endpoint;
        this.preference = preference;
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
            FlightClient.Builder builder = FlightClient.builder(allocator, location);
            if (options.endpoint().tls()) {
                applyTls(builder, options.tls());
            }
            FlightClient transport = builder.build();
            return new PravahaFlightClient(
                    allocator,
                    ownsAllocator,
                    transport,
                    new FlightSqlClient(transport),
                    credentialsOf(options),
                    node.host() + ":" + node.port(),
                    new com.ash.messaging.pravaha.api.wire.ControlWire.SubscriberPreference(
                            options.subscriberBufferRows(), options.conflateOnOverflow() ? "CONFLATE" : "FAIL"));
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
     * Applies {@link TlsOptions} to a Flight client builder, bridging a keystore to the PEM bytes
     * the builder actually accepts when one was configured (see {@link KeystoreMaterial}).
     *
     * <p>Called only when {@code options.endpoint().tls()} -- TLS options on a plaintext endpoint are
     * refused earlier, in {@link ClientOptions.Builder#build()}, so by the time this runs the two
     * agree.
     */
    private static void applyTls(FlightClient.Builder builder, TlsOptions tls) {
        try {
            if (tls.caCertificate().isPresent()) {
                builder.trustedCertificates(
                        java.nio.file.Files.newInputStream(tls.caCertificate().get()));
            } else if (tls.trustStore().isPresent()) {
                builder.trustedCertificates(KeystoreMaterial.trustedCertificatesPem(
                        tls.trustStore().get(), tls.trustStorePassword().orElse(null), tls.trustStoreType()));
            }
            if (tls.clientCertificate().isPresent()) {
                // clientKey() is guaranteed present too: TlsOptions.Builder.build() refuses one
                // without the other.
                builder.clientCertificate(
                        java.nio.file.Files.newInputStream(
                                tls.clientCertificate().get()),
                        java.nio.file.Files.newInputStream(tls.clientKey().get()));
            } else if (tls.keyStore().isPresent()) {
                InputStream[] certAndKey = KeystoreMaterial.clientCertificateAndKeyPem(
                        tls.keyStore().get(), tls.keyStorePassword().orElse(null), tls.keyStoreType());
                builder.clientCertificate(certAndKey[0], certAndKey[1]);
            }
        } catch (java.io.IOException e) {
            throw new PravahaClientException(
                    ClientErrors.TLS_UNREADABLE, "cannot read TLS material: " + e.getMessage(), false, e);
        }
        if (!tls.verifyHostname()) {
            // Arrow's own name for "disable hostname/server verification". A separate, explicit
            // TlsOptions method had to be called to reach this branch; nothing here would flip it
            // on its own.
            builder.verifyServer(false);
        }
        tls.overrideHostname().ifPresent(builder::overrideHostname);
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
            // Refused here, not sent: protobuf would put '?' where a lone surrogate was.
            ControlWire.requireWellFormed(sql, "the SQL");
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
        ControlWire.requireWellFormed(sql, "the SQL");
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
        return register(name, sql, keyColumns, null);
    }

    /**
     * Registers a continuous query that also writes its changelog to a sink the server has bound
     * under {@code pravaha.sinks.<sink>} (ADR-043).
     *
     * <p>The server refuses the pair before anything runs when the query revises its answer and the
     * sink can only append ({@code PRV-2041}), and refuses a name it has no binding for. Once
     * registered, the sink receives every committed change, retractions included, at least once.
     *
     * @param sink the sink's binding name on the server, or null to write only the view
     */
    public RegisteredQueryInfo register(String name, String sql, List<Integer> keyColumns, String sink) {
        return register(name, sql, keyColumns, sink, null);
    }

    /**
     * Registers with the view's retention chosen, as well as an optional sink.
     *
     * @param retention how much event time the view keeps, as ISO-8601 ({@code PT24H}, {@code P7D}) or
     *     {@code forever}; null for the server's default. A server that cannot read it refuses the
     *     registration rather than keeping a different amount than you asked for
     */
    public RegisteredQueryInfo register(
            String name, String sql, List<Integer> keyColumns, String sink, String retention) {
        String ordinals = keyColumns.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        boolean hasSink = sink != null && !sink.isBlank();
        boolean hasRetention = retention != null && !retention.isBlank();
        // Trailing fields are optional on the wire, so only what is set is sent: a server that
        // predates retention answers a four-field registration exactly as it always did.
        List<List<String>> results;
        if (hasRetention) {
            results = act(ControlWire.REGISTER, name, sql, ordinals, hasSink ? sink : "", retention.strip());
        } else if (hasSink) {
            results = act(ControlWire.REGISTER, name, sql, ordinals, sink);
        } else {
            results = act(ControlWire.REGISTER, name, sql, ordinals);
        }
        if (results.isEmpty()) {
            throw new PravahaClientException(
                    ClientErrors.QUERY_REFUSED,
                    "the server accepted the registration but said nothing about it",
                    false);
        }
        List<String> row = results.get(0);
        return new RegisteredQueryInfo(field(row, 0), field(row, 1), sql, field(row, 2), 0);
    }

    /** A {@link ControlWire#LIST} row's field by its name (WIRE-1); empty when an older server did not send it. */
    private static String listed(List<String> row, String name) {
        return field(row, ControlWire.listField(name));
    }

    /** Every continuous query this server is running. */
    public List<RegisteredQueryInfo> queries() {
        List<RegisteredQueryInfo> queries = new java.util.ArrayList<>();
        for (List<String> row : act(ControlWire.LIST)) {
            long rowsIn = 0;
            try {
                rowsIn = Long.parseLong(listed(row, "rows_in"));
            } catch (NumberFormatException e) {
                // An older server that does not report it. Not worth failing a listing over.
            }
            // Fields 5-7 are trailing additions; a server that predates them sends five and these read
            // as empty, which is what "unknown" means here.
            String sink = listed(row, "sink");
            String retention = listed(row, "retention");
            // 8-12 are the feed (FEED-1): its state, and the first stopped source's code, message,
            // stream#partition and time. Empty from a server that predates them, read as unknown.
            String feed = listed(row, "feed_state");
            String code = listed(row, "feed_code");
            // 13-15 are the sink's own state (SINK-3): ATTACHED, DETACHED or NONE, and the code and
            // message it was detached with. Empty from a server that predates them.
            String sinkState = listed(row, "sink_state");
            String sinkCode = listed(row, "sink_code");
            queries.add(new RegisteredQueryInfo(
                    listed(row, "name"),
                    listed(row, "state"),
                    listed(row, "sql"),
                    listed(row, "fingerprint"),
                    rowsIn,
                    ordinalsOf(listed(row, "key_ordinals")),
                    sink.isEmpty() ? null : sink,
                    retention.isEmpty() ? null : retention,
                    feed.isEmpty() ? null : feed,
                    code.isEmpty()
                            ? null
                            : new RegisteredQueryInfo.FeedStop(
                                    code,
                                    listed(row, "feed_message"),
                                    listed(row, "feed_where"),
                                    listed(row, "feed_at")),
                    sinkState.isEmpty() ? null : sinkState,
                    sinkCode.isEmpty()
                            ? null
                            : new RegisteredQueryInfo.SinkFailure(sinkCode, listed(row, "sink_message"))));
        }
        return queries;
    }

    // -------------------------------------------------------------------------------------
    // Blue/green replacement (ADR-046): a new version beside the running one, backfilled, cut
    // over to at a position both have consumed exactly, and rolled back from while it is retained.
    // -------------------------------------------------------------------------------------

    /**
     * Starts replacing {@code name} with a new version, and returns where that has got to.
     *
     * <p>The name goes on answering the version it answers now. What this starts is a shadow: it
     * reads the same sources from the beginning, splices onto the live stream at the position the
     * running version has reached, and is compared with it. {@link #cutOver} is what moves the
     * name, and only when the two have consumed exactly the same input.
     *
     * <p>Requires the administer permission on the name, as dropping it does.
     *
     * @param options {@code backfill} ({@code history} or {@code none}), {@code
     *     backfill.rate.limit}, {@code cutover} ({@code manual} or {@code auto}) and {@code
     *     rollback.retention}, or null for the defaults. An option the server does not build is
     *     refused by name rather than ignored
     */
    public ReplacementInfo replace(String name, String sql, List<Integer> keyColumns, String options) {
        String ordinals = keyColumns.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        List<List<String>> results = options == null || options.isBlank()
                ? act(ControlWire.REPLACE, name, sql, ordinals)
                : act(ControlWire.REPLACE, name, sql, ordinals, options);
        return one(results, name);
    }

    /** {@link #replace(String, String, List, String)} with the server's default options. */
    public ReplacementInfo replace(String name, String sql, List<Integer> keyColumns) {
        return replace(name, sql, keyColumns, null);
    }

    /** How the replacement of {@code name} is getting on, or empty when there is not one. */
    public java.util.Optional<ReplacementInfo> replacement(String name) {
        List<List<String>> results = act(ControlWire.REPLACEMENT, name);
        return results.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(ReplacementInfo.of(results.get(0)));
    }

    /** Every replacement this server knows about, in flight or finished. */
    public List<ReplacementInfo> replacements() {
        List<ReplacementInfo> all = new java.util.ArrayList<>();
        for (List<String> row : act(ControlWire.REPLACEMENT)) {
            all.add(ReplacementInfo.of(row));
        }
        return all;
    }

    /**
     * Moves the name to the new version.
     *
     * <p>Refused with {@code PRV-4014} when the new version has not caught up, or when the two
     * cannot be brought to the same position in their input: a cutover at different positions
     * would leave the records between them in neither version's output, or in both.
     */
    public ReplacementInfo cutOver(String name) {
        return one(act(ControlWire.CUTOVER, name), name);
    }

    /** Puts the replaced version back, while it is still retained. */
    public ReplacementInfo rollBack(String name) {
        return one(act(ControlWire.ROLLBACK, name), name);
    }

    /** Ends a replacement that has not cut over, releasing the candidate. */
    public ReplacementInfo abandonReplacement(String name) {
        return one(act(ControlWire.ABANDON, name), name);
    }

    /** Confirms a cutover: the replaced version is released, and there is no rollback after this. */
    public ReplacementInfo finishReplacement(String name) {
        return one(act(ControlWire.FINISH, name), name);
    }

    /**
     * Sets how fast the backfill reads history, up to the ceiling the replacement was started with.
     *
     * <p>A ceiling, not a suggestion: above it the server refuses rather than quietly reading
     * faster than an operator allowed.
     */
    public ReplacementInfo throttleBackfill(String name, long recordsPerSecond) {
        return one(act(ControlWire.BACKFILL, name, "throttle", Long.toString(recordsPerSecond)), name);
    }

    /** Stops the backfill reading, without giving up what it has read. */
    public ReplacementInfo pauseBackfill(String name) {
        return one(act(ControlWire.BACKFILL, name, "pause"), name);
    }

    public ReplacementInfo resumeBackfill(String name) {
        return one(act(ControlWire.BACKFILL, name, "resume"), name);
    }

    private static ReplacementInfo one(List<List<String>> results, String name) {
        if (results.isEmpty()) {
            throw new PravahaClientException(
                    ClientErrors.QUERY_REFUSED,
                    "the server accepted the request but said nothing about the replacement of '" + name + "'",
                    false);
        }
        return ReplacementInfo.of(results.get(0));
    }

    /**
     * A page of the records this query's feed could not decode, newest first (B5).
     *
     * <p>Newest first and no other order offered: a queue is read because something has just
     * started failing, and the entries that answer "what is happening now" are at the end of the
     * file.
     *
     * <p>An entry's record may be withheld -- see {@link DeadLetterInfo#isWithheld()} -- when this
     * caller reads the view through a row filter. The count, the offset and the code are not.
     *
     * @param offset how many of the newest to skip
     * @param limit how many to return; the server clamps it
     */
    public DeadLetterPageInfo deadLetters(String name, int offset, int limit) {
        List<DeadLetterInfo> entries = new java.util.ArrayList<>();
        DeadLetterPageInfo totals = null;
        for (List<String> row : act(ControlWire.DLQ_LIST, name, Integer.toString(offset), Integer.toString(limit))) {
            // A '#' in the first field is the trailer carrying the queue's totals. It cannot be an
            // id, which is a UUID, so a client tells the two apart without being told how many
            // entries to expect.
            if ("#".equals(field(row, 0))) {
                totals = new DeadLetterPageInfo(
                        name,
                        entries,
                        offset,
                        number(row, 1),
                        number(row, 2),
                        number(row, 3),
                        number(row, 4),
                        number(row, 5),
                        number(row, 6),
                        field(row, 7),
                        Boolean.parseBoolean(field(row, 8)));
                continue;
            }
            entries.add(deadLetterOf(row));
        }
        // A server that predates the trailer sends entries and nothing else; the page is still a
        // page, with the totals it could not report left at what the page itself shows.
        return totals != null
                ? totals
                : new DeadLetterPageInfo(
                        name, entries, offset, offset + entries.size(), 0, 0, 0, 0, 0, "unknown", true);
    }

    /** The newest fifty. */
    public DeadLetterPageInfo deadLetters(String name) {
        return deadLetters(name, 0, 50);
    }

    /**
     * One dead letter whole, by its id.
     *
     * @throws PravahaClientException {@code PRV-4091} when no entry with that id is in the queue
     */
    public DeadLetterInfo deadLetter(String name, String id) {
        List<List<String>> results = act(ControlWire.DLQ_SHOW, name, id);
        if (results.isEmpty()) {
            throw new IllegalStateException("the server answered pravaha.dlq.show with no result");
        }
        return deadLetterOf(results.get(0));
    }

    /**
     * Feeds chosen dead letters back through the query that rejected them.
     *
     * <p><strong>A new row at the current frontier, not a rewind.</strong> Nothing is re-read, no
     * offset moves, and no earlier result is recomputed. A record that fails to decode again goes
     * back on the queue as a fresh entry -- named in {@link DeadLetterReplayInfo#newId()} -- and
     * is not retried, so a caller walking a queue moves forwards through it.
     *
     * <p>Not idempotent: replaying the same id twice puts the row in twice.
     *
     * @throws PravahaClientException {@code PRV-7002} when this caller may not administer the
     *     view, {@code PRV-4091} when an id is not in the queue, {@code PRV-4092} when replaying
     *     one could not be correct
     */
    public List<DeadLetterReplayInfo> replayDeadLetters(String name, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("say which dead letters to replay; replaying a whole queue by "
                    + "omission is not offered, because a queue is usually a mix of causes");
        }
        String[] fields = new String[ids.size() + 1];
        fields[0] = name;
        for (int i = 0; i < ids.size(); i++) {
            fields[i + 1] = ids.get(i);
        }
        List<DeadLetterReplayInfo> replayed = new java.util.ArrayList<>(ids.size());
        for (List<String> row : act(ControlWire.DLQ_REPLAY, fields)) {
            replayed.add(new DeadLetterReplayInfo(field(row, 0), field(row, 1), field(row, 2), field(row, 3)));
        }
        return replayed;
    }

    /** Feeds one dead letter back through the query. */
    public DeadLetterReplayInfo replayDeadLetter(String name, String id) {
        return replayDeadLetters(name, List.of(id)).get(0);
    }

    private static DeadLetterInfo deadLetterOf(List<String> row) {
        String at = field(row, 6);
        String replayedAt = field(row, 11);
        byte[] raw;
        try {
            raw = java.util.Base64.getDecoder().decode(field(row, 8));
        } catch (IllegalArgumentException notBase64) {
            raw = new byte[0];
        }
        return new DeadLetterInfo(
                field(row, 0),
                number(row, 1),
                field(row, 2),
                field(row, 3),
                field(row, 4),
                field(row, 5),
                at.isEmpty() ? null : java.time.Instant.parse(at),
                (int) number(row, 7),
                raw,
                field(row, 9),
                field(row, 10),
                replayedAt.isEmpty() ? null : java.time.Instant.parse(replayedAt));
    }

    /** A numeric field, or zero from a server that did not send one. */
    private static long number(List<String> row, int at) {
        try {
            String value = field(row, at);
            return value.isEmpty() ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
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
        return open(ControlWire.subscribeTicket(view, pairs(filters), preference), onBatch);
    }

    /**
     * Watches a registered query from its current state: the first batch is the view's snapshot,
     * and every batch after it is a commit after that snapshot, with nothing between (SUB-1).
     *
     * <p>The way to keep a copy of a view. {@link #subscribe} starts at the next commit and carries
     * no state, so reading the view beside it -- before or after -- can miss the commit in flight at
     * the time, silently. Here the first {@link ChangeBatch} has {@link ChangeBatch#isSnapshot()} set
     * and holds every row of the view at a commit (filtered, each with its multiplicity as its
     * weight, and delivered even when empty); apply it, then every later batch by weight.
     *
     * <p>The rows of the snapshot batch are copies and may be kept; rows of later batches are
     * flyweights, as on {@link #subscribe}. A subscriber that falls too far behind has its stream
     * ended with {@code PRV-6105} rather than skipped past a commit: subscribe again and start from a
     * fresh snapshot. A server older than this SDK refuses the subscription with {@code PRV-6102}.
     */
    public Subscription subscribeFromSnapshot(String view, Map<String, String> filters, Consumer<ChangeBatch> onBatch) {
        return open(ControlWire.subscribeFromSnapshotTicket(view, pairs(filters)), onBatch);
    }

    /** {@link #subscribeFromSnapshot(String, Map, Consumer)} with no filter. */
    public Subscription subscribeFromSnapshot(String view, Consumer<ChangeBatch> onBatch) {
        return subscribeFromSnapshot(view, Map.of(), onBatch);
    }

    private static List<String> pairs(Map<String, String> filters) {
        List<String> pairs = new java.util.ArrayList<>();
        filters.forEach((column, value) -> {
            pairs.add(column);
            pairs.add(value);
        });
        return pairs;
    }

    private Subscription open(byte[] ticket, Consumer<ChangeBatch> onBatch) {
        requireOpen();
        FlightStream stream = client.getStream(new Ticket(ticket), callOptions);
        // API-F7: the same mapper every other call on this connection uses. A subscription is a
        // result that arrives over time, so its fallback is READ_FAILED rather than QUERY_REFUSED,
        // matching QueryResult; a server that diagnosed the failure is reported under its own code
        // either way, and a node that is not there is PRV-1040 naming the address.
        Subscription subscription =
                new Subscription(stream, onBatch, subscriptions::remove, e -> failureOf(e, ClientErrors.READ_FAILED));
        subscriptions.add(subscription);
        return subscription;
    }

    /** Watches every row a registered query produces. */
    public Subscription subscribe(String view, Consumer<ChangeBatch> onBatch) {
        return subscribe(view, Map.of(), onBatch);
    }

    // ------------------------------------------------------------------ the debugger (ADR-048)

    /**
     * Forks a debug session from a query's newest retained checkpoint.
     *
     * <p>A second copy of the query, restored from that checkpoint, reading the same sources from
     * the offsets it recorded, with every sink disabled and nothing able to read its view. It costs
     * what the query costs, so it is bounded and it expires; end it when the incident is understood.
     */
    public DebugSessionInfo debugFork(String query) {
        return debugFork(query, null);
    }

    /** Forks from a particular checkpoint. {@link #debugCheckpoints} says which there are. */
    public DebugSessionInfo debugFork(String query, Long checkpointId) {
        return oneSession(act(ControlWire.DEBUG_FORK, query, checkpointId == null ? "" : checkpointId.toString()));
    }

    /** Which checkpoints of {@code query} a session could be forked from, newest first. */
    public List<Long> debugCheckpoints(String query) {
        List<List<String>> results = act(ControlWire.DEBUG_CHECKPOINTS, query);
        List<Long> ids = new java.util.ArrayList<>();
        if (!results.isEmpty()) {
            // Field 0 is the query's name, so the ids start at 1.
            for (int index = 1; index < results.get(0).size(); index++) {
                ids.add(Wire.number(results.get(0), index));
            }
        }
        return ids;
    }

    /**
     * Advances a session and reports what changed.
     *
     * @param step {@code row}, {@code rows:N}, {@code commit}, {@code watermark:<nanos>} or {@code
     *     until:<column>:<op>:<value>}; a blank one is {@code row}
     */
    public DebugStepReport debugStep(String sessionId, String step) {
        List<List<String>> results = act(ControlWire.DEBUG_STEP, sessionId, step == null ? "row" : step);
        if (results.isEmpty()) {
            throw new PravahaClientException(
                    ClientErrors.QUERY_REFUSED,
                    "the server accepted the step but said nothing about what it did",
                    false);
        }
        return DebugStepReport.of(results.get(0));
    }

    /** Every debug session on the server that this caller may administer. */
    public List<DebugSessionInfo> debugSessions() {
        List<DebugSessionInfo> open = new java.util.ArrayList<>();
        act(ControlWire.DEBUG_SESSION, "").forEach(row -> open.add(DebugSessionInfo.of(row)));
        return open;
    }

    /** One session, or empty if the server does not know it. */
    public java.util.Optional<DebugSessionInfo> debugSession(String sessionId) {
        List<List<String>> results = act(ControlWire.DEBUG_SESSION, sessionId);
        return results.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(DebugSessionInfo.of(results.get(0)));
    }

    /** What state a session's fork holds: the operator, its kind, and how many entries. */
    public List<DebugStateSlot> debugState(String sessionId) {
        List<DebugStateSlot> slots = new java.util.ArrayList<>();
        act(ControlWire.DEBUG_STATE, sessionId)
                .forEach(row -> slots.add(new DebugStateSlot(
                        Wire.text(row, 0), Wire.text(row, 1), Wire.text(row, 2), Wire.number(row, 3))));
        return slots;
    }

    /** One piece of inspectable state, as {@link #debugState} reports it. */
    public record DebugStateSlot(String id, String kind, String label, long entries) {}

    /** One page of one operator's state, read without changing it. */
    public DebugStatePage debugInspect(String sessionId, String operator, String key, int offset, int limit) {
        List<List<String>> results = act(
                ControlWire.DEBUG_INSPECT,
                sessionId,
                operator,
                key == null ? "" : key,
                Integer.toString(offset),
                Integer.toString(limit));
        if (results.isEmpty()) {
            throw new PravahaClientException(
                    ClientErrors.QUERY_REFUSED, "the server answered no page for '" + operator + "'", false);
        }
        return DebugStatePage.of(results.get(0));
    }

    /** The fork's own view, which nothing outside the session can read. */
    public List<DebugStepReport.ViewDelta> debugView(String sessionId) {
        List<DebugStepReport.ViewDelta> rows = new java.util.ArrayList<>();
        act(ControlWire.DEBUG_VIEW, sessionId).forEach(row -> {
            List<String> values = new java.util.ArrayList<>(row.subList(Math.min(1, row.size()), row.size()));
            rows.add(new DebugStepReport.ViewDelta(Wire.number(row, 0), values));
        });
        return rows;
    }

    /**
     * Writes the session out as a JUnit test, returning the generated source.
     *
     * @param name what to call it, turned into a class name: "bob goes negative" becomes
     *     {@code BobGoesNegativeFixtureTest}
     * @return the class name, the path it belongs at, and the source
     */
    public DebugFixture debugExport(String sessionId, String name) {
        List<List<String>> results = act(ControlWire.DEBUG_EXPORT, sessionId, name);
        if (results.isEmpty()) {
            throw new PravahaClientException(
                    ClientErrors.QUERY_REFUSED, "the server accepted the export and returned no fixture", false);
        }
        List<String> row = results.get(0);
        return new DebugFixture(Wire.text(row, 0), Wire.text(row, 1), Wire.text(row, 2));
    }

    /** A generated JUnit fixture: what to call the file, where it goes, and what is in it. */
    public record DebugFixture(String className, String path, String source) {}

    /** Ends a session and releases its fork. */
    public void debugEnd(String sessionId) {
        act(ControlWire.DEBUG_END, sessionId);
    }

    private static DebugSessionInfo oneSession(List<List<String>> results) {
        if (results.isEmpty()) {
            throw new PravahaClientException(
                    ClientErrors.QUERY_REFUSED,
                    "the server accepted the fork and said nothing about the session it opened",
                    false);
        }
        return DebugSessionInfo.of(results.get(0));
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

    private static List<Integer> ordinalsOf(String text) {
        List<Integer> ordinals = new java.util.ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                try {
                    ordinals.add(Integer.parseInt(part.strip()));
                } catch (NumberFormatException e) {
                    // Not a field this client understands; an empty key is "unknown", not wrong.
                    return List.of();
                }
            }
        }
        return ordinals;
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
