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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.DebugSession;
import com.ash.messaging.pravaha.registry.DebugSessions;
import com.ash.messaging.pravaha.registry.DebugStep;
import com.ash.messaging.pravaha.registry.FixtureExport;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.runtime.exec.OperatorState;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewChange;

/**
 * The time-travel debugger over HTTP (ADR-048, design section 16.4).
 *
 * <p>What the console's debugger screen (design section 23.9) is built on, and it is shaped by
 * that screen: one step returns everything the screen shows at once -- the rows that entered, what
 * each operator did with them, what changed in the view and where event time stands -- because a
 * screen that had to ask four times would be showing four moments of a query that is moving.
 *
 * <p>Every call requires the <strong>administer</strong> permission on the query, reading the
 * session's status included. A fork exposes the query's SQL, its input rows and its operator
 * state; that is more than reading its view exposes, so it takes the permission that dropping it
 * takes, as a replacement does (ADR-046).
 *
 * <p>{@code GET /queries/{name}/debug/checkpoints} is the list a screen offers before a fork:
 * design section 23.9's timeline is a choice between retained checkpoints, and there is no point
 * offering a position this node can no longer reach.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Debugger", description = "Fork a query from a checkpoint and step it under inspection")
public class DebugController {

    private final RegistryAccess registry;
    private final HttpAuthorizer authorizer;

    public DebugController(RegistryAccess registry, HttpAuthorizer authorizer) {
        this.registry = registry;
        this.authorizer = authorizer;
    }

    /** A session, as a screen shows it before and between steps. */
    public record Session(
            String id,
            String query,
            String sql,
            long checkpointId,
            String owner,
            String startedAt,
            String lastUsedAt,
            long steps,
            long rowsConsumed,
            int viewSize,
            @Nullable Long watermarkNanos,
            boolean sinksDisabled,
            List<String> streams) {}

    /** One row that entered the query at a step. */
    public record InputRow(
            String stream, int partition, String offset, long weight, long eventTimeNanos, List<String> values) {}

    /** What one operator did during a step. */
    public record Operator(String id, String kind, String label, long rowsIn, long rowsOut) {}

    /** One change to the fork's view, with its weight: negative withdraws a row. */
    public record Change(long weight, List<String> values) {}

    /** Everything one step did. */
    public record Step(
            String session,
            long sequence,
            String kind,
            List<InputRow> rowsIn,
            List<Operator> operators,
            List<Change> viewChanges,
            @Nullable Long watermarkNanos,
            long rowsConsumed,
            int viewSize,
            boolean exhausted,
            String stopped) {}

    /** One piece of state a fork holds. */
    public record Slot(String id, String kind, String label, long entries) {}

    /** A page of one operator's state. */
    public record StatePage(
            String id,
            String kind,
            @Nullable String key,
            int offset,
            int limit,
            long total,
            boolean hasMore,
            List<StateEntry> entries) {}

    /**
     * One entry of an operator's state: its key, and its columns beside it rather than around it.
     *
     * <p>DBG-1. The entry was one flat map -- {@code "key"} and then every column -- so an operator
     * with a column literally named {@code key} overwrote the entry's own key, and the caller could
     * not tell which it had received. Flight and both SDKs already kept the two apart.
     */
    public record StateEntry(String key, Map<String, String> values) {}

    /** A generated JUnit fixture: where it belongs and what it says. */
    public record Fixture(String className, String path, Map<String, String> files) {}

    /** What a caller asks for when starting a session. Both fields have a default. */
    public record StartSession(Long checkpointId) {}

    /** How to advance: {@code row}, {@code rows:N}, {@code commit}, {@code watermark:N}, {@code until:col:op:value}. */
    public record TakeStep(String step) {}

    /** What to call the exported fixture. */
    public record ExportFixture(String name) {}

    @GetMapping("/queries/{name}/debug/checkpoints")
    @Operation(summary = "Which checkpoints of a query a debug session could be forked from")
    public List<Long> checkpoints(@PathVariable String name, HttpServletRequest http) {
        return sessions().checkpointsOf(name, principal(http));
    }

    @PostMapping("/queries/{name}/debug")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Fork a debug session from a query's checkpoint, with every sink disabled")
    public Session fork(
            @PathVariable String name,
            @RequestBody(required = false) @Nullable StartSession request,
            HttpServletRequest http) {
        Long checkpoint = request == null ? null : request.checkpointId();
        return session(sessions().fork(name, checkpoint, principal(http)));
    }

    @GetMapping("/debug/sessions")
    @Operation(summary = "Every debug session on this node that the caller may administer")
    public List<Session> list(HttpServletRequest http) {
        List<Session> open = new ArrayList<>();
        for (DebugSession.Status status : sessions().all(principal(http))) {
            open.add(session(status));
        }
        return open;
    }

    @GetMapping("/debug/sessions/{id}")
    @Operation(summary = "One debug session: where it is, what it has consumed, what it is forked from")
    public Session get(@PathVariable String id, HttpServletRequest http) {
        return session(sessions()
                .of(id, principal(http))
                .orElseThrow(() -> new PravahaException(
                        com.ash.messaging.pravaha.registry.DebugErrors.NO_SUCH_SESSION,
                        "there is no debug session '" + id + "' on this node")));
    }

    @PostMapping("/debug/sessions/{id}/step")
    @Operation(summary = "Advance a session and report what changed")
    public Step step(@PathVariable String id, @RequestBody TakeStep request, HttpServletRequest http) {
        String asked = request == null || request.step() == null ? "row" : request.step();
        return step(sessions().step(id, DebugStep.Request.parse(asked), principal(http)));
    }

    @GetMapping("/debug/sessions/{id}/state")
    @Operation(summary = "What state this session's fork holds, and how much of each")
    public List<Slot> state(@PathVariable String id, HttpServletRequest http) {
        List<Slot> slots = new ArrayList<>();
        for (OperatorState.Slot slot : sessions().state(id, principal(http))) {
            slots.add(new Slot(slot.id(), slot.kind(), slot.label(), slot.entries()));
        }
        return slots;
    }

    @GetMapping("/debug/sessions/{id}/state/{operator}")
    @Operation(summary = "One page of one operator's state, read without changing it")
    public StatePage inspect(
            @PathVariable String id,
            @PathVariable String operator,
            @RequestParam(value = "key", required = false) @Nullable String key,
            @RequestParam(value = "offset", required = false) @Nullable String offset,
            @RequestParam(value = "limit", required = false) @Nullable String limit,
            HttpServletRequest http) {
        OperatorState.Page page = sessions()
                .inspect(
                        id,
                        operator,
                        key,
                        PageParameters.intOrDefault("offset", offset, 0),
                        PageParameters.intOrDefault("limit", limit, 50),
                        principal(http));
        return statePage(page);
    }

    /** A page as the REST surface answers it: each entry's key beside its columns, never among them. */
    static StatePage statePage(OperatorState.Page page) {
        List<StateEntry> entries = new ArrayList<>();
        for (OperatorState.Entry entry : page.entries()) {
            entries.add(new StateEntry(entry.key(), new LinkedHashMap<>(entry.values())));
        }
        return new StatePage(
                page.id(),
                page.kind(),
                page.keyFilter(),
                page.offset(),
                page.limit(),
                page.total(),
                page.hasMore(),
                entries);
    }

    @GetMapping("/debug/sessions/{id}/view")
    @Operation(summary = "The fork's own view, which nothing outside the session can read")
    public List<Change> view(@PathVariable String id, HttpServletRequest http) {
        List<Change> rows = new ArrayList<>();
        for (ViewChange change : sessions().view(id, principal(http))) {
            rows.add(change(change));
        }
        return rows;
    }

    @PostMapping("/debug/sessions/{id}/fixture")
    @Operation(summary = "Write the session out as a JUnit test this repository can run offline")
    public Fixture export(@PathVariable String id, @RequestBody ExportFixture request, HttpServletRequest http) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new PravahaException(
                    ApiErrors.MISSING_FIELD,
                    "an exported fixture needs a name in the request's 'name' field: name it after the thing "
                            + "it reproduces, and it becomes the test class's name.");
        }
        FixtureExport export = sessions().export(id, request.name(), principal(http));
        return new Fixture(export.className(), export.path(), export.files());
    }

    @DeleteMapping("/debug/sessions/{id}")
    @Operation(summary = "End a session and release its fork")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@PathVariable String id, HttpServletRequest http) {
        sessions().end(id, principal(http));
    }

    private static Session session(DebugSession.Status status) {
        return new Session(
                status.id(),
                status.query(),
                status.sql(),
                status.checkpointId(),
                status.owner(),
                status.startedAt().toString(),
                status.lastUsedAt().toString(),
                status.steps(),
                status.rowsConsumed(),
                status.viewSize(),
                status.watermarkNanos().isPresent() ? status.watermarkNanos().getAsLong() : null,
                status.sinksDisabled(),
                status.streams());
    }

    private static Step step(DebugStep report) {
        List<InputRow> rows = new ArrayList<>();
        for (DebugStep.InputRow row : report.rowsIn()) {
            rows.add(new InputRow(
                    row.stream(), row.partition(), row.offset(), row.weight(), row.eventTimeNanos(), row.values()));
        }
        List<Operator> operators = new ArrayList<>();
        for (DebugStep.Operator operator : report.operators()) {
            operators.add(new Operator(
                    operator.id(), operator.kind(), operator.label(), operator.rowsIn(), operator.rowsOut()));
        }
        List<Change> changes = new ArrayList<>();
        for (ViewChange change : report.viewChanges()) {
            changes.add(change(change));
        }
        return new Step(
                report.sessionId(),
                report.sequence(),
                report.kind().name(),
                rows,
                operators,
                changes,
                report.watermarkNanos().isPresent() ? report.watermarkNanos().getAsLong() : null,
                report.rowsConsumed(),
                report.viewSize(),
                report.exhausted(),
                report.stopped());
    }

    private static Change change(ViewChange change) {
        List<String> values = new ArrayList<>();
        for (Object value : change.values()) {
            values.add(String.valueOf(value));
        }
        return new Change(change.weight(), values);
    }

    private DebugSessions sessions() {
        return registryOrRefuse().debugSessions();
    }

    private QueryRegistry registryOrRefuse() {
        return registry.registry()
                .orElseThrow(() -> new PravahaException(
                        ApiErrors.INVALID_PARAMETER,
                        "this node hosts no registry, so it runs no continuous queries and has none to debug"));
    }

    private Principal principal(HttpServletRequest http) {
        return authorizer.principalOf(http);
    }
}
