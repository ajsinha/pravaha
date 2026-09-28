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

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three {@code docs/qa/cases/JOIN.md} cases that need a real node: {@code pravaha-engine run}
 * cannot run a join at all (JOIN-001), a two-stream join registered on a node does run (JOIN-002),
 * and a self-join registers (JOIN-060(c)).
 *
 * <p>An in-process {@link PravahaFlightServer} over a real {@link QueryRegistry}, no Docker and no
 * external process. {@code run} goes through {@link PravahaCli}; registering and reading go through
 * the Java SDK ({@link SdkVerbs}), since those commands moved to the Python CLI.
 */
@Timeout(120)
class JoinReachabilityAgainstServerTest {

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("order_id", Types.int64())
            .field("user_id", Types.string())
            .build();

    private static final StreamSchema USERS = StreamSchema.builder("users")
            .field("user_id", Types.string())
            .field("tier", Types.string())
            .build();

    private ViewCatalog views;
    private QueryRegistry registry;
    private PravahaFlightServer server;
    private RowArena arena;
    private String url;

    @BeforeEach
    void start() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, ORDERS, USERS);
        server = new PravahaFlightServer(views).hosting(registry).start("localhost", 0);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        url = "grpc://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        registry.close();
        arena.close();
    }

    /** {@code run} is pravaha-engine's own; the server verbs go through the SDK. */
    private CliResult cli(String... args) {
        if (!"run".equals(args[0])) {
            SdkVerbs.Result result = SdkVerbs.run(args);
            return new CliResult(result.code(), result.out(), result.err());
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code;
        try (java.io.PrintStream outStream =
                        new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8);
                java.io.PrintStream errStream =
                        new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8)) {
            code = new PravahaCli(outStream, errStream).run(args);
        }
        return new CliResult(
                code,
                out.toString(java.nio.charset.StandardCharsets.UTF_8),
                err.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private record CliResult(int code, String out, String err) {
        CliResult {
            System.out.println("CLI[" + code + "] out=" + out.replace('\n', '|') + " err=" + err.replace('\n', '|'));
        }
    }

    private void feed(String stream, StreamSchema schema, Object... values) {
        RegisteredQuery query = registry.require("v_join");
        RowLayout layout = RowLayout.of(schema);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Long l) {
                writer.setLong(i, l);
            } else {
                writer.setString(i, (String) values[i]);
            }
        }
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        // The two-stream accept(streamName, row) overload -- without it a registered join could only
        // ever be fed from whichever single stream a caller happened to name.
        query.accept(stream, view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(java.time.Duration.ofSeconds(10));
        query.commit();
    }

    // ======================= JOIN-001: `pravaha-engine run` cannot run a join at all =======================

    @Test
    void pravahaRunCannotRunATwoStreamJoinBecauseThereIsNoSecondStreamFlag(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("orders.csv");
        Files.writeString(in, "1,u1\n2,u9\n");
        Path out = dir.resolve("o.csv");

        // The only stream RunCommand knows about is named by --stream; there is no flag for a
        // second one, so a join naming "users" fails to resolve it, however the SQL is phrased.
        CliResult result = cli(
                "run",
                "--sql",
                "SELECT o.order_id, u.tier FROM orders o JOIN users u ON o.user_id = u.user_id",
                "--stream",
                "orders",
                "--schema",
                "order_id:INT64,user_id:STRING",
                "--in",
                in.toString(),
                "--out-schema",
                "order_id:INT64,tier:STRING",
                "--out",
                out.toString());

        assertThat(result.code()).as(result.out() + result.err()).isNotZero();
        // The catalog RunCommand builds knows only the one stream it was given.
        assertThat(result.err()).contains("users");
        assertThat(Files.exists(out))
                .as("no output file from a query that never planned")
                .isFalse();
    }

    // ======================= JOIN-002: a two-stream join registered on a node does run =======================

    @Test
    void aTwoStreamJoinRegisteredOnANodeDoesRun() {
        CliResult registered = cli(
                "register",
                "--url",
                url,
                "--name",
                "v_join",
                "--sql",
                "SELECT o.order_id, u.tier FROM orders o JOIN users u ON o.user_id = u.user_id",
                "--keys",
                "0");
        assertThat(registered.code()).as(registered.err()).isZero();

        feed("users", USERS, "u1", "gold");
        feed("users", USERS, "u2", "silver");
        feed("orders", ORDERS, 1L, "u1");
        feed("orders", ORDERS, 2L, "u2");
        feed("orders", ORDERS, 3L, "u9"); // matches nothing

        CliResult queried = cli("query", "--url", url, "--sql", "SELECT order_id, tier FROM v_join");
        assertThat(queried.code()).as(queried.err()).isZero();
        assertThat(queried.out()).contains("2 rows");
        assertThat(queried.out()).contains("gold").contains("silver");
        assertThat(queried.out()).as("u9 matched nothing and must not appear").doesNotContain("u9");
    }

    // ======================= JOIN-060(c): a self-join registers =======================

    @Test
    void aSelfJoinRegistersAgainstTheServer() {
        // JOIN-060(c) recorded this refused at registration with no server code of its own. A stream
        // read on both sides now runs: one entry point hands each row to both sides.
        CliResult registered = cli(
                "register",
                "--url",
                url,
                "--name",
                "v_self",
                "--sql",
                "SELECT a.order_id, b.order_id FROM orders a JOIN orders b ON a.user_id = b.user_id",
                "--keys",
                "0,1");

        assertThat(registered.code()).as(registered.err()).isZero();
    }
}
