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
 * Java SDK and the CLI (ADR-047).
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
                    .as("the plan's edges came back with their counts")
                    .isNotEmpty()
                    .anySatisfy(operator -> assertThat(operator.kind()).isEqualTo("scan"));
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
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(TXN);

        SourceBindingProperties sources = new SourceBindingProperties();
        SourceBindingProperties.Spec source = new SourceBindingProperties.Spec();
        source.setPlugin("filesystem");
        source.getOptions().put("path", input.toString());
        source.getOptions().put("schema", SCHEMA_SPEC);
        sources.getSources().put("txn", source);

        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getCheckpoint().setDirectory(dir.resolve("checkpoints").toString());
        persistence.getCheckpoint().setInterval(Duration.ofMillis(200));
        persistence.getCheckpoint().setKeep(5);

        return PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(sources)
                .withSinks(new SinkBindingProperties())
                .withDeclaredStreams(new StreamDeclarationProperties())
                .withSecurity(security)
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("debug-surfaces")
                .build();
    }
}
