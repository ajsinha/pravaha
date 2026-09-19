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
package com.ash.messaging.pravaha.plugin.cassandra;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.cassandra.CassandraContainer;

import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin;
import com.ash.messaging.pravaha.testkit.tck.SourcePluginTck;

import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The source TCK with {@code deletes: detect}, against a real node. The mode claims exactly-once, so
 * the replay case holds it to the strict reading: resumed two rows into the first pass, the reader
 * emits exactly the three it had not.
 */
@Timeout(300)
class CassandraDeleteDetectionTckIT extends SourcePluginTck {

    private static final int RECORDS = 5;
    private static final AtomicInteger TABLES = new AtomicInteger();

    private static CassandraContainer cassandra;
    private static CqlSession admin;
    private static String contactPoints;
    private static String localDatacenter;
    private static Path stateDir;

    private record Ctx(String instanceName, Map<String, String> config) implements PluginContext {}

    @BeforeAll
    static void startServer() throws Exception {
        assumeThat(DockerClientFactory.instance().isDockerAvailable())
                .as("docker is not available; the Cassandra TCK run needs a real server")
                .isTrue();
        cassandra = CassandraTestContainer.create();
        cassandra.start();
        contactPoints = cassandra.getContactPoint().getHostString() + ":"
                + cassandra.getContactPoint().getPort();
        localDatacenter = cassandra.getLocalDatacenter();
        admin = CqlSession.builder()
                .addContactPoint(cassandra.getContactPoint())
                .withLocalDatacenter(localDatacenter)
                .build();
        admin.execute("CREATE KEYSPACE IF NOT EXISTS " + CassandraTestContainer.KEYSPACE
                + " WITH replication = {'class':'SimpleStrategy','replication_factor':1}");
        stateDir = Files.createTempDirectory("pravaha-deletes-tck");
    }

    @AfterAll
    static void stopServer() {
        if (admin != null) {
            admin.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @Override
    protected StreamSourcePlugin createPlugin() {
        String table = "tckd" + TABLES.incrementAndGet();
        admin.execute("CREATE TABLE " + CassandraTestContainer.KEYSPACE + "." + table
                + " (id bigint PRIMARY KEY, name text)");
        for (long id = 1; id <= RECORDS; id++) {
            admin.execute("INSERT INTO " + CassandraTestContainer.KEYSPACE + "." + table + " (id, name) VALUES (" + id
                    + ", 'row-" + id + "')");
        }
        CassandraSourcePlugin plugin = new CassandraSourcePlugin();
        plugin.configure(new Ctx(
                "tck",
                Map.of(
                        "contact.points",
                        contactPoints,
                        "local.datacenter",
                        localDatacenter,
                        "keyspace",
                        CassandraTestContainer.KEYSPACE,
                        "table",
                        table,
                        "schema",
                        "id:INT64,name:STRING",
                        "partition.key",
                        "id",
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
        return new CassandraCollector(plugin.discoverSchemas().get(0));
    }
}
