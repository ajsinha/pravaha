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

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Host;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.policy.ClientPolicy;

import com.ash.messaging.pravaha.api.PravahaException;

/** Connecting, with the diagnosis a connection failure actually needs. */
final class AerospikeClients {

    private AerospikeClients() {}

    static IAerospikeClient connect(ClientPolicy policy, Host[] hosts, String instanceName) {
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
                            + "from the same machine and not from another.",
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
