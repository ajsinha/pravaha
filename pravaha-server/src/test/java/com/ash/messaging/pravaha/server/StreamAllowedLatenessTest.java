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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.WindowedAggregateOperator;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.api.ApiDtos;
import com.ash.messaging.pravaha.server.api.DtoMapper;
import com.ash.messaging.pravaha.server.api.StreamController;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HLP-7: a stream's allowed lateness, declared on a server.
 *
 * <p>{@code StreamSchema.allowedLateness} reaches every windowed aggregate the planner builds over
 * the stream, and the correction path it enables -- a late row retracting the published window and
 * inserting the corrected one -- was built and tested. Nothing on a server could set it: neither
 * {@code pravaha.streams.<n>} nor {@code POST /api/v1/streams} had the key, so every late row was
 * dropped.
 */
class StreamAllowedLatenessTest {

    private static final String WINDOWED =
            "SELECT user_id, COUNT(*) AS n FROM txn " + "GROUP BY user_id, TUMBLE(event_time, INTERVAL '10' SECOND)";

    @Test
    void aDeclaredAllowedLatenessReachesTheWindowedAggregate() {
        StreamDeclarationProperties declared = new Binder(new MapConfigurationPropertySource(Map.of(
                        "pravaha.streams.txn.schema", "user_id:STRING,amount:INT64,event_time:TIMESTAMP",
                        "pravaha.streams.txn.event-time", "event_time",
                        "pravaha.streams.txn.allowed-lateness", "30s")))
                .bind("pravaha", StreamDeclarationProperties.class)
                .get();
        assertThat(declared.getStreams().get("txn").getAllowedLateness()).isEqualTo(Duration.ofSeconds(30));

        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        PravahaNode node = PravahaNode.builder()
                .withDeclaredStreams(declared)
                .withSecurity(security)
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("lateness-node")
                .build();
        node.start();
        try {
            var query = node.registry()
                    .orElseThrow()
                    .register(
                            "late_counts",
                            WINDOWED,
                            List.of(0),
                            new Principal("dana", "acme", Set.of("analyst"), Map.of()));
            assertThat(windowed(query.plan()).allowedLatenessNanos())
                    .as("the window stays open to a correction for the declared 30 s")
                    .isEqualTo(Duration.ofSeconds(30).toNanos());
        } finally {
            node.stop();
        }
    }

    private static WindowedAggregateOperator windowed(PhysicalOperator plan) {
        if (plan instanceof WindowedAggregateOperator windowed) {
            return windowed;
        }
        for (PhysicalOperator input : plan.inputs()) {
            WindowedAggregateOperator found = windowed(input);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void aStreamDeclaredOverHttpTakesAnAllowedLatenessAndReportsIt() {
        StreamCatalog catalog = new StreamCatalog();
        StreamController streams = new StreamController(
                catalog,
                new DtoMapper(),
                new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE),
                new SourceBindingProperties());
        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.setAttribute(
                BearerTokenFilter.PRINCIPAL_ATTRIBUTE, new Principal("root", "acme", Set.of("admin"), Map.of()));

        ApiDtos.StreamSummary created = streams.register(
                        new StreamController.RegisterStreamRequest(
                                "clicks", "user:STRING,at:TIMESTAMP", "at", "PT5S", "PT1M"),
                        admin)
                .getBody();

        assertThat(created.allowedLateness()).isEqualTo("PT1M");
        assertThat(catalog.require("clicks").allowedLateness()).isEqualTo(Duration.ofMinutes(1));
        assertThat(streams.register(new StreamController.RegisterStreamRequest("plain", "user:STRING"), admin)
                        .getBody()
                        .allowedLateness())
                .as("no event time, so nothing for a lateness to be about")
                .isNull();
    }

    @Test
    void anAllowedLatenessWithoutAnEventTimeOrBelowZeroIsRefused() {
        var parsed = com.ash.messaging.pravaha.api.data.StreamSchema.builder("s")
                .field("at", com.ash.messaging.pravaha.api.data.Types.timestamp())
                .build();
        assertThatThrownBy(() -> StreamCatalog.withEventTime(parsed, null, null, Duration.ofSeconds(5)))
                .hasMessageContaining("allowed lateness and no event-time column");
        assertThatThrownBy(() -> StreamCatalog.withEventTime(parsed, "at", null, Duration.ofSeconds(-5)))
                .hasMessageContaining("negative allowed lateness");
    }
}
