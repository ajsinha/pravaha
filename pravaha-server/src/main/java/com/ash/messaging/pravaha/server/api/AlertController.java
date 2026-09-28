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

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.registry.alert.AlertErrors;
import com.ash.messaging.pravaha.registry.alert.AlertOptions;
import com.ash.messaging.pravaha.registry.alert.AlertService;
import com.ash.messaging.pravaha.registry.alert.AlertStatus;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * Alerts over REST (ADR-057): the alerts a caller may see, one with every key it holds and its recent
 * notifications, and the four things a person does to one -- pause, resume, snooze, acknowledge. Every
 * rule is {@link AlertService}'s, so this decides exactly as {@code PAUSE ALERT} and {@code SNOOZE ALERT}
 * do over SQL, and every change is an audit event there. Creating, altering and dropping are statements
 * ({@code CREATE ALERT} ...), sent as any other statement is.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alerts", description = "Alerts on views: state per key, notifications, pause, snooze, ack (ADR-057)")
public class AlertController {

    public record AlertPage(List<AlertStatus.Summary> items) {}

    public record ChannelDto(String name, String plugin) {}

    public record ChannelPage(List<ChannelDto> items) {}

    /** {@code duration}: {@code 30m}, {@code 2h}, {@code PT2H}. */
    public record SnoozeRequest(String duration) {}

    /** {@code key}: one key as the alert shows it ({@code sku=sku-100, warehouse=LDN}), or none for every one. */
    public record AckRequest(String key) {}

    public record AckResult(String name, int acknowledged) {}

    private final PravahaNode node;
    private final HttpAuthorizer authorizer;

    public AlertController(PravahaNode node, HttpAuthorizer authorizer) {
        this.node = node;
        this.authorizer = authorizer;
    }

    @GetMapping
    @Operation(summary = "The alerts the caller may see, with how many keys each has firing")
    public AlertPage list(HttpServletRequest http) {
        return new AlertPage(service().list(caller(http)));
    }

    @GetMapping("/channels")
    @Operation(summary = "The notifier channels this node has bound (pravaha.notifiers.*)")
    public ChannelPage channels(HttpServletRequest http) {
        caller(http);
        return new ChannelPage(service().channels().entrySet().stream()
                .map(e -> new ChannelDto(e.getKey(), e.getValue()))
                .toList());
    }

    @GetMapping("/{name}")
    @Operation(summary = "One alert: every key it holds, its state, and its recent notifications (SELECT)")
    public AlertStatus.Detail detail(HttpServletRequest http, @PathVariable String name) {
        return service().detail(caller(http), name);
    }

    @PostMapping("/{name}/pause")
    @Operation(summary = "Pause: it keeps following the view and says nothing until resumed (MODIFY)")
    public AlertStatus.Summary pause(HttpServletRequest http, @PathVariable String name) {
        return service().pause(caller(http), name);
    }

    @PostMapping("/{name}/resume")
    @Operation(summary = "Resume, ending a pause or a snooze; what changed meanwhile is sent (MODIFY)")
    public AlertStatus.Summary resume(HttpServletRequest http, @PathVariable String name) {
        return service().resume(caller(http), name);
    }

    @PostMapping("/{name}/snooze")
    @Operation(summary = "Snooze for a duration; what changed meanwhile is sent when it ends (MODIFY)")
    public AlertStatus.Summary snooze(
            HttpServletRequest http, @PathVariable String name, @RequestBody SnoozeRequest body) {
        String duration = body == null ? null : body.duration();
        return service().snooze(caller(http), name, AlertOptions.duration("the snooze", duration));
    }

    @PostMapping("/{name}/ack")
    @Operation(summary = "Acknowledge firing keys, which stops their reminders until they fire again (MODIFY)")
    public AckResult ack(
            HttpServletRequest http, @PathVariable String name, @RequestBody(required = false) AckRequest body) {
        return new AckResult(name, service().acknowledge(caller(http), name, body == null ? null : body.key()));
    }

    private Principal caller(HttpServletRequest http) {
        return authorizer.principalOf(http);
    }

    private AlertService service() {
        return node.alerts()
                .orElseThrow(() -> new PravahaException(
                        AlertErrors.NOT_SERVED,
                        "this node serves no alerts: it has not started, or pravaha.alerts.enabled is false"));
    }
}
