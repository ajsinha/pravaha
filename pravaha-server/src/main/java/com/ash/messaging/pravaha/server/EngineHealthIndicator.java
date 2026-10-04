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
import java.util.List;
import java.util.Map;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.registry.FeedStatus;
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
        boolean[] degraded = {false};
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
            // FEED-1. Computations, not names, like the counts above: two names on one stopped feed
            // are one source that stopped. The code only -- which query it was is for the listing to
            // disclose, and health detail is shown to whoever show-details admits.
            List<FeedStatus> stopped = registry.queries().stream()
                    .map(RegisteredQuery::feedStatus)
                    .filter(FeedStatus::stopped)
                    .toList();
            detail.put("stoppedFeeds", stopped.size());
            stopped.stream()
                    .flatMap(status -> status.firstStopped().stream())
                    .findFirst()
                    .ifPresent(source -> detail.put(
                            "firstStoppedFeed",
                            java.util.Objects.requireNonNull(source.stop(), "a stopped source says why")
                                            .code()
                                    + " reading " + source.where()));
            degraded[0] = !stopped.isEmpty();
            // RECOVERYHEALTH-1. A journalled registration the restart refused is a view some client
            // expects and will not find; it used to leave one WARN line and the node UP. The code
            // only, as for a stopped feed: which query it was is for the listing to disclose.
            List<com.ash.messaging.pravaha.registry.RecoveryRefusals.Refused> refused =
                    registry.refusedAtRecovery().all();
            detail.put("refusedAtRecovery", refused.size());
            refused.stream()
                    .findFirst()
                    .ifPresent(first ->
                            detail.put("firstRefusedAtRecovery", first.code().isEmpty() ? "uncoded" : first.code()));
            degraded[0] = degraded[0] || !refused.isEmpty();
            // B5. How many records this node is holding that it could not decode, and which query
            // has the most. A node whose feeds are all reading and whose queues are filling looks
            // perfectly healthy from everything above, and its views are quietly incomplete --
            // which is the state design 15.6 calls DEGRADED and nothing reported.
            node.sources().ifPresent(sources -> {
                long waiting = 0;
                String worst = "";
                long most = 0;
                for (String name : sources.deadLetteringQueries()) {
                    long depth = sources.deadLetterHealth(name).depth();
                    waiting += depth;
                    if (depth > most) {
                        most = depth;
                        worst = name;
                    }
                }
                detail.put("deadLetters", waiting);
                if (!worst.isEmpty()) {
                    detail.put("deepestDeadLetterQueue", worst + " (" + most + ")");
                }
                // Degraded when the rejection rate has passed its threshold, not when the queue is
                // merely non-empty: every real feed produces some rejects, and an indicator that
                // went amber on the first one is an indicator whose alerts get muted in week two.
                boolean anyDegraded = sources.deadLetteringQueries().stream()
                        .anyMatch(name -> sources.deadLetterHealth(name).degraded());
                if (anyDegraded) {
                    detail.put("deadLetterRate", "past pravaha.dlq's threshold: this node's answers are incomplete");
                    degraded[0] = true;
                }
            });
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
        // AUDITROTATE-1. A durable audit sink that is not recording -- a file that cannot be written
        // or cannot rotate -- must not leave the node UP: the queries it audits go on (auditing never
        // fails a query), so this is DEGRADED rather than DOWN, with the sink's own words.
        node.auditTrail().ifPresent(trail -> {
            long unrecorded = trail.unrecorded();
            if (unrecorded > 0) {
                detail.put("auditUnrecorded", unrecorded);
            }
            trail.failure().ifPresent(failure -> {
                detail.put("audit", failure);
                degraded[0] = true;
            });
        });
        if (degraded[0]) {
            // Degraded, not down: the node serves every view, the stopped one included, at the
            // frontier it reached, and taking it out of rotation would take its healthy queries
            // with it. application.yaml orders DEGRADED between OUT_OF_SERVICE and UP, so the
            // aggregate says it and the probe still answers 200.
            return Health.status(DEGRADED).withDetails(detail).build();
        }
        return Health.up().withDetails(detail).build();
    }

    /**
     * A node whose every query is served but at least one of whose sources has stopped (FEED-1), or a
     * journalled registration of which was refused when the node recovered (RECOVERYHEALTH-1).
     *
     * <p>Not one of Boot's four. Boot's aggregator ignores a status it has not been told the order of,
     * so the node's own configuration places this one; an application that has not is shown it on this
     * indicator and an unchanged aggregate.
     */
    public static final Status DEGRADED = new Status(
            "DEGRADED",
            "a source feed has stopped, a registration was refused at recovery, or the audit trail "
                    + "is not being recorded");
}
