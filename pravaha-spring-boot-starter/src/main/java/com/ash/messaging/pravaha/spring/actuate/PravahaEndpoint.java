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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.registry.FeedStatus;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.spring.ListenerContainer;
import com.ash.messaging.pravaha.spring.PravahaListenerProcessor;

/**
 * {@code /actuator/pravaha}: the embedded engine's queries, as an operator asks about them.
 *
 * <p>For each registered query: its state, the lane it runs on, its sink and what that sink is
 * promised, its feed -- each source partition, and the code a stopped one stopped with -- how far
 * behind it is -- watermark lag, and the commits each {@code @PravahaListener} on it has waiting --
 * and why it failed, if it did. {@code /actuator/pravaha/{name}} is one query.
 *
 * <p><strong>Read-only, and not exposed unless asked for.</strong> There are no write or delete
 * operations: pausing, resuming or dropping a query is the application's decision, through {@code
 * PravahaTemplate}, not something a management port should be able to do to it. The endpoint follows
 * Boot's exposure rules like any other -- a web client sees it only once {@code
 * management.endpoints.web.exposure.include} names {@code pravaha} -- and the auto-configuration does
 * not create it at all until it is exposed somewhere.
 */
@Endpoint(id = "pravaha")
public class PravahaEndpoint {

    private final PravahaEngine engine;
    private final PravahaListenerProcessor listeners;

    public PravahaEndpoint(PravahaEngine engine, PravahaListenerProcessor listeners) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.listeners = listeners;
    }

    /** The engine and every query on it. */
    @ReadOperation
    public EngineDescriptor engine() {
        Map<String, QueryDescriptor> queries = new LinkedHashMap<>();
        for (String name : engine.queries()) {
            engine.find(name).ifPresent(query -> queries.put(name, describe(name, query)));
        }
        return new EngineDescriptor(engine.instanceId(), engine.state().name(), queries);
    }

    /** One query, or a 404 when none has that name. */
    @ReadOperation
    public QueryDescriptor query(@Selector String name) {
        return engine.find(name).map(query -> describe(name, query)).orElse(null);
    }

    private QueryDescriptor describe(String name, RegisteredQuery query) {
        QueryRegistry registry = engine.registry();
        SinkDescriptor sink = registry.sinkOf(name)
                .map(sinkName -> new SinkDescriptor(
                        sinkName,
                        registry.sinkGuarantee(name).orElse(null),
                        registry.rowsWrittenToSink(name),
                        registry.sinkFailure(name).map(Throwable::getMessage).orElse(null)))
                .orElse(null);
        Long watermarkNanos = query.watermarkNanos().orElse(null);
        List<ListenerDescriptor> onQuery = listeners == null
                ? List.of()
                : listeners.containers().stream()
                        .filter(container -> container.queryName().equals(name))
                        .map(PravahaEndpoint::describe)
                        .toList();
        return new QueryDescriptor(
                query.state().name(),
                query.sql(),
                registry.sharedLaneOf(name).map(lane -> "shared-" + lane).orElse("own"),
                query.rowsIn(),
                query.subscriberCount(),
                watermarkNanos == null ? null : Instant.EPOCH.plusNanos(watermarkNanos),
                watermarkNanos == null ? null : (System.currentTimeMillis() * 1_000_000d - watermarkNanos) / 1e9,
                query.lastCheckpoint().orElse(null),
                sink,
                onQuery,
                query.failure().map(Throwable::getMessage).orElse(null),
                describe(query.feedStatus()));
    }

    /** Whether rows still reach the query, source by source, and why not when one has stopped (FEED-1). */
    private static FeedDescriptor describe(FeedStatus status) {
        return new FeedDescriptor(
                status.state().name(),
                status.description(),
                status.sources().stream()
                        .map(source -> new SourceDescriptor(
                                source.stream(),
                                source.partition(),
                                source.state().name(),
                                source.shared(),
                                source.stop() == null ? null : source.stop().code(),
                                source.stop() == null ? null : source.stop().message(),
                                source.stop() == null ? null : source.stop().at()))
                        .toList());
    }

    private static ListenerDescriptor describe(ListenerContainer container) {
        return new ListenerDescriptor(
                container.listenerName(),
                container.isRunning(),
                container.isStopped(),
                container.isDetached(),
                container.delivered(),
                container.failures(),
                container.pending(),
                container
                        .lastFailure()
                        .map(failure -> String.valueOf(failure.exception()))
                        .orElse(null));
    }

    /** The engine, and its queries by name. */
    public record EngineDescriptor(String instanceId, String state, Map<String, QueryDescriptor> queries) {}

    /**
     * One query.
     *
     * @param lane {@code own}, or {@code shared-N} for a query multiplexed onto shared lane N
     * @param watermarkLagSeconds how far event time trails the wall clock; null until a watermark
     *     exists, because a query that has never seen a row is not zero seconds behind
     * @param failure why it failed, or null
     * @param feed whether rows still reach it: a source that fails mid-read stops its feed and leaves the
     *     query {@code RUNNING}, and this is where that shows (FEED-1)
     */
    public record QueryDescriptor(
            String state,
            String sql,
            String lane,
            long rowsIn,
            int subscribers,
            Instant watermark,
            Double watermarkLagSeconds,
            Instant lastCheckpoint,
            SinkDescriptor sink,
            List<ListenerDescriptor> listeners,
            String failure,
            FeedDescriptor feed) {}

    /**
     * A query's feed.
     *
     * @param state {@code RUNNING}, {@code PAUSED}, {@code STOPPED} (a source has stopped) or {@code
     *     NONE} (nothing is bound: the application pushes rows itself)
     */
    public record FeedDescriptor(String state, String description, List<SourceDescriptor> sources) {}

    /** One partition of one bound stream; {@code code}, {@code failure} and {@code stoppedAt} once it stopped. */
    public record SourceDescriptor(
            String stream,
            int partition,
            String state,
            boolean shared,
            String code,
            String failure,
            Instant stoppedAt) {}

    /** The sink a query writes to, what its delivery is promised, and why it stopped if it did. */
    public record SinkDescriptor(String name, String guarantee, long rowsWritten, String failure) {}

    /** One {@code @PravahaListener} method; {@code pending} is its lag in commits. */
    public record ListenerDescriptor(
            String listener,
            boolean running,
            boolean stopped,
            boolean detached,
            long delivered,
            long failures,
            int pending,
            String lastFailure) {}
}
