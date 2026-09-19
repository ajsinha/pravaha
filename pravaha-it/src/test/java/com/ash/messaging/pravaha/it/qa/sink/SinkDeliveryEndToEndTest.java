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
package com.ash.messaging.pravaha.it.qa.sink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-039 item 5, end to end: a query registered over the wire with {@code --sink} writes its
 * answer into the sink the node binds under {@code pravaha.sinks}, through a real plugin.
 *
 * <p>Every piece of this existed before and none of it was connected: the node bound sinks and
 * validated them, the registry negotiated a changelog against one, and nothing ever wrote a row to
 * any of them. The assertion is on the bytes in the output file, because that is the only place the
 * difference between "attached" and "delivering" shows.
 */
@Timeout(90)
class SinkDeliveryEndToEndTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String SCHEMA_SPEC = "user_id:STRING,amount:INT64";

    @Test
    void sink001_aQueryRegisteredWithASinkWritesItsAnswerThroughARealPlugin(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "u1,300\nu2,50\nu3,700\n");
        Path output = dir.resolve("large.csv");
        Path journal = dir.resolve("registry.journal");

        PravahaNode node = node(input, output, journal);
        node.start();
        try (PravahaFlightClient client = PravahaFlightClient.connect(
                "grpc://127.0.0.1:" + node.flightPort().orElseThrow())) {
            client.register("big_txn", "SELECT user_id, amount FROM txn WHERE amount > 100", List.of(0), "large_txn");

            List<String> written = awaitLines(output, 2);
            assertThat(written)
                    .as("the filtered answer, in the file the sink binding names -- not only in the view")
                    .containsExactlyInAnyOrder("u1,300", "u3,700");
            assertThat(node.registry().orElseThrow().sinkOf("big_txn")).contains("large_txn");
        } finally {
            node.stop();
        }

        // The journal carried the sink, and the node binds sinks before it recovers: the query comes
        // back writing to the same place rather than only to its view.
        Files.deleteIfExists(output);
        PravahaNode restarted = node(input, output, journal);
        restarted.start();
        try {
            assertThat(restarted.registry().orElseThrow().sinkOf("big_txn")).contains("large_txn");
            assertThat(awaitLines(output, 2)).containsExactlyInAnyOrder("u1,300", "u3,700");
        } finally {
            restarted.stop();
        }
    }

    @Test
    void sink002_aRevisingQueryAgainstAnAppendOnlySinkIsRefusedOverTheWireWithPrv2041(@TempDir Path dir)
            throws Exception {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "u1,300\n");
        Path output = dir.resolve("never.csv");

        PravahaNode node = node(input, output, dir.resolve("registry.journal"));
        node.start();
        try (PravahaFlightClient client = PravahaFlightClient.connect(
                "grpc://127.0.0.1:" + node.flightPort().orElseThrow())) {
            // A running count revises its answer on every row; a file can only be appended to. The
            // pair is refused at registration, before the sink is opened -- and the filesystem sink
            // truncates its file when it opens, so "before" is visible on disk.
            assertThatThrownBy(() -> client.register("n", "SELECT COUNT(*) FROM txn", List.of(0), "large_txn"))
                    .hasMessageContaining("PRV-2041");
            assertThat(node.registry().orElseThrow().names()).doesNotContain("n");
            assertThat(output).doesNotExist();
        } finally {
            node.stop();
        }
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
                .withNodeId("sink-e2e")
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
