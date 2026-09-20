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
package com.ash.messaging.pravaha.server.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Converts engine types into wire types.
 *
 * <p>One place, so the wire format cannot drift per endpoint. Two endpoints that each build a
 * {@code FieldInfo} their own way will eventually disagree about how a nullable decimal is rendered,
 * and the client discovers it rather than the build.
 */
@Component
public class DtoMapper {

    public ApiDtos.StreamSummary toSummary(StreamSchema schema) {
        return toSummary(schema, null);
    }

    /**
     * A stream, with the plugin a source binding feeds it from.
     *
     * @param sourcePlugin the binding's plugin name, or null when the stream is not bound. Only the
     *     name is ever taken from a binding: its options can hold credentials
     */
    public ApiDtos.StreamSummary toSummary(StreamSchema schema, String sourcePlugin) {
        java.util.OptionalInt eventTime = schema.eventTimeOrdinal();
        return new ApiDtos.StreamSummary(
                schema.name(),
                schema.version(),
                schema.fieldCount(),
                toFields(schema),
                eventTime.isPresent() ? schema.field(eventTime.getAsInt()).name() : null,
                // The schema always holds a lateness, defaulted when nothing declared one, and it means
                // nothing without an event time for it to be about -- so it is only said beside one.
                eventTime.isPresent() ? schema.outOfOrderness().toString() : null,
                sourcePlugin,
                eventTime.isPresent() ? schema.allowedLateness().toString() : null);
    }

    /** A view's key, by name and by the ordinal registration took. */
    public List<ApiDtos.KeyColumn> toKeyColumns(StreamSchema schema, List<Integer> ordinals) {
        List<ApiDtos.KeyColumn> keys = new ArrayList<>(ordinals.size());
        for (int ordinal : ordinals) {
            keys.add(new ApiDtos.KeyColumn(schema.field(ordinal).name(), ordinal));
        }
        return keys;
    }

    /** The plan as nodes and edges, with the query-level telemetry the engine measures, if any. */
    public ApiDtos.PlanGraph toPlanGraph(
            com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan, ApiDtos.QueryTelemetry telemetry) {
        return toPlanGraph(plan, telemetry, java.util.List.of());
    }

    /**
     * The plan as nodes and edges, with what the engine measures for each of its operators.
     *
     * <p>Keyed by the same ids the nodes carry, because both come from {@code PlanNodes} -- one
     * definition of the order, so the counters cannot land on the wrong boxes.
     *
     * @param operators in plan-node order, or empty when nothing is measuring them: SQL that is not
     *     registered has nothing running, and a node started with {@code pravaha.metrics.operators}
     *     off has counters that were never built. Those are different answers and the note says
     *     which one this is
     */
    public ApiDtos.PlanGraph toPlanGraph(
            com.ash.messaging.pravaha.runtime.plan.PhysicalOperator plan,
            ApiDtos.QueryTelemetry telemetry,
            java.util.List<com.ash.messaging.pravaha.runtime.exec.OperatorMetrics.Snapshot> operators) {
        var graph = com.ash.messaging.pravaha.sql.plan.PlanGraph.of(plan);
        java.util.Map<String, ApiDtos.OperatorTelemetry> measured = toOperatorMetrics(operators);
        return new ApiDtos.PlanGraph(
                graph.nodes().stream()
                        .map(node -> new ApiDtos.PlanNode(
                                node.id(), node.operator(), node.detail(), node.stateful(), node.fields()))
                        .toList(),
                graph.edges().stream()
                        .map(edge -> new ApiDtos.PlanEdge(edge.from(), edge.to()))
                        .toList(),
                measured,
                com.ash.messaging.pravaha.runtime.exec.OperatorTelemetry.bottleneck(operators)
                        .map(com.ash.messaging.pravaha.runtime.exec.OperatorMetrics.Snapshot::nodeId)
                        .orElse(null),
                metricsNote(telemetry, measured),
                telemetry);
    }

    private static String metricsNote(
            ApiDtos.QueryTelemetry telemetry, java.util.Map<String, ApiDtos.OperatorTelemetry> measured) {
        if (measured != null) {
            return "Per-operator rows, rows out, state bytes and watermark are measured. Self time is "
                    + "sampled: one row in every 1,024 that enters the pipeline is timed at every operator "
                    + "on its path, and 'sampledRows' says how many that was, so a share read off a handful "
                    + "of samples can be recognised as one. 'bottleneck' is the node most of the sampled "
                    + "time went into.";
        }
        if (telemetry == null) {
            return "Per-operator numbers are not published for a plan that is not running: there is nothing "
                    + "to measure. Register the query and read its plan to get them.";
        }
        return "Per-operator numbers are not published on this node: pravaha.metrics.operators is off, so the "
                + "counters were never built into this query's stages. Set it and re-register the query. "
                + "The query's own totals are under 'query'.";
    }

    /** Each operator's numbers, by node id, or null when nothing was measuring them. */
    private static java.util.Map<String, ApiDtos.OperatorTelemetry> toOperatorMetrics(
            java.util.List<com.ash.messaging.pravaha.runtime.exec.OperatorMetrics.Snapshot> operators) {
        if (operators.isEmpty()) {
            return null;
        }
        long total = com.ash.messaging.pravaha.runtime.exec.OperatorTelemetry.totalSelfNanos(operators);
        java.util.Map<String, ApiDtos.OperatorTelemetry> byNode = new java.util.LinkedHashMap<>();
        for (var each : operators) {
            byNode.put(
                    each.nodeId(),
                    new ApiDtos.OperatorTelemetry(
                            each.rowsIn(),
                            each.rowsOut(),
                            each.stateBytes(),
                            each.watermarkNanos() == null
                                    ? null
                                    : java.time.Instant.ofEpochSecond(0, each.watermarkNanos())
                                            .toString(),
                            each.selfNanos(),
                            each.sampledRows(),
                            total == 0 ? 0 : (double) each.selfNanos() / total));
        }
        return byNode;
    }

    /** A refusal or failure, as the API shows one. */
    /**
     * A replacement as the API renders it.
     *
     * <p>Static, because it maps one immutable answer onto another and needs nothing of the node:
     * the engine already decided everything in it.
     */
    public static ApiDtos.ReplacementStatus replacement(
            com.ash.messaging.pravaha.registry.QueryReplacement.Status status) {
        com.ash.messaging.pravaha.backfill.BackfillJob.Progress progress = status.progress();
        return new ApiDtos.ReplacementStatus(
                status.name(),
                status.state().name(),
                status.sql(),
                status.candidate(),
                status.replacing(),
                status.sink(),
                status.options().toString(),
                status.owner(),
                status.startedAt(),
                status.cutOverAt(),
                status.rollbackUntil(),
                status.rollbackAvailable(),
                new ApiDtos.BackfillProgress(
                        progress.historyRows(),
                        progress.liveRows(),
                        progress.rowsPerSecond(),
                        progress.partitions(),
                        progress.partitionsLive(),
                        progress.historyComplete(),
                        progress.rateLimit(),
                        progress.paused(),
                        status.lagNanos() / 1_000_000_000.0),
                status.history(),
                status.failureCode() == null
                        ? null
                        // DOCX-21: through HelpUrls like every other help link, so an unset base
                        // gives an empty field rather than a dead one. It also stops lower-casing
                        // the code on the way: this was the one surface that published
                        // `.../errors/prv-5040` while everything else published `PRV-5040`.
                        : new ApiDtos.Problem(
                                status.failureCode(),
                                status.failure(),
                                com.ash.messaging.pravaha.api.HelpUrls.forCode(status.failureCode())));
    }

    public ApiDtos.Problem toProblem(com.ash.messaging.pravaha.api.PravahaException failure, String message) {
        return new ApiDtos.Problem(failure.errorCode().code(), message, failure.helpUrl());
    }

    public List<ApiDtos.FieldInfo> toFields(StreamSchema schema) {
        List<ApiDtos.FieldInfo> fields = new ArrayList<>(schema.fieldCount());
        for (int i = 0; i < schema.fieldCount(); i++) {
            var field = schema.field(i);
            fields.add(new ApiDtos.FieldInfo(
                    field.name(),
                    // The SQL rendering, not the internal enum: a client should see DECIMAL(18, 4),
                    // not the name of a Java constant.
                    field.type().sqlName(),
                    field.type().nullable(),
                    field.ordinal()));
        }
        return fields;
    }
}
