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
import java.util.Optional;
import java.util.Set;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegistryErrors;
import com.ash.messaging.pravaha.registry.TenantQuotas;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.AuditTrail;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.security.AuthenticatedOnlyPolicy;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.server.tenancy.TenancyMeters;
import com.ash.messaging.pravaha.server.tenancy.TenancyProperties;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-050's operator surface: {@code GET /api/v1/tenants}, the {@code pravaha.tenant.*} meters, the
 * HTTP status a quota refusal carries, and {@code pravaha.tenancy.*} refusing what it cannot bind.
 */
class TenancyEndpointsTest {

    private static final StreamSchema ORDERS = StreamSchema.builder("orders")
            .field("order_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final Principal ADMIN = new Principal("root", "ops", Set.of("admin"), Map.of());
    private static final Principal ANN = new Principal("ann", "acme", Set.of("analyst"), Map.of());
    private static final Principal OMAR = new Principal("omar", "globex", Set.of("analyst"), Map.of());

    private static final SecurityPolicy POLICY = new AuthenticatedOnlyPolicy();

    private AuditTrail trail;
    private QueryRegistry registry;

    @BeforeEach
    void start() {
        trail = new AuditTrail(new AuditSink.InMemory(), "memory", 1_000);
        registry = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, trail, ORDERS)
                .limitingTenants(new TenantQuotas(
                        TenantQuotas.Limits.of(1L, null), Map.of("globex", TenantQuotas.Limits.of(3L, 1000L))));
        registry.register("acme_orders", "SELECT order_id FROM orders", List.of(0), ANN);
        registry.register("globex_orders", "SELECT order_id FROM orders", List.of(0), OMAR);
    }

    @AfterEach
    void stop() {
        registry.close();
    }

    private static MockHttpServletRequest as(Principal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, principal);
        return request;
    }

    private TenancyController controller() {
        return new TenancyController(POLICY, trail, new HttpAuthorizer(POLICY, trail), registry);
    }

    @Test
    void aTenantAtItsQuotaIsAConflictAndAReplacementFromAnotherTenantIsForbidden() {
        assertThat(ApiExceptionHandler.statusFor(RegistryErrors.TENANT_QUERY_QUOTA))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(ApiExceptionHandler.statusFor(RegistryErrors.TENANT_STATE_QUOTA))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(ApiExceptionHandler.statusFor(RegistryErrors.TENANT_MISMATCH))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAuditorSeesEveryTenantWithItsQuotasUseAndRefusals() {
        assertThatThrownBy(() -> registry.register("acme_more", "SELECT amount FROM orders", List.of(0), ANN))
                .isInstanceOf(PravahaException.class);

        TenancyController.Page page = controller().read(as(ADMIN));

        assertThat(page.scope()).isEqualTo("all");
        assertThat(page.defaults().maxQueries()).isEqualTo(1L);
        assertThat(page.defaults().maxStateKeys())
                .as("no limit is null, never zero")
                .isNull();
        assertThat(page.tenants()).extracting(TenancyController.Tenant::tenant).containsExactly("acme", "globex");
        TenancyController.Tenant acme = page.tenants().get(0);
        assertThat(acme.queries()).isEqualTo(1);
        assertThat(acme.queryRefusals()).isEqualTo(1);
        TenancyController.Tenant globex = page.tenants().get(1);
        assertThat(globex.limits().maxQueries()).isEqualTo(3L);
        assertThat(globex.limits().maxStateKeys()).isEqualTo(1000L);
    }

    @Test
    void anyoneElseSeesTheirOwnTenantAndNoOther() {
        TenancyController.Page page = controller().read(as(ANN));

        assertThat(page.scope()).isEqualTo("own");
        assertThat(page.tenants()).extracting(TenancyController.Tenant::tenant).containsExactly("acme");
        assertThat(trail.read(new AuditTrail.Filter(null, null, "ann", null, "http.tenants.read", null), 10, 0)
                        .entries())
                .as("the read is on the record, with how much it showed")
                .singleElement()
                .satisfies(entry -> assertThat(entry.event().detail()).contains("own tenant only"));
    }

    @Test
    void eachTenantHasMetersForItsUseItsQuotasAndItsRefusals() {
        assertThatThrownBy(() -> registry.register("acme_more", "SELECT amount FROM orders", List.of(0), ANN))
                .isInstanceOf(PravahaException.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TenancyMeters tenancy = new TenancyMeters(meters, () -> Optional.of(registry));
        tenancy.sync(registry);

        assertThat(tenancy.tenants()).containsExactlyInAnyOrder("acme", "globex");
        assertThat(meters.get("pravaha.tenant.queries")
                        .tag("tenant", "acme")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(meters.get("pravaha.tenant.quota.queries")
                        .tag("tenant", "globex")
                        .gauge()
                        .value())
                .isEqualTo(3.0);
        assertThat(meters.get("pravaha.tenant.quota.state.keys")
                        .tag("tenant", "acme")
                        .gauge()
                        .value())
                .as("no limit reads NaN, so an alert on use/limit cannot mistake it for zero")
                .isNaN();
        assertThat(meters.get("pravaha.tenant.refusals")
                        .tags("tenant", "acme", "quota", "queries")
                        .functionCounter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get("pravaha.tenant.refusals")
                        .tags("tenant", "acme", "quota", "state")
                        .functionCounter()
                        .count())
                .isZero();
        tenancy.close();
        assertThat(meters.getMeters()).isEmpty();
    }

    @EnableConfigurationProperties(TenancyProperties.class)
    static class Binding {}

    @Test
    void theQuotasBindFromConfigurationAndAMisspeltKeyIsRefused() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);
        runner.withPropertyValues(
                        "pravaha.tenancy.defaults.max-queries=10", "pravaha.tenancy.tenants.globex.max-state-keys=500")
                .run(context -> {
                    TenantQuotas quotas =
                            context.getBean(TenancyProperties.class).quotas();
                    assertThat(quotas.limitsFor("acme").maxQueries()).hasValue(10);
                    assertThat(quotas.limitsFor("globex").maxQueries()).hasValue(10);
                    assertThat(quotas.limitsFor("globex").maxStateKeys()).hasValue(500);
                });
        runner.withPropertyValues("pravaha.tenancy.defaults.max-querys=10")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aNodeConfiguredWithQuotasHandsThemToItsRegistryBeforeItRecovers() {
        com.ash.messaging.pravaha.server.catalog.StreamCatalog catalog =
                new com.ash.messaging.pravaha.server.catalog.StreamCatalog();
        catalog.register(ORDERS);
        com.ash.messaging.pravaha.server.security.SecurityProperties security =
                new com.ash.messaging.pravaha.server.security.SecurityProperties();
        security.setAllowAnonymous(true);
        com.ash.messaging.pravaha.server.state.PersistenceProperties persistence =
                new com.ash.messaging.pravaha.server.state.PersistenceProperties();
        persistence.getRegistry().setJournal("");
        com.ash.messaging.pravaha.server.PravahaNode node = com.ash.messaging.pravaha.server.PravahaNode.builder()
                .withCatalog(catalog)
                .withSecurity(security)
                .withWatermark(java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("tenancy-node")
                .build();
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.getDefaults().setMaxQueries(7L);
        node.setTenancy(tenancy);
        node.start();
        try {
            assertThat(node.registry().orElseThrow().tenantQuotas().defaults().maxQueries())
                    .hasValue(7);
        } finally {
            node.stop();
        }
    }
}
