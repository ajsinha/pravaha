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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.bindings.ingest.PluginSourceFeeds;
import com.ash.messaging.pravaha.bindings.ingest.SourceBinding;
import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.api.ApiDtos;
import com.ash.messaging.pravaha.server.api.DtoMapper;
import com.ash.messaging.pravaha.server.api.QueryController;
import com.ash.messaging.pravaha.server.api.RegistryAccess;
import com.ash.messaging.pravaha.server.api.StatusController;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEED-1 on a running node: a source that fails mid-read is shown by every surface an operator uses.
 *
 * <p>The feed stopped and recorded why long before this; the query stayed {@code RUNNING}; and the
 * record was reachable only through {@code SourceFeed.describe()}, which nothing here called. So this
 * stops a real source on a real node -- a followed file gains a line it cannot decode -- and asks
 * each surface in turn: the query detail and listing, {@code /status} and its page, the Prometheus
 * meters and the health indicator. A second query on a good file is the control: its feed must say
 * {@code RUNNING}, so a surface that reported every query stopped would fail here too.
 */
class FeedStatusSurfacesTest {

    private static final String SCHEMA_SPEC = "id:INT64,user_id:STRING,amount:INT64";

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private PravahaNode node;
    private SimpleMeterRegistry meters;
    private PravahaMetrics metrics;
    private Path broken;

    @BeforeEach
    void start(@TempDir Path dir) throws Exception {
        broken = dir.resolve("broken.csv");
        Files.writeString(broken, "1,ann,100\n2,bob,250\n");
        Path good = dir.resolve("good.csv");
        Files.writeString(good, "1,ann,100\n");

        StreamCatalog catalog = new StreamCatalog();
        catalog.register(schema("broken"));
        catalog.register(schema("good"));
        SourceBindingProperties sources = new SourceBindingProperties();
        sources.getSources().put("broken", file(broken, true));
        sources.getSources().put("good", file(good, false));

        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        node = PravahaNode.builder()
                .withCatalog(catalog)
                .withSources(sources)
                .withSecurity(security)
                .withFlight(true, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("feed-node")
                .build();
        node.start();
        meters = new SimpleMeterRegistry();
        metrics = new PravahaMetrics(meters, node);
    }

    @AfterEach
    void stop() {
        metrics.close();
        meters.close();
        node.stop();
    }

    @Test
    void aSourceThatFailsMidReadIsShownByTheApiStatusMetricsAndHealth() throws Exception {
        QueryRegistry registry = node.registry().orElseThrow();
        RegisteredQuery stalled = registry.register("stalled", "SELECT id, amount FROM broken", List.of(0), DANA);
        registry.register("healthy", "SELECT id, amount FROM good", List.of(0), DANA);
        awaitRows(stalled, 2);

        // Before: every surface says the feeds are running.
        StatusController status = new StatusController(
                PravahaEngine.createDefault(), new StreamCatalog(), new RegistryAccess(node, AuditSink.NONE));
        assertThat(status.status().stoppedFeeds()).isZero();
        assertThat(new EngineHealthIndicator(node).health().getStatus()).isEqualTo(Status.UP);

        Files.writeString(broken, "3,cat,not-a-number\n", StandardOpenOption.APPEND);
        awaitStopped(stalled);

        // The query's own detail, and the listing, through the HTTP API.
        QueryController api = new QueryController(
                new StreamCatalog(),
                new DtoMapper(),
                new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE),
                new RegistryAccess(node, AuditSink.NONE));
        ApiDtos.QueryDetail detail = api.get("stalled", as(DANA));
        assertThat(detail.state()).as("the state keeps its meaning").isEqualTo("RUNNING");
        assertThat(detail.feed().state()).isEqualTo("STOPPED");
        assertThat(detail.feed().stoppedSources()).isEqualTo(1);
        assertThat(detail.feed().failure().code()).isEqualTo("PRV-5040");
        assertThat(detail.feed().failure().helpUrl()).endsWith("/PRV-5040");
        assertThat(detail.feed().sources()).singleElement().satisfies(source -> {
            assertThat(source.stream()).isEqualTo("broken");
            assertThat(source.partition()).isZero();
            assertThat(source.state()).isEqualTo("STOPPED");
            assertThat(source.origin()).isTrue();
            assertThat(source.stoppedAt()).isNotNull();
            assertThat(source.failure().code()).isEqualTo("PRV-5040");
        });
        ApiDtos.QueryDetail control = api.get("healthy", as(DANA));
        assertThat(control.feed().state()).isEqualTo("RUNNING");
        assertThat(control.feed().failure()).isNull();
        assertThat(control.feed().sources())
                .singleElement()
                .satisfies(source -> assertThat(source.state()).isEqualTo("RUNNING"));
        assertThat(api.list(as(DANA)))
                .extracting(query -> query.name() + "=" + query.feed().state())
                .containsExactlyInAnyOrder("stalled=STOPPED", "healthy=RUNNING");

        // /status: a count, and a row on the page.
        assertThat(status.status().stoppedFeeds()).isEqualTo(1);
        assertThat(status.statusPage())
                .contains("<th>Stopped sources</th><td class=\"bad\">1 query not receiving rows");

        // Prometheus.
        metrics.sync();
        assertThat(meters.find("pravaha.query.feed.stopped")
                        .tag("query", "stalled")
                        .gauge()
                        .value())
                .isEqualTo(1);
        assertThat(meters.find("pravaha.query.feed.stopped")
                        .tag("query", "healthy")
                        .gauge()
                        .value())
                .isZero();
        assertThat(meters.find("pravaha.query.feed.failures")
                        .tag("query", "stalled")
                        .functionCounter()
                        .count())
                .isEqualTo(1);
        assertThat(meters.find("pravaha.query.running")
                        .tag("query", "stalled")
                        .gauge()
                        .value())
                .as("running says 1, which is why the feed gauge exists")
                .isEqualTo(1);

        // Health: degraded, not down, with the code.
        Health health = new EngineHealthIndicator(node).health();
        assertThat(health.getStatus()).isEqualTo(EngineHealthIndicator.DEGRADED);
        assertThat(health.getDetails())
                .containsEntry("stoppedFeeds", 1)
                .containsEntry("firstStoppedFeed", "PRV-5040 reading broken#0");
    }

    @Test
    void aRowFilteredCallerGetsTheCodeAndNotTheTextAndNobodyGetsACredential(@TempDir Path dir) throws Exception {
        String password = "s3cret-source-password";
        Path file = dir.resolve("txn.csv");
        Files.writeString(file, "1,ann,100\n");
        PluginSourceFeeds feeds = new PluginSourceFeeds()
                .bind(new SourceBinding(
                        "txn",
                        "filesystem",
                        Map.of(
                                "path",
                                file.toString(),
                                "schema",
                                SCHEMA_SPEC,
                                "follow",
                                "true",
                                "password",
                                password)));
        Principal sliced = new Principal("bob", "acme", Set.of("sliced"), Map.of());
        SecurityPolicy policy = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return principal.hasRole("sliced")
                        ? AccessDecision.allowWithRowFilter("amount > 0")
                        : AccessDecision.allow();
            }

            @Override
            public AccessDecision mayRegisterQuery(Principal principal) {
                return AccessDecision.allow();
            }
        };
        try (QueryRegistry registry =
                new QueryRegistry(new ViewCatalog(), policy, AuditSink.NONE, schema("txn")).feedingFrom(feeds)) {
            RegisteredQuery query = registry.register("txn_view", "SELECT id, amount FROM txn", List.of(0), DANA);
            awaitRows(query, 1);
            // The file's own content carries the credential into the decode failure's message.
            Files.writeString(file, "2,cat," + password + "\n", StandardOpenOption.APPEND);
            awaitStopped(query);

            QueryController api = new QueryController(
                    new StreamCatalog(),
                    new DtoMapper(),
                    new HttpAuthorizer(policy, AuditSink.NONE),
                    new RegistryAccess(registry, null, feeds, AuditSink.NONE));
            ApiDtos.QueryFeed full = api.get("txn_view", as(DANA)).feed();
            assertThat(full.failure().code()).isEqualTo("PRV-5040");
            assertThat(full.failure().message()).doesNotContain(password);

            ApiDtos.QueryFeed withheld = api.get("txn_view", as(sliced)).feed();
            assertThat(withheld.state()).isEqualTo("STOPPED");
            assertThat(withheld.failure().code()).isEqualTo("PRV-5040");
            assertThat(withheld.failure().message()).startsWith("the message is withheld");
            assertThat(withheld.sources().get(0).failure().message()).startsWith("the message is withheld");
        }
    }

    private static StreamSchema schema(String name) {
        return StreamSchema.builder(name)
                .field("id", Types.int64())
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build();
    }

    private static SourceBindingProperties.Spec file(Path path, boolean follow) {
        SourceBindingProperties.Spec spec = new SourceBindingProperties.Spec();
        spec.setPlugin("filesystem");
        spec.getOptions().put("path", path.toString());
        spec.getOptions().put("schema", SCHEMA_SPEC);
        spec.getOptions().put("follow", Boolean.toString(follow));
        return spec;
    }

    private static MockHttpServletRequest as(Principal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, principal);
        return request;
    }

    private static void awaitRows(RegisteredQuery query, long rows) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (query.rowsIn() < rows && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(query.rowsIn()).isGreaterThanOrEqualTo(rows);
    }

    private static void awaitStopped(RegisteredQuery query) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!query.feedStatus().stopped() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(query.feedStatus().stopped()).as("the feed stopped").isTrue();
    }
}
