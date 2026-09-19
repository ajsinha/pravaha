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
package com.ash.messaging.pravaha.bindings.egress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.EmitMode;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.common.arena.ArenaHandle;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.plugin.filesystem.CollectingWriter;
import com.ash.messaging.pravaha.plugin.filesystem.FilesystemSourcePlugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W8-13, sink bindings: the egress half of the discovery/configuration/plugin surface that
 * {@code PluginSourceFeeds} already proves for sources.
 *
 * <p>Deliberately does not touch {@code QueryRegistry}. What this proves is exactly what ADR-039
 * item 5 says was missing: {@code pravaha.sinks.*} names a sink, {@code ServiceLoader} finds a
 * plugin that answers to that name, and the resulting instance can be written to, flushed and
 * closed -- driven directly rather than through a registered query, because nothing yet attaches a
 * registered query's output to a sink (that attachment is the registry-side half, and it is not
 * this).
 */
class PluginSinksTest {

    private static final String SCHEMA_SPEC = "id:INT64,user_id:STRING,amount:INT64";

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("id", com.ash.messaging.pravaha.api.data.Types.int64())
            .field("user_id", com.ash.messaging.pravaha.api.data.Types.string())
            .field("amount", com.ash.messaging.pravaha.api.data.Types.int64())
            .build();

    @Test
    void aBoundSinkIsDiscoveredOpenedWrittenToAndClosed(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("in.csv");
        Files.writeString(input, "1,ann,100\n2,bob,250\n3,ann,50\n");
        Path output = dir.resolve("out.csv");

        try (PluginSinks sinks = new PluginSinks()
                        .bind(new SinkBinding(
                                "audit_trail",
                                "filesystem",
                                Map.of("path", output.toString(), "schema", SCHEMA_SPEC)));
                FilesystemSourcePlugin source = new FilesystemSourcePlugin()) {
            source.configure(ctx(Map.of("path", input.toString(), "schema", SCHEMA_SPEC)));
            source.open();

            StreamSinkPlugin sink = sinks.open("audit_trail");
            // The rows a reader produces are flyweights into an arena that must stay open for as
            // long as they are read -- withRows keeps that arena alive across the write, exactly as
            // FilesystemPluginTest's own round-trip test does.
            withRows(source, rows -> {
                assertThat(sink.write(rows)).isEqualTo(3);
                sink.flush();
            });

            assertThat(Files.readAllLines(output)).containsExactly("1,ann,100", "2,bob,250", "3,ann,50");
        }
        // Closing PluginSinks closes every sink it opened; a second close is safe.
        assertThat(Files.readAllLines(output)).hasSize(3);
    }

    @Test
    void capabilitiesAreHonestAndDoNotRequireOpening() {
        PluginSinks sinks = new PluginSinks()
                .bind(new SinkBinding(
                        "audit_trail",
                        "filesystem",
                        Map.of("path", "/tmp/does-not-need-to-exist.csv", "schema", SCHEMA_SPEC)));

        SinkCapabilities capabilities = sinks.capabilitiesOf("audit_trail");

        assertThat(capabilities.accepts(EmitMode.APPEND)).isTrue();
        assertThat(capabilities.accepts(EmitMode.UPSERT))
                .as("a file cannot express an upsert, and must not claim it can")
                .isFalse();
        assertThat(capabilities.transactional()).isFalse();
        assertThat(capabilities.idempotentUpsert()).isFalse();
        assertThat(capabilities.guarantee())
                .as("append-only and non-idempotent: at-least-once is the honest ceiling")
                .isEqualTo(com.ash.messaging.pravaha.api.plugin.DeliveryGuarantee.AT_LEAST_ONCE);
    }

    @Test
    void aBindingNamingAPluginThatIsNotThereIsRefusedWithWhatIsAvailable() {
        PluginSinks sinks = new PluginSinks().bind(new SinkBinding("out", "kafka", Map.of("topic", "t")));

        assertThatThrownBy(() -> sinks.open("out"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5093")
                .hasMessageContaining("kafka")
                // Naming what *is* there turns "no such plugin" into a one-line fix, exactly as
                // IngestErrors.NO_SUCH_PLUGIN does on the source side.
                .hasMessageContaining("filesystem");
    }

    @Test
    void anUnboundSinkNameIsRefused() {
        PluginSinks sinks = new PluginSinks();

        assertThatThrownBy(() -> sinks.open("nowhere"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5093")
                .hasMessageContaining("nowhere")
                .hasMessageContaining("pravaha.sinks.nowhere");
    }

    @Test
    void aMisconfiguredSinkIsRefusedRatherThanOpened(@TempDir Path dir) {
        // No 'schema' option: the filesystem sink requires it, and the failure must be reported as
        // this binding's problem rather than an unchecked exception escaping the resolver.
        PluginSinks sinks = new PluginSinks()
                .bind(new SinkBinding(
                        "broken",
                        "filesystem",
                        Map.of("path", dir.resolve("x.csv").toString())));

        assertThatThrownBy(() -> sinks.open("broken"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5094")
                .hasMessageContaining("broken");
    }

    @Test
    void twoSinksBoundToTheSamePluginAreIndependentInstances(@TempDir Path dir) throws Exception {
        Path first = dir.resolve("a.csv");
        Path second = dir.resolve("b.csv");

        try (PluginSinks sinks = new PluginSinks()
                .bind(new SinkBinding("a", "filesystem", Map.of("path", first.toString(), "schema", SCHEMA_SPEC)))
                .bind(new SinkBinding("b", "filesystem", Map.of("path", second.toString(), "schema", SCHEMA_SPEC)))) {
            StreamSinkPlugin sinkA = sinks.open("a");
            StreamSinkPlugin sinkB = sinks.open("b");

            assertThat(sinkA).isNotSameAs(sinkB);
            assertThat(sinks.bindings()).containsOnlyKeys("a", "b");
        }
    }

    /**
     * Reads every row of an already-opened source and hands them to {@code body} while the arena
     * backing them is still open -- the same shape {@code FilesystemPluginTest.withRows} uses, since
     * a {@link RowView} is a flyweight into memory the arena owns and reading one after the arena
     * closes fails with "region is closed".
     */
    private static void withRows(FilesystemSourcePlugin source, java.util.function.Consumer<List<RowView>> body)
            throws IOException {
        try (PartitionReader reader =
                        source.createReader(source.partitions("txn").get(0), (SourceOffset) null);
                Collector collector = new Collector(TXN)) {
            reader.poll(collector, 1_000);
            body.accept(List.copyOf(collector.rows));
        }
    }

    private static PluginContext ctx(Map<String, String> config) {
        return new PluginContext() {
            @Override
            public Map<String, String> config() {
                return config;
            }

            @Override
            public String instanceName() {
                return "txn";
            }
        };
    }

    /** Collects rows a {@link PartitionReader} produces, the way {@code FilesystemPluginTest} does. */
    private static final class Collector implements PartitionReader.RecordSink, AutoCloseable {
        private final RowLayout layout;
        private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 32);
        private final BinaryRowWriter writer;
        final List<RowView> rows = new ArrayList<>();
        private long pending = ArenaHandle.NULL;

        Collector(StreamSchema schema) {
            this.layout = RowLayout.of(schema);
            this.writer = new BinaryRowWriter(layout);
        }

        @Override
        public RowWriter beginRow() {
            pending = arena.allocate(layout.rowSize(512));
            writer.begin(arena.regionOf(pending), arena.offsetOf(pending));
            long handle = pending;
            return new CollectingWriter(
                    writer,
                    () -> rows.add(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle))));
        }

        @Override
        public void close() {
            arena.close();
        }
    }
}
