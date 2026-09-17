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
import java.util.Map;
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
    private final TlsOptions tls;

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
        this.tls = b.tls;
    }

    public static Builder builder(String connectionString) {
        return new Builder(Endpoint.parse(connectionString));
    }

    public static Builder builder(Endpoint endpoint) {
        return new Builder(endpoint);
    }

    /**
     * Builds options entirely from a config map -- the endpoint (including whether it is TLS at
     * all, via {@link Endpoint#fromConfig(Map)}), every TLS detail (via {@link
     * TlsOptions.Builder#applyConfig(Map)}), and the few other settings an operator commonly needs
     * to change without a recompile: {@code token}, {@code allow-insecure-token} ({@code
     * "true"}/{@code "false"}), and {@code application-name}.
     *
     * <p>{@link SdkConfig#layered(Map, String)} is how {@code config} itself typically gets built,
     * so a system property or environment variable can override one key from a file without
     * editing the file.
     */
    public static ClientOptions fromConfig(Map<String, String> config) {
        Objects.requireNonNull(config, "config");
        Builder b = builder(Endpoint.fromConfig(config));
        b.applyConfig(config);
        return b.build();
    }

    public Endpoint endpoint() {
        return endpoint;
    }

    /** How this client verifies the server's certificate and, for mutual TLS, presents its own. */
    public TlsOptions tls() {
        return tls;
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
        private TlsOptions tls = TlsOptions.defaults();

        private Builder(Endpoint endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        }

        /**
         * How this client verifies the server's certificate and, for mutual TLS, presents its own.
         *
         * <p>Meaningless -- and refused -- on a plaintext ({@code grpc://}) endpoint: certificate
         * material configured for a connection that will not use TLS at all is exactly the kind of
         * setting that looks like it did something and did not.
         */
        public Builder tls(TlsOptions value) {
            this.tls = Objects.requireNonNull(value, "tls");
            return this;
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

        /**
         * Applies {@code tls.*} (via {@link TlsOptions.Builder#applyConfig(Map)}), {@code token},
         * {@code allow-insecure-token}, and {@code application-name} from {@code config}, each only
         * if present. Called by {@link ClientOptions#fromConfig(Map)}; exposed directly so a caller
         * can layer a config map onto a builder already carrying programmatic settings, or follow
         * it with more builder calls that override what the config supplied.
         */
        public Builder applyConfig(Map<String, String> config) {
            Objects.requireNonNull(config, "config");
            tls(TlsOptions.builder().applyConfig(config).build());
            String tok = config.get("token");
            if (tok != null && !tok.isBlank()) {
                token(tok);
            }
            String insecure = config.get("allow-insecure-token");
            if (insecure != null && !insecure.isBlank()) {
                allowInsecureToken(SdkConfig.truthy(insecure));
            }
            String app = config.get("application-name");
            if (app != null && !app.isBlank()) {
                applicationName(app);
            }
            return this;
        }

        public ClientOptions build() {
            if (!endpoint.tls() && !tls.isDefault()) {
                // Certificate material configured for a connection that will not use TLS at all --
                // the endpoint's own scheme decides that, and this setting has no way to change it.
                // A setting that looks like it did something and silently did nothing is exactly
                // what ClientOptions is built to refuse rather than accept.
                throw new PravahaClientException(
                        INVALID,
                        "TLS options were configured (" + tls + ") but the endpoint " + endpoint
                                + " is plaintext; use grpc+tls:// or remove the TLS options",
                        false);
            }
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
            // I-6. LATEST, AT_LEAST and AS_OF are API surface with nothing behind them: ViewQuery
            // reads view.scan() -- committed state only -- and ServedView.get(Consistency, ...) has
            // no transport caller at all. A client setting one of them was silently served
            // CONSISTENT, which is a different answer from the one it asked for.
            //
            // Refused rather than implemented. Wiring the modes through the Flight surface is real
            // work, and until it is done a refusal is the only honest response: a caller who asked
            // for LATEST and got committed-only has no way to discover that, and an answer nobody
            // can tell is wrong is the worst kind this system produces.
            if (defaultConsistency != Consistency.CONSISTENT) {
                throw new PravahaClientException(
                        INVALID,
                        "consistency " + defaultConsistency + " is declared by this SDK and not implemented "
                                + "by the server: reads are served from committed state, which is CONSISTENT. "
                                + "Asking for " + defaultConsistency + " and being given CONSISTENT would be a "
                                + "different answer than the one requested, with nothing to tell you so. Use "
                                + "CONSISTENT until the other modes reach the wire (I-6).",
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
