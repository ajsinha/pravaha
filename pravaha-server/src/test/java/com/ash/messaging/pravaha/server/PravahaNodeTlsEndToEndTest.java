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

import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code pravaha.flight.tls.certificate} and {@code .key}, proved through the same path an
 * operator's {@code application.yaml} actually takes -- not through {@code
 * PravahaFlightServer.encryptedWith} called directly (that is {@code
 * FlightServerTlsEndToEndTest}'s job, one module down), but through {@link PravahaNode}'s own
 * constructor, the one Spring populates from {@code @Value("${pravaha.flight.tls.certificate:}")}
 * and {@code @Value("${pravaha.flight.tls.key:}")}.
 *
 * <p>This distinction turned out to matter. {@code pravaha-flight} in isolation was missing {@code
 * io.netty:netty-transport-native-unix-common} entirely -- confirmed by {@code dependency:tree} and
 * by {@code FlightServerTlsEndToEndTest} failing with {@code NoClassDefFoundError:
 * io/netty/channel/unix/UnixChannel} before that module's own {@code pom.xml} fix. But {@code
 * pravaha-server}'s dependency tree turned out to already carry that same class transitively, at a
 * <em>different</em> version (4.1.135.Final, pulled in independently of {@code pravaha-flight}'s
 * own 4.2.9.Final) -- almost certainly through Spring Boot's own Netty stack. Whether that
 * coincidence was enough to make a real handshake succeed, or whether it too failed for some other
 * reason, was exactly the kind of thing not to assume: hence this test, run against the actual
 * {@link PravahaNode} construction path rather than the lower-level class alone.
 */
class PravahaNodeTlsEndToEndTest {

    private static StreamCatalog catalog() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build());
        return catalog;
    }

    private static SecurityProperties openServer() {
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        return security;
    }

    private static PersistenceProperties persistence() {
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        return persistence;
    }

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    @Test
    void aNodeConfiguredWithTlsCertificateAndKeyActuallyServesTlsToARealClient() throws Exception {
        try (SelfSignedTestCertificate certificate = SelfSignedTestCertificate.generate()) {
            PravahaNode node = PravahaNode.builder()
                    .withCatalog(catalog())
                    .withSecurity(openServer())
                    .withFlightTls(
                            certificate.pemCertificate().toString(),
                            certificate.pemPrivateKey().toString())
                    .withWatermark(java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(1))
                    .withFlight(true, "127.0.0.1", 0)
                    .withPersistence(persistence())
                    .withNodeId("tls-test-node")
                    .build();

            node.start();
            try {
                assertThat(node.flightPort()).isPresent();
                int port = node.flightPort().orElseThrow();

                // A stream declared in the catalog is not yet a view: PravahaFlightSqlProducer
                // serves registered continuous queries, and SELECT * FROM txn with nothing
                // registered is correctly refused with PRV-4023 -- a real answer, not a TLS
                // failure, but not the proof this test is after either. Registering one over the
                // same TLS connection this test is proving is what makes "SELECT ... FROM
                // by_user" reach an actual view.
                node.registry()
                        .orElseThrow()
                        .register(
                                "by_user",
                                "SELECT user_id, amount FROM txn",
                                java.util.List.of(0),
                                new com.ash.messaging.pravaha.security.Principal(
                                        "dana", "public", java.util.Set.of("analyst"), java.util.Map.of()));

                try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                        FlightSqlClient client = new FlightSqlClient(org.apache.arrow.flight.FlightClient.builder(
                                        allocator, Location.forGrpcTls("127.0.0.1", port))
                                .trustedCertificates(java.nio.file.Files.newInputStream(certificate.pemCertificate()))
                                .build())) {
                    // The proof: a real TLS handshake through the exact construction path
                    // application.yaml drives, followed by a real Flight SQL call that reaches a
                    // real registered view. An empty result is fine -- by_user has no rows pushed
                    // into it -- what matters is that this returned an answer instead of
                    // NoClassDefFoundError or a hung handshake.
                    FlightInfo info = client.execute("SELECT user_id FROM by_user");
                    assertThat(info.getEndpoints()).isNotEmpty();
                }
            } finally {
                node.stop();
            }
        }
    }
}
