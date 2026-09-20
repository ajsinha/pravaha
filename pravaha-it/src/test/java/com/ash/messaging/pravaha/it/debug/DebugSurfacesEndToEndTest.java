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
package com.ash.messaging.pravaha.it.debug;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.cli.PravahaCli;
import com.ash.messaging.pravaha.sdk.flight.DebugSessionInfo;
import com.ash.messaging.pravaha.sdk.flight.DebugStatePage;
import com.ash.messaging.pravaha.sdk.flight.DebugStepReport;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.egress.SinkBindingProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The debugger through the surfaces a person actually uses: a real node, the Flight actions, the
 * Java SDK and the CLI (ADR-048).
 *
 * <p>{@code DebugSessionTest} proves the engine; this proves the wire. The control wire is a flat
 * list of strings and a step's report is not flat -- it carries three variable-length lists -- so
 * the thing most likely to be wrong is the encoding, and the only way to find out is to put a
 * report through it and read it back on the other side.
 *
 * <p>The arrangement is the same as the engine tests': the query reads a short file, is paused so
 * its offsets stop moving, is checkpointed at that position, and then more rows are appended for a
 * fork to step through.
 */
@Timeout(240)
class DebugSurfacesEndToEndTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String SCHEMA_SPEC = "user_id:STRING,amount:INT64";

    private static final String SQL = "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn";

    @Test
    void aSessionCanBeForkedSteppedInspectedAndExportedOverFlightAndFromTheCli(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "ann,100\nbob,250\n");

        PravahaNode node = node(dir, input);
        node.start();
        String url = "grpc://127.0.0.1:" + node.flightPort().orElseThrow();
        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            client.register("spend", SQL, List.of(0));
            await(() -> !client.debugCheckpoints("spend").isEmpty(), "the query to take its first checkpoint");
            client.pause("spend");
            List<Long> already = client.debugCheckpoints("spend");
            await(
                    () -> client.debugCheckpoints("spend").stream().anyMatch(id -> !already.contains(id)),
                    "a checkpoint taken while the feed was paused");
            long checkpoint = client.debugCheckpoints("spend").stream()
                    .filter(id -> !already.contains(id))
                    .max(Long::compare)
                    .orElseThrow();

            Files.writeString(input, "cat,7\ndan,11\neve,13\n", StandardOpenOption.APPEND);

            DebugSessionInfo session = client.debugFork("spend", checkpoint);
            assertThat(session.query()).isEqualTo("spend");
            assertThat(session.checkpointId()).isEqualTo(checkpoint);
            assertThat(session.sinksDisabled()).isTrue();
            assertThat(session.streams()).containsExactly("txn");
            assertThat(client.debugSessions()).extracting(DebugSessionInfo::id).contains(session.id());

            DebugStepReport first = client.debugStep(session.id(), "row");
            assertThat(first.sequence()).isEqualTo(1);
            assertThat(first.rowsIn())
                    .as("one row, decoded off the wire with its stream, weight and values intact")
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.stream()).isEqualTo("txn");
                        assertThat(row.weight()).isEqualTo(1);
                        assertThat(row.values()).containsExactly("cat", "7");
                    });
            assertThat(first.operators())
                    .as("every plan node came back with its own id, kind, label and counts")
                    .isNotEmpty()
                    .anySatisfy(operator -> {
                        assertThat(operator.kind()).isEqualTo("Scan");
                        assertThat(operator.label()).contains("txn");
                        assertThat(operator.id()).startsWith("n");
                    });
            assertThat(first.viewChanges())
                    .as("the count was (2, 350) and is now (3, 357)")
                    .hasSize(2);
            assertThat(first.stopped()).isNotBlank();

            DebugStepReport rest = client.debugStep(session.id(), "rows:10");
            assertThat(rest.exhausted()).isTrue();
            assertThat(rest.rowsConsumed()).isEqualTo(3);

            assertThat(client.debugState(session.id()))
                    .extracting(PravahaFlightClient.DebugStateSlot::id)
                    .containsExactly("global#0");
            DebugStatePage page = client.debugInspect(session.id(), "global#0", null, 0, 10);
            assertThat(page.entries())
                    .singleElement()
                    .satisfies(entry ->
                            assertThat(entry.values()).containsEntry("n", "5").containsEntry("total", "381"));

            assertThat(client.debugView(session.id()))
                    .singleElement()
                    .satisfies(row -> assertThat(row.values()).containsExactly("5", "381"));

            PravahaFlightClient.DebugFixture fixture = client.debugExport(session.id(), "spend goes up");
            assertThat(fixture.className()).isEqualTo("SpendGoesUpFixtureTest");
            assertThat(fixture.source()).contains(SQL).contains("harness.row(\"txn\"");

            // And from the CLI, against the same node: the two ends of the journey design 23.9
            // says a terminal is good for.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code = new PravahaCli(new PrintStream(out, true, StandardCharsets.UTF_8), System.err)
                    .run(new String[] {"debug", "sessions", "--url", url});
            assertThat(code).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8))
                    .as("the CLI lists the session the SDK opened")
                    .contains(session.id())
                    .contains("spend");

            ByteArrayOutputStream stepped = new ByteArrayOutputStream();
            assertThat(new PravahaCli(new PrintStream(stepped, true, StandardCharsets.UTF_8), System.err)
                            .run(new String[] {
                                "debug", "step", "--session", session.id(), "--step", "watermark:1", "--url", url
                            }))
                    .isZero();
            assertThat(stepped.toString(StandardCharsets.UTF_8)).contains("WATERMARK");

            Path written = dir.resolve("fixtures");
            assertThat(new PravahaCli(
                                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                                    System.err)
                            .run(new String[] {
                                "debug",
                                "fixture",
                                "--session",
                                session.id(),
                                "--name",
                                "spend goes up",
                                "--out",
                                written.toString(),
                                "--url",
                                url
                            }))
                    .isZero();
            assertThat(written.resolve("SpendGoesUpFixtureTest.java")).exists();

            client.debugEnd(session.id());
            assertThat(client.debugSessions()).extracting(DebugSessionInfo::id).doesNotContain(session.id());
        } finally {
            node.stop();
        }
    }

    @Test
    void aForkOfAQueryThatWritesToASinkWritesNothingToIt(@TempDir Path dir) throws Exception {
        // The claim "sinks are hard-disabled" is worth nothing asserted, so it is made against a
        // real sink: a query bound to a filesystem sink is forked, the fork consumes rows the live
        // query never read, and the file it would have written is byte for byte what it was.
        Path input = dir.resolve("txn.csv");
        Files.writeString(input, "ann,300\nbob,50\n");
        Path output = dir.resolve("large.csv");

        PravahaNode node = node(dir, input, output);
        node.start();
        String url = "grpc://127.0.0.1:" + node.flightPort().orElseThrow();
        try (PravahaFlightClient client = PravahaFlightClient.connect(url)) {
            // A filter, not an aggregate: a query that revises its answer is refused against an
            // append-only sink (PRV-2041) before it ever reaches a debugger.
            client.register("big", "SELECT user_id, amount FROM txn WHERE amount > 100", List.of(0), "large_txn");
            await(() -> !client.debugCheckpoints("big").isEmpty(), "the query to take its first checkpoint");
            client.pause("big");
            List<Long> already = client.debugCheckpoints("big");
            await(
                    () -> client.debugCheckpoints("big").stream().anyMatch(id -> !already.contains(id)),
                    "a checkpoint taken while the feed was paused");
            long checkpoint = client.debugCheckpoints("big").stream()
                    .filter(id -> !already.contains(id))
                    .max(Long::compare)
                    .orElseThrow();
            await(() -> Files.exists(output), "the live query to write what it read");
            String before = Files.readString(output);

            // Four rows past the checkpoint, three of which the sink would take.
            Files.writeString(input, "cat,700\ndan,10\neve,900\nfay,400\n", StandardOpenOption.APPEND);

            DebugSessionInfo session = client.debugFork("big", checkpoint);
            DebugStepReport stepped = client.debugStep(session.id(), "rows:10");
            assertThat(stepped.rowsConsumed()).as("the fork read all four").isEqualTo(4);
            assertThat(client.debugView(session.id()))
                    .as("and its own view holds the one the checkpoint carried plus the three the filter passed")
                    .hasSize(4);

            // Twice, a moment apart: a sink write is on the commit path, so a file compared once
            // immediately would pass even if a write were in flight.
            Thread.sleep(500);
            assertThat(Files.readString(output))
                    .as("the sink the live query writes to has not been written by the fork")
                    .isEqualTo(before);
            assertThat(before).doesNotContain("cat").doesNotContain("eve").doesNotContain("fay");

            client.debugEnd(session.id());
            Thread.sleep(200);
            assertThat(Files.readString(output)).as("nor by its release").isEqualTo(before);
        } finally {
            node.stop();
        }
    }

    private static void await(java.util.function.BooleanSupplier until, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (until.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(until.getAsBoolean()).as("waited 30s for %s", what).isTrue();
    }

    private static PravahaNode node(Path dir, Path input) {
        return node(dir, input, null);
    }

    private static PravahaNode node(Path dir, Path input, Path sinkFile) {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(TXN);

        SourceBindingProperties sources = new SourceBindingProperties();
        SourceBindingProperties.Spec source = new SourceBindingProperties.Spec();
        source.setPlugin("filesystem");
        source.getOptions().put("path", input.toString());
        source.getOptions().put("schema", SCHEMA_SPEC);
        sources.getSources().put("txn", source);

        SinkBindingProperties sinks = new SinkBindingProperties();
        if (sinkFile != null) {
            SinkBindingProperties.Spec sink = new SinkBindingProperties.Spec();
            sink.setPlugin("filesystem");
            sink.getOptions().put("path", sinkFile.toString());
            sink.getOptions().put("schema", SCHEMA_SPEC);
            sinks.getSinks().put("large_txn", sink);
        }

        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getCheckpoint().setDirectory(dir.resolve("checkpoints").toString());
        persistence.getCheckpoint().setInterval(Duration.ofMillis(200));
        persistence.getCheckpoint().setKeep(5);

        return PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(sources)
                .withSinks(sinks)
                .withDeclaredStreams(new StreamDeclarationProperties())
                .withSecurity(security)
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("debug-surfaces")
                .build();
    }
}
