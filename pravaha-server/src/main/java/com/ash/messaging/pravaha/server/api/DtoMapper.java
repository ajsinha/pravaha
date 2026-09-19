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
        var graph = com.ash.messaging.pravaha.sql.plan.PlanGraph.of(plan);
        return new ApiDtos.PlanGraph(
                graph.nodes().stream()
                        .map(node -> new ApiDtos.PlanNode(
                                node.id(), node.operator(), node.detail(), node.stateful(), node.fields()))
                        .toList(),
                graph.edges().stream()
                        .map(edge -> new ApiDtos.PlanEdge(edge.from(), edge.to()))
                        .toList(),
                null,
                "Per-operator rows, state and watermarks are not published: the runtime counts them per "
                        + "query, not per operator, and splitting a query's totals across its operators would "
                        + "be a number that looks measured and is not."
                        + (telemetry == null ? "" : " The query's own totals are under 'query'."),
                telemetry);
    }

    /** A refusal or failure, as the API shows one. */
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
