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
package com.ash.messaging.pravaha.embedded;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginManifest;
import com.ash.messaging.pravaha.api.plugin.PravahaPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;
import com.ash.messaging.pravaha.common.config.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PravahaEngineTest {

    static final class CountingPlugin implements PravahaPlugin {
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        RuntimeException openFailure;

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public Version version() {
            return new Version(1, 0, 0);
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {
            opens.incrementAndGet();
            if (openFailure != null) {
                throw openFailure;
            }
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static PluginManifest manifest(String name) {
        return new PluginManifest(name, new Version(1, 0, 0), Version.apiVersion(), "X", Map.of());
    }

    @Test
    void movesThroughItsLifecycle() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            assertThat(engine.state()).isEqualTo(EngineState.CREATED);
            engine.start();
            assertThat(engine.state()).isEqualTo(EngineState.RUNNING);
            assertThat(engine.state().isRunning()).isTrue();
            engine.stop();
            assertThat(engine.state()).isEqualTo(EngineState.STOPPED);
            assertThat(engine.state().isTerminal()).isTrue();
        }
    }

    @Test
    void opensAndClosesItsPluginsExactlyOnce() {
        CountingPlugin plugin = new CountingPlugin();
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.plugins().register(manifest("counting"), plugin);
            engine.start();
            assertThat(plugin.opens).hasValue(1);
            engine.stop();
            engine.stop(); // idempotent
            assertThat(plugin.closes).hasValue(1);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void concurrentStartsDoNotOpenPluginsTwice() throws Exception {
        // Not hypothetical: a Spring context and an application's own initialiser both holding a
        // reference is exactly how this happens, and a double open would leak a connection pool.
        CountingPlugin plugin = new CountingPlugin();
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            engine.plugins().register(manifest("counting"), plugin);

            int threads = 8;
            CountUpDown ready = new CountUpDown(threads);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger rejected = new AtomicInteger();
            Thread[] starters = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                starters[i] = new Thread(() -> {
                    ready.arrive();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        engine.start();
                    } catch (IllegalStateException expected) {
                        rejected.incrementAndGet();
                    }
                });
                starters[i].start();
            }
            ready.await();
            go.countDown();
            for (Thread t : starters) {
                t.join(TimeUnit.SECONDS.toMillis(5));
            }

            assertThat(plugin.opens).as("plugins must be opened exactly once").hasValue(1);
            assertThat(rejected).as("every start but one must be rejected").hasValue(threads - 1);
            assertThat(engine.state()).isEqualTo(EngineState.RUNNING);
        }
    }

    @Test
    void aFailedStartLeavesTheEngineFailedRatherThanRestartable() {
        // Whatever failed is still in whatever state it failed in; restarting over it hides the
        // cause and produces a second, more confusing failure.
        CountingPlugin plugin = new CountingPlugin();
        plugin.openFailure = new IllegalStateException("cannot reach the broker");

        PravahaEngine engine = PravahaEngine.createDefault();
        engine.plugins().register(manifest("counting"), plugin);

        assertThatThrownBy(engine::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broker");
        assertThat(engine.state()).isEqualTo(EngineState.FAILED);
        assertThatThrownBy(engine::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create a new one");
        engine.close();
    }

    @Test
    void stoppingAnUnstartedEngineIsSafe() {
        PravahaEngine engine = PravahaEngine.createDefault();
        engine.stop();
        assertThat(engine.state()).isEqualTo(EngineState.STOPPED);
    }

    @Test
    void closeStopsSoTryWithResourcesWorks() {
        PravahaEngine engine = PravahaEngine.createDefault();
        engine.start();
        engine.close();
        assertThat(engine.state()).isEqualTo(EngineState.STOPPED);
    }

    @Test
    void severalEnginesCoexistInOneJvmWithSeparateConfigAndPlugins() {
        // The property embedded mode exists for, and the reason none of this is a singleton.
        Configuration a =
                Configuration.builder().set("pravaha.node.id", "engine-a").build();
        Configuration b =
                Configuration.builder().set("pravaha.node.id", "engine-b").build();

        try (PravahaEngine first = PravahaEngine.create(a);
                PravahaEngine second = PravahaEngine.create(b)) {
            first.plugins().register(manifest("only-in-first"), new CountingPlugin());

            assertThat(first.instanceId()).isEqualTo("engine-a");
            assertThat(second.instanceId()).isEqualTo("engine-b");
            assertThat(first.plugins().size()).isOne();
            assertThat(second.plugins().size()).isZero();

            first.start();
            assertThat(second.state())
                    .as("starting one engine must not start another")
                    .isEqualTo(EngineState.CREATED);
        }
    }

    @Test
    void configurationIsCarriedThroughUnchanged() {
        Configuration config = Configuration.builder()
                .set("pravaha.runtime.lanes", "8")
                .set("pravaha.node.id", "n1")
                .build();
        try (PravahaEngine engine = PravahaEngine.create(config)) {
            assertThat(engine.configuration().requireInt("pravaha.runtime.lanes"))
                    .isEqualTo(8);
            assertThat(engine.configuration()).isSameAs(config);
        }
    }

    @Test
    void toStringIsDiagnosable() {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            assertThat(engine.toString()).contains("pravaha-embedded").contains("CREATED");
        }
    }

    /** A tiny arrival barrier; avoids pulling in a dependency for one test. */
    private static final class CountUpDown {
        private final CountDownLatch latch;

        CountUpDown(int parties) {
            this.latch = new CountDownLatch(parties);
        }

        void arrive() {
            latch.countDown();
        }

        void await() throws InterruptedException {
            latch.await(5, TimeUnit.SECONDS);
        }
    }
}
