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

import java.net.URL;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PluginClassLoaderTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "com.ash.messaging.pravaha.api.data.RowView",
                "com.ash.messaging.pravaha.api.plugin.PravahaPlugin",
                "java.lang.String",
                "javax.net.ssl.SSLContext",
                "jdk.internal.misc.Unsafe",
                "org.slf4j.Logger"
            })
    void sharedPackagesResolveFromTheParent(String className) {
        // The api package especially: a RowWriter handed across the boundary must be the *same*
        // class to both sides, or every call throws ClassCastException.
        assertThat(PluginClassLoader.isParentFirst(className)).as(className).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "io.netty.buffer.ByteBuf",
                "com.google.common.collect.ImmutableList",
                "com.aerospike.client.AerospikeClient",
                "org.apache.kafka.clients.consumer.KafkaConsumer",
                "com.ash.messaging.pravaha.common.memory.MemoryRegion",
                "com.ash.messaging.pravaha.runtime.Lane"
            })
    void everythingElseResolvesFromThePluginFirst(String className) {
        // Including engine-internal packages: a plugin must never bind to them, and if it somehow
        // ships its own copy that is its problem rather than the engine's.
        assertThat(PluginClassLoader.isParentFirst(className)).as(className).isFalse();
    }

    @Test
    void loadsApiTypesAsTheSameClassAsTheEngineHolds() {
        // The concrete consequence of the parent-first rule, asserted rather than assumed.
        try (PluginClassLoader loader =
                new PluginClassLoader("test", new URL[0], getClass().getClassLoader())) {
            Class<?> viaPlugin = loader.loadClass("com.ash.messaging.pravaha.api.data.RowView");
            assertThat(viaPlugin).isSameAs(com.ash.messaging.pravaha.api.data.RowView.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void isNamedForDiagnosis() {
        try (PluginClassLoader loader =
                new PluginClassLoader("aerospike", new URL[0], getClass().getClassLoader())) {
            assertThat(loader.pluginName()).isEqualTo("aerospike");
            assertThat(loader.getName()).isEqualTo("pravaha-plugin-aerospike");
            assertThat(loader.toString()).contains("aerospike");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theParentFirstListIsDeliberatelyShort() {
        // Every prefix here is a package plugins cannot isolate from. Growing this list quietly is
        // how classloader isolation stops working, so its size is asserted.
        assertThat(PluginClassLoader.parentFirstPrefixes())
                .hasSize(6)
                .contains("com.ash.messaging.pravaha.api.", "org.slf4j.");
    }
}
