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
package com.ash.messaging.pravaha.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code pravaha.pgwire.tls.certificate} and {@code .key}, through the construction path {@code
 * application.yaml} drives (HLP-5).
 *
 * <p>The gateway itself has had TLS since its third slice ({@code PsqlSessionTest}, {@code
 * JdbcClientTest}); what was missing was the node passing the configured pair to it, so a
 * deployment that set both keys served plaintext. The proof here is the protocol's own: a client's
 * {@code SSLRequest} is answered {@code 'S'} and a TLS handshake completes with the configured
 * certificate. A plaintext gateway answers {@code 'N'}.
 */
class PravahaNodePgWireTlsTest {

    /** The PostgreSQL protocol's SSLRequest code: 1234 in the high half, 5679 in the low. */
    private static final int SSL_REQUEST = 80877103;

    private static PravahaNode.Builder node() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build());
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        return PravahaNode.builder()
                .withCatalog(catalog)
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPgWire(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("pgwire-tls-node");
    }

    /** Sends an SSLRequest and returns the one byte the server answers with. */
    private static char sslRequest(Socket socket) throws Exception {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(8);
        out.writeInt(SSL_REQUEST);
        out.flush();
        return (char) new DataInputStream(socket.getInputStream()).readUnsignedByte();
    }

    @Test
    void aNodeConfiguredWithPgWireTlsNegotiatesTlsWithTheConfiguredCertificate() throws Exception {
        try (SelfSignedTestCertificate certificate = SelfSignedTestCertificate.generate()) {
            PravahaNode node = node().withPgWireTls(
                            certificate.pemCertificate().toString(),
                            certificate.pemPrivateKey().toString())
                    .build();
            node.start();
            try {
                int port = node.pgwirePort().orElseThrow();
                try (Socket plain = new Socket("127.0.0.1", port)) {
                    assertThat(sslRequest(plain))
                            .as("a TLS-configured gateway accepts the SSLRequest rather than serving plaintext")
                            .isEqualTo('S');

                    KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
                    trust.load(null, null);
                    try (InputStream pem = java.nio.file.Files.newInputStream(certificate.pemCertificate())) {
                        Certificate cert =
                                CertificateFactory.getInstance("X.509").generateCertificate(pem);
                        trust.setCertificateEntry("pgwire", cert);
                    }
                    TrustManagerFactory tmf =
                            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(trust);
                    SSLContext context = SSLContext.getInstance("TLS");
                    context.init(null, tmf.getTrustManagers(), null);
                    try (SSLSocket tls =
                            (SSLSocket) context.getSocketFactory().createSocket(plain, "localhost", port, true)) {
                        tls.setUseClientMode(true);
                        tls.startHandshake();
                        assertThat(tls.getSession().getPeerCertificates()[0])
                                .as("the handshake presented the configured certificate")
                                .isEqualTo(trust.getCertificate("pgwire"));
                    }
                }
            } finally {
                node.stop();
            }
        }
    }

    @Test
    void aNodeWithoutPgWireTlsDeclinesTheSslRequest() throws Exception {
        PravahaNode node = node().build();
        node.start();
        try {
            try (Socket plain = new Socket("127.0.0.1", node.pgwirePort().orElseThrow())) {
                assertThat(sslRequest(plain)).isEqualTo('N');
            }
        } finally {
            node.stop();
        }
    }

    @Test
    void halfAPgWireTlsPairIsRefusedAtStartRatherThanServedInPlaintext() throws Exception {
        try (SelfSignedTestCertificate certificate = SelfSignedTestCertificate.generate()) {
            PravahaNode node = node().withPgWireTls(certificate.pemCertificate().toString(), "")
                    .build();
            try {
                assertThatThrownBy(node::start).hasMessageContaining("pravaha.pgwire.tls.key");
            } finally {
                node.stop();
            }
        }
    }
}
