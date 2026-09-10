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

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Aerospike server, for tests that would otherwise be guesses.
 *
 * <p>Mocking a store is mocking one's own beliefs about it, and the beliefs are the part most likely
 * to be wrong. Two things in this plugin were only discoverable against the real server: that a
 * record's last-update time is not readable from a scan callback, and that {@code Exp.lastUpdate()}
 * counts from 2010 rather than 1970. A mock would have agreed with whatever the code assumed.
 *
 * <p>Community Edition, deliberately. It is the edition with no change feed at all, which is the
 * constraint the plugin's whole strategy hierarchy exists to handle -- testing against Enterprise
 * would test the easy case and ship the hard one untried.
 */
final class AerospikeContainer {

    /** The namespace the stock image configures. Using it avoids shipping a config file. */
    static final String NAMESPACE = "test";

    /** With host networking there is no mapping: the server is on the host's own service port. */
    static final int PORT = 3000;

    private AerospikeContainer() {}

    static GenericContainer<?> create() {
        return new GenericContainer<>(DockerImageName.parse("aerospike/aerospike-server:latest"))
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
}
