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
package com.ash.messaging.pravaha.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.wire.ControlWire;
import com.ash.messaging.pravaha.registry.DebugSession;
import com.ash.messaging.pravaha.registry.DebugSessions;
import com.ash.messaging.pravaha.registry.DebugStep;
import com.ash.messaging.pravaha.registry.FixtureExport;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.runtime.exec.OperatorState;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewChange;

/**
 * The time-travel debugger over Flight actions (ADR-048, design section 16.4).
 *
 * <p>Its own class rather than nine more cases in {@code PravahaFlightSqlProducer}'s switch, which
 * is already the largest method in the gateway and is near this repository's file-size limit. The
 * producer routes {@code pravaha.debug.*} here and this decides nothing else.
 *
 * <h2>Why the encoding looks like this</h2>
 *
 * <p>The control wire is a flat list of strings, and a step's report is not flat: it carries the
 * rows that entered, what each operator did, and what changed in the view. Each list is written as
 * a count followed by that many fixed-width groups, in the order the report holds them. That is
 * uglier than JSON and it is what the rest of this wire already does -- a listing is a row of
 * positional fields, a replacement's status is twenty-three of them -- so a client that can read
 * one can read this, and no surface has to take a JSON parser as a dependency to talk to a Flight
 * server.
 *
 * <p>Design section 23.11 reserves a WebSocket for the debugger, as the one genuinely
 * bidirectional surface. It is not built; this is request/response, which is what stepping
 * actually is -- the client asks for a step and is told what happened.
 */
final class DebugActions {

    private DebugActions() {}

    /** Whether {@code type} is one of these. */
    static boolean handles(String type) {
        return type != null && type.startsWith("pravaha.debug.");
    }

    static void act(
            String type, QueryRegistry registry, Principal principal, List<String> fields, Consumer<byte[]> out) {
        DebugSessions sessions = registry.debugSessions();
        switch (type) {
            case ControlWire.DEBUG_FORK -> {
                String name = require(fields, 0, "a query name to fork");
                Long checkpoint = fields.size() > 1 && !fields.get(1).isBlank()
                        ? Long.parseLong(fields.get(1).strip())
                        : null;
                out.accept(ControlWire.encode(session(sessions.fork(name, checkpoint, principal))));
            }
            case ControlWire.DEBUG_SESSION -> {
                if (fields.isEmpty() || fields.get(0).isBlank()) {
                    // Filtered, not refused: a listing is how a client finds the session it left
                    // open, and refusing it would mean nothing could.
                    for (DebugSession.Status status : sessions.all(principal)) {
                        out.accept(ControlWire.encode(session(status)));
                    }
                } else {
                    sessions.of(fields.get(0), principal)
                            .ifPresent(status -> out.accept(ControlWire.encode(session(status))));
                }
            }
            case ControlWire.DEBUG_STEP -> {
                String id = require(fields, 0, "a session id to step");
                DebugStep.Request request = DebugStep.Request.parse(fields.size() > 1 ? fields.get(1) : "row");
                out.accept(ControlWire.encode(step(sessions.step(id, request, principal))));
            }
            case ControlWire.DEBUG_STATE -> {
                String id = require(fields, 0, "a session id");
                for (OperatorState.Slot slot : sessions.state(id, principal)) {
                    out.accept(ControlWire.encode(slot.id(), slot.kind(), slot.label(), Long.toString(slot.entries())));
                }
            }
            case ControlWire.DEBUG_INSPECT -> {
                String id = require(fields, 0, "a session id");
                String operator = require(fields, 1, "an operator to inspect");
                String key = fields.size() > 2 && !fields.get(2).isBlank() ? fields.get(2) : null;
                int offset = number(fields, 3, 0);
                int limit = number(fields, 4, 50);
                out.accept(ControlWire.encode(page(sessions.inspect(id, operator, key, offset, limit, principal))));
            }
            case ControlWire.DEBUG_VIEW -> {
                String id = require(fields, 0, "a session id");
                for (ViewChange change : sessions.view(id, principal)) {
                    List<String> row = new ArrayList<>();
                    row.add(Long.toString(change.weight()));
                    for (Object value : change.values()) {
                        row.add(String.valueOf(value));
                    }
                    out.accept(ControlWire.encode(row));
                }
            }
            case ControlWire.DEBUG_EXPORT -> {
                String id = require(fields, 0, "a session id");
                String name = require(fields, 1, "a name for the fixture");
                FixtureExport export = sessions.export(id, name, principal);
                out.accept(ControlWire.encode(export.className(), export.path(), export.source()));
            }
            case ControlWire.DEBUG_END -> {
                String id = require(fields, 0, "a session id to end");
                sessions.end(id, principal);
                out.accept(ControlWire.encode(id, "ENDED"));
            }
            case ControlWire.DEBUG_CHECKPOINTS -> {
                String name = require(fields, 0, "a query name");
                List<String> ids = new ArrayList<>();
                ids.add(name);
                sessions.checkpointsOf(name, principal).forEach(id -> ids.add(Long.toString(id)));
                out.accept(ControlWire.encode(ids));
            }
            default ->
                throw new PravahaException(
                        FlightErrors.UNSUPPORTED_REQUEST, "this server does not answer the action '" + type + "'");
        }
    }

    private static String require(List<String> fields, int index, String what) {
        if (fields.size() <= index || fields.get(index).isBlank()) {
            throw new PravahaException(
                    FlightErrors.BAD_HANDLE, "this debug action needs " + what + " and the request carried none");
        }
        return fields.get(index).strip();
    }

    private static int number(List<String> fields, int index, int fallback) {
        if (fields.size() <= index || fields.get(index).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(fields.get(index).strip());
        } catch (NumberFormatException e) {
            throw new PravahaException(
                    FlightErrors.BAD_HANDLE, "'" + fields.get(index) + "' is not a number in a debug request");
        }
    }

    /** A session's status, in {@link ControlWire#DEBUG_SESSION_FIELDS} order. */
    static List<String> session(DebugSession.Status status) {
        return List.of(
                status.id(),
                status.query(),
                status.sql(),
                Long.toString(status.checkpointId()),
                status.owner(),
                status.startedAt().toString(),
                status.lastUsedAt().toString(),
                Long.toString(status.steps()),
                Long.toString(status.rowsConsumed()),
                Integer.toString(status.viewSize()),
                status.watermarkNanos().isPresent()
                        ? Long.toString(status.watermarkNanos().getAsLong())
                        : "",
                Boolean.toString(status.sinksDisabled()),
                String.join(",", status.streams()));
    }

    /**
     * One step's report: the fixed fields, then each list as a count followed by its groups.
     *
     * <p>Rows are {@code stream, partition, offset, weight, event_time, column count, columns...};
     * operators are {@code id, kind, label, rows_in, rows_out}; changes are {@code weight, column
     * count, columns...}. Self-describing enough to be read back without a schema, which is the
     * property a positional wire has to have to survive a version skew.
     */
    static List<String> step(DebugStep report) {
        List<String> out = new ArrayList<>();
        out.add(report.sessionId());
        out.add(Long.toString(report.sequence()));
        out.add(report.kind().name());
        out.add(
                report.watermarkNanos().isPresent()
                        ? Long.toString(report.watermarkNanos().getAsLong())
                        : "");
        out.add(Long.toString(report.rowsConsumed()));
        out.add(Integer.toString(report.viewSize()));
        out.add(Boolean.toString(report.exhausted()));
        out.add(report.stopped());

        out.add(Integer.toString(report.rowsIn().size()));
        for (DebugStep.InputRow row : report.rowsIn()) {
            out.add(row.stream());
            out.add(Integer.toString(row.partition()));
            out.add(row.offset());
            out.add(Long.toString(row.weight()));
            out.add(Long.toString(row.eventTimeNanos()));
            out.add(Integer.toString(row.values().size()));
            out.addAll(row.values());
        }

        out.add(Integer.toString(report.operators().size()));
        for (DebugStep.Operator operator : report.operators()) {
            out.add(operator.id());
            out.add(operator.kind());
            out.add(operator.label());
            out.add(Long.toString(operator.rowsIn()));
            out.add(Long.toString(operator.rowsOut()));
        }

        out.add(Integer.toString(report.viewChanges().size()));
        for (ViewChange change : report.viewChanges()) {
            out.add(Long.toString(change.weight()));
            Object[] values = change.values();
            out.add(Integer.toString(values.length));
            for (Object value : values) {
                out.add(String.valueOf(value));
            }
        }
        return out;
    }

    /** A page of operator state: the page's own fields, then each entry as key, count, pairs. */
    static List<String> page(OperatorState.Page page) {
        List<String> out = new ArrayList<>();
        out.add(page.id());
        out.add(page.kind());
        out.add(page.keyFilter() == null ? "" : page.keyFilter());
        out.add(Integer.toString(page.offset()));
        out.add(Integer.toString(page.limit()));
        out.add(Long.toString(page.total()));
        out.add(Integer.toString(page.entries().size()));
        for (OperatorState.Entry entry : page.entries()) {
            out.add(entry.key());
            out.add(Integer.toString(entry.values().size()));
            entry.values().forEach((column, value) -> {
                out.add(column);
                out.add(value);
            });
        }
        return out;
    }
}
