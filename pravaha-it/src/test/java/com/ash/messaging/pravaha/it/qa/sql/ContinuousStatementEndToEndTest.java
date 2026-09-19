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
package com.ash.messaging.pravaha.it.qa.sql;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.cli.PravahaCli;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CREATE CONTINUOUS QUERY} end to end: a real node, a real source and sink plugin, the Java
 * SDK's {@code query()} and the CLI's {@code pravaha query --sql} -- no registration call anywhere.
 *
 * <p>The assertions are on what the registration did rather than on what the statement answered:
 * the bytes in the sink's file, the rows a read of the view returns, and the registration a restarted
 * node recovers from its journal.
 */
@Timeout(120)
class ContinuousStatementEndToEndTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String SCHEMA_SPEC = "user_id:STRING,amount:INT64";

    @Test
    void sql001_aQueryCreatedThroughTheSdkWritesItsSinkIsListedSurvivesARestartAndIsDroppedByTheCli(@TempDir Path dir)
            throws Exception {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "u1,300\nu2,50\nu3,700\n");
        Path output = dir.resolve("large.csv");
        Path journal = dir.resolve("registry.journal");

        PravahaNode node = node(input, output, journal);
        node.start();
        String url = "grpc://127.0.0.1:" + node.flightPort().orElseThrow();
        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            List<Object[]> created = rows(() -> client.query("""
                    CREATE CONTINUOUS QUERY big_txn
                        KEYED BY (user_id)
                        WRITING TO large_txn
                    AS SELECT user_id, amount FROM txn WHERE amount > 100
                    """));
            assertThat(created).singleElement().satisfies(row -> {
                assertThat(String.valueOf(row[0])).isEqualTo("big_txn");
                assertThat(String.valueOf(row[1])).isEqualTo("RUNNING");
                assertThat(String.valueOf(row[3])).isEqualTo("large_txn");
            });

            assertThat(awaitLines(output, 2))
                    .as("the filtered answer, in the file the sink binding names")
                    .containsExactlyInAnyOrder("u1,300", "u3,700");
            assertThat(awaitRows(() -> rows(() -> client.query("SELECT user_id FROM big_txn")), 2))
                    .as("and in the view, read by the name the statement gave it")
                    .hasSize(2);

            assertThat(rows(() -> client.query("SHOW CONTINUOUS QUERIES")))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(String.valueOf(row[0])).isEqualTo("big_txn");
                        assertThat(String.valueOf(row[5])).isEqualTo("user_id");
                        assertThat(String.valueOf(row[6])).isEqualTo("large_txn");
                    });
        } finally {
            node.stop();
        }

        // The statement journalled what the argument form journals: the restarted node recovers the
        // query writing to the same sink.
        Files.deleteIfExists(output);
        PravahaNode restarted = node(input, output, journal);
        restarted.start();
        try {
            assertThat(restarted.registry().orElseThrow().sinkOf("big_txn")).contains("large_txn");
            assertThat(awaitLines(output, 2)).containsExactlyInAnyOrder("u1,300", "u3,700");

            String restartedUrl = "grpc://127.0.0.1:" + restarted.flightPort().orElseThrow();
            Cli dropped = cli("query", "--sql", "DROP CONTINUOUS QUERY big_txn;", "--url", restartedUrl);
            assertThat(dropped.code()).as(dropped.err()).isZero();
            assertThat(dropped.out()).contains("big_txn").contains("DROPPED");
            assertThat(restarted.registry().orElseThrow().names()).isEmpty();
        } finally {
            restarted.stop();
        }
    }

    @Test
    void sql002_theCliCreatesListsReadsAndRefusesAMalformedStatementWithItsShape(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "u1,300\nu2,50\n");

        PravahaNode node = node(input, dir.resolve("unused.csv"), dir.resolve("registry.journal"));
        node.start();
        String url = "grpc://127.0.0.1:" + node.flightPort().orElseThrow();
        try {
            Cli created = cli(
                    "query",
                    "--sql",
                    "CREATE CONTINUOUS QUERY spend KEYED BY (user_id) RETAIN FOR PT24H "
                            + "AS SELECT amount, user_id FROM txn",
                    "--url",
                    url);
            assertThat(created.code()).as(created.err()).isZero();
            assertThat(created.out()).contains("spend").contains("RUNNING");
            assertThat(node.registry().orElseThrow().require("spend").view().keyOrdinals())
                    .as("user_id by name is output column 1")
                    .containsExactly(1);

            Cli listed = cli("query", "--sql", "SHOW CONTINUOUS QUERIES", "--url", url);
            assertThat(listed.code()).as(listed.err()).isZero();
            assertThat(listed.out()).contains("spend").contains("PT24H").contains("1 row");

            Cli malformed =
                    cli("query", "--sql", "CREATE CONTINUOUS QUERY spend2 AS SELECT amount FROM txn", "--url", url);
            assertThat(malformed.code()).isNotZero();
            assertThat(malformed.out() + malformed.err()).contains("PRV-2070").contains("KEYED BY");
            assertThat(node.registry().orElseThrow().names()).containsExactly("spend");
        } finally {
            node.stop();
        }
    }

    private record Cli(int code, String out, String err) {}

    private static Cli cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new PravahaCli(
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(args);
        return new Cli(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static List<Object[]> rows(Supplier<QueryResult> query) {
        try (QueryResult result = query.get()) {
            return result.toList();
        }
    }

    private static List<Object[]> awaitRows(Supplier<List<Object[]>> read, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<Object[]> rows = List.of();
        while (System.nanoTime() < deadline) {
            rows = read.get();
            if (rows.size() >= expected) {
                return rows;
            }
            Thread.sleep(50);
        }
        return rows;
    }

    private static PravahaNode node(Path input, Path output, Path journal) {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(TXN);

        SourceBindingProperties sources = new SourceBindingProperties();
        SourceBindingProperties.Spec source = new SourceBindingProperties.Spec();
        source.setPlugin("filesystem");
        source.getOptions().put("path", input.toString());
        source.getOptions().put("schema", SCHEMA_SPEC);
        sources.getSources().put("txn", source);

        SinkBindingProperties sinks = new SinkBindingProperties();
        SinkBindingProperties.Spec sink = new SinkBindingProperties.Spec();
        sink.setPlugin("filesystem");
        sink.getOptions().put("path", output.toString());
        sink.getOptions().put("schema", SCHEMA_SPEC);
        sinks.getSinks().put("large_txn", sink);

        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(journal.toString());

        return PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(sources)
                .withSinks(sinks)
                .withDeclaredStreams(new StreamDeclarationProperties())
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("statement-e2e")
                .build();
    }

    /** The feed commits on its own timer, so the file fills a moment after the rows are read. */
    private static List<String> awaitLines(Path file, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<String> lines = List.of();
        while (System.nanoTime() < deadline) {
            if (Files.exists(file)) {
                lines = Files.readAllLines(file).stream()
                        .filter(line -> !line.isBlank())
                        .toList();
                if (lines.size() >= expected) {
                    return lines;
                }
            }
            Thread.sleep(50);
        }
        return lines;
    }
}
