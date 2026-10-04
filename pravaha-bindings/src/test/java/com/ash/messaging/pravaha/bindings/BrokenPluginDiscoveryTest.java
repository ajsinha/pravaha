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
package com.ash.messaging.pravaha.bindings;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.PartitionReader;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PravahaPlugin;
import com.ash.messaging.pravaha.api.plugin.SinkCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceCapabilities;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;
import com.ash.messaging.pravaha.api.plugin.SourcePartition;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.bindings.egress.PluginSinks;
import com.ash.messaging.pravaha.bindings.egress.SinkBinding;
import com.ash.messaging.pravaha.bindings.ingest.PluginLookupSources;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PKG-3: one provider that cannot be loaded no longer takes every plugin of its kind down with it.
 *
 * <p>Each test runs with a context class loader whose service files list, for each of the three
 * kinds, a class that is not on the classpath and a class whose constructor throws -- the two ways a
 * provider entry breaks (the second is the missing Cassandra driver's shape) -- ahead of the plugins
 * the test classpath already carries. The working plugins must still resolve, and a name that
 * resolves to nothing must be refused {@code PRV-5012} naming both broken classes with their causes,
 * since the plugin asked for may be one of them.
 */
class BrokenPluginDiscoveryTest {

    private static final String MISSING = "com.ash.messaging.pravaha.nowhere.MissingPlugin";

    /**
     * A provider whose construction fails, as one needing an absent driver does. Nothing past the
     * constructor ever runs.
     */
    public abstract static class ThrowingPlugin implements PravahaPlugin {
        protected ThrowingPlugin() {
            throw new NoClassDefFoundError("com/datastax/oss/driver/api/core/CqlSession");
        }

        @Override
        public String name() {
            throw new AssertionError();
        }

        @Override
        public Version version() {
            throw new AssertionError();
        }

        @Override
        public void configure(PluginContext context) {
            throw new AssertionError();
        }

        @Override
        public void open() {
            throw new AssertionError();
        }

        @Override
        public void close() {
            throw new AssertionError();
        }
    }

    public static final class ThrowingSource extends ThrowingPlugin implements StreamSourcePlugin {
        @Override
        public SourceCapabilities capabilities() {
            throw new AssertionError();
        }

        @Override
        public List<StreamSchema> discoverSchemas() {
            throw new AssertionError();
        }

        @Override
        public List<SourcePartition> partitions(String streamName) {
            throw new AssertionError();
        }

        @Override
        public PartitionReader createReader(SourcePartition partition, @Nullable SourceOffset resumeFrom) {
            throw new AssertionError();
        }
    }

    public static final class ThrowingSink extends ThrowingPlugin implements StreamSinkPlugin {
        @Override
        public SinkCapabilities capabilities() {
            throw new AssertionError();
        }

        @Override
        public int write(List<RowView> batch) {
            throw new AssertionError();
        }

        @Override
        public void flush() {
            throw new AssertionError();
        }
    }

    public static final class ThrowingLookup extends ThrowingPlugin implements LookupSourcePlugin {
        @Override
        public StreamSchema schema() {
            throw new AssertionError();
        }

        @Override
        public List<String> keyColumns() {
            throw new AssertionError();
        }

        @Override
        public int lookup(Object[] key, PartitionReader.RecordSink sink) {
            throw new AssertionError();
        }
    }

    private Path services;

    @BeforeEach
    void brokenProviders(@TempDir Path dir) throws Exception {
        services = dir;
        Path meta = dir.resolve("META-INF/services");
        Files.createDirectories(meta);
        Files.writeString(
                meta.resolve(StreamSourcePlugin.class.getName()), MISSING + "\n" + ThrowingSource.class.getName());
        Files.writeString(
                meta.resolve(StreamSinkPlugin.class.getName()), MISSING + "\n" + ThrowingSink.class.getName());
        Files.writeString(
                meta.resolve(LookupSourcePlugin.class.getName()), MISSING + "\n" + ThrowingLookup.class.getName());
    }

    private <T> T withBrokenProviders(Callable<T> body) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        // Our service files first, then the parent's: the broken providers are met before the
        // working ones, which is the order in which one loop inside one try lost them all.
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {services.toUri().toURL()}, previous) {
                    @Override
                    public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
                        List<URL> all = java.util.Collections.list(findResources(name));
                        all.addAll(java.util.Collections.list(getParent().getResources(name)));
                        return java.util.Collections.enumeration(all);
                    }
                }) {
            thread.setContextClassLoader(loader);
            return body.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void aWorkingSourceStillResolvesBesideTwoBrokenOnes(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("in.csv");
        Files.writeString(input, "1,ann\n");
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn", "filesystem", Map.of("path", input.toString(), "schema", "id:INT64,u:STRING")));

        assertThat(withBrokenProviders(() -> feeds.retracts("txn")))
                .as("filesystem is found although two source providers before it cannot be loaded")
                .isFalse();
    }

    @Test
    void aSourceThatResolvesToNothingIsRefusedNamingTheBrokenOnes() {
        // Refused when bound (PLUGINLATE-1), which is where a node binds its sources at startup.
        assertThatThrownBy(() -> withBrokenProviders(
                        () -> new PluginSourceFeeds().bind(new SourceBinding("txn", "cassandra", Map.of()))))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5012")
                .hasMessageContaining("'cassandra'")
                .hasMessageContaining(MISSING)
                .hasMessageContaining(ThrowingSource.class.getName())
                .hasMessageContaining("CqlSession")
                .hasMessageContaining("filesystem");
    }

    @Test
    void aWorkingSinkStillResolvesBesideTwoBrokenOnes() throws Exception {
        PluginSinks sinks = new PluginSinks()
                .bind(new SinkBinding(
                        "out", "filesystem", Map.of("path", "/nonexistent/out.csv", "schema", "id:INT64")));

        assertThat(withBrokenProviders(() -> sinks.capabilitiesOf("out")))
                .as("the filesystem sink is found although two sink providers before it cannot be loaded")
                .isNotNull();
    }

    @Test
    void aSinkThatResolvesToNothingIsRefusedNamingTheBrokenOnes() {
        PluginSinks sinks = new PluginSinks().bind(new SinkBinding("out", "cassandra", Map.of()));

        assertThatThrownBy(() -> withBrokenProviders(() -> sinks.capabilitiesOf("out")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5012")
                .hasMessageContaining(MISSING)
                .hasMessageContaining(ThrowingSink.class.getName());
    }

    @Test
    void aWorkingLookupStillResolvesBesideTwoBrokenOnes() throws Exception {
        try (PluginLookupSources lookups = new PluginLookupSources()
                .bind(new SourceBinding("users", "stub-lookup", Map.of("set", "users", "key.bin", "user_id")))) {
            assertThat(withBrokenProviders(lookups::open)).hasSize(1);
        }
    }

    @Test
    void aLookupThatResolvesToNothingIsRefusedNamingTheBrokenOnes() {
        try (PluginLookupSources lookups =
                new PluginLookupSources().bind(new SourceBinding("users", "cassandra-lookup", Map.of()))) {
            assertThatThrownBy(() -> withBrokenProviders(lookups::open))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-5012")
                    .hasMessageContaining(MISSING)
                    .hasMessageContaining(ThrowingLookup.class.getName());
        }
    }
}
