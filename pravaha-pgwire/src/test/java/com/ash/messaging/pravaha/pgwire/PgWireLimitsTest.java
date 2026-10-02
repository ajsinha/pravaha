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
package com.ash.messaging.pravaha.pgwire;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.StaticTokenVerifier;
import com.ash.messaging.pravaha.security.TokenVerifier;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PGPREAUTH-1 and PGREVOKE-1, over raw sockets: what an unauthenticated peer can make this server
 * hold, how many connections it holds at all, and what becomes of an open connection whose
 * credential is revoked.
 */
class PgWireLimitsTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private static final Principal DANA = new Principal("dana", "public", Set.of("analyst"), Map.of());

    private PravahaPgWireServer server;
    private final List<Socket> sockets = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        for (Socket socket : sockets) {
            socket.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static ViewCatalog populated() {
        ServedView view = new ServedView("user_volume", SCHEMA, List.of(0), 10_000);
        view.applyValues(new Object[] {"u1", 300L}, 1, 100);
        view.applyValues(new Object[] {"u2", 50L}, 1, 100);
        view.commit(100);
        return new ViewCatalog().register(view);
    }

    private static PgWireLimits limits(
            int max, int perPrincipal, int unauthenticated, Duration auth, int message, Duration idle) {
        return new PgWireLimits(max, perPrincipal, unauthenticated, auth, message, idle);
    }

    private PravahaPgWireServer start(TokenVerifier verifier, PgWireLimits limits) {
        PravahaPgWireServer built = new PravahaPgWireServer(populated()).limitedBy(limits);
        if (verifier != null) {
            built.authenticatedBy(verifier);
        }
        server = built.start("127.0.0.1", 0);
        return server;
    }

    private static PgTestClient signedIn(int port, String password) throws IOException {
        PgTestClient client = new PgTestClient(port);
        client.startup(Map.of("user", "dana"));
        assertThat(client.read().type()).isEqualTo('R');
        client.password(password);
        assertThat(PgTestClient.shape(client.readUntilReady())).endsWith("Z");
        return client;
    }

    /** A socket that has connected and said nothing. */
    private Socket silent() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 5_000);
        socket.setSoTimeout(10_000);
        sockets.add(socket);
        return socket;
    }

    /** The first backend message on a raw socket, read by hand: type, then the error fields. */
    private static Map<Character, String> errorOn(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        int type = in.read();
        assertThat((char) type).as("an ErrorResponse").isEqualTo('E');
        byte[] lengthBytes = in.readNBytes(4);
        int length = ByteBuffer.wrap(lengthBytes).getInt();
        byte[] payload = in.readNBytes(length - 4);
        return PgTestClient.errorFields(new PgTestClient.Message('E', payload));
    }

    private static void awaitOpen(PravahaPgWireServer server, int open) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (server.openConnections() != open && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(server.openConnections()).isEqualTo(open);
    }

    // ------------------------------------------------------------------ before authentication

    @Test
    void aPasswordMessageDeclaringSixteenMibIsRefusedOnItsLengthBeforeAnyOfItIsRead() throws Exception {
        start(StaticTokenVerifier.of("s3cret", DANA), PgWireLimits.DEFAULTS);
        try (PgTestClient client = new PgTestClient(server.port())) {
            client.startup(Map.of("user", "dana"));
            assertThat(client.read().type()).isEqualTo('R');
            // The QI-012 attack: a 'p' declaring 16 MiB, and then nothing. It used to be allocated in
            // full and held for the handshake's ten seconds; sixty of them ended a 1 GiB node.
            client.raw(ByteBuffer.allocate(5)
                    .put((byte) 'p')
                    .putInt(16 * 1024 * 1024)
                    .array());
            Map<Character, String> error = PgTestClient.errorFields(client.read());
            assertThat(error).containsEntry('S', "FATAL").containsEntry('C', "54000");
            assertThat(error.get('M')).contains("PRV-6217").contains("before authentication");
            assertThat(client.read()).as("and the connection closes").isNull();
        }
        awaitOpen(server, 0);
    }

    @Test
    void anSslRequestWithPaddingIsAProtocolViolationNotABufferToFill() throws Exception {
        start(null, PgWireLimits.DEFAULTS);
        try (PgTestClient client = new PgTestClient(server.port())) {
            ByteBuffer request = ByteBuffer.allocate(9000).putInt(9000).putInt(PgFrontend.SSL_REQUEST_CODE);
            client.raw(request.array());
            Map<Character, String> error = PgTestClient.errorFields(client.read());
            assertThat(error).containsEntry('C', "08P01");
            assertThat(error.get('M')).contains("the protocol says 8");
        }
    }

    @Test
    void pastMaxUnauthenticatedANewSocketIsRefusedAtOnceWith53300AndASignedInOneDoesNotCount() throws Exception {
        start(StaticTokenVerifier.of("s3cret", DANA), limits(10, 0, 2, Duration.ofSeconds(30), 1 << 20, Duration.ZERO));
        try (PgTestClient dana = signedIn(server.port(), "s3cret")) {
            silent();
            silent();
            awaitOpen(server, 3);
            Map<Character, String> error = errorOn(silent());
            assertThat(error).containsEntry('S', "FATAL").containsEntry('C', "53300");
            assertThat(error.get('M')).contains("PRV-6216").contains("pravaha.pgwire.limits.max-unauthenticated");

            dana.query("SELECT user_id FROM user_volume");
            assertThat(PgTestClient.shape(dana.readUntilReady()))
                    .as("the signed-in connection is unaffected")
                    .isEqualTo("TDDCZ");
        }
    }

    @Test
    void pastMaxConnectionsANewSocketIsRefusedAndAClosedOneFreesItsSlot() throws Exception {
        start(null, limits(2, 0, 2, Duration.ofSeconds(30), 1 << 20, Duration.ZERO));
        Socket first = silent();
        silent();
        awaitOpen(server, 2);
        Map<Character, String> error = errorOn(silent());
        assertThat(error).containsEntry('C', "53300");
        assertThat(error.get('M')).contains("too many clients").contains("pravaha.pgwire.limits.max-connections");

        first.close();
        awaitOpen(server, 1);
        try (PgTestClient client = new PgTestClient(server.port())) {
            client.startup(Map.of("user", "anyone"));
            assertThat(PgTestClient.shape(client.readHandshake())).endsWith("Z");
        }
    }

    @Test
    void theHandshakeDeadlineIsOneDeadlineThatAByteEveryFewHundredMillisecondsDoesNotRenew() throws Exception {
        start(StaticTokenVerifier.of("s3cret", DANA), limits(10, 0, 4, Duration.ofMillis(700), 1 << 20, Duration.ZERO));
        Socket trickle = silent();
        OutputStream out = trickle.getOutputStream();
        long started = System.nanoTime();
        // A startup packet declaring 200 bytes, sent one byte at a time: each byte renews a per-read
        // timeout, and only an overall deadline ends it.
        byte[] packet = ByteBuffer.allocate(200)
                .putInt(200)
                .putInt(PgFrontend.PROTOCOL_VERSION_3_0)
                .array();
        boolean closed = false;
        for (int at = 0; at < packet.length && !closed; at++) {
            try {
                out.write(packet[at]);
                out.flush();
                Thread.sleep(150);
            } catch (IOException reset) {
                closed = true;
            }
            if (server.openConnections() == 0) {
                closed = true;
            }
        }
        assertThat(closed).as("the server let go of the trickling peer").isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        awaitOpen(server, 0);
    }

    // ------------------------------------------------------------------ after authentication

    @Test
    void aSignedInMessageLargerThanMaxMessageSizeIsRefusedAndOneUnderItIsReadInPieces() throws Exception {
        start(null, limits(10, 0, 4, Duration.ofSeconds(10), 256 * 1024, Duration.ZERO));
        try (PgTestClient client = new PgTestClient(server.port())) {
            client.startup(Map.of("user", "anyone"));
            client.readHandshake();
            // Larger than one read chunk, smaller than the cap: read as it arrives, and answered.
            client.query("SELECT user_id FROM user_volume" + " ".repeat(150_000));
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("TDDCZ");

            client.raw(ByteBuffer.allocate(5).put((byte) 'Q').putInt(512 * 1024).array());
            Map<Character, String> error = PgTestClient.errorFields(client.read());
            assertThat(error).containsEntry('S', "FATAL").containsEntry('C', "54000");
            assertThat(error.get('M')).contains("PRV-6217").contains("pravaha.pgwire.limits.max-message-size");
        }
    }

    @Test
    void oneCredentialMayHoldOnlyItsShareOfConnections() throws Exception {
        start(StaticTokenVerifier.of("s3cret", DANA), limits(10, 1, 4, Duration.ofSeconds(10), 1 << 20, Duration.ZERO));
        PgTestClient first = signedIn(server.port(), "s3cret");
        try (PgTestClient second = new PgTestClient(server.port())) {
            second.startup(Map.of("user", "dana"));
            assertThat(second.read().type()).isEqualTo('R');
            second.password("s3cret");
            List<PgTestClient.Message> answer = second.readUntilReady();
            Map<Character, String> error =
                    PgTestClient.errorFields(PgTestClient.ofType(answer, 'E').get(0));
            assertThat(error).containsEntry('C', "53300");
            assertThat(error.get('M')).contains("pravaha.pgwire.limits.max-connections-per-principal");
        }
        first.terminate();
        first.close();
        awaitOpen(server, 0);
        try (PgTestClient third = signedIn(server.port(), "s3cret")) {
            third.query("SELECT user_id FROM user_volume");
            assertThat(PgTestClient.shape(third.readUntilReady())).isEqualTo("TDDCZ");
        }
    }

    @Test
    void anIdleSignedInConnectionIsEndedWith57P05AfterIdleTimeout() throws Exception {
        start(null, limits(10, 0, 4, Duration.ofSeconds(10), 1 << 20, Duration.ofMillis(300)));
        try (PgTestClient client = new PgTestClient(server.port())) {
            client.startup(Map.of("user", "anyone"));
            client.readHandshake();
            Map<Character, String> error = PgTestClient.errorFields(client.read());
            assertThat(error).containsEntry('S', "FATAL").containsEntry('C', "57P05");
            assertThat(error.get('M')).contains("PRV-6219").contains("idle-session timeout");
        }
        awaitOpen(server, 0);
    }

    @Test
    void limitsOutOfRangeAreRefusedByName() {
        assertThatThrownBy(() -> limits(0, 0, 1, Duration.ofSeconds(1), 1 << 20, Duration.ZERO))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-6220")
                .hasMessageContaining("max-connections");
        assertThatThrownBy(() -> limits(10, 0, 11, Duration.ofSeconds(1), 1 << 20, Duration.ZERO))
                .hasMessageContaining("max-unauthenticated");
        assertThatThrownBy(() -> limits(10, 0, 1, Duration.ZERO, 1 << 20, Duration.ZERO))
                .hasMessageContaining("authentication-timeout");
        assertThatThrownBy(() -> limits(10, 0, 1, Duration.ofSeconds(1), 1024, Duration.ZERO))
                .hasMessageContaining("max-message-size");
    }

    // ------------------------------------------------------------------ revocation (PGREVOKE-1)

    /** A verifier whose credentials can be revoked while a connection is open, as identity's can. */
    private static final class Revocable implements TokenVerifier {

        private final Map<String, Principal> valid = new ConcurrentHashMap<>();

        @Override
        public Principal verify(String token) {
            Principal principal = token == null ? null : valid.get(token);
            if (principal == null) {
                throw new PravahaException(SecurityErrors.UNAUTHENTICATED, "the credential was rejected");
            }
            return principal;
        }
    }

    @Test
    void aRevokedCredentialEndsItsOpenConnectionAtTheNextSimpleQueryWithFatal28000() throws Exception {
        Revocable verifier = new Revocable();
        verifier.valid.put("prv_key", DANA);
        start(verifier, PgWireLimits.DEFAULTS);
        try (PgTestClient client = signedIn(server.port(), "prv_key")) {
            client.query("SELECT user_id FROM user_volume");
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("TDDCZ");

            verifier.valid.remove("prv_key"); // DELETE /api/v1/keys/{id}, a logout, a disabled user

            client.query("SELECT user_id FROM user_volume");
            List<PgTestClient.Message> answer = client.readUntilReady();
            assertThat(PgTestClient.shape(answer))
                    .as("no rows, one FATAL, and the end")
                    .isEqualTo("E");
            Map<Character, String> error = PgTestClient.errorFields(answer.get(0));
            assertThat(error).containsEntry('S', "FATAL").containsEntry('C', "28000");
            assertThat(error.get('M')).contains("PRV-6218").contains("no longer accepted");
            assertThat(client.read()).isNull();
        }
        awaitOpen(server, 0);
    }

    @Test
    void aRevokedCredentialEndsItsOpenConnectionAtTheNextExtendedQueryMessageToo() throws Exception {
        Revocable verifier = new Revocable();
        verifier.valid.put("prv_session", DANA);
        start(verifier, PgWireLimits.DEFAULTS);
        try (PgTestClient client = signedIn(server.port(), "prv_session")) {
            client.parse("s1", "SELECT user_id FROM user_volume");
            client.bind("", "s1", List.of());
            client.execute("", 0);
            client.sync();
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("12DDCZ");

            verifier.valid.remove("prv_session");

            // A statement prepared before the revocation is no way round it.
            client.bind("", "s1", List.of());
            client.execute("", 0);
            client.sync();
            Map<Character, String> error = PgTestClient.errorFields(client.read());
            assertThat(error).containsEntry('C', "28000");
            assertThat(client.read()).isNull();
        }
    }

    @Test
    void aCredentialThatNowVerifiesWithFewerRolesIsTheOneTheNextStatementRunsAs() throws Exception {
        Revocable verifier = new Revocable();
        verifier.valid.put("prv_key", DANA);
        start(verifier, PgWireLimits.DEFAULTS);
        try (PgTestClient client = signedIn(server.port(), "prv_key")) {
            verifier.valid.put("prv_key", new Principal("dana", "public", Set.of(), Map.of()));
            client.query("SELECT user_id FROM user_volume");
            // The permissive policy serves either way; what matters is that the connection survives a
            // change that is not a revocation.
            assertThat(PgTestClient.shape(client.readUntilReady())).isEqualTo("TDDCZ");
            verifier.valid.put("prv_key", new Principal("mallory", "public", Set.of("admin"), Map.of()));
            client.query("SELECT user_id FROM user_volume");
            assertThat(PgTestClient.errorFields(client.read()))
                    .as("the same credential naming somebody else is not the same sign-in")
                    .containsEntry('C', "28000");
        }
    }
}
