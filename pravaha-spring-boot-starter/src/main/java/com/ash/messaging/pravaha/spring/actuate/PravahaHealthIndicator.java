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
package com.ash.messaging.pravaha.spring.actuate;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.spring.ListenerContainer;
import com.ash.messaging.pravaha.spring.PravahaListenerProcessor;

/**
 * The embedded engine's contribution to {@code /actuator/health}, as {@code pravaha}.
 *
 * <p>DOWN when the engine is not running, because then nothing the application asks of it will be
 * answered. Otherwise UP, with what went wrong as detail: failed queries, sinks that were detached,
 * listeners that were stopped or fell behind. The same judgement as the server's own indicator --
 * one broken query is not a broken application, and taking the instance out of rotation would take
 * its healthy queries with it -- but never a silent UP: every one of those is counted in the detail.
 */
public class PravahaHealthIndicator implements HealthIndicator {

    private final PravahaEngine engine;
    private final PravahaListenerProcessor listeners;

    public PravahaHealthIndicator(PravahaEngine engine, PravahaListenerProcessor listeners) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.listeners = listeners;
    }

    @Override
    public Health health() {
        EngineState state = engine.state();
        Health.Builder health = state == EngineState.RUNNING ? Health.up() : Health.down();
        health.withDetail("instanceId", engine.instanceId()).withDetail("state", state.name());
        if (state != EngineState.RUNNING) {
            return health.build();
        }
        QueryRegistry registry = engine.registry();
        health.withDetail("queries", engine.queries().size());
        List<String> failed = engine.queries().stream()
                .filter(name ->
                        engine.find(name).flatMap(RegisteredQuery::failure).isPresent())
                .toList();
        health.withDetail("failedQueries", failed.size());
        failed.stream()
                .findFirst()
                .flatMap(name ->
                        engine.find(name).flatMap(RegisteredQuery::failure).map(f -> name + ": " + f.getMessage()))
                .ifPresent(first -> health.withDetail("firstFailure", first));
        List<String> detachedSinks = engine.queries().stream()
                .filter(name -> registry.sinkFailure(name).isPresent())
                .toList();
        health.withDetail("detachedSinks", detachedSinks);
        List<ListenerContainer> containers = listeners == null ? List.of() : listeners.containers();
        health.withDetail("listeners", containers.size());
        health.withDetail(
                "stoppedListeners",
                containers.stream()
                        .filter(c -> c.isStopped() || c.isDetached())
                        .map(c -> c.listenerName() + " on '" + c.queryName() + "'")
                        .toList());
        health.withDetail(
                "listenerFailures",
                containers.stream().mapToLong(ListenerContainer::failures).sum());
        return health.build();
    }
}
