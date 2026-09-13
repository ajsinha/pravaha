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
package com.ash.messaging.pravaha.server;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.registry.RegisteredQuery;

/**
 * Whether this node can actually do its job.
 *
 * <p>There was no health indicator at all. A node with Flight disabled -- unreachable by any client,
 * by either SDK, or by the CLI -- reported UP on health, liveness and readiness, because the only
 * contributors were Spring's disk space and ping. An orchestrator would have kept it in rotation
 * indefinitely. A health check that is always UP is worse than no health check: it is a monitoring
 * system reporting confidently on nothing.
 *
 * <p>Down for the two conditions a client can feel: the engine is not running, or the wire protocol
 * every client speaks is not listening. Failed queries are reported as detail rather than as DOWN --
 * one broken query is not a broken node, and taking the node out of rotation would take its healthy
 * queries with it.
 */
@Component
public class EngineHealthIndicator implements HealthIndicator {

    private final PravahaNode node;

    public EngineHealthIndicator(PravahaNode node) {
        this.node = node;
    }

    @Override
    public Health health() {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (!node.isRunning()) {
            return Health.down().withDetail("engine", "not started").build();
        }
        node.registry().ifPresent(registry -> {
            detail.put("queries", registry.size());
            long failed = registry.queries().stream()
                    .filter(query -> query.failure().isPresent())
                    .count();
            detail.put("failedQueries", failed);
            registry.queries().stream()
                    .map(RegisteredQuery::failure)
                    .flatMap(java.util.Optional::stream)
                    .findFirst()
                    .ifPresent(failure -> detail.put("firstFailure", failure.getMessage()));
        });

        if (node.flightPort().isEmpty()) {
            // The HTTP surface answering while Flight is down is precisely the state that looked
            // healthy: operators can reach the status page and no client can reach the engine.
            return Health.down()
                    .withDetail("flight", "not listening; no client can reach this node")
                    .withDetails(detail)
                    .build();
        }
        detail.put("flightPort", node.flightPort().orElseThrow());
        return Health.up().withDetails(detail).build();
    }
}
