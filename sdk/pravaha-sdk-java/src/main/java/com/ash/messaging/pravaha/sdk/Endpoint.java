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
package com.ash.messaging.pravaha.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * Where a client connects.
 *
 * <p>Parsed and validated up front rather than on first use. A typo in a connection string should
 * fail when the client is constructed, next to the code that supplied it -- not fifteen minutes
 * later inside a request, where the stack trace points somewhere unhelpful.
 *
 * <p>Accepted forms:
 *
 * <pre>
 *   grpc://host:9090              plaintext
 *   grpc+tls://host:9090          TLS
 *   grpc+tls://h1:9090,h2:9090    several nodes; the client picks and fails over
 *   host:9090                     scheme omitted, TLS assumed
 * </pre>
 */
public final class Endpoint {

    /** Default gRPC port, matching the design's gateway configuration. */
    public static final int DEFAULT_PORT = 9090;

    private static final ErrorCode MALFORMED = new ErrorCode(1030, "CLIENT_MALFORMED_ENDPOINT");

    private final List<HostPort> nodes;
    private final boolean tls;

    private Endpoint(List<HostPort> nodes, boolean tls) {
        this.nodes = List.copyOf(nodes);
        this.tls = tls;
    }

    /** One host and port. */
    public record HostPort(String host, int port) {
        public HostPort {
            Objects.requireNonNull(host, "host");
            if (host.isBlank()) {
                throw malformed(host + ":" + port, "the host is blank");
            }
            if (port < 1 || port > 65535) {
                throw malformed(host + ":" + port, "port " + port + " is outside 1-65535");
            }
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    /**
     * Parses a connection string.
     *
     * @throws PravahaClientException if it is malformed. The message quotes the input and lists the
     *     accepted forms, because a connection string is usually wrong by one character.
     */
    public static Endpoint parse(String connectionString) {
        Objects.requireNonNull(connectionString, "connectionString");
        String raw = connectionString.strip();
        if (raw.isEmpty()) {
            throw malformed(connectionString, "it is empty");
        }

        boolean tls = true;
        String remainder = raw;
        int schemeEnd = raw.indexOf("://");
        if (schemeEnd >= 0) {
            String scheme = raw.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            tls = switch (scheme) {
                case "grpc+tls", "grpcs", "https" -> true;
                case "grpc", "http" -> false;
                default -> throw malformed(connectionString, "'" + scheme + "' is not a known scheme");
            };
            remainder = raw.substring(schemeEnd + 3);
        }
        if (remainder.isBlank()) {
            throw malformed(connectionString, "it names no host");
        }

        List<HostPort> nodes = new ArrayList<>();
        for (String part : remainder.split(",")) {
            nodes.add(parseHostPort(connectionString, part.strip()));
        }
        return new Endpoint(nodes, tls);
    }

    private static HostPort parseHostPort(String original, String text) {
        if (text.isEmpty()) {
            throw malformed(original, "it contains an empty host entry");
        }
        int colon = text.lastIndexOf(':');
        if (colon < 0) {
            return new HostPort(text, DEFAULT_PORT);
        }
        String host = text.substring(0, colon);
        String portText = text.substring(colon + 1);
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw malformed(original, "'" + portText + "' is not a port number");
        }
        // Re-thrown against the original string so the message quotes what the caller actually
        // wrote, not the fragment this method happens to be looking at.
        if (port < 1 || port > 65535) {
            throw malformed(original, "port " + port + " is outside 1-65535");
        }
        return new HostPort(host, port);
    }

    private static PravahaClientException malformed(String input, String why) {
        return new PravahaClientException(
                MALFORMED,
                "cannot parse endpoint '" + input + "': " + why
                        + ". Expected grpc://host:port, grpc+tls://host:port, or a comma-separated list.",
                false);
    }

    /** The nodes to connect to, in the order given. */
    public List<HostPort> nodes() {
        return nodes;
    }

    public boolean tls() {
        return tls;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Endpoint other && tls == other.tls && nodes.equals(other.nodes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, tls);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(tls ? "grpc+tls://" : "grpc://");
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(nodes.get(i));
        }
        return sb.toString();
    }
}
