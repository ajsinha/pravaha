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
package com.ash.messaging.pravaha.server;

import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.plugin.LookupSourcePlugin;
import com.ash.messaging.pravaha.api.plugin.StreamSinkPlugin;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every connector this project ships is found by the server, by the mechanism the server uses.
 *
 * <p>Since 2026-09-26 every connector is a dependency of {@code pravaha-server}, so {@code java -jar}
 * binds any of them with nothing to install: the owner's decision, for portability. This is the list
 * that decision promises. It asks {@link ServiceLoader} -- the call {@code PluginSourceFeeds},
 * {@code PluginSinks} and {@code PluginLookupSources} make -- rather than naming classes, because a
 * class that exists and is never declared to the loader is a connector no node can reach.
 *
 * <p>That is not hypothetical. Writing this test found that {@code jdbc-lookup} and
 * {@code aerospike-lookup} had never been declared: the only {@code LookupSourcePlugin} service file
 * in the tree was a test fixture's, so every lookup join on a real node was refused with
 * {@code PRV-5090}, and {@code OrphanedClassTest} had exempted both classes as reached through a
 * ServiceLoader that could not see them.
 *
 * <p>A dependency bump that drops a connector, or a plugin whose service file goes missing, fails
 * here in the build rather than at a deployment's first registration.
 */
class ShippedConnectorsTest {

    @Test
    void everyShippedSourceIsFoundByTheServer() {
        // containsAll rather than exactly: this module's own test resources declare fixture
        // plugins (a guarantee probe among them), and the promise is about what ships.
        assertThat(names(ServiceLoader.load(StreamSourcePlugin.class)))
                .contains(
                        "filesystem",
                        "feedfile",
                        "delta",
                        "jdbc",
                        "kafka",
                        "postgres-cdc",
                        "mysql-cdc",
                        "aerospike",
                        "cassandra");
    }

    @Test
    void everyShippedSinkIsFoundByTheServer() {
        assertThat(names(ServiceLoader.load(StreamSinkPlugin.class)))
                .contains("filesystem", "delta-sink", "iceberg-sink", "jdbc-sink", "kafka-sink", "aerospike-sink");
    }

    @Test
    void everyShippedLookupIsFoundByTheServer() {
        assertThat(names(ServiceLoader.load(LookupSourcePlugin.class)))
                .as("a lookup join names its table's plugin; one the loader cannot find is PRV-5090 at registration")
                .contains("jdbc-lookup", "aerospike-lookup");
    }

    private static <T> Set<String> names(ServiceLoader<T> loader) {
        Set<String> names = new TreeSet<>();
        for (T plugin : loader) {
            names.add(nameOf(plugin));
        }
        return names;
    }

    private static String nameOf(Object plugin) {
        if (plugin instanceof StreamSourcePlugin source) {
            return source.name();
        }
        if (plugin instanceof StreamSinkPlugin sink) {
            return sink.name();
        }
        return ((LookupSourcePlugin) plugin).name();
    }
}
