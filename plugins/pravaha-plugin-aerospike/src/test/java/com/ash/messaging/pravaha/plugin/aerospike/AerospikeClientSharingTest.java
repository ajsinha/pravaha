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

import com.aerospike.client.Host;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.policy.ClientPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One client per cluster per credential, and never one across two credentials.
 *
 * <p>SRC-2. A client was built per plugin instance and a plugin instance is per query, so a thousand
 * Aerospike-backed continuous queries were a thousand clients, a thousand {@code tend} platform
 * threads and a thousand connection pools. {@code AerospikeSourceScaleIT} measures the consequence
 * against a real cluster; this states the rule itself, in milliseconds, with no docker.
 *
 * <p><strong>No cluster is needed and none is running.</strong> With {@code failIfNotConnected} left
 * false a client constructs against a dead address -- it simply has no nodes yet. That is enough:
 * what is under test is which callers are handed the same object, which is decided before a single
 * packet is sent.
 *
 * <p>The addresses are per-test and unreachable by construction, because the cache is process-wide
 * static state and a test that shared a key with its neighbour would pass or fail on test order.
 */
@Timeout(60)
final class AerospikeClientSharingTest {

    private static ClientPolicy policy(String user, String password) {
        ClientPolicy policy = new ClientPolicy();
        policy.user = user;
        policy.password = password;
        // Left false deliberately: see the class comment. With it true this test would need a
        // cluster to say anything about a decision taken before the cluster is contacted.
        policy.failIfNotConnected = false;
        policy.timeout = 1_000;
        return policy;
    }

    private static Host[] hosts(String name) {
        // TEST-NET-1 (RFC 5737), which is guaranteed not to be routable. A hostname that resolved
        // would make this test's speed depend on somebody's DNS.
        int octet = 1 + Math.floorMod(name.hashCode(), 200);
        return new Host[] {new Host("192.0.2." + octet, 3000)};
    }

    @Test
    void twoQueriesOnOneClusterAndOneCredentialShareOneClient() {
        Host[] hosts = hosts("shared");
        int clustersBefore = AerospikeClients.openClusters();

        IAerospikeClient first = AerospikeClients.connect(policy("u", "p"), hosts, "q1");
        IAerospikeClient second = AerospikeClients.connect(policy("u", "p"), hosts, "q2");
        try {
            assertThat(second)
                    .as("the same cluster and the same credential is the same client -- the whole of SRC-2")
                    .isSameAs(first);
            assertThat(AerospikeClients.openClusters() - clustersBefore)
                    .as("two registrations, one real client: one tend thread and one pool, not two")
                    .isEqualTo(1);
            assertThat(AerospikeClients.holdersOf(first)).isEqualTo(2);
        } finally {
            AerospikeClients.release(first);
            AerospikeClients.release(second);
        }

        assertThat(AerospikeClients.openClusters() - clustersBefore)
                .as("the last release closes it; a shared client that outlived its holders would be a leak")
                .isZero();
    }

    @Test
    void aDifferentUserGetsADifferentClient() {
        // The one that is not an optimisation question. A client authenticates once, at connect,
        // and every request afterwards runs as that identity -- so sharing across credentials would
        // execute one query's reads under another query's authorisation. Authorisation is enforced
        // at the Pravaha layer precisely so it is not delegated downwards, and this is the seam
        // where a cache keyed on the host list alone would have undone that.
        Host[] hosts = hosts("two-users");
        IAerospikeClient alice = AerospikeClients.connect(policy("alice", "secret"), hosts, "q1");
        IAerospikeClient bob = AerospikeClients.connect(policy("bob", "secret"), hosts, "q2");
        try {
            assertThat(bob)
                    .as("two users on one cluster are two clients; sharing would run bob's query as alice")
                    .isNotSameAs(alice);
            assertThat(AerospikeClients.holdersOf(alice)).isEqualTo(1);
            assertThat(AerospikeClients.holdersOf(bob)).isEqualTo(1);
        } finally {
            AerospikeClients.release(alice);
            AerospikeClients.release(bob);
        }
    }

    @Test
    void theSameUserWithADifferentPasswordGetsADifferentClient() {
        // Subtler than two users and the reason the password is in the key rather than just the
        // name. If a deployment rotates a credential and a query reconnects with the new one, it
        // must not be handed the client still authenticated with the old.
        Host[] hosts = hosts("rotated");
        IAerospikeClient old = AerospikeClients.connect(policy("svc", "old-password"), hosts, "q1");
        IAerospikeClient rotated = AerospikeClients.connect(policy("svc", "new-password"), hosts, "q2");
        try {
            assertThat(rotated)
                    .as("same user, different secret: a different authentication and so a different client")
                    .isNotSameAs(old);
        } finally {
            AerospikeClients.release(old);
            AerospikeClients.release(rotated);
        }
    }

    @Test
    void aClientStaysOpenWhileAnyHolderRemains() {
        Host[] hosts = hosts("outlives");
        IAerospikeClient held = AerospikeClients.connect(policy("u", "p"), hosts, "q1");
        IAerospikeClient same = AerospikeClients.connect(policy("u", "p"), hosts, "q2");

        AerospikeClients.release(same);

        assertThat(AerospikeClients.holdersOf(held))
                .as("one query closing must not take the client out from under the other -- that is the "
                        + "failure a naive cache produces, and it appears only under shutdown of one of many")
                .isEqualTo(1);
        AerospikeClients.release(held);
        assertThat(AerospikeClients.holdersOf(held)).isZero();
    }

    @Test
    void releasingTwiceIsToleratedRatherThanFatal() {
        // A caller bug, but one that surfaces during shutdown, where throwing would replace a clean
        // stop with a confusing one and hide whatever was actually being shut down.
        Host[] hosts = hosts("twice");
        IAerospikeClient client = AerospikeClients.connect(policy("u", "p"), hosts, "q1");
        AerospikeClients.release(client);
        AerospikeClients.release(client);
        assertThat(AerospikeClients.holdersOf(client)).isZero();
    }
}
