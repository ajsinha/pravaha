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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;

/**
 * What this node is and how it is doing.
 *
 * <p>Two representations of the same thing, and the second is the point. {@code /api/v1/status}
 * serves JSON for the console; {@code /status} serves a **plain HTML page rendered here, in Java,
 * with no console process involved**.
 *
 * <p>That page exists because the console is a separate process (§23.2a), and a console that is the
 * only way to see anything makes itself a single point of failure for diagnosis. When someone is
 * working out why the console cannot reach the engine, the last thing they need is to require the
 * console to find out.
 */
@RestController
@Tag(name = "Status", description = "Node health and identity")
public class StatusController {

    private final PravahaEngine engine;
    private final StreamCatalog catalog;
    private final RegistryAccess registry;
    private final Instant startedAt = Instant.now();

    public StatusController(PravahaEngine engine, StreamCatalog catalog, RegistryAccess registry) {
        this.engine = engine;
        this.catalog = catalog;
        this.registry = registry;
    }

    @GetMapping("/api/v1/status")
    @Operation(summary = "Node status as JSON")
    public ApiDtos.NodeStatus status() {
        List<ApiDtos.PluginStatus> plugins = new ArrayList<>();
        engine.plugins().registrations().forEach(registration -> {
            var health = registration.plugin().health();
            plugins.add(new ApiDtos.PluginStatus(
                    registration.manifest().name(),
                    registration.manifest().version().toString(),
                    health.state().name(),
                    health.detail()));
        });

        return new ApiDtos.NodeStatus(
                engine.instanceId(),
                version(),
                engine.state().name(),
                Duration.between(startedAt, Instant.now()).toSeconds(),
                // HLP-8: this was catalog.size(), the stream count, under a name that says queries.
                registry.registry().map(r -> r.names().size()).orElse(0),
                plugins,
                catalog.size());
    }

    /**
     * The same information as a self-contained HTML page.
     *
     * <p>No template engine, no static assets, no JavaScript, no external font. It has to render
     * from a single HTTP response on a machine that may have nothing else working, so every
     * dependency it could have is one it might not get.
     */
    @GetMapping(value = "/status", produces = MediaType.TEXT_HTML_VALUE)
    public String statusPage() {
        ApiDtos.NodeStatus status = status();
        StringBuilder html = new StringBuilder(2048);
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Pravaha node ")
                .append(escape(status.instanceId()))
                .append("</title><style>")
                .append(
                        "body{background:#0C1416;color:#E4ECEC;font:14px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace;")
                .append("margin:0;padding:40px 24px}")
                .append("main{max-width:720px;margin:0 auto}")
                .append("h1{font-size:1.5rem;margin:0 0 4px;letter-spacing:-.01em}")
                .append(".sub{color:#6A8083;margin:0 0 28px}")
                .append("table{border-collapse:collapse;width:100%;margin:0 0 24px}")
                .append(
                        "th{text-align:left;color:#8CA3A5;font-weight:500;padding:8px 12px 8px 0;width:180px;vertical-align:top}")
                .append("td{padding:8px 0;border-bottom:1px solid #1B2C2F}")
                .append(".ok{color:#3FB3AB}.warn{color:#E0B066}.bad{color:#E08466}")
                .append("</style></head><body><main>")
                .append("<h1>Pravaha <span class=\"ok\">")
                .append(escape(status.instanceId()))
                .append("</span></h1>")
                .append("<p class=\"sub\">Ask once. Answer always. &mdash; served by the engine, ")
                .append("independently of the console.</p><table>");

        row(html, "State", status.engineState(), stateClass(status.engineState()));
        row(html, "Version", status.version(), "");
        row(html, "Uptime", status.uptimeSeconds() + "s", "");
        row(html, "Registered queries", String.valueOf(status.registeredQueries()), "");
        row(html, "Streams", String.valueOf(status.streams()), "");

        html.append("</table>");
        if (status.plugins().isEmpty()) {
            html.append("<p class=\"sub\">No plugins registered.</p>");
        } else {
            html.append("<table>");
            status.plugins()
                    .forEach(plugin -> row(
                            html,
                            plugin.name() + " " + plugin.version(),
                            plugin.health() + (plugin.detail().isEmpty() ? "" : " — " + plugin.detail()),
                            healthClass(plugin.health())));
            html.append("</table>");
        }
        return html.append("</main></body></html>").toString();
    }

    private static void row(StringBuilder html, String label, String value, String cssClass) {
        html.append("<tr><th>")
                .append(escape(label))
                .append("</th><td")
                .append(cssClass.isEmpty() ? "" : " class=\"" + cssClass + "\"")
                .append('>')
                .append(escape(value))
                .append("</td></tr>");
    }

    private static String stateClass(String state) {
        return switch (state) {
            case "RUNNING" -> "ok";
            case "STARTING", "STOPPING" -> "warn";
            default -> "bad";
        };
    }

    private static String healthClass(String health) {
        return switch (health) {
            case "HEALTHY" -> "ok";
            case "DEGRADED" -> "warn";
            default -> "bad";
        };
    }

    /** Escapes for HTML. Node and plugin names come from configuration, which is not always ours. */
    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String version() {
        String implementation = StatusController.class.getPackage().getImplementationVersion();
        return implementation == null ? "0.1.0-SNAPSHOT" : implementation;
    }
}
