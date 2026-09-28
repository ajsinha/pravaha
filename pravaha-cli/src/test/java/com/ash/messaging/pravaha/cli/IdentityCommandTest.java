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
package com.ash.messaging.pravaha.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The identity commands against a stand-in for the engine's /api/v1, as the engine answers. */
class IdentityCommandTest {

    private HttpServer server;
    private final Map<String, String> authorization = new ConcurrentHashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        answer("/api/v1/auth/login", 200, "{\"token\":\"prv_s_abc\",\"mustChangePassword\":false,\"mfa\":\"ok\"}");
        answer(
                "/api/v1/users",
                200,
                "{\"users\":[{\"username\":\"ana\",\"roles\":[\"analyst\",\"operator\"],"
                        + "\"status\":\"active\",\"tenant\":\"public\",\"lastLoginAt\":null}]}");
        answer(
                "/api/v1/keys",
                200,
                "{\"key\":\"prv_qa_0123456789ab_secret\",\"keyId\":\"0123456789ab\","
                        + "\"expiresAt\":\"2026-12-26T00:00:00Z\"}");
        answer(
                "/api/v1/lanes",
                200,
                "{\"mode\":\"auto\",\"autoFrom\":64,\"maxQueriesPerLane\":300,\"sharedLanes\":[],"
                        + "\"ownLaneQueries\":2,\"dedicatedQueries\":1,\"hosted\":3}");
        answer(
                "/api/v1/queries",
                200,
                "[{\"name\":\"totals\",\"state\":\"RUNNING\",\"lane\":\"shared\",\"sharedLane\":0}]");
        answer(
                "/api/v1/lanes/rebalance",
                200,
                "{\"mode\":\"auto\",\"room\":1,\"running\":false,\"moves\":[{\"name\":\"totals\","
                        + "\"fromSharedLane\":0,\"status\":\"planned\",\"detail\":\"\"}]}");
        answer("/api/v1/sessions", 401, "{\"code\":\"PRV-7001\",\"message\":\"the credential was rejected\"}");
        server.start();
    }

    private void answer(String path, int status, String body) {
        server.createContext(path, exchange -> {
            authorization.put(path, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private int run(String command, String... args) {
        List<String> all = new java.util.ArrayList<>(List.of(args));
        all.add("--http");
        all.add("http://127.0.0.1:" + server.getAddress().getPort());
        return new IdentityCommand(new PrintStream(out, true), new PrintStream(err, true)).run(command, all);
    }

    @Test
    void loginPrintsTheSessionToken() {
        assertThat(run("login", "--user", "ana", "--password", "Correct-horse-9"))
                .isZero();
        assertThat(out.toString().strip()).isEqualTo("prv_s_abc");
    }

    @Test
    void usersPrintAsATableAndTheBearerIsSent() {
        assertThat(run("user", "list", "--token", "prv_s_abc")).isZero();
        assertThat(out.toString()).contains("USERNAME").contains("ana").contains("analyst,operator");
        assertThat(authorization.get("/api/v1/users")).isEqualTo("Bearer prv_s_abc");
    }

    @Test
    void aNewKeyIsShownOnce() {
        assertThat(run("key", "create", "reports", "--roles", "analyst", "--token", "prv_s_abc"))
                .isZero();
        assertThat(out.toString())
                .contains("key 0123456789ab")
                .contains("Shown once:")
                .contains("prv_qa_0123456789ab_secret");
    }

    @Test
    void aRefusalPrintsTheEnginesCodeAndExitsOne() {
        assertThat(run("session", "list", "--token", "bad")).isEqualTo(1);
        assertThat(err.toString()).contains("PRV-7001").contains("the credential was rejected");
    }

    @Test
    void lanesShowWhereEachQueryRunsAndARebalanceIsOnlyAPlanWithoutYes() {
        assertThat(run("lanes", "--token", "prv_s_abc")).isZero();
        assertThat(out.toString())
                .contains("mode auto, a lane each until 64")
                .contains("(1 dedicated)")
                .contains("totals")
                .contains("shared");

        out.reset();
        assertThat(run("lanes", "rebalance", "--token", "prv_s_abc")).isZero();
        assertThat(out.toString()).contains("room for 1").contains("planned").contains("--yes");
    }
}
