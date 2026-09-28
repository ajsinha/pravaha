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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.governance.CatalogProperties;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code /api/v1/catalog/policies} (ADR-059 §4): row filters and masks over REST, checked on a real node. */
class PolicyEndpointsTest {

    private static final Principal OPS = new Principal("ops", "acme", Set.of("admin"), Map.of());
    private static final Principal ANA = new Principal("ana", "acme", Set.of("analyst"), Map.of("region", "EU"));

    private PravahaNode node;
    private PolicyController policies;
    private CatalogController catalog;

    @BeforeEach
    void start() {
        StreamCatalog streams = new StreamCatalog();
        streams.register(StreamSchema.builder("txn")
                .field("id", Types.string())
                .field("region", Types.string())
                .field("card", Types.string())
                .field("amount", Types.int64())
                .build());
        SecurityProperties security = new SecurityProperties();
        security.setAllowAnonymous(true);
        security.setAudit("memory");
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        node = PravahaNode.builder()
                .withCatalog(streams)
                .withSecurity(security)
                .withWatermark(Duration.ofSeconds(30), Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence)
                .withNodeId("policy-rest")
                .build();
        CatalogProperties properties = new CatalogProperties();
        properties.setEnabled(true);
        properties.setAuthority("catalog");
        node.setCatalog(properties);
        node.start();
        SecurityPolicy policy = node.securityPolicy();
        HttpAuthorizer authorizer = new HttpAuthorizer(policy, AuditSink.NONE);
        policies = new PolicyController(policy, authorizer);
        catalog = new CatalogController(policy, authorizer);
        node.registry().orElseThrow().register("payments", "SELECT id, region, card, amount FROM txn", List.of(0), OPS);
    }

    @AfterEach
    void stop() {
        node.stop();
    }

    private static MockHttpServletRequest as(Principal who) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BearerTokenFilter.PRINCIPAL_ATTRIBUTE, who);
        return request;
    }

    @Test
    void createBindShowUnbindAndDrop() {
        var created = policies.create(
                as(OPS),
                new PolicyController.NewPolicy(
                        "region_scope",
                        "ROW_FILTER",
                        null,
                        "region = session_attribute('region')",
                        List.of("finance_admin"),
                        "EU and US each see their own"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().name()).isEqualTo("acme.default.region_scope");
        assertThat(created.getBody().owner()).isEqualTo(new CatalogController.GranteeDto("USER", "ops"));
        policies.create(
                as(OPS), new PolicyController.NewPolicy("card_hidden", "MASK", "card", "'XXXX'", List.of(), null));

        var bound = policies.bind(as(OPS), "region_scope", new PolicyController.BindRequest("payments", null));
        assertThat(bound.getBody().object()).isEqualTo("acme.default.payments");
        policies.bind(as(OPS), "card_hidden", new PolicyController.BindRequest(null, "pii"));
        catalog.change(
                as(OPS), "payments", new CatalogController.ObjectChange(null, Map.of("pii", ""), null, null, null));

        assertThat(policies.list(as(OPS), "payments").items())
                .extracting(PolicyController.PolicyDto::name)
                .containsExactly("acme.default.region_scope", "acme.default.card_hidden");
        CatalogController.ObjectDetail detail = catalog.object(as(OPS), "payments");
        assertThat(detail.policies()).hasSize(2);
        CatalogController.AccessDto why = catalog.access(as(OPS), "ops", "payments");
        assertThat(why.policies())
                .extracting(CatalogController.PolicyLineDto::kind)
                .contains("ROW_FILTER", "MASK");

        // Checked against the view's columns before it is recorded.
        policies.create(
                as(OPS), new PolicyController.NewPolicy("bad", "ROW_FILTER", null, "country = 'DE'", null, null));
        assertThatThrownBy(() -> policies.bind(as(OPS), "bad", new PolicyController.BindRequest("payments", null)))
                .isInstanceOfSatisfying(
                        PravahaException.class, e -> assertThat(e.getMessage()).contains("PRV-7038"));
        policies.create(as(OPS), new PolicyController.NewPolicy("wrong", "MASK", "amount", "'x'", null, null));
        assertThatThrownBy(() -> policies.bind(as(OPS), "wrong", new PolicyController.BindRequest("payments", null)))
                .hasMessageContaining("PRV-7038")
                .hasMessageContaining("keeps the column's type");
        assertThatThrownBy(() -> policies.bind(as(OPS), "bad", new PolicyController.BindRequest("payments", "pii")))
                .hasMessageContaining("PRV-7037");

        // A bound policy is not dropped; unbinding says whether it was bound.
        assertThatThrownBy(() -> policies.drop(as(OPS), "region_scope")).hasMessageContaining("PRV-7040");
        assertThat(policies.unbind(as(OPS), "region_scope", "payments", null).unbound())
                .isTrue();
        assertThat(policies.unbind(as(OPS), "region_scope", "payments", null).unbound())
                .isFalse();
        assertThat(policies.drop(as(OPS), "region_scope").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(policies.unbind(as(OPS), "card_hidden", null, "pii").unbound())
                .isTrue();
        assertThatThrownBy(() -> policies.show(as(OPS), "region_scope")).hasMessageContaining("PRV-7031");
    }

    @Test
    void anAnalystMayNotBindToWhatTheyDoNotManage() {
        policies.create(as(OPS), new PolicyController.NewPolicy("f", "ROW_FILTER", null, "region = 'EU'", null, null));
        assertThatThrownBy(() -> policies.bind(as(ANA), "f", new PolicyController.BindRequest("payments", null)))
                .isInstanceOf(PravahaException.class);
        assertThatThrownBy(() -> policies.create(
                        as(ANA), new PolicyController.NewPolicy("g", "ROW_FILTER", null, "region = 'EU'", null, null)))
                .hasMessageContaining("PRV-7033");
        assertThatThrownBy(() -> policies.create(
                        as(OPS), new PolicyController.NewPolicy("h", "ROW_FILTER", null, "RAND() < 1", null, null)))
                .hasMessageContaining("PRV-7038");
    }
}
