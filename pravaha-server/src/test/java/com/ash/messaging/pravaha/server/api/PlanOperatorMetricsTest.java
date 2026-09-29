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
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B6 at the HTTP surface: what {@code GET /api/v1/queries/{name}/plan} carries once
 * {@code pravaha.metrics.operators} is on.
 *
 * <p>A class of its own rather than a case in {@code RegistryEndpointsTest}, because the switch is
 * read when a query compiles its stages: it has to be on <em>before</em> the registration, and
 * turning it on for that whole class would change what every other case there is measuring.
 *
 * <p>What is pinned here is the contract the console's plan graph is written against: the metrics
 * are keyed by the same node ids the graph's nodes carry, the scan's rows in is the query's rows
 * in, and a caller entitled only to a row-filtered slice is told nothing per operator -- rows past
 * a filter it may not see is still a count of rows it may not see.
 */
class PlanOperatorMetricsTest {

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("order_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal ROOT = new Principal("root", "public", Set.of("analyst"), Map.of());
    private static final Principal ANALYST = new Principal("ann", "public", Set.of("analyst"), Map.of());
    private static final Principal SLICED = new Principal("bob", "public", Set.of("sliced"), Map.of());

    private static final SecurityPolicy POLICY = new SecurityPolicy() {
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

    private QueryRegistry registry;
    private QueryController queries;

    @BeforeEach
    void start() {
        // Before the registration, because the counters are compiled into the stages.
        InterpretedPipeline.measureOperators(true);
        AuditSink.InMemory audit = new AuditSink.InMemory();
        registry = new QueryRegistry(new ViewCatalog(), POLICY, audit, ORDERS);
        registry.register("orders_view", "SELECT order_id, amount FROM orders WHERE amount > 10", List.of(0), ROOT);

        StreamCatalog catalog = new StreamCatalog();
        catalog.register(ORDERS);
        queries = new QueryController(
                catalog, new DtoMapper(), new HttpAuthorizer(POLICY, audit), new RegistryAccess(registry, null, audit));
    }

    @AfterEach
    void stop() throws Exception {
        registry.close();
        InterpretedPipeline.measureOperators(false);
    }

    private static MockHttpServletRequest as(Principal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, principal);
        return request;
    }

    @Test
    void thePlanCarriesEachOperatorsOwnNumbersUnderTheNodeIdsTheGraphUses() {
        feed(20);

        ApiDtos.PlanGraph plan = queries.plan("orders_view", as(ANALYST));
        assertThat(plan.operatorMetrics()).isNotNull();
        assertThat(plan.operatorMetrics().keySet())
                .as("keyed by the graph's own node ids, so a console can hang them on its boxes")
                .containsExactlyInAnyOrderElementsOf(
                        plan.nodes().stream().map(ApiDtos.PlanNode::id).toList());

        String scanId = plan.nodes().stream()
                .filter(node -> "Scan".equals(node.operator()))
                .findFirst()
                .orElseThrow()
                .id();
        ApiDtos.OperatorTelemetry scan = plan.operatorMetrics().get(scanId);
        assertThat(scan.rowsIn()).isEqualTo(20);
        assertThat(scan.rowsOut()).isEqualTo(20);

        ApiDtos.OperatorTelemetry root = plan.operatorMetrics().get("n0");
        assertThat(root.rowsOut()).as("amounts 1..20, kept above 10").isEqualTo(10);
        assertThat(scan.stateBytes())
                .as("a scan holds no state and says so rather than reporting zero bytes")
                .isNull();
        assertThat(plan.metricsNote()).contains("are measured").contains("sampled");
    }

    @Test
    void aRowFilteredCallerIsToldNothingPerOperator() {
        feed(20);

        ApiDtos.PlanGraph plan = queries.plan("orders_view", as(SLICED));
        assertThat(plan.nodes()).as("the shape of the plan is not a secret").isNotEmpty();
        assertThat(plan.operatorMetrics())
                .as("rows past a filter this caller may not see is still a count of rows it may not see")
                .isNull();
        assertThat(plan.bottleneck()).isNull();
        assertThat(plan.query().rowsIn()).isEqualTo(-1);
    }

    private void feed(int rows) {
        var query = registry.find("orders_view").orElseThrow();
        RowLayout layout = RowLayout.of(ORDERS);
        try (RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8)) {
            for (int i = 1; i <= rows; i++) {
                BinaryRowWriter writer = new BinaryRowWriter(layout);
                long handle = arena.allocate(layout.rowSize(64));
                writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
                writer.setString(0, "o" + i).setLong(1, i);
                writer.weight(1L).eventTimestampNanos(0).sequence(i).commit();
                query.accept(new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle)));
            }
            assertThat(query.awaitApplied(java.time.Duration.ofSeconds(10))).isTrue();
        }
    }
}
