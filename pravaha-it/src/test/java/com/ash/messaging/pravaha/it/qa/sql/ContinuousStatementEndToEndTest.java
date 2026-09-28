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
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.Row;
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
 * SDK's {@code query()} for every statement, the DROP included -- no registration call anywhere.
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
    void sql001_aQueryCreatedThroughTheSdkWritesItsSinkIsListedSurvivesARestartAndIsDroppedByAStatementOfItsOwn(
            @TempDir Path dir) throws Exception {
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
            Cli dropped = sql(restartedUrl, "DROP CONTINUOUS QUERY big_txn;");
            assertThat(dropped.code()).as(dropped.err()).isZero();
            assertThat(dropped.out()).contains("big_txn").contains("DROPPED");
            assertThat(restarted.registry().orElseThrow().names()).isEmpty();
        } finally {
            restarted.stop();
        }
    }

    @Test
    void sql002_statementsCreateListAndRefuseAMalformedOneWithItsShape(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "u1,300\nu2,50\n");

        PravahaNode node = node(input, dir.resolve("unused.csv"), dir.resolve("registry.journal"));
        node.start();
        String url = "grpc://127.0.0.1:" + node.flightPort().orElseThrow();
        try {
            Cli created = sql(
                    url,
                    "CREATE CONTINUOUS QUERY spend KEYED BY (user_id) RETAIN FOR PT24H "
                            + "AS SELECT amount, user_id FROM txn");
            assertThat(created.code()).as(created.err()).isZero();
            assertThat(created.out()).contains("spend").contains("RUNNING");
            assertThat(node.registry().orElseThrow().require("spend").view().keyOrdinals())
                    .as("user_id by name is output column 1")
                    .containsExactly(1);

            Cli listed = sql(url, "SHOW CONTINUOUS QUERIES");
            assertThat(listed.code()).as(listed.err()).isZero();
            assertThat(listed.out()).contains("spend").contains("PT24H").contains("1 row");

            Cli malformed = sql(url, "CREATE CONTINUOUS QUERY spend2 AS SELECT amount FROM txn");
            assertThat(malformed.code()).isNotZero();
            assertThat(malformed.out() + malformed.err()).contains("PRV-2070").contains("KEYED BY");
            assertThat(node.registry().orElseThrow().names()).containsExactly("spend");
        } finally {
            node.stop();
        }
    }

    private record Cli(int code, String out, String err) {}

    /**
     * One statement through a fresh SDK connection, printed the way a terminal client prints it: a
     * header, one tab-separated line per row, and a row count. A refusal is exit 1 with the server's
     * own message.
     */
    private static Cli sql(String url, String statement) {
        StringBuilder out = new StringBuilder();
        try (PravahaFlightClient client = PravahaFlightClient.connect(url);
                QueryResult result = client.query(statement)) {
            out.append(String.join("\t", result.columns())).append('\n');
            int rows = 0;
            for (Row row : result) {
                for (int i = 0; i < row.columns().size(); i++) {
                    out.append(i == 0 ? "" : "\t").append(row.getString(i));
                }
                out.append('\n');
                rows++;
            }
            out.append(rows).append(rows == 1 ? " row" : " rows").append('\n');
            return new Cli(0, out.toString(), "");
        } catch (RuntimeException e) {
            return new Cli(1, out.toString(), String.valueOf(e.getMessage()));
        }
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
