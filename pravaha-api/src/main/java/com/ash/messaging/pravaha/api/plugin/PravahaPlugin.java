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
package com.ash.messaging.pravaha.api.plugin;

import com.ash.messaging.pravaha.api.ConfigurationException;

/**
 * The base contract every plugin implements.
 *
 * <p>Lifecycle is {@code configure -> open -> ... -> close}, and both {@code close} and a repeated
 * {@code close} must be safe. {@link #configure} runs before {@link #open} specifically so that a
 * misconfiguration fails before any connection is attempted -- a plugin that discovers a missing
 * host while already half-started leaves the engine deciding what to do with a partly-live query.
 *
 * <p>Implementations are loaded through an isolated classloader (design section 10.3), so a plugin may
 * bundle whatever dependency versions it likes without consulting the engine or the other plugins.
 */
public interface PravahaPlugin extends AutoCloseable {

    /** Stable identifier used in configuration, e.g. {@code "aerospike"}. Lower-case, no spaces. */
    String name();

    Version version();

    /** The API version this plugin was built against. Checked before {@link #configure}. */
    default Version requiredApiVersion() {
        return Version.apiVersion();
    }

    /**
     * Validates and applies configuration.
     *
     * @throws ConfigurationException with an actionable message. This runs at query registration,
     *     where a clear failure costs a developer a minute; the same failure at 3 a.m. costs
     *     considerably more.
     */
    void configure(PluginContext context) throws ConfigurationException;

    /** Acquires resources. Called once, after a successful {@link #configure}. */
    void open();

    /** Releases resources. Must be idempotent. */
    @Override
    void close();

    /** Current health, polled by the engine and shown in the console. */
    default HealthStatus health() {
        return HealthStatus.healthy();
    }
}
