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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.connect.PluginRegistry;

/**
 * The standard {@link PravahaEngine}.
 *
 * <p>State transitions go through a compare-and-set so that two threads calling {@code start()}
 * concurrently cannot both proceed. That is not hypothetical: a Spring context and an application's
 * own initialiser both holding a reference is exactly how it happens, and the second start would
 * otherwise open every plugin twice.
 */
final class DefaultPravahaEngine implements PravahaEngine {

    private final Configuration configuration;
    private final PluginRegistry plugins = new PluginRegistry();
    private final String instanceId;
    private final AtomicReference<EngineState> state = new AtomicReference<>(EngineState.CREATED);

    DefaultPravahaEngine(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.instanceId = configuration.getString("pravaha.node.id", "pravaha-embedded");
    }

    @Override
    public void start() {
        if (!state.compareAndSet(EngineState.CREATED, EngineState.STARTING)) {
            EngineState current = state.get();
            throw new IllegalStateException("cannot start an engine that is " + current
                    + "; create a new one rather than restarting this instance");
        }
        try {
            for (PluginRegistry.Registration registration : plugins.registrations()) {
                registration.plugin().open();
            }
            state.set(EngineState.RUNNING);
        } catch (RuntimeException e) {
            // FAILED rather than back to CREATED: whatever failed is still in whatever state it
            // failed in, and restarting over it would hide the cause.
            state.set(EngineState.FAILED);
            throw e;
        }
    }

    @Override
    public void stop() {
        EngineState current = state.get();
        if (current.isTerminal() || current == EngineState.CREATED) {
            state.compareAndSet(EngineState.CREATED, EngineState.STOPPED);
            return;
        }
        if (!state.compareAndSet(current, EngineState.STOPPING)) {
            return;
        }
        try {
            plugins.close();
        } finally {
            state.set(EngineState.STOPPED);
        }
    }

    @Override
    public EngineState state() {
        return state.get();
    }

    @Override
    public Configuration configuration() {
        return configuration;
    }

    @Override
    public PluginRegistry plugins() {
        return plugins;
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public String toString() {
        return "PravahaEngine[" + instanceId + ", " + state.get() + ", " + plugins.size() + " plugins]";
    }
}
