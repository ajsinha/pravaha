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

import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightCallHeaders;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;

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
     * The credential, sent as a header on every call.
     *
     * <p>Held rather than sent once at connect time because Flight has no session: each call is
     * authenticated on its own, which is what lets a server behind a load balancer answer without
     * the balancer having to pin a client to a node.
     */
    private final CallOption[] callOptions;

    private PravahaFlightClient(
            BufferAllocator allocator, boolean ownsAllocator, FlightSqlClient client, CallOption[] callOptions) {
        this.allocator = allocator;
        this.ownsAllocator = ownsAllocator;
        this.client = client;
        this.callOptions = callOptions;
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
            return new PravahaFlightClient(
                    allocator,
                    ownsAllocator,
                    new FlightSqlClient(
                            FlightClient.builder(allocator, location).build()),
                    credentialsOf(options));
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
        try {
            FlightInfo info = client.execute(sql, callOptions);
            return new QueryResult(client.getStream(info.getEndpoints().get(0).getTicket(), callOptions));
        } catch (FlightRuntimeException e) {
            throw new PravahaClientException(
                    ClientErrors.QUERY_REFUSED,
                    // The server's own diagnosis, PRV code and all, rather than a wrapper that hides
                    // it: "PRV-4023 ... this server serves [user_volume]" is actionable and "query
                    // failed" is not.
                    e.status().description() == null
                            ? e.getMessage()
                            : e.status().description(),
                    false,
                    e);
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            throw new PravahaClientException(
                    ClientErrors.CLOSED, "cannot close the client: " + e.getMessage(), false, e);
        } finally {
            if (ownsAllocator) {
                allocator.close();
            }
        }
    }
}
