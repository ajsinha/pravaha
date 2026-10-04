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

import com.datastax.oss.driver.api.core.ssl.ProgrammaticSslEngineFactory;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.PluginTls;

/**
 * Turns the shared {@code tls.*} options into the driver's SSL engine factory.
 *
 * <p>The obvious call is {@code CqlSessionBuilder.withSslContext}, and it is the wrong one: it
 * encrypts the connection but performs <em>no hostname verification</em>, so anything holding a
 * certificate this client trusts can answer for any node. Nothing in the configuration would say
 * so. {@link ProgrammaticSslEngineFactory} is the form that takes the flag, which is why
 * {@code tls.verify-hostname} can mean something here rather than being quietly ignored.
 */
final class CassandraTls {

    private CassandraTls() {}

    /** The engine factory these options describe, or {@code null} when TLS is off. */
    static @Nullable ProgrammaticSslEngineFactory engineFactory(PluginContext context) {
        return PluginTls.from(context, CassandraErrors.BAD_CONFIGURATION)
                .map(ssl -> new ProgrammaticSslEngineFactory(ssl, null, PluginTls.verifyHostname(context)))
                .orElse(null);
    }
}
