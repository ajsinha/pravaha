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

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PravahaFlightServer.encryptedWith}, proved rather than assumed.
 *
 * <p>{@code TlsPairTest} only ever calls {@code encryptedWith} itself: it writes placeholder files
 * containing the literal text {@code "-----BEGIN-----\n"} -- not a real certificate -- and never
 * calls {@link PravahaFlightServer#start}, so nothing before this test ever bound a TLS socket, let
 * alone completed a handshake against it. That gap matters because {@code sdk-java-flight}'s own TLS
 * tests failed this same way until {@code netty-transport-native-unix-common} was added to its
 * dependency tree -- a class ({@code io.netty.channel.unix.UnixChannel}) that grpc-netty's TLS
 * transport references even though nothing here uses a Unix domain socket. {@code pravaha-flight}
 * shares the identical {@code grpc-netty}/{@code netty-tcnative-boringssl-static} stack (confirmed by
 * {@code dependency:tree}: the same gap existed here, byte for byte, before this test's fix), so it
 * was exactly as unproven and exactly as broken until this test's own {@code pom.xml} change.
 *
 * <p>A genuine certificate ({@link SelfSignedTestCertificate}, generated fresh per run via {@code
 * keytool}, never committed) is handed to {@code encryptedWith}, the server is actually started on a
 * TLS {@link Location}, and a real Arrow Flight SQL client -- the same client class {@code
 * FlightSqlEndToEndTest} uses over plaintext -- connects with the certificate as its trusted root and
 * reads rows back. A test that only asserted {@code isEncrypted()} or that {@code encryptedWith}
 * stored a file reference would have stayed green over a server that could never actually negotiate
 * TLS; this one cannot.
 */
@Timeout(60)
class FlightServerTlsEndToEndTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private SelfSignedTestCertificate certificate;
    private BufferAllocator allocator;
    private PravahaFlightServer server;
    private FlightSqlClient client;

    @BeforeEach
    void startEncryptedServerAndConnectARealClient() throws IOException {
        certificate = SelfSignedTestCertificate.generate();
        allocator = new RootAllocator(Long.MAX_VALUE);

        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 10);
        view.applyValues(new Object[] {"u2", 50L}, 1, 10);
        view.commit(10);

        server = new PravahaFlightServer(new ViewCatalog().register(view), allocator)
                .encryptedWith(
                        certificate.pemCertificate().toFile(),
                        certificate.pemPrivateKey().toFile())
                .start("localhost", 0);

        assertThat(server.isEncrypted()).isTrue();

        client = new FlightSqlClient(
                org.apache.arrow.flight.FlightClient.builder(allocator, Location.forGrpcTls("localhost", server.port()))
                        .trustedCertificates(Files.newInputStream(certificate.pemCertificate()))
                        .build());
    }

    @AfterEach
    void stopServer() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (allocator != null) {
            allocator.close();
        }
        if (certificate != null) {
            certificate.close();
        }
    }

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    private List<String> query(String sql) {
        FlightInfo info = client.execute(sql);
        List<String> rows = new java.util.ArrayList<>();
        try (FlightStream stream = client.getStream(info.getEndpoints().get(0).getTicket())) {
            while (stream.next()) {
                VectorSchemaRoot root = stream.getRoot();
                VarCharVector ids = (VarCharVector) root.getVector(0);
                for (int i = 0; i < root.getRowCount(); i++) {
                    rows.add(new String(ids.get(i), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        }
        return rows;
    }

    @Test
    void aRealClientCompletesATlsHandshakeAgainstEncryptedWithAndReadsRealRows() {
        // This is the proof the coordinator asked for: not that encryptedWith stored two files, but
        // that a client with no special knowledge beyond the server's own certificate can negotiate
        // TLS against it and get an answer back. Before this test's dependency fix, this call did not
        // time out or refuse cleanly -- it threw NoClassDefFoundError deep inside grpc-netty's
        // handshake, the same failure sdk-java-flight had.
        assertThat(query("SELECT user_id FROM user_volume")).containsExactlyInAnyOrder("u1", "u2");
    }

    @SuppressWarnings("try") // Arrow's close() declares InterruptedException; a test has nothing to restore
    @Test
    void aClientThatDoesNotTrustTheCertificateIsRefused() throws Exception {
        // The other half of the proof: this is TLS actually checking something, not a transport that
        // happens to still work with verification effectively disabled. A client with an empty trust
        // store must fail the handshake against a self-signed certificate it was never told to trust.
        try (BufferAllocator untrustingAllocator = new RootAllocator(Long.MAX_VALUE);
                FlightSqlClient untrusting = new FlightSqlClient(org.apache.arrow.flight.FlightClient.builder(
                                untrustingAllocator, Location.forGrpcTls("localhost", server.port()))
                        .build())) {
            // The top-level FlightRuntimeException says only "io exception" -- Arrow's own
            // transport is not specific here -- so the proof this is a genuine certificate-trust
            // refusal, and not some other connection failure, is in the cause chain: an
            // SSLHandshakeException over a PKIX path-building failure naming exactly why an
            // untrusted self-signed certificate is rejected.
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> untrusting.execute("SELECT 1"))
                    .isNotNull()
                    .satisfies(e -> assertThat(causeChainOf(e))
                            .anyMatch(cause -> cause instanceof javax.net.ssl.SSLHandshakeException)
                            .anyMatch(cause -> cause.getMessage() != null
                                    && cause.getMessage().contains("PKIX path")));
        }
    }

    private static List<Throwable> causeChainOf(Throwable top) {
        List<Throwable> chain = new java.util.ArrayList<>();
        for (Throwable cause = top; cause != null; cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }
}
