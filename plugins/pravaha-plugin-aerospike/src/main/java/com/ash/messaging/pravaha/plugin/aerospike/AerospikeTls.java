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

import javax.net.ssl.SSLContext;

import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.TlsPolicy;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginTls;

/**
 * Turns the shared {@code tls.*} options into Aerospike's two-part TLS setup.
 *
 * <p>Aerospike is unusual in wanting the trust material and the expected certificate name in two
 * different places: the {@link SSLContext} goes on the {@link ClientPolicy}, but the name the
 * server's certificate must carry goes on each individual {@code Host}. Set one without the other
 * and the client fails at connect time with a message that names neither -- so this class refuses
 * at configure time instead, and says which half is missing.
 */
final class AerospikeTls {

    private AerospikeTls() {}

    /**
     * The certificate name to stamp on every host, or {@code ""} when TLS is off.
     *
     * <p>Call this before parsing hosts, because the name has to be built into each one.
     */
    static String tlsName(PluginContext context) {
        if (!PluginTls.isConfigured(context)) {
            return "";
        }
        String tlsName = context.get("tls.name", "").strip();
        if (tlsName.isBlank()) {
            throw new ConfigurationException(
                    AerospikeErrors.BAD_CONFIGURATION,
                    "TLS is enabled but 'tls.name' is not set. Aerospike checks the server certificate "
                            + "against this name rather than against the host you dialled, so there is no "
                            + "default that could be right. Set it to the name the certificate carries -- "
                            + "it is the 'tls-name' in the server's aerospike.conf, commonly something like "
                            + "'aerospike-cluster'. It is not usually the hostname.");
        }
        return tlsName;
    }

    /**
     * Installs the TLS context on the policy when TLS is on, and does nothing when it is off.
     *
     * <p>Silence here is only ever the configured silence: {@link PluginTls} has already refused a
     * half-set pair, and {@code tls.enabled: false} is honoured in both directions.
     */
    static void apply(PluginContext context, ClientPolicy policy) {
        SSLContext ssl =
                PluginTls.from(context, AerospikeErrors.BAD_CONFIGURATION).orElse(null);
        if (ssl == null) {
            return;
        }
        TlsPolicy tls = new TlsPolicy();
        tls.context = ssl;
        policy.tlsPolicy = tls;
    }
}
