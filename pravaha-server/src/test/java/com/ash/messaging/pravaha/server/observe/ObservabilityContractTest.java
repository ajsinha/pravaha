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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import com.ash.messaging.pravaha.registry.ContinuousQueryStatements;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.PravahaMetrics;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The observability contract, against a started node: every metric a shipped Grafana dashboard or
 * the shipped Prometheus rules name is one this node actually publishes; the new features' meters are
 * on the scrape; a request carries a correlation id; and with tracing off -- the default -- no span
 * is made at all, even with an exporter standing by.
 *
 * <p>The node has every surface the dashboards read switched on: the catalogue, an alert on a {@code
 * log} channel, shared lanes, a registered query and a Flight call. A metric only a dashboard knows
 * about is a panel that says "No data" on the day it is needed, and that is what this refuses. The
 * console's metrics ({@code pravaha_console_*}) are the console's to publish; its own tests check them
 * against the same files.
 */
@SpringBootTest(
        properties = {
            "pravaha.security.allow-anonymous=true",
            "pravaha.security.policy=permissive",
            "pravaha.flight.port=0",
            "pravaha.flight.host=127.0.0.1",
            "pravaha.catalog.enabled=true",
            "pravaha.notifiers.ops-log.plugin=log",
            "pravaha.lane.multiplex.enabled=true",
            "pravaha.lane.multiplex.lanes=1",
            "pravaha.streams.txn.schema=user_id:STRING,amount:INT64"
        })
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObservabilityContractTest {

    /** Where the shipped dashboards and rules live, from this module's directory. */
    static final Path OBSERVABILITY = Path.of("..", "deploy", "observability");

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    /** A metric name in a PromQL expression: ours, the JVM's, the process's and Spring's HTTP ones. */
    private static final Pattern METRIC = Pattern.compile("\\b((?:pravaha|jvm|process|http_server)_[a-z0-9_]+)\\b");

    @TestConfiguration
    static class Exporter {
        @Bean
        InMemorySpanExporter spans() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PravahaNode node;

    @Autowired
    private PravahaMetrics metrics;

    @Autowired
    private InMemorySpanExporter spans;

    @Autowired
    private ApplicationContext context;

    private String scrape;

    @BeforeAll
    void exerciseEverySurfaceAndScrape() throws Exception {
        QueryRegistry registry = node.registry().orElseThrow();
        registry.register("by_user", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        new ContinuousQueryStatements(registry, node.securityPolicy(), AuditSink.NONE)
                .execute(
                        ContinuousStatements.recognize("CREATE ALERT big_spender ON by_user NOTIFY \"ops-log\"")
                                .orElseThrow(),
                        DANA);
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
                FlightClient client = FlightClient.builder(
                                allocator,
                                Location.forGrpcInsecure(
                                        "127.0.0.1", node.flightPort().orElseThrow()))
                        .build()) {
            client.listActions().forEach(action -> {});
        }
        mvc.perform(get("/api/v1/queries")).andExpect(status().isOk());
        metrics.sync();
        scrape = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        // Kept beside the test's reports: what a node publishes, for whoever edits a dashboard next.
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "observability-scrape.txt"), scrape, StandardCharsets.UTF_8);
    }

    /** The metric names on the scrape, without their labels. */
    private Set<String> published() {
        Set<String> names = new TreeSet<>();
        for (String line : scrape.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int end = line.indexOf('{');
            if (end < 0) {
                end = line.indexOf(' ');
            }
            names.add(line.substring(0, end));
        }
        return names;
    }

    /** Every metric name the expressions in {@code text} use, the console's left out. */
    static Set<String> namesIn(String text) {
        Set<String> names = new TreeSet<>();
        Matcher found = METRIC.matcher(text);
        while (found.find()) {
            String name = found.group(1);
            if (!name.startsWith("pravaha_console_")) {
                names.add(name);
            }
        }
        return names;
    }

    /** Every PromQL expression the shipped dashboards hold, from their panels and variables. */
    static String dashboardExpressions() throws IOException {
        ObjectMapper json = new ObjectMapper();
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(OBSERVABILITY.resolve("grafana"))) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonNode dashboard = json.readTree(file.toFile());
                assertThat(dashboard.path("schemaVersion").asInt())
                        .as("%s: a recent Grafana schema", file)
                        .isGreaterThanOrEqualTo(36);
                assertThat(dashboard.path("description").asText())
                        .as("%s carries the proprietary notice in its description", file)
                        .contains("Copyright (c) 2026 Ashutosh Sinha");
                List<String> variables =
                        dashboard.path("templating").path("list").findValuesAsText("name");
                assertThat(variables).as("%s: a data source variable", file).contains("datasource");
                dashboard.findValues("expr").forEach(e -> all.append(e.asText()).append('\n'));
                dashboard
                        .findValues("definition")
                        .forEach(e -> all.append(e.asText()).append('\n'));
            }
        }
        return all.toString();
    }

    @Test
    void everyMetricADashboardOrARuleNamesIsPublishedByTheNode() throws IOException {
        Set<String> used = namesIn(dashboardExpressions());
        String rules = Files.readString(OBSERVABILITY.resolve("prometheus/pravaha-rules.yaml"), StandardCharsets.UTF_8);
        used.addAll(namesIn(rules));
        assertThat(used).as("the files name metrics at all").hasSizeGreaterThan(30);
        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(published());
        assertThat(missing)
                .as("metrics a dashboard or rule reads that this node does not publish; a panel on one of these "
                        + "says No data when it is needed")
                .isEmpty();
    }

    @Test
    void theNewFeaturesMetersAreOnTheScrape() {
        assertThat(scrape)
                .contains("pravaha_alert_keys_firing{", "alert=\"acme.default.big_spender\"")
                .contains("pravaha_alert_transitions_total{", "kind=\"fired\"")
                .contains("pravaha_alert_notifications_total{", "channel=\"ops-log\"", "outcome=\"failed\"")
                .contains("pravaha_alert_notification_retries_total{")
                .contains("pravaha_alert_delivery_seconds_count{")
                .contains("pravaha_alert_notifications_owed{")
                .contains("pravaha_alert_journal_write_failures_total{")
                .contains("pravaha_catalog_access_decisions_total{", "privilege=\"SELECT\"", "outcome=\"deny\"")
                .contains("pravaha_catalog_decision_cache_lookups_total{", "result=\"hit\"")
                .contains("pravaha_catalog_changes_total{", "kind=\"grant\"")
                .contains("pravaha_catalog_subscriptions_ended_total{", "reason=\"narrowing_changed\"")
                .contains("pravaha_flight_calls_seconds_count{", "operation=\"list.actions\"");
        // No user, key, statement or row is a label anywhere on the scrape.
        assertThat(scrape).doesNotContain("dana").doesNotContain("SELECT user_id");
    }

    @Test
    void aRequestAnswersItsCorrelationIdKeepingAPlainOneAndReplacingAnUnsafeOne() throws Exception {
        mvc.perform(get("/api/v1/queries").header(Correlation.HEADER, "req-42"))
                .andExpect(header().string(Correlation.HEADER, "req-42"));
        mvc.perform(get("/api/v1/queries").header(Correlation.HEADER, "bad\nid"))
                .andExpect(header().string(Correlation.HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f]{16}")));
        assertThat(CorrelationFilter.queryOf("/api/v1/queries/by_user/plan")).isEqualTo("by_user");
        assertThat(CorrelationFilter.queryOf("/api/v1/queries/validate")).isNull();
        assertThat(CorrelationFilter.queryOf("/api/v1/streams")).isNull();
    }

    @Test
    void withTracingOffByDefaultThereIsNoTracerAndNoSpan() {
        assertThat(context.getBeanProvider(SdkTracerProvider.class).getIfAvailable())
                .isNull();
        // This context installed nothing. (The facade is process-wide, and a tracing context another test
        // class left in Spring's cache could have; TracingTest closes its own for that reason.)
        assertThat(context.getBean(EngineTracing.class).installed()).isFalse();
        assertThat(spans.getFinishedSpanItems()).isEmpty();
    }
}
