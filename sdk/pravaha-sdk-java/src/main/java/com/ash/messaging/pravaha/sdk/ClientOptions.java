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

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * How a client connects and behaves.
 *
 * <p>Immutable and validated at construction. Defaults are chosen so that the zero-configuration
 * case is the safe one: TLS on, a bounded subscriber buffer, and {@link Consistency#CONSISTENT}
 * reads. A client that silently defaults to the fastest-but-loosest behaviour is how an application
 * ends up reporting numbers that do not reconcile, months before anyone notices.
 */
public final class ClientOptions {

    private static final ErrorCode INVALID = new ErrorCode(1031, "CLIENT_INVALID_OPTIONS");

    private final Endpoint endpoint;
    private final String token;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Consistency defaultConsistency;
    private final int subscriberBufferRows;
    private final boolean conflateOnOverflow;
    private final String applicationName;
    private final boolean allowInsecureToken;

    private ClientOptions(Builder b) {
        this.endpoint = b.endpoint;
        this.token = b.token;
        this.connectTimeout = b.connectTimeout;
        this.requestTimeout = b.requestTimeout;
        this.defaultConsistency = b.defaultConsistency;
        this.subscriberBufferRows = b.subscriberBufferRows;
        this.conflateOnOverflow = b.conflateOnOverflow;
        this.applicationName = b.applicationName;
        this.allowInsecureToken = b.allowInsecureToken;
    }

    public static Builder builder(String connectionString) {
        return new Builder(Endpoint.parse(connectionString));
    }

    public static Builder builder(Endpoint endpoint) {
        return new Builder(endpoint);
    }

    public Endpoint endpoint() {
        return endpoint;
    }

    /** Whether a token may travel over a plaintext connection; false unless asked for. */
    public boolean allowInsecureToken() {
        return allowInsecureToken;
    }

    /** Bearer token, if one was supplied. Never rendered by {@link #toString()}. */
    public Optional<String> token() {
        return Optional.ofNullable(token);
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Consistency defaultConsistency() {
        return defaultConsistency;
    }

    /** Rows buffered for a subscriber before {@link #conflateOnOverflow()} decides what happens. */
    public int subscriberBufferRows() {
        return subscriberBufferRows;
    }

    /**
     * What happens when a subscriber cannot keep up.
     *
     * <p>{@code true} drops the oldest rows and reports the count, which is right for a dashboard.
     * {@code false} disconnects, which is right for a consumer that must not miss rows. Either way
     * the engine is never backpressured by a slow client (design section 20.2) -- the choice is only
     * about what the client sees.
     */
    public boolean conflateOnOverflow() {
        return conflateOnOverflow;
    }

    /** Reported to the server for audit and diagnostics. */
    public String applicationName() {
        return applicationName;
    }

    @Override
    public String toString() {
        // Deliberately omits the token. A ClientOptions reaching a log line must not leak it.
        return "ClientOptions[" + endpoint + ", consistency=" + defaultConsistency + ", buffer="
                + subscriberBufferRows + ", app=" + applicationName + (token != null ? ", authenticated" : "")
                + "]";
    }

    public static final class Builder {
        private final Endpoint endpoint;
        private String token;
        private boolean allowInsecureToken;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Consistency defaultConsistency = Consistency.CONSISTENT;
        private int subscriberBufferRows = 10_000;
        private boolean conflateOnOverflow = true;
        private String applicationName = "pravaha-java-sdk";

        private Builder(Endpoint endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        }

        /**
         * Permits a token over a plaintext connection.
         *
         * <p>For a test against a loopback server, and for a deployment where TLS is terminated by
         * a sidecar on the same host. Both are real; neither is the common case, which is why it
         * takes a call whose name says what is being given up. On a network anybody else can reach,
         * this hands the credential to whoever is listening.
         */
        public Builder allowInsecureToken(boolean value) {
            this.allowInsecureToken = value;
            return this;
        }

        public Builder token(String value) {
            this.token = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            this.connectTimeout = requirePositive("connectTimeout", value);
            return this;
        }

        public Builder requestTimeout(Duration value) {
            this.requestTimeout = requirePositive("requestTimeout", value);
            return this;
        }

        public Builder defaultConsistency(Consistency value) {
            this.defaultConsistency = Objects.requireNonNull(value, "defaultConsistency");
            return this;
        }

        public Builder subscriberBufferRows(int value) {
            if (value < 1) {
                throw new PravahaClientException(
                        INVALID, "subscriberBufferRows must be at least 1, got " + value, false);
            }
            this.subscriberBufferRows = value;
            return this;
        }

        public Builder conflateOnOverflow(boolean value) {
            this.conflateOnOverflow = value;
            return this;
        }

        public Builder applicationName(String value) {
            if (value == null || value.isBlank()) {
                throw new PravahaClientException(INVALID, "applicationName must not be blank", false);
            }
            this.applicationName = value;
            return this;
        }

        public ClientOptions build() {
            if (!endpoint.tls() && token != null && !allowInsecureToken) {
                // Sending a bearer token over plaintext hands it to anyone on the path. Refusing is
                // less convenient than warning and considerably safer.
                throw new PravahaClientException(
                        INVALID,
                        "refusing to send a token over a plaintext connection to " + endpoint
                                + "; use grpc+tls://, remove the token, or call allowInsecureToken(true) if "
                                + "the connection is loopback or TLS ends at a local sidecar",
                        false);
            }
            return new ClientOptions(this);
        }

        private static Duration requirePositive(String name, Duration value) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new PravahaClientException(INVALID, name + " must be positive, got " + value, false);
            }
            return value;
        }
    }
}
