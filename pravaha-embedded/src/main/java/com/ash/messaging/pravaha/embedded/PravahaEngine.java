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
package com.ash.messaging.pravaha.embedded;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.connect.PluginRegistry;

/**
 * An engine instance.
 *
 * <p><strong>This is the seam ADR-019 is built on.</strong> Everything above it -- the Spring Boot
 * server, the Spring Boot starter, the CLI, {@code @PravahaTest} -- is a bootstrap that creates one
 * of these and manages its lifecycle. Nothing below it knows what created it, which is what keeps
 * Spring out of the engine core and lets the same engine run in a host application on whatever
 * Spring version that application happens to use.
 *
 * <p>Not a singleton, and the interface has no static accessor by design. Embedded mode runs several
 * engines in one JVM and {@code @PravahaTest} gives each test its own; a process-wide instance would
 * make both impossible and make tests order-dependent.
 *
 * <p>Lifecycle is {@code create -> start -> ... -> stop}. {@link #close()} stops if needed, so an
 * engine works in try-with-resources.
 */
public interface PravahaEngine extends AutoCloseable {

    /** Builds an engine from configuration. Does not start it. */
    static PravahaEngine create(Configuration configuration) {
        return new DefaultPravahaEngine(configuration);
    }

    /** An engine with default configuration, for tests and {@code pravaha dev}. */
    static PravahaEngine createDefault() {
        return create(Configuration.empty());
    }

    /**
     * Starts the engine.
     *
     * @throws IllegalStateException if already started, or if it previously failed. A failed engine
     *     is not restarted in place -- whatever failed is still in whatever state it failed in, and
     *     restarting over it hides the cause.
     */
    void start();

    /** Stops the engine, draining work. Idempotent. */
    void stop();

    EngineState state();

    /** The configuration this engine was built with. Never mutated. */
    Configuration configuration();

    /** The plugins available to this engine. */
    PluginRegistry plugins();

    /** A stable identifier for this instance, used in logs and metrics. */
    String instanceId();

    @Override
    void close();
}
