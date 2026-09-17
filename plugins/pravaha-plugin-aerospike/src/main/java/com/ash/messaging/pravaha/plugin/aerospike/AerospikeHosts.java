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

import com.ash.messaging.pravaha.api.ConfigurationException;

/**
 * Parses {@code host:port,host:port}. Small, and the first thing a misconfiguration hits.
 *
 * <p>When TLS is on, every host also carries the name the server certificate must present.
 * Aerospike checks that name rather than the address that was dialled, so it belongs on the host
 * rather than on the policy.
 */
final class AerospikeHosts {

    private AerospikeHosts() {}

    static Host[] parse(String spec, String tlsName) {
        String[] entries = spec.split(",");
        Host[] hosts = new Host[entries.length];
        for (int i = 0; i < entries.length; i++) {
            String[] parts = entries[i].strip().split(":");
            if (parts.length != 2) {
                throw new ConfigurationException(
                        AerospikeErrors.BAD_CONFIGURATION,
                        "host entry '" + entries[i].strip() + "' is not 'host:port'. Example: 127.0.0.1:3000");
            }
            try {
                int port = Integer.parseInt(parts[1].strip());
                hosts[i] = tlsName.isBlank()
                        ? new Host(parts[0].strip(), port)
                        : new Host(parts[0].strip(), tlsName, port);
            } catch (NumberFormatException e) {
                throw new ConfigurationException(
                        AerospikeErrors.BAD_CONFIGURATION, "port '" + parts[1].strip() + "' is not a number");
            }
        }
        return hosts;
    }
}
