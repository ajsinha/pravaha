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
package com.ash.messaging.pravaha.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * A single-node Aerospike Community server for the integration tests here, on free host ports.
 *
 * <p>The same server as the Aerospike plugin's own {@code AerospikeContainer}, which says why each
 * setting is what it is; this module cannot see that plugin's test classes.
 */
public final class AerospikeTestServer {

    /** The namespace the stock image configures. Using it avoids shipping a config file. */
    public static final String NAMESPACE = "test";

    /** Where the server this class last created is configured: see {@link #create}. */
    private static volatile int port = -1;

    private AerospikeTestServer() {}

    /** The service port of the server {@link #create} last configured. */
    public static int port() {
        if (port < 0) {
            throw new IllegalStateException("no Aerospike container has been created");
        }
        return port;
    }

    /**
     * A server on host networking whose service, fabric and heartbeat ports are free ones chosen now.
     *
     * <p>Not the stock 3000-3002. Host networking binds the host's own ports, and a developer running
     * an Aerospike of their own -- a container publishing 3000, a local install -- has them. The node
     * then fails to bind and the container exits; worse, where the other server's port is reachable
     * without a listening socket on the host, the test connects to it and truncates its sets. A port
     * nobody holds cannot be anybody else's server.
     */
    public static GenericContainer<?> create() {
        int service = freePort();
        int fabric = freePort();
        int heartbeat = freePort();
        port = service;
        return new GenericContainer<>(DockerImageName.parse("aerospike/aerospike-server:latest"))
                .withCopyToContainer(Transferable.of(config(service, fabric, heartbeat)), CONFIG)
                .withCommand("asd", "--config-file", CONFIG)
                // Host networking, not a port mapping, and this is not a preference. An Aerospike
                // client routes every operation by the cluster's partition map, and a node in a
                // bridged container advertises its container-internal address. Through a mapped
                // port the connection succeeds and the routing does not: a scan waits for data from
                // an address that never answers, with no error and no timeout worth the name.
                // Forcing a single seed node does not help, because the routing is still by
                // partition.
                .withNetworkMode("host")
                // The stock configuration asks for 15 000 file descriptors and the daemon refuses to
                // start with the default 1024 -- a CRITICAL in the log and an immediate exit, which
                // looks like a broken image rather than a ulimit.
                .withCreateContainerCmdModifier(
                        cmd -> cmd.getHostConfig().withUlimits(new com.github.dockerjava.api.model.Ulimit[] {
                            new com.github.dockerjava.api.model.Ulimit("nofile", 16_000L, 16_000L)
                        }))
                // "service ready" is printed before the node has finished balancing its partitions,
                // and a client connecting in that window is told the node "is not yet fully
                // initialized" -- which reads like a broken image rather than a race. The migration
                // line is the one that means ready.
                .waitingFor(Wait.forLogMessage(".*migrations: complete.*\\n", 1))
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    private static final String CONFIG = "/etc/aerospike/pravaha-test.conf";

    /** The stock image's single-node developer configuration, on the ports given. */
    static String config(int service, int fabric, int heartbeat) {
        return """
                service {
                    cluster-name pravaha-test
                }
                logging {
                    console {
                        context any info
                    }
                }
                network {
                    service {
                        address any
                        port %d
                    }
                    heartbeat {
                        mode mesh
                        address local
                        port %d
                        interval 150
                        timeout 10
                    }
                    fabric {
                        address local
                        port %d
                    }
                }
                namespace %s {
                    replication-factor 1
                    nsup-period 120
                    storage-engine device {
                        file /opt/aerospike/data/%s.dat
                        filesize 4G
                        read-page-cache true
                    }
                }
                """.formatted(service, heartbeat, fabric, NAMESPACE, NAMESPACE);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
