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
package com.ash.messaging.pravaha.server.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTPBODY-1 on a real port: a body is bounded before anything reads it. (The bound on sign-ins at
 * once is {@code RequestLimitFilterTest}'s, where it is deterministic.)
 *
 * <p>A socket rather than an HTTP client, because what is being tested is what the server does
 * before it has the body: a client library would send the whole body first.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "pravaha.flight.port=0",
            "pravaha.security.authentication=token",
            "pravaha.security.policy=authenticated",
            "pravaha.identity.enabled=true",
            "pravaha.identity.environment=qa",
            "pravaha.identity.dev=true"
        })
class RequestLimitHttpTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void store(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("pravaha-request-limits");
        registry.add(
                "pravaha.identity.store", () -> dir.resolve("identity.journal").toString());
    }

    /** The status line and body of the answer to {@code head} followed by {@code body}. */
    private String exchange(String head, byte[] body) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            if (body != null) {
                out.write(body);
            }
            out.flush();
            // Half-closed, so a server waiting for the rest of a declared body sees there is none
            // and closes after its answer, rather than at its read timeout.
            socket.shutdownOutput();
            return readAll(socket.getInputStream());
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        try {
            while ((n = in.read(buffer)) > 0) {
                answer.write(buffer, 0, n);
            }
        } catch (java.net.SocketException reset) {
            // The server closed after answering; what arrived is the answer.
        }
        return answer.toString(StandardCharsets.UTF_8);
    }

    private static String loginHead(long contentLength) {
        return "POST /api/v1/auth/login HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Connection: close\r\nContent-Length: " + contentLength + "\r\n\r\n";
    }

    @Test
    void aNineteenMegabyteSignInIsRefused413OnItsContentLengthWithoutSendingTheBody() throws Exception {
        // QI-045: thirty of these ran a 1 GiB node out of heap. The body is never sent: the answer
        // comes on the declaration alone.
        String answer = exchange(loginHead(19L * 1024 * 1024), null);
        assertThat(answer).startsWith("HTTP/1.1 413");
        assertThat(answer)
                .contains("\"code\":\"PRV-1054\"")
                .contains("pravaha.http.max-anonymous-body")
                .contains("\"path\":\"/api/v1/auth/login\"");
    }

    @Test
    void aChunkedSignInBodyIsStoppedAtTheLimitAsItIsRead() throws Exception {
        StringBuilder chunks = new StringBuilder();
        String piece = "{\"username\":\"" + "a".repeat(8000);
        chunks.append(Integer.toHexString(piece.length()))
                .append("\r\n")
                .append(piece)
                .append("\r\n");
        String more = "b".repeat(8000);
        for (int i = 0; i < 4; i++) {
            chunks.append(Integer.toHexString(more.length()))
                    .append("\r\n")
                    .append(more)
                    .append("\r\n");
        }
        chunks.append("0\r\n\r\n");
        String answer = exchange(
                "POST /api/v1/auth/login HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                        + "Connection: close\r\nTransfer-Encoding: chunked\r\n\r\n",
                chunks.toString().getBytes(StandardCharsets.US_ASCII));
        assertThat(answer).startsWith("HTTP/1.1 413");
        assertThat(answer).contains("\"code\":\"PRV-1054\"").contains("pravaha.http.max-anonymous-body");
    }

    @Test
    void anAuthenticatedPathTakesTheLargerLimitAndTheCredentialIsAskedForFirst() throws Exception {
        byte[] body = ("{\"sql\":\"SELECT 1 " + " ".repeat(50_000) + "\"}").getBytes(StandardCharsets.UTF_8);
        String answer = exchange(
                "POST /api/v1/queries/validate HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                        + "Connection: close\r\nContent-Length: " + body.length + "\r\n\r\n",
                body);
        assertThat(answer)
                .as("50 KB is over the anonymous limit and under the authenticated one: the answer is the "
                        + "missing credential, not the size")
                .startsWith("HTTP/1.1 401")
                .contains("PRV-7001");

        String tooLarge = exchange(
                "POST /api/v1/queries/validate HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                        + "Connection: close\r\nContent-Length: " + (5L * 1024 * 1024) + "\r\n\r\n",
                null);
        assertThat(tooLarge).startsWith("HTTP/1.1 413").contains("pravaha.http.max-request-body");
    }

    @Test
    void anOrdinarySignInStillWorks() throws Exception {
        byte[] body = "{\"username\":\"admin\",\"password\":\"pravaha-dev-admin\"}".getBytes(StandardCharsets.UTF_8);
        String answer = exchange(loginHead(body.length), body);
        assertThat(answer).startsWith("HTTP/1.1 200").contains("\"token\"");
    }
}
