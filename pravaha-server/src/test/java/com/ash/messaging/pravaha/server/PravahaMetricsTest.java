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

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.catalog.StreamDeclarationProperties;
import com.ash.messaging.pravaha.server.ingest.SourceBindingProperties;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Continuous queries appear in the metrics, and stop appearing when they are dropped.
 *
 * <p>The actuator endpoints were exposing Prometheus from the start with nothing of Pravaha's in
 * them: a deployment could watch JVM heap and HTTP latency while the thing the process exists to do
 * was entirely dark.
 *
 * <p>Most of the care here is about removal rather than publication. A gauge registered per query and
 * never removed leaks twice over -- the meter, and the query state the meter's reference keeps alive
 * -- and Micrometer will not complain, because a meter that exists is a meter somebody wanted.
 */
class PravahaMetricsTest {

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());

    private SimpleMeterRegistry meters;
    private PravahaNode node;
    private PravahaMetrics metrics;

    @BeforeEach
    void setUp() {
        StreamCatalog catalog = new StreamCatalog();
        catalog.register(StreamSchema.builder("txn")
                .field("user_id", Types.string())
                .field("amount", Types.int64())
                .build());
        node = new PravahaNode(
                catalog,
                new SourceBindingProperties(),
                new StreamDeclarationProperties(),
                openServer(),
                null,
                null,
                java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(1),
                false,
                "127.0.0.1",
                0,
                persistence(""),
                "SINGLE",
                "single",
                "metrics-node",
                true,
                false,
                null,
                false,
                "127.0.0.1",
                0);
        node.start();
        meters = new SimpleMeterRegistry();
        metrics = new PravahaMetrics(meters, node);
    }

    @AfterEach
    void tearDown() {
        metrics.close();
        meters.close();
        node.stop();
    }

    @Test
    void aRegisteredQueryGetsMeters() {
        node.registry().orElseThrow().register("by_user", "SELECT user_id, amount FROM txn", List.of(0), DANA);

        metrics.sync();

        assertThat(meters.find("pravaha.query.rows.in").tag("query", "by_user").gauge())
                .isNotNull();
        assertThat(meters.find("pravaha.query.view.size")
                        .tag("query", "by_user")
                        .gauge())
                .isNotNull();
        assertThat(meters.find("pravaha.query.running")
                        .tag("query", "by_user")
                        .gauge()
                        .value())
                .isEqualTo(1);
    }

    @Test
    void droppingAQueryRemovesItsMeters() {
        node.registry().orElseThrow().register("temporary", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        metrics.sync();
        assertThat(metrics.published()).containsKey("temporary");

        node.registry().orElseThrow().drop("temporary");
        metrics.sync();

        // The leak this prevents has two parts: the meter itself, and the query state that the
        // meter's reference would otherwise keep alive for as long as the process runs.
        assertThat(metrics.published()).doesNotContainKey("temporary");
        assertThat(meters.find("pravaha.query.rows.in")
                        .tag("query", "temporary")
                        .gauge())
                .isNull();
    }

    @Test
    void syncingTwiceDoesNotPublishTwice() {
        node.registry().orElseThrow().register("stable", "SELECT user_id, amount FROM txn", List.of(0), DANA);

        metrics.sync();
        int afterFirst = metrics.published().get("stable");
        metrics.sync();

        assertThat(metrics.published().get("stable")).isEqualTo(afterFirst);
        assertThat(meters.find("pravaha.query.rows.in").tag("query", "stable").gauges())
                .hasSize(1);
    }

    @Test
    void aQueryThatHasSeenNothingReportsNoLagRatherThanZeroLag() {
        node.registry().orElseThrow().register("quiet", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        metrics.sync();

        double lag = meters.find("pravaha.query.watermark.lag.seconds")
                .tag("query", "quiet")
                .gauge()
                .value();

        // Zero would show a query that has never seen a row as perfectly up to date, which is the
        // opposite of what is true.
        assertThat(Double.isNaN(lag)).isTrue();
    }

    @Test
    void closingRemovesEverythingItPublished() {
        node.registry().orElseThrow().register("one_view", "SELECT user_id, amount FROM txn", List.of(0), DANA);
        node.registry().orElseThrow().register("two", "SELECT user_id, amount, user_id FROM txn", List.of(0), DANA);
        metrics.sync();

        metrics.close();

        assertThat(metrics.published()).isEmpty();
        assertThat(meters.find("pravaha.query.rows.in").gauges()).isEmpty();
    }

    /**
     * A node that serves everything to everybody, said out loud.
     *
     * <p>These tests exercise the lifecycle rather than the security model, and the node now refuses
     * to start open unless a deployment states that it means to. Stating it here keeps the refusal
     * honest: if the guard is ever removed, these tests do not quietly start covering a different
     * configuration from the one they name.
     */
    private static SecurityProperties openServer() {
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        return security;
    }

    /** Journal where the caller asked for one, and no checkpoint directory. */
    private static PersistenceProperties persistence(String journal) {
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal(journal == null ? "" : journal);
        return persistence;
    }
}
