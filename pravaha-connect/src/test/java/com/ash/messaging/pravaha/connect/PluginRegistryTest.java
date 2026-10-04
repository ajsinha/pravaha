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
package com.ash.messaging.pravaha.connect;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginManifest;
import com.ash.messaging.pravaha.api.plugin.PravahaPlugin;
import com.ash.messaging.pravaha.api.plugin.Version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginRegistryTest {

    static final class FakePlugin implements PravahaPlugin {
        final String name;
        boolean closed;

        @Nullable
        RuntimeException closeFailure;

        FakePlugin(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Version version() {
            return new Version(1, 0, 0);
        }

        @Override
        public void configure(PluginContext context) {}

        @Override
        public void open() {}

        @Override
        public void close() {
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        @Override
        public HealthStatus health() {
            return HealthStatus.healthy();
        }
    }

    private static PluginManifest manifest(String name, Version requiredApi) {
        return new PluginManifest(name, new Version(1, 0, 0), requiredApi, FakePlugin.class.getName(), Map.of());
    }

    @Test
    void registersAndLooksUp() {
        PluginRegistry registry = new PluginRegistry();
        FakePlugin plugin = new FakePlugin("filesystem");
        registry.register(manifest("filesystem", Version.apiVersion()), plugin);

        assertThat(registry.find("filesystem")).hasValue(plugin);
        assertThat(registry.require("filesystem")).isSameAs(plugin);
        assertThat(registry.names()).containsExactly("filesystem");
        assertThat(registry.size()).isOne();
    }

    @Test
    void anUnknownPluginListsWhatIsRegistered() {
        // "No such plugin" is a dead end; listing what exists turns it into a diagnosis, because
        // the answer is nearly always a typo or a jar that did not get deployed.
        PluginRegistry registry = new PluginRegistry();
        registry.register(manifest("kafka", Version.apiVersion()), new FakePlugin("kafka"));

        assertThatThrownBy(() -> registry.require("kafta"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5010")
                .hasMessageContaining("kafta")
                .hasMessageContaining("kafka");
    }

    @Test
    void anIncompatibleApiVersionIsRejectedBeforeTheCodeRuns() {
        // Otherwise it fails with a NoSuchMethodError from inside the plugin's own initialisation,
        // which tells an operator nothing useful.
        PluginRegistry registry = new PluginRegistry(new Version(1, 0, 0));
        assertThatThrownBy(() -> registry.register(manifest("old", new Version(0, 9, 0)), new FakePlugin("old")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5011")
                .hasMessageContaining("built against API 0.9.0")
                .hasMessageContaining("Rebuild the plugin");
    }

    @Test
    void aNewerMinorApiIsCompatible() {
        // Ordinary semver: a plugin built against 1.2 runs on 1.5.
        PluginRegistry registry = new PluginRegistry(new Version(1, 5, 0));
        registry.register(manifest("p", new Version(1, 2, 0)), new FakePlugin("p"));
        assertThat(registry.size()).isOne();
    }

    @Test
    void duplicateNamesAreRejected() {
        PluginRegistry registry = new PluginRegistry();
        registry.register(manifest("dup", Version.apiVersion()), new FakePlugin("dup"));
        assertThatThrownBy(() -> registry.register(manifest("dup", Version.apiVersion()), new FakePlugin("dup")))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5013")
                .hasMessageContaining("must be unique");
    }

    @Test
    void aTypeMismatchSuggestsTheLikelyCause() {
        PluginRegistry registry = new PluginRegistry();
        registry.register(manifest("p", Version.apiVersion()), new FakePlugin("p"));
        assertThatThrownBy(() -> registry.require("p", com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin.class))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-5030")
                .hasMessageContaining("source where a sink was meant");
    }

    @Test
    void closingClosesEveryPluginEvenWhenOneFails() {
        // Leaking a connection pool because an unrelated plugin threw on shutdown is how a restart
        // loop becomes resource exhaustion.
        PluginRegistry registry = new PluginRegistry();
        FakePlugin first = new FakePlugin("a");
        FakePlugin bad = new FakePlugin("b");
        FakePlugin last = new FakePlugin("c");
        bad.closeFailure = new IllegalStateException("connection reset");

        registry.register(manifest("a", Version.apiVersion()), first);
        registry.register(manifest("b", Version.apiVersion()), bad);
        registry.register(manifest("c", Version.apiVersion()), last);

        assertThatThrownBy(registry::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 plugin(s) failed to close");

        assertThat(first.closed).isTrue();
        assertThat(last.closed)
                .as("a later plugin must still be closed after an earlier one throws")
                .isTrue();
        assertThat(registry.size()).isZero();
    }

    @Test
    void isNotASingletonSoEnginesInOneJvmCanDifferInTheirPlugins() {
        PluginRegistry a = new PluginRegistry();
        PluginRegistry b = new PluginRegistry();
        a.register(manifest("only-in-a", Version.apiVersion()), new FakePlugin("only-in-a"));
        assertThat(b.find("only-in-a")).isEmpty();
    }
}
