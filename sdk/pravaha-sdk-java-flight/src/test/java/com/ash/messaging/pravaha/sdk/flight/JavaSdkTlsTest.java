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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.PravahaClientException;
import com.ash.messaging.pravaha.sdk.TlsOptions;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Java SDK's TLS configuration surface, proven against a real server -- not a test that only
 * checks a builder stored a value.
 *
 * <p>The certificate is a fresh, self-signed one generated per run by {@code keytool}
 * ({@link SelfSignedTestCertificate}), never committed, exactly as {@code PravahaFlightServer}'s own
 * {@code encryptedWith} expects: a PEM certificate chain and its PEM private key. The server is the
 * real {@link PravahaFlightServer}, started with that certificate; the client is the real
 * {@link PravahaFlightClient}, configured with the new {@link TlsOptions}, connecting over an actual
 * TLS handshake against a CA nothing in the JVM's default trust store has ever heard of.
 *
 * <p><strong>What this does not prove:</strong> full mutual TLS, in the sense of the server verifying
 * a client certificate and refusing a connection without one. {@code PravahaFlightServer.encryptedWith}
 * takes only the server's own certificate and key; it has no client-trust configuration to require or
 * verify one. {@link #aClientCertificateDoesNotBreakAConnectionToAServerThatDoesNotRequireOne} proves
 * the client-side half -- that a configured client certificate is valid, well-formed PEM Arrow's
 * builder accepts, and does not break an otherwise-ordinary TLS handshake -- which is as far as this
 * can go without a change to {@code pravaha-flight} adding server-side client-certificate
 * verification, which was out of scope for this round.
 */
@Timeout(60)
class JavaSdkTlsTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    // Each test starts the server, client and certificate it needs; @AfterEach closes whichever exist.
    private @Nullable PravahaFlightServer server;
    private @Nullable PravahaFlightClient client;
    private @Nullable SelfSignedTestCertificate certificate;

    private PravahaFlightServer startTlsServer(SelfSignedTestCertificate cert) {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 10);
        view.applyValues(new Object[] {"u2", 50L}, 1, 10);
        view.commit(10);
        return new PravahaFlightServer(new ViewCatalog().register(view))
                .encryptedWith(
                        cert.pemCertificate().toFile(), cert.pemPrivateKey().toFile())
                .start("localhost", 0);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (certificate != null) {
            certificate.close();
        }
    }

    @Test
    void aClientTrustingAPrivateCaOverPemCanReadRows() {
        certificate = SelfSignedTestCertificate.generate();
        server = startTlsServer(certificate);

        TlsOptions tls =
                TlsOptions.builder().caCertificate(certificate.pemCertificate()).build();
        ClientOptions options = ClientOptions.builder("grpc+tls://localhost:" + server.port())
                .tls(tls)
                .build();
        client = PravahaFlightClient.connect(options);

        List<String> seen = new java.util.ArrayList<>();
        try (QueryResult result = client.query("SELECT user_id, total FROM user_volume WHERE total > 40")) {
            for (Row row : result) {
                seen.add(row.getString("user_id") + "=" + row.getLong("total"));
            }
        }
        assertThat(seen).containsExactlyInAnyOrder("u1=300", "u2=50");
    }

    @Test
    void aClientTrustingAPkcs12TrustStoreCanAlsoReadRows() {
        certificate = SelfSignedTestCertificate.generate();
        server = startTlsServer(certificate);

        TlsOptions tls = TlsOptions.builder()
                .trustStore(certificate.keystorePath(), certificate.password(), certificate.storeType())
                .build();
        ClientOptions options = ClientOptions.builder("grpc+tls://localhost:" + server.port())
                .tls(tls)
                .build();
        client = PravahaFlightClient.connect(options);

        try (QueryResult result = client.query("SELECT user_id FROM user_volume")) {
            assertThat(result.toList()).hasSize(2);
        }
    }

    @Test
    void aClientWithoutTheCaCannotConnectToAServerUsingAPrivateCertificate() {
        // The negative case that makes the two tests above mean something: without configuring the
        // CA at all, this server's self-signed certificate is not trusted by the JVM's default trust
        // store, and the connection must fail rather than quietly succeed.
        certificate = SelfSignedTestCertificate.generate();
        server = startTlsServer(certificate);
        int port = server.port();

        assertThatThrownBy(() -> {
                    try (PravahaFlightClient insecure = PravahaFlightClient.connect("grpc+tls://localhost:" + port)) {
                        insecure.query("SELECT user_id FROM user_volume").close();
                    }
                })
                .isInstanceOf(PravahaClientException.class);
    }

    @Test
    void hostnameVerificationRefusesACertificateForADifferentNameByDefault() {
        // A certificate genuinely naming something else -- not the same one relaxed, a different
        // one -- so this proves hostname verification checks the name rather than only whether the
        // signature chains to a trusted CA.
        certificate = SelfSignedTestCertificate.generate(
                "CN=totally-different-name.example, OU=pravaha-sdk-test, O=pravaha",
                "dns:totally-different-name.example");
        server = startTlsServer(certificate);

        TlsOptions tls =
                TlsOptions.builder().caCertificate(certificate.pemCertificate()).build();
        ClientOptions options = ClientOptions.builder("grpc+tls://localhost:" + server.port())
                .tls(tls)
                .build();

        assertThatThrownBy(() -> {
                    try (PravahaFlightClient mismatched = PravahaFlightClient.connect(options)) {
                        mismatched.query("SELECT user_id FROM user_volume").close();
                    }
                })
                .as("the CA is trusted but the certificate's own name does not match localhost")
                .isInstanceOf(PravahaClientException.class);
    }

    @Test
    void overrideHostnameAcceptsTheCertificatesRealNameWithoutDisablingVerification() {
        certificate = SelfSignedTestCertificate.generate(
                "CN=totally-different-name.example, OU=pravaha-sdk-test, O=pravaha",
                "dns:totally-different-name.example");
        server = startTlsServer(certificate);

        TlsOptions tls = TlsOptions.builder()
                .caCertificate(certificate.pemCertificate())
                .overrideHostname("totally-different-name.example")
                .build();
        ClientOptions options = ClientOptions.builder("grpc+tls://localhost:" + server.port())
                .tls(tls)
                .build();
        client = PravahaFlightClient.connect(options);

        try (QueryResult result = client.query("SELECT user_id FROM user_volume")) {
            assertThat(result.toList()).hasSize(2);
        }
    }

    @Test
    void disablingHostnameVerificationInsecureAlsoAcceptsTheMismatch() {
        // No caCertificate configured alongside it: disabling verification means nothing is
        // checked against anything, including which CA signed the certificate, so this is the
        // genuinely different case from overrideHostname above -- not the same test with one more
        // setting. TlsOptions itself refuses combining the two; see
        // TlsOptionsTest.combiningDisabledHostnameVerificationWithCertificateMaterialIsRefused.
        certificate = SelfSignedTestCertificate.generate(
                "CN=totally-different-name.example, OU=pravaha-sdk-test, O=pravaha",
                "dns:totally-different-name.example");
        server = startTlsServer(certificate);

        TlsOptions tls =
                TlsOptions.builder().disableHostnameVerificationInsecure(true).build();
        ClientOptions options = ClientOptions.builder("grpc+tls://localhost:" + server.port())
                .tls(tls)
                .build();
        client = PravahaFlightClient.connect(options);

        try (QueryResult result = client.query("SELECT user_id FROM user_volume")) {
            assertThat(result.toList()).hasSize(2);
        }
    }

    @Test
    void aClientCertificateDoesNotBreakAConnectionToAServerThatDoesNotRequireOne() {
        // See the class javadoc: this is the client-side half of mutual TLS, as far as this round
        // proves it. A separate certificate stands in for the client's own identity, distinct from
        // the server's, to show this is genuinely presenting a second certificate and not silently
        // reusing the server's.
        certificate = SelfSignedTestCertificate.generate();
        server = startTlsServer(certificate);
        try (SelfSignedTestCertificate clientIdentity = SelfSignedTestCertificate.generate(
                "CN=test-client, OU=pravaha-sdk-test, O=pravaha", "dns:test-client")) {
            TlsOptions tls = TlsOptions.builder()
                    .caCertificate(certificate.pemCertificate())
                    .clientCertificate(clientIdentity.pemCertificate())
                    .clientKey(clientIdentity.pemPrivateKey())
                    .build();
            ClientOptions options = ClientOptions.builder("grpc+tls://localhost:" + server.port())
                    .tls(tls)
                    .build();
            client = PravahaFlightClient.connect(options);

            try (QueryResult result = client.query("SELECT user_id FROM user_volume")) {
                assertThat(result.toList()).hasSize(2);
            }
        }
    }

    @Test
    void aClientKeyStoreAlsoWorksForMutualTls() {
        // Both halves as a keystore this time, not PEM -- caCertificate() plus keyStore() would be
        // exactly the "PEM and a keystore together" combination TlsOptions refuses, and correctly
        // so: trustStore() is the keystore-shaped way to say the same thing caCertificate() says.
        certificate = SelfSignedTestCertificate.generate();
        server = startTlsServer(certificate);
        try (SelfSignedTestCertificate clientIdentity = SelfSignedTestCertificate.generate(
                "CN=test-client, OU=pravaha-sdk-test, O=pravaha", "dns:test-client")) {
            TlsOptions tls = TlsOptions.builder()
                    .trustStore(certificate.keystorePath(), certificate.password(), certificate.storeType())
                    .keyStore(clientIdentity.keystorePath(), clientIdentity.password(), clientIdentity.storeType())
                    .build();
            ClientOptions options = ClientOptions.builder("grpc+tls://localhost:" + server.port())
                    .tls(tls)
                    .build();
            client = PravahaFlightClient.connect(options);

            try (QueryResult result = client.query("SELECT user_id FROM user_volume")) {
                assertThat(result.toList()).hasSize(2);
            }
        }
    }
}
