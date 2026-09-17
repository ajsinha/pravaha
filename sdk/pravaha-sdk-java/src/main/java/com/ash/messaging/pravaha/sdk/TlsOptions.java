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

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * How a client verifies a server's certificate and, for mutual TLS, presents its own.
 *
 * <p>Without this, a client can only do TLS against a certificate the JVM's own default trust store
 * already trusts -- so it cannot reach a Pravaha server using a private CA or a self-signed
 * certificate, which is most real deployments. This is the configuration surface that closes that
 * gap.
 *
 * <p>Two independent forms, because both are what people actually have:
 *
 * <ul>
 *   <li><strong>PEM</strong> -- a CA bundle for verifying the server, and a client certificate plus
 *       its own key for mutual TLS, three separate files.
 *   <li><strong>Keystore/truststore</strong> -- a JKS or PKCS12 file with a password and a type,
 *       because that is what a JVM shop already has rather than something to export.
 * </ul>
 *
 * <p><strong>Giving both forms is refused</strong>, not silently resolved by preferring one: two
 * settings that both claim to say how to trust the server, with no rule for which wins, is exactly
 * the shape of configuration nobody should be allowed to leave in place.
 *
 * <p><strong>A client certificate without its key, or a key without its certificate, is refused</strong>
 * naming which is missing -- see {@code PravahaFlightServer.encryptedWith}'s own comment (CFG-6):
 * that server used to null-check only the certificate, so a key configured alone was read into a
 * field and never used, and the node served plaintext while its own advice told the operator to set
 * settings they had half-set. The fix there was ordering the check before either half is
 * dereferenced; the fix here is the same discipline on the client's side of the same mistake.
 *
 * <p>Hostname verification is on by default. Turning it off entirely needs a call whose name says
 * what is being given up, mirroring {@link ClientOptions.Builder#allowInsecureToken(boolean)} --
 * {@link Builder#disableHostnameVerificationInsecure(boolean)}. A narrower escape hatch,
 * {@link Builder#overrideHostname(String)}, still verifies a certificate is properly signed; it only
 * changes which name the certificate is checked against, which is what a test connecting to
 * {@code 127.0.0.1} against a certificate issued for {@code localhost} needs without giving up
 * verification altogether.
 *
 * <p>Disabling hostname verification does not touch {@link ClientOptions}'s separate refusal of a
 * bearer token over a plaintext connection -- that check is keyed on {@link Endpoint#tls()}, which
 * this class has no way to change, so the two compose rather than one quietly undoing the other.
 *
 * <p>Protocol and cipher-suite selection is deliberately not offered. Arrow Flight's client builder
 * -- the transport both the Java and Python SDKs use -- exposes no hook for either, in neither
 * language; a setting this class accepted and could not act on would be exactly the kind of knob
 * that does nothing, which this project treats as a defect rather than a convenience.
 */
public final class TlsOptions {

    private static final ErrorCode INVALID = new ErrorCode(1032, "CLIENT_INVALID_TLS_OPTIONS");

    private final Path caCertificate;
    private final Path clientCertificate;
    private final Path clientKey;
    private final Path trustStore;
    private final String trustStorePassword;
    private final String trustStoreType;
    private final Path keyStore;
    private final String keyStorePassword;
    private final String keyStoreType;
    private final boolean verifyHostname;
    private final String overrideHostname;

    private TlsOptions(Builder b) {
        this.caCertificate = b.caCertificate;
        this.clientCertificate = b.clientCertificate;
        this.clientKey = b.clientKey;
        this.trustStore = b.trustStore;
        this.trustStorePassword = b.trustStorePassword;
        this.trustStoreType = b.trustStoreType;
        this.keyStore = b.keyStore;
        this.keyStorePassword = b.keyStorePassword;
        this.keyStoreType = b.keyStoreType;
        this.verifyHostname = b.verifyHostname;
        this.overrideHostname = b.overrideHostname;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** No certificate material of its own: TLS, if the endpoint asks for it, against the JVM's default trust store. */
    public static TlsOptions defaults() {
        return builder().build();
    }

    /**
     * Builds options entirely from a config map -- a properties file an operator edits, not a
     * recompile. See {@link Builder#applyConfig(Map)} for the keys read.
     */
    public static TlsOptions fromConfig(Map<String, String> config) {
        return builder().applyConfig(config).build();
    }

    public Optional<Path> caCertificate() {
        return Optional.ofNullable(caCertificate);
    }

    public Optional<Path> clientCertificate() {
        return Optional.ofNullable(clientCertificate);
    }

    public Optional<Path> clientKey() {
        return Optional.ofNullable(clientKey);
    }

    public Optional<Path> trustStore() {
        return Optional.ofNullable(trustStore);
    }

    /** Empty when no truststore was configured; never rendered by {@link #toString()} either way. */
    public Optional<String> trustStorePassword() {
        return Optional.ofNullable(trustStorePassword);
    }

    public String trustStoreType() {
        return trustStoreType;
    }

    public Optional<Path> keyStore() {
        return Optional.ofNullable(keyStore);
    }

    public Optional<String> keyStorePassword() {
        return Optional.ofNullable(keyStorePassword);
    }

    public String keyStoreType() {
        return keyStoreType;
    }

    public boolean verifyHostname() {
        return verifyHostname;
    }

    public Optional<String> overrideHostname() {
        return Optional.ofNullable(overrideHostname);
    }

    /** Whether any PEM material was configured -- a CA bundle, or a client certificate/key. */
    public boolean hasPemMaterial() {
        return caCertificate != null || clientCertificate != null || clientKey != null;
    }

    /** Whether a keystore or truststore was configured. */
    public boolean hasKeystoreMaterial() {
        return trustStore != null || keyStore != null;
    }

    /** True for a client that presents no certificate of its own and verifies the server normally. */
    public boolean isDefault() {
        return !hasPemMaterial() && !hasKeystoreMaterial() && verifyHostname && overrideHostname == null;
    }

    @Override
    public String toString() {
        // Deliberately omits every password. A TlsOptions reaching a log line must not leak one.
        return "TlsOptions[" + (hasPemMaterial() ? "pem" : hasKeystoreMaterial() ? "keystore" : "default")
                + (verifyHostname ? "" : ", hostname verification disabled") + "]";
    }

    public static final class Builder {
        private Path caCertificate;
        private Path clientCertificate;
        private Path clientKey;
        private Path trustStore;
        private String trustStorePassword;
        private String trustStoreType = "JKS";
        private Path keyStore;
        private String keyStorePassword;
        private String keyStoreType = "JKS";
        private boolean verifyHostname = true;
        private String overrideHostname;

        private Builder() {}

        /** A PEM CA bundle to verify the server's certificate against, instead of the JVM's default trust store. */
        public Builder caCertificate(Path path) {
            this.caCertificate = Objects.requireNonNull(path, "caCertificate");
            return this;
        }

        /** The client's own PEM certificate, for mutual TLS. Needs {@link #clientKey(Path)} too. */
        public Builder clientCertificate(Path path) {
            this.clientCertificate = Objects.requireNonNull(path, "clientCertificate");
            return this;
        }

        /** The client's own PEM private key, for mutual TLS. Needs {@link #clientCertificate(Path)} too. */
        public Builder clientKey(Path path) {
            this.clientKey = Objects.requireNonNull(path, "clientKey");
            return this;
        }

        /**
         * A JKS or PKCS12 truststore to verify the server's certificate against.
         *
         * <p>Path, password and type together, in one call, so there is no way to set a password
         * without the file it opens -- the same discipline the refusals below enforce for the pieces
         * that cannot be bundled into one call.
         *
         * @param type {@code "JKS"} or {@code "PKCS12"}, case-insensitive
         */
        public Builder trustStore(Path path, String password, String type) {
            this.trustStore = Objects.requireNonNull(path, "trustStore");
            this.trustStorePassword = password;
            this.trustStoreType = requireStoreType(type);
            return this;
        }

        /**
         * A JKS or PKCS12 keystore holding the client's own certificate and private key, for mutual
         * TLS.
         *
         * @param type {@code "JKS"} or {@code "PKCS12"}, case-insensitive
         */
        public Builder keyStore(Path path, String password, String type) {
            this.keyStore = Objects.requireNonNull(path, "keyStore");
            this.keyStorePassword = password;
            this.keyStoreType = requireStoreType(type);
            return this;
        }

        /**
         * Disables hostname verification entirely.
         *
         * <p>A call whose name says what is being given up, deliberately -- {@code
         * verifyHostname(false)} reads, out of context, like a setting rather than a decision. This
         * does not touch {@link ClientOptions}'s refusal of a bearer token over plaintext, which is
         * keyed on the connection's own scheme and has no way to consult this setting; disabling
         * hostname verification is not a way around it.
         */
        public Builder disableHostnameVerificationInsecure(boolean value) {
            this.verifyHostname = !value;
            return this;
        }

        /**
         * Verifies the server's certificate against a different expected name than the one connected
         * to.
         *
         * <p>The narrower alternative to disabling verification altogether: a certificate issued for
         * {@code localhost} still has to be properly signed and still has to name {@code localhost},
         * this just tells the client to check for that name even when it dialled {@code 127.0.0.1}
         * or a load balancer's own address. For a test against a certificate whose subject does not
         * match how the test reaches it -- not for production, where the certificate should simply
         * name the address it is served on.
         */
        public Builder overrideHostname(String hostname) {
            if (hostname == null || hostname.isBlank()) {
                throw new PravahaClientException(INVALID, "overrideHostname must not be blank", false);
            }
            this.overrideHostname = hostname;
            return this;
        }

        /**
         * Applies every recognised {@code tls.*} key present in {@code config}, so a properties
         * file (or any other source an embedding application already reads into a map) can name
         * PEM files, a keystore, or hostname-verification settings without a line of Java. Keys not
         * present in {@code config} are left as whatever this builder already had -- calling this
         * first, then a direct setter afterward, lets programmatic configuration override the file
         * in the ordinary last-call-wins way.
         *
         * <p>Recognised keys, all optional: {@code tls.ca-certificate}, {@code
         * tls.client-certificate}, {@code tls.client-key}, {@code tls.trust-store}, {@code
         * tls.trust-store-password}, {@code tls.trust-store-type}, {@code tls.key-store}, {@code
         * tls.key-store-password}, {@code tls.key-store-type}, {@code
         * tls.disable-hostname-verification-insecure} ({@code "true"}/{@code "false"}), and {@code
         * tls.override-hostname}. {@code tls.enabled} is read by {@link
         * Endpoint#fromConfig(Map)}, not here -- it decides whether the endpoint itself is TLS at
         * all, which this class has no way to change.
         *
         * <p>A store password given without its path, or vice versa, is not specially checked here:
         * a path with no password is simply an unprotected store (the underlying {@code KeyStore}
         * API already permits that), and a password with no path does nothing because {@code
         * trust-store}/{@code key-store} is what triggers reading either at all.
         */
        public Builder applyConfig(Map<String, String> config) {
            Objects.requireNonNull(config, "config");
            String ca = config.get("tls.ca-certificate");
            if (ca != null && !ca.isBlank()) {
                caCertificate(Path.of(ca));
            }
            String cert = config.get("tls.client-certificate");
            if (cert != null && !cert.isBlank()) {
                clientCertificate(Path.of(cert));
            }
            String key = config.get("tls.client-key");
            if (key != null && !key.isBlank()) {
                clientKey(Path.of(key));
            }
            String ts = config.get("tls.trust-store");
            if (ts != null && !ts.isBlank()) {
                trustStore(
                        Path.of(ts),
                        config.get("tls.trust-store-password"),
                        config.getOrDefault("tls.trust-store-type", "JKS"));
            }
            String ks = config.get("tls.key-store");
            if (ks != null && !ks.isBlank()) {
                keyStore(
                        Path.of(ks),
                        config.get("tls.key-store-password"),
                        config.getOrDefault("tls.key-store-type", "JKS"));
            }
            String disableVerify = config.get("tls.disable-hostname-verification-insecure");
            if (disableVerify != null && !disableVerify.isBlank()) {
                disableHostnameVerificationInsecure(SdkConfig.truthy(disableVerify));
            }
            String overrideHost = config.get("tls.override-hostname");
            if (overrideHost != null && !overrideHost.isBlank()) {
                overrideHostname(overrideHost);
            }
            return this;
        }

        public TlsOptions build() {
            boolean certGiven = clientCertificate != null;
            boolean keyGiven = clientKey != null;
            if (certGiven != keyGiven) {
                throw new PravahaClientException(
                        INVALID,
                        "TLS needs both halves of a client certificate and got only "
                                + (certGiven ? "the certificate" : "the private key")
                                + ". Call clientCertificate(...) and clientKey(...) together, or neither -- "
                                + "a client given one of them cannot present the other, and connecting without "
                                + "a client certificate because half a setting was missing is how a deployment "
                                + "that asked for mutual TLS ends up without it.",
                        false);
            }
            boolean pem = caCertificate != null || certGiven;
            boolean keystore = trustStore != null || keyStore != null;
            if (!verifyHostname && (pem || keystore)) {
                // Arrow's own transport refuses this combination outright -- "FlightClient has
                // been configured to disable server verification, but certificate options have
                // been specified" -- and its message names neither setting by the name this SDK
                // uses, so a caller who hit it directly would have nothing to act on. Disabling
                // verification already means no certificate is checked against anything, which is
                // what makes the combination meaningless rather than merely redundant.
                throw new PravahaClientException(
                        INVALID,
                        "disableHostnameVerificationInsecure(true) was called along with certificate "
                                + "material (caCertificate/clientCertificate/clientKey/trustStore/keyStore). "
                                + "Disabling verification means no certificate is checked against anything, so "
                                + "the two do not compose -- configure one or the other, not both.",
                        false);
            }
            if (pem && keystore) {
                throw new PravahaClientException(
                        INVALID,
                        "both PEM material (caCertificate/clientCertificate/clientKey) and a keystore "
                                + "(trustStore/keyStore) are configured, with no rule for which one wins. "
                                + "Configure PEM files or a keystore, not both.",
                        false);
            }
            return new TlsOptions(this);
        }

        private static String requireStoreType(String type) {
            if (type == null || type.isBlank()) {
                throw new PravahaClientException(INVALID, "a store type must not be blank; use JKS or PKCS12", false);
            }
            String upper = type.strip().toUpperCase(java.util.Locale.ROOT);
            if (!upper.equals("JKS") && !upper.equals("PKCS12")) {
                throw new PravahaClientException(
                        INVALID, "unknown store type '" + type + "'; use JKS or PKCS12", false);
            }
            return upper;
        }
    }
}
