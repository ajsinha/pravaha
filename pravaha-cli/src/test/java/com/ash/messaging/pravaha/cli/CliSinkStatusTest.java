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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.flight.PravahaFlightServer;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.SinkFactory;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A query's sink in {@code pravaha queries}, and {@code PRV-8009} where somebody will see it.
 *
 * <p>The listing had no sink in it at all: which table a query writes to was answerable from the
 * HTTP API and from nowhere an operator at a terminal would look. Worse, a sink that refused a
 * batch is detached and never written to again -- the query stays {@code RUNNING} and its view
 * stays right, so the listing said everything was fine while the table it was registered to fill
 * stopped filling. `PRV-8009` was recorded, surfaced over REST and drawn in the console, and was
 * unreachable from the CLI.
 *
 * <p>End to end through the real server: the registry detaches, the Flight listing carries it, the
 * SDK parses it and the CLI prints it.
 */
@Timeout(90)
class CliSinkStatusTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", Types.int64())
            .field("amount", Types.int64())
            .build();

    private final FailingSinks sinks = new FailingSinks();

    private QueryRegistry registry;
    private PravahaFlightServer server;
    private RowArena arena;
    private String url;

    @BeforeEach
    void start() {
        ViewCatalog views = new ViewCatalog();
        registry = new QueryRegistry(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, TXN).writingTo(sinks);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
        server = new PravahaFlightServer(views).hosting(registry).start("localhost", 0);
        url = "grpc://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        server.close();
        registry.close();
        arena.close();
    }

    @Test
    void theListingNamesEachQuerysSinkAndSaysWhenThereIsNone() {
        registry.registerWritingTo(
                "to_orders", "SELECT id, amount FROM txn", List.of(0), Principal.ANONYMOUS, "orders");
        registry.register("to_nowhere", "SELECT amount, id FROM txn", List.of(0), Principal.ANONYMOUS);

        String out = plain(cli("queries", "--url", url));

        assertThat(out).contains("NAME\tSTATE\tFINGERPRINT\tROWS IN\tSINK");
        assertThat(out).containsPattern("to_orders\tRUNNING\t[0-9a-f]+\t0\torders");
        assertThat(out)
                .as("a query that writes nowhere has a dash, not a blank cell somebody has to interpret")
                .containsPattern("to_nowhere\tRUNNING\t[0-9a-f]+\t0\t-");
        assertThat(out).doesNotContain("detached");
    }

    @Test
    void aDetachedSinkIsMarkedInTheListingAndExplainedUnderIt() {
        RegisteredQuery query = registry.registerWritingTo(
                "to_orders", "SELECT id, amount FROM txn", List.of(0), Principal.ANONYMOUS, "orders");
        sinks.sink.failNextWrite();
        feed(query, 1L, 300L);
        query.commit();
        assertThat(registry.sinkFailure("to_orders"))
                .as("the sink refused the batch, which is the precondition of everything below")
                .isPresent();

        String out = plain(cli("queries", "--url", url));

        assertThat(out)
                .as("the SINK cell says the name is no longer being written to")
                .contains("to_orders\tRUNNING\t")
                .contains("orders (detached)");
        assertThat(out)
                .contains("to_orders: sink 'orders' detached with PRV-8009")
                .contains("the sink's connection was reset");
        assertThat(out).contains("a detached sink is not retried");
        assertThat(out)
                .as("the query itself is fine and must not be dressed up as failing")
                .doesNotContain("source stopped");
    }

    @Test
    void aDetachedSinksMessageCarriesNoConfiguredCredential() {
        registry.registerWritingTo(
                "to_orders", "SELECT id, amount FROM txn", List.of(0), Principal.ANONYMOUS, "orders");
        RegisteredQuery query = registry.require("to_orders");
        sinks.sink.failWith("could not reach jdbc:postgresql://db/orders?password=hunter2correct");
        feed(query, 1L, 300L);
        query.commit();

        String out = plain(cli("queries", "--url", url));

        assertThat(out).contains("PRV-8009");
        assertThat(out)
                .as("the factory strikes its own option values out where the failure is recorded, so "
                        + "every surface downstream of it is safe rather than each one remembering")
                .doesNotContain("hunter2correct")
                .contains("[redacted password]");
    }

    private void feed(RegisteredQuery query, long id, long amount) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id).setLong(1, amount);
        writer.weight(1L).eventTimestampNanos(0).sequence(0).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept("txn", new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(java.time.Duration.ofSeconds(10));
    }

    private static String cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            new PravahaCli(outStream, errStream).run(args);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String plain(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    /**
     * One sink named {@code orders}, configured with a password, which fails on demand.
     *
     * <p>{@link #redact} is the production {@code PluginSinks}'s rule in miniature: the failure the
     * registry records must not carry the option value, whichever surface reads it afterwards.
     */
    private static final class FailingSinks implements SinkFactory {

        private final FailingSink sink = new FailingSink();

        @Override
        public SinkCapabilities capabilitiesOf(String sinkName) {
            if (!"orders".equals(sinkName)) {
                throw new IllegalArgumentException("no sink named '" + sinkName + "' is bound");
            }
            return new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), false, true, 0);
        }

        @Override
        public StreamSinkPlugin open(String sinkName) {
            capabilitiesOf(sinkName);
            return sink;
        }

        @Override
        public String redact(String text) {
            return text == null ? null : text.replace("hunter2correct", "[redacted password]");
        }

        @Override
        public void release(StreamSinkPlugin plugin) {}
    }

    private static final class FailingSink implements StreamSinkPlugin {

        private final List<String> written = new ArrayList<>();
        private volatile String failure;

        void failNextWrite() {
            failure = "the sink's connection was reset";
        }

        void failWith(String message) {
            failure = message;
        }

        @Override
        public SinkCapabilities capabilities() {
            return new SinkCapabilities(EnumSet.of(EmitMode.UPSERT, EmitMode.RETRACT), false, true, 0);
        }

        @Override
        public int write(List<RowView> batch) {
            String why = failure;
            if (why != null) {
                failure = null;
                throw new IllegalStateException(why);
            }
            batch.forEach(row -> written.add(Long.toString(row.getLong(0))));
            return batch.size();
        }

        @Override
        public void flush() {}

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public Version version() {
            return Version.apiVersion();
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public void close() {}
    }
}
