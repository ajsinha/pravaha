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
package com.ash.messaging.pravaha.flight;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a Flight node says its own address is, and what it does with a certificate that is not its
 * key's.
 *
 * <p>Findings SX-16 and SX-17. Both are the same mistake in two places: describing the server by
 * what was <em>asked for</em> rather than by what happened. An ephemeral port reported as
 * {@code 0}; a TLS node reported {@code grpc+tcp://}; a certificate and key that did not belong
 * together started a node that said {@code transport=TLS} and that no client could reach.
 */
class FlightAddressAndTlsPairTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private static ViewCatalog oneView() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 10);
        view.commit(10);
        return new ViewCatalog().register(view);
    }

    /**
     * SX-16(a). {@code --pravaha.flight.port=0} asks the operating system for a free port; the node
     * bound one and then told clients to dial {@code 0}.
     */
    @Test
    void sx16_anEphemeralPortIsReportedAsTheOneActuallyBound() throws Exception {
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                PravahaFlightServer server = new PravahaFlightServer(oneView(), allocator).start("localhost", 0)) {

            assertThat(server.port()).as("the OS gave us a real port").isNotZero();
            assertThat(server.uri())
                    .as("and the node's own reported address carries it, not the 0 it asked for")
                    .contains(":" + server.port())
                    .doesNotContain(":0");
        }
    }

    /**
     * SX-16(a), through a client. The half that actually reaches a caller.
     *
     * <p>{@code getFlightInfo} hands back a {@link org.apache.arrow.flight.FlightEndpoint} carrying
     * the location to fetch from, and the producer was constructed with the location the server was
     * <em>asked</em> to bind — which is all that exists before {@code start()}. With
     * {@code port: 0} that is port {@code 0}, so a client following the endpoint it was just given
     * dialled a dead port. The server's own {@code uri()} is asserted above; this asserts what goes
     * on the wire, which is a different field set in a different place.
     */
    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    @Test
    void sx16_theEndpointAClientIsHandedCarriesThePortThatWasBound() throws Exception {
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                PravahaFlightServer server = new PravahaFlightServer(oneView(), allocator).start("localhost", 0);
                org.apache.arrow.flight.sql.FlightSqlClient client =
                        new org.apache.arrow.flight.sql.FlightSqlClient(org.apache.arrow.flight.FlightClient.builder(
                                        allocator,
                                        org.apache.arrow.flight.Location.forGrpcInsecure("localhost", server.port()))
                                .build())) {

            org.apache.arrow.flight.FlightInfo info = client.execute("SELECT user_id FROM user_volume");

            assertThat(info.getEndpoints()).isNotEmpty();
            assertThat(info.getEndpoints().get(0).getLocations())
                    .as("the endpoint a client follows must name the port this server is listening on")
                    .allSatisfy(
                            location -> assertThat(location.getUri().getPort()).isEqualTo(server.port()));
        }
    }

    /** SX-16(b). A node genuinely serving TLS reported a plaintext scheme. */
    @Test
    void sx16_aTlsNodeReportsATlsScheme() throws Exception {
        try (SelfSignedTestCertificate certificate = SelfSignedTestCertificate.generate();
                BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                PravahaFlightServer server = new PravahaFlightServer(oneView(), allocator)
                        .encryptedWith(
                                certificate.pemCertificate().toFile(),
                                certificate.pemPrivateKey().toFile())
                        .start("localhost", 0)) {

            assertThat(server.isEncrypted()).isTrue();
            assertThat(server.uri())
                    .as("a client following grpc+tcp:// at a port that speaks TLS gets nowhere")
                    .startsWith("grpc+tls://")
                    .contains(":" + server.port());
        }
    }

    /** A plaintext node still reports a plaintext scheme: the fix must not invert. */
    @Test
    void sx16_aPlaintextNodeStillReportsAPlaintextScheme() throws Exception {
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                PravahaFlightServer server = new PravahaFlightServer(oneView(), allocator).start("localhost", 0)) {
            assertThat(server.uri()).startsWith("grpc+tcp://");
        }
    }

    /**
     * SX-17. A certificate and a key that are each individually valid and are not a pair started
     * the node, which then reported {@code transport=TLS} while every client's handshake failed
     * with {@code tlsv1 alert internal error} — on the client, with nothing in this log.
     */
    @Test
    void sx17_aCertificateThatIsNotThisKeysIsRefusedAtStartRatherThanAtTheFirstHandshake() throws Exception {
        try (SelfSignedTestCertificate mine = SelfSignedTestCertificate.generate();
                SelfSignedTestCertificate somebodyElses = SelfSignedTestCertificate.generate();
                BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {

            PravahaFlightServer server = new PravahaFlightServer(oneView(), allocator)
                    .encryptedWith(
                            mine.pemCertificate().toFile(),
                            somebodyElses.pemPrivateKey().toFile());

            assertThatThrownBy(() -> server.start("localhost", 0))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-6104")
                    .hasMessageContaining("are not a pair")
                    .hasMessageContaining(mine.pemCertificate().toString())
                    .hasMessageContaining(somebodyElses.pemPrivateKey().toString());
        }
    }

    /** SX-17. Swapping the two files threw a raw {@code IllegalArgumentException}, uncoded. */
    @Test
    void sx17_swappedCertificateAndKeyFilesAreRefusedWithACode() throws Exception {
        try (SelfSignedTestCertificate certificate = SelfSignedTestCertificate.generate();
                BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {

            PravahaFlightServer server = new PravahaFlightServer(oneView(), allocator)
                    .encryptedWith(
                            certificate.pemPrivateKey().toFile(),
                            certificate.pemCertificate().toFile());

            assertThatThrownBy(() -> server.start("localhost", 0))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-6104");
        }
    }

    /** SX-17. A key file that is not PKCS#8 at all says which format it is and how to convert it. */
    @Test
    void sx17_aKeyThatIsNotPkcs8SaysSoAndSaysHowToConvertIt(@TempDir Path dir) throws Exception {
        try (SelfSignedTestCertificate certificate = SelfSignedTestCertificate.generate();
                BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE)) {

            Path pkcs1 = dir.resolve("legacy-key.pem");
            Files.writeString(pkcs1, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");

            PravahaFlightServer server = new PravahaFlightServer(oneView(), allocator)
                    .encryptedWith(certificate.pemCertificate().toFile(), pkcs1.toFile());

            assertThatThrownBy(() -> server.start("localhost", 0))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-6104")
                    .hasMessageContaining("PKCS#8")
                    .hasMessageContaining("openssl pkcs8");
        }
    }

    /** The control: a real pair still starts, and still serves. */
    @Test
    void sx17_aMatchingPairStartsAsItAlwaysDid() throws Exception {
        try (SelfSignedTestCertificate certificate = SelfSignedTestCertificate.generate();
                BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                PravahaFlightServer server = new PravahaFlightServer(oneView(), allocator)
                        .encryptedWith(
                                certificate.pemCertificate().toFile(),
                                certificate.pemPrivateKey().toFile())
                        .start("localhost", 0)) {
            assertThat(server.isEncrypted()).isTrue();
            assertThat(server.port()).isNotZero();
        }
    }
}
