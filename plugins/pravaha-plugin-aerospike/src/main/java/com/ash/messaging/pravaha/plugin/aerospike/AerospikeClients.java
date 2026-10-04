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

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Host;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.policy.ClientPolicy;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.io.FileDescriptors;

/**
 * One client per cluster per credential, shared by every plugin instance that wants it.
 *
 * <p>SRC-2. A client was built per plugin instance, and a plugin instance is per query -- so a
 * thousand Aerospike-backed continuous queries were a thousand clients. Each carries its own
 * {@code tend} platform thread and its own connection pool, which at the default
 * {@code maxConnsPerNode} of 100 is a ceiling of 100,000 sockets against one cluster. ADR-036 took
 * the engine's own thread count off the query count; this is the same defect one layer out, and the
 * one that would have been met first by a real workload.
 *
 * <p>The client is built to be shared: it is thread-safe, it multiplexes every caller over one pool,
 * and its {@code tend} thread maintains the cluster map for all of them at once. Nothing about a
 * per-query client bought isolation -- it bought a thousand copies of the same cluster map.
 *
 * <h2>The key is the credential, and that is not an optimisation detail</h2>
 *
 * <p>Two queries reaching the same cluster as different Aerospike users must not share a client.
 * The client authenticates once, at connect, and every request afterwards carries that identity --
 * so a shared client would silently execute one query's reads under another query's authorisation.
 * That is precisely the boundary this system enforces at the Pravaha layer rather than delegating
 * downwards, and sharing by host alone would have punched a hole straight through it.
 *
 * <p>So the key carries user and password, not just the host list. Two plugin instances share a
 * client only when they are indistinguishable to the cluster. The key is never logged and never
 * appears in a message; {@link Key} deliberately has no {@code toString} that could leak one.
 *
 * <h2>Lifetime</h2>
 *
 * <p>Reference counted. A plugin that took a client calls {@link #release} where it used to call
 * {@code close}, and the last release closes the real client. Callers must not call {@code close}
 * on what they are given -- it is not theirs -- which is why this returns the bare client rather
 * than a wrapper that could absorb the mistake: a delegating wrapper would put a reflective hop on
 * the lookup hot path, and a lookup runs per row.
 */
final class AerospikeClients {

    /** What makes two plugin instances indistinguishable to the cluster. Never logged. */
    private record Key(String hosts, String user, String password, int timeout, boolean failIfNotConnected) {}

    /** A live client and how many plugin instances hold it. */
    private static final class Shared {
        private final IAerospikeClient client;
        private int holders = 1;

        Shared(IAerospikeClient client) {
            this.client = client;
        }
    }

    private static final Object LOCK = new Object();
    private static final Map<Key, Shared> BY_KEY = new HashMap<>();

    /** Identity, not equality: two distinct clients could compare equal and must not be confused. */
    private static final IdentityHashMap<IAerospikeClient, Key> KEY_OF = new IdentityHashMap<>();

    private AerospikeClients() {}

    /**
     * The client for this cluster and credential, connecting only if nobody holds one already.
     *
     * <p>The connect happens under the lock. That is deliberate: without it, a thousand queries
     * starting together would each find the map empty and each build a client, which is the defect
     * this exists to remove. Waiting is bounded by the number of distinct clusters, not by callers.
     */
    static IAerospikeClient connect(ClientPolicy policy, Host[] hosts, String instanceName) {
        Key key = new Key(
                describe(hosts),
                policy.user == null ? "" : policy.user,
                policy.password == null ? "" : policy.password,
                policy.timeout,
                policy.failIfNotConnected);
        synchronized (LOCK) {
            Shared shared = BY_KEY.get(key);
            if (shared != null) {
                shared.holders++;
                return shared.client;
            }
            IAerospikeClient client = build(policy, hosts, instanceName);
            shared = new Shared(client);
            BY_KEY.put(key, shared);
            KEY_OF.put(client, key);
            return client;
        }
    }

    /**
     * Gives back a client taken from {@link #connect}; the last holder closes it.
     *
     * <p>Tolerant of a client this never handed out, and of a second release of the same one. Both
     * are caller bugs, but they surface during shutdown, where throwing would replace a clean stop
     * with a confusing one and lose whatever was actually being shut down.
     */
    static void release(IAerospikeClient client) {
        if (client == null) {
            return;
        }
        IAerospikeClient toClose = null;
        synchronized (LOCK) {
            Key key = KEY_OF.get(client);
            Shared shared = key == null ? null : BY_KEY.get(key);
            if (shared == null) {
                toClose = client;
            } else if (--shared.holders <= 0) {
                BY_KEY.remove(key);
                KEY_OF.remove(client);
                toClose = shared.client;
            }
        }
        if (toClose != null) {
            closeQuietly(toClose);
        }
    }

    /**
     * How many real clients are open, which is how many {@code tend} threads and connection pools
     * this process is paying for. The number {@code AerospikeClientSharingTest} asserts does not
     * follow the query count.
     */
    static int openClusters() {
        synchronized (LOCK) {
            return BY_KEY.size();
        }
    }

    /** How many plugin instances hold a client for this cluster and credential. For tests. */
    static int holdersOf(IAerospikeClient client) {
        synchronized (LOCK) {
            Key key = KEY_OF.get(client);
            Shared shared = key == null ? null : BY_KEY.get(key);
            return shared == null ? 0 : shared.holders;
        }
    }

    private static void closeQuietly(IAerospikeClient client) {
        try {
            client.close();
        } catch (AerospikeException e) {
            // Closing a client whose cluster has already gone is not a failure worth propagating
            // out of a shutdown path.
        }
    }

    private static IAerospikeClient build(ClientPolicy policy, Host[] hosts, String instanceName) {
        try {
            return new AerospikeClient(policy, hosts);
        } catch (AerospikeException e) {
            throw new PravahaException(
                    AerospikeErrors.CONNECT_FAILED,
                    "plugin '" + instanceName + "' cannot reach Aerospike at " + describe(hosts) + ": "
                            + e.getMessage()
                            + ". Check the host list, that the cluster is up, and that this process can reach "
                            + "the service port -- the client also needs the fabric and heartbeat addresses the "
                            + "cluster advertises, which is the usual cause when a single-node cluster works "
                            + "from the same machine and not from another."
                            + FileDescriptors.exhaustionHint()
                                    .map(hint -> " " + hint)
                                    .orElse(""),
                    e);
        }
    }

    private static String describe(Host[] hosts) {
        StringBuilder text = new StringBuilder();
        for (Host host : hosts) {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(host.name).append(':').append(host.port);
        }
        return text.toString();
    }
}
