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
package com.ash.messaging.pravaha.plugin.aerospike;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.policy.WritePolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The source TCK with {@code deletes: detect}, against a real server. The mode claims exactly-once,
 * so the TCK's replay case holds it to the strict reading: resumed from a position two rows into the
 * first pass, the reader must emit exactly the three it had not -- no more, no fewer.
 */
@Timeout(300)
class AerospikeDeleteDetectionTckIT extends SourcePluginTck {

    private static final int RECORDS = 5;
    private static final AtomicInteger SETS = new AtomicInteger();

    private static GenericContainer<?> aerospike;
    private static IAerospikeClient admin;
    private static Path stateDir;

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    @BeforeAll
    static void startServer() throws Exception {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker is not available; the Aerospike TCK run needs a real server")
                .isTrue();
        aerospike = AerospikeContainer.create();
        aerospike.start();
        stateDir = Files.createTempDirectory("pravaha-deletes-tck");
        com.aerospike.client.policy.ClientPolicy policy = new com.aerospike.client.policy.ClientPolicy();
        policy.failIfNotConnected = true;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        while (admin == null) {
            try {
                admin = new AerospikeClient(
                        policy, new com.aerospike.client.Host("127.0.0.1", AerospikeContainer.PORT));
            } catch (RuntimeException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
    }

    @AfterAll
    static void stopServer() {
        if (admin != null) {
            admin.close();
        }
        if (aerospike != null) {
            aerospike.stop();
        }
    }

    @Override
    protected StreamSourcePlugin createPlugin() {
        String set = "tckd" + SETS.incrementAndGet();
        for (long id = 1; id <= RECORDS; id++) {
            admin.put(
                    new WritePolicy(),
                    new Key(AerospikeContainer.NAMESPACE, set, id),
                    new Bin("order_id", id),
                    new Bin("name", "row-" + id));
        }
        AerospikeSourcePlugin plugin = new AerospikeSourcePlugin();
        plugin.configure(new Ctx(
                "tck",
                Map.of(
                        "hosts",
                        "127.0.0.1:" + AerospikeContainer.PORT,
                        "namespace",
                        AerospikeContainer.NAMESPACE,
                        "set",
                        set,
                        "schema",
                        "order_id:INT64,name:STRING",
                        "stream",
                        "tck",
                        "deletes",
                        "detect",
                        "deletes.state.dir",
                        stateDir.toString())));
        plugin.open();
        return plugin;
    }

    @Override
    protected String streamName() {
        return "tck";
    }

    @Override
    protected int expectedRecordCount() {
        return RECORDS;
    }

    @Override
    protected RowCollector newCollector(StreamSourcePlugin plugin) {
        return new AerospikeCollector(plugin.discoverSchemas().get(0));
    }
}
