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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import com.ash.messaging.pravaha.api.EngineState;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.registry.FeedStatus;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.spring.ListenerContainer;
import com.ash.messaging.pravaha.spring.PravahaListenerProcessor;

/**
 * The embedded engine's contribution to {@code /actuator/health}, as {@code pravaha}.
 *
 * <p>DOWN when the engine is not running, because then nothing the application asks of it will be
 * answered. DEGRADED when a query's source has stopped mid-read (FEED-1): the query reports RUNNING
 * and its view has stopped moving, which is the one failure nothing else here would show. Otherwise
 * UP, with what went wrong as detail: failed queries, sinks that were detached, listeners that were
 * stopped or fell behind. The same judgement as the server's own indicator --
 * one broken query is not a broken application, and taking the instance out of rotation would take
 * its healthy queries with it -- but never a silent UP: every one of those is counted in the detail.
 */
public class PravahaHealthIndicator implements HealthIndicator {

    /**
     * The engine runs and answers, and at least one query's source has stopped (FEED-1).
     *
     * <p>Not DOWN, for the reason a failed query is not: taking the instance out of rotation would
     * take its healthy queries with it. Boot's aggregator ignores a status it has no order for, so an
     * application that wants the aggregate to say it adds {@code DEGRADED} to {@code
     * management.endpoint.health.status.order} (between {@code OUT_OF_SERVICE} and {@code UP});
     * without that, this component says DEGRADED and the aggregate is unchanged.
     */
    public static final Status DEGRADED = new Status("DEGRADED", "a source feed has stopped");

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
        // FEED-1. A query whose source stopped mid-read is RUNNING and not moving; named with the
        // code it stopped with, which is what an operator looks up.
        List<String> stoppedFeeds = new ArrayList<>();
        for (String name : engine.queries()) {
            engine.find(name)
                    .map(RegisteredQuery::feedStatus)
                    .flatMap(FeedStatus::firstStopped)
                    .ifPresent(source -> stoppedFeeds.add(name + ": "
                            + java.util.Objects.requireNonNull(source.stop(), "a stopped source says why")
                                    .code()
                            + " reading " + source.where()));
        }
        health.withDetail("stoppedFeeds", stoppedFeeds);
        if (!stoppedFeeds.isEmpty()) {
            health.status(DEGRADED);
        }
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
