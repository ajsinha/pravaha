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
package com.ash.messaging.pravaha.server.observe;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Turns {@code pravaha.logging.format} and {@code pravaha.tracing.*} into the Spring Boot settings
 * that do the work, before logging starts and before any bean exists.
 *
 * <h2>Logs</h2>
 *
 * {@code pravaha.logging.format: text} (the default) keeps Boot's pattern. {@code json} switches the
 * console to Boot's built-in structured logging (3.4+) in its Logstash shape: one JSON object per line
 * with {@code @timestamp}, {@code level}, {@code logger_name}, {@code thread_name}, {@code message} and
 * every logging-context entry -- {@code correlationId}, {@code query}, and {@code traceId}/{@code
 * spanId} when a span is open -- as fields of their own. What Loki, Elasticsearch and every log shipper
 * parse without a grok pattern. An explicit {@code logging.structured.format.console} (say {@code ecs})
 * is left as it is. Any other value refuses the start: a node asked for JSON and writing text would
 * break a pipeline quietly.
 *
 * <h2>Traces</h2>
 *
 * Off by default, and off means <em>off</em>: the OpenTelemetry tracing auto-configuration is
 * excluded, so no tracer exists, no span is made and no trace id enters a log line. (Boot's own {@code
 * management.tracing.enabled} only stops export; spans would still be made and sampled.) With {@code
 * pravaha.tracing.enabled: true}:
 *
 * <ul>
 *   <li>{@code pravaha.tracing.endpoint} -- the OTLP/HTTP traces endpoint ({@code
 *       http://collector:4318/v1/traces}); else {@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}, else {@code
 *       OTEL_EXPORTER_OTLP_ENDPOINT} + {@code /v1/traces}. With none of them spans are made and not
 *       exported, which the log says.
 *   <li>{@code pravaha.tracing.sampling-probability} -- 0 to 1, default 1.0.
 * </ul>
 *
 * Every {@code management.tracing.*} and {@code management.otlp.tracing.*} setting stays available and
 * wins over what is derived here.
 */
public class ObservabilityEnvironment implements EnvironmentPostProcessor {

    /** The tracing auto-configurations excluded while tracing is off. */
    static final List<String> TRACING_AUTO_CONFIGURATIONS = List.of(
            "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration");

    private final Log log;

    public ObservabilityEnvironment(DeferredLogFactory logs) {
        this.log = logs.getLog(ObservabilityEnvironment.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> decided = new LinkedHashMap<>();
        Map<String, Object> defaults = new LinkedHashMap<>();
        logging(environment, decided);
        tracing(environment, decided, defaults);
        if (!decided.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("pravaha-observability", decided));
        }
        if (!defaults.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource("pravaha-observability-defaults", defaults));
        }
    }

    private static void logging(ConfigurableEnvironment environment, Map<String, Object> decided) {
        String format = environment
                .getProperty("pravaha.logging.format", "text")
                .strip()
                .toLowerCase(Locale.ROOT);
        switch (format) {
            case "text" -> {}
            case "json" -> {
                if (environment
                        .getProperty("logging.structured.format.console", "")
                        .isBlank()) {
                    decided.put("logging.structured.format.console", "logstash");
                }
            }
            default ->
                throw new IllegalStateException("pravaha.logging.format is '"
                        + environment.getProperty("pravaha.logging.format")
                        + "', and it is text or json: text for a person reading the console, json for one JSON "
                        + "object per line that a log shipper parses as it is");
        }
    }

    private void tracing(
            ConfigurableEnvironment environment, Map<String, Object> decided, Map<String, Object> defaults) {
        String enabled = environment
                .getProperty("pravaha.tracing.enabled", "false")
                .strip()
                .toLowerCase(Locale.ROOT);
        if (!enabled.equals("true") && !enabled.equals("false")) {
            throw new IllegalStateException("pravaha.tracing.enabled is '" + enabled + "', and it is true or false");
        }
        if (enabled.equals("false")) {
            Set<String> excluded = new LinkedHashSet<>();
            String already = environment.getProperty("spring.autoconfigure.exclude", "");
            for (String name : already.split(",", -1)) {
                if (!name.isBlank()) {
                    excluded.add(name.strip());
                }
            }
            excluded.addAll(TRACING_AUTO_CONFIGURATIONS);
            decided.put("spring.autoconfigure.exclude", String.join(",", excluded));
            decided.put("management.tracing.enabled", "false");
            return;
        }
        defaults.put("management.tracing.enabled", "true");
        String probability = environment.getProperty("pravaha.tracing.sampling-probability", "1.0");
        double parsed;
        try {
            parsed = Double.parseDouble(probability.strip());
        } catch (NumberFormatException e) {
            parsed = Double.NaN;
        }
        if (!(parsed >= 0.0 && parsed <= 1.0)) {
            throw new IllegalStateException("pravaha.tracing.sampling-probability is '" + probability
                    + "', and it is a number from 0 (trace nothing) to 1 (trace every call)");
        }
        defaults.put("management.tracing.sampling.probability", Double.toString(parsed));
        String endpoint = environment.getProperty("pravaha.tracing.endpoint", "");
        if (endpoint.isBlank()) {
            endpoint = environment.getProperty("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "");
        }
        if (endpoint.isBlank()) {
            String base = environment.getProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "");
            if (!base.isBlank()) {
                endpoint = base.replaceAll("/+$", "") + "/v1/traces";
            }
        }
        if (!endpoint.isBlank()) {
            if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                throw new IllegalStateException("pravaha.tracing.endpoint is '" + endpoint
                        + "', and it is the http:// or https:// URL of an OTLP/HTTP traces endpoint, "
                        + "such as http://collector:4318/v1/traces");
            }
            defaults.put("management.otlp.tracing.endpoint", endpoint);
            log.info("tracing: on, sampling " + parsed + ", exporting OTLP to " + endpoint);
        } else if (environment
                .getProperty("management.otlp.tracing.endpoint", "")
                .isBlank()) {
            log.warn("tracing: pravaha.tracing.enabled is true and no endpoint is set (pravaha.tracing.endpoint, "
                    + "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT or OTEL_EXPORTER_OTLP_ENDPOINT): spans are made, and trace "
                    + "ids appear in the logs, but nothing is exported");
        }
    }
}
