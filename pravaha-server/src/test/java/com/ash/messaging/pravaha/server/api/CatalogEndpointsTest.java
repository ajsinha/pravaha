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
import org.springframework.mock.web.MockHttpServletRequest;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.AuditTrail;
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

/** {@code /api/v1/catalog} (ADR-059): the same rules as the statements, over REST. */
class CatalogEndpointsTest {

    private static final Principal OPS = new Principal("ops", "acme", Set.of("admin"), Map.of());
    private static final Principal ANA = new Principal("ana", "acme", Set.of("analyst"), Map.of());

    private PravahaNode node;
    private CatalogController rest;

    @BeforeEach
    void start() {
        StreamCatalog streams = new StreamCatalog();
        streams.register(StreamSchema.builder("txn")
                .field("region", Types.string())
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
                .withNodeId("catalog-rest")
                .build();
        CatalogProperties catalog = new CatalogProperties();
        catalog.setEnabled(true);
        catalog.setAuthority("catalog");
        node.setCatalog(catalog);
        node.start();
        SecurityPolicy policy = node.securityPolicy();
        rest = new CatalogController(policy, new HttpAuthorizer(policy, AuditSink.NONE));
        node.registry().orElseThrow().register("revenue", "SELECT region, amount FROM txn", List.of(0), OPS);
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
    void anAdministratorOrganisesAndGrantsAndAnAnalystSeesExactlyThat() {
        rest.createNamespace(as(OPS), new CatalogController.NewNamespace("sales", "Order-to-cash", false));
        CatalogController.CatalogObjectDto moved = rest.change(
                as(OPS),
                "revenue",
                new CatalogController.ObjectChange(
                        "Revenue per region", Map.of("domain", "finance"), null, null, "sales"));
        assertThat(moved.name()).isEqualTo("acme.sales.revenue");
        assertThat(moved.tags()).containsEntry("domain", "finance");
        assertThat(moved.owner()).isEqualTo(new CatalogController.GranteeDto("USER", "ops"));

        // Not yet: the namespace is not one ana may use, so the object is not one she may see.
        assertThatThrownBy(() -> rest.object(as(ANA), "sales.revenue")).hasMessageContaining("PRV-7031");
        assertThat(rest.objects(as(ANA), null, null, "revenue").items()).isEmpty();

        rest.grant(as(OPS), new CatalogController.GrantRequest("acme.sales", List.of("USE"), "ROLE", "analyst"));
        rest.grant(
                as(OPS),
                new CatalogController.GrantRequest("sales.revenue", List.of("SELECT", "SUBSCRIBE"), "ROLE", "analyst"));

        CatalogController.ObjectDetail detail = rest.object(as(ANA), "sales.revenue");
        assertThat(detail.object().description()).isEqualTo("Revenue per region");
        assertThat(detail.grants())
                .extracting(CatalogController.GrantDto::privilege)
                .containsExactly("SELECT", "SUBSCRIBE");
        assertThat(detail.access().privileges())
                .filteredOn(CatalogController.AccessLineDto::allowed)
                .extracting(CatalogController.AccessLineDto::privilege)
                .containsExactlyInAnyOrder("SELECT", "SUBSCRIBE");
        assertThat(rest.objects(as(ANA), null, null, "finance").items())
                .extracting(CatalogController.CatalogObjectDto::name)
                .containsExactly("acme.sales.revenue");
        assertThat(rest.namespaces(as(ANA)).items())
                .extracting(CatalogController.CatalogObjectDto::name)
                .contains("acme.sales");

        CatalogController.AccessDto why = rest.access(as(ANA), "ana", "sales.revenue");
        assertThat(why.privileges())
                .filteredOn(line -> line.privilege().equals("SELECT"))
                .singleElement()
                .satisfies(line ->
                        assertThat(line.via()).containsExactly("grant SELECT on acme.sales.revenue to ROLE analyst"));

        // The engine enforces what the catalogue says.
        assertThat(node.securityPolicy()
                        .maySubscribe(ANA, "acme.default.revenue")
                        .allowed())
                .isTrue();
        rest.revoke(as(OPS), "sales.revenue", List.of("SUBSCRIBE"), "ROLE", "analyst");
        assertThat(node.securityPolicy()
                        .maySubscribe(ANA, "acme.default.revenue")
                        .allowed())
                .isFalse();
        assertThat(node.securityPolicy().mayRead(ANA, "acme.default.revenue").allowed())
                .isTrue();

        assertThat(rest.grants(as(ANA), null, "ROLE", "analyst").items()).hasSize(2);
    }

    @Test
    void changesNeedManageAndEveryAttemptIsAudited() {
        assertThatThrownBy(() -> rest.change(
                        as(ANA), "revenue", new CatalogController.ObjectChange("mine", null, null, null, null)))
                .hasMessageContaining("PRV-7033");
        assertThatThrownBy(() -> rest.grant(
                        as(ANA), new CatalogController.GrantRequest("revenue", List.of("SELECT"), "USER", "ana")))
                .hasMessageContaining("PRV-7033");
        assertThatThrownBy(() -> rest.grant(
                        as(OPS), new CatalogController.GrantRequest("revenue", List.of("WRITE"), "USER", "ana")))
                .hasMessageContaining("PRV-7032");
        assertThatThrownBy(() -> rest.grants(as(OPS), null, null, null)).hasMessageContaining("PRV-7037");
        List<AuditEvent> events = node.auditTrail().orElseThrow().read(AuditTrail.Filter.ANY, 500, 0).entries().stream()
                .map(AuditTrail.Entry::event)
                .toList();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo("catalog.comment");
            assertThat(e.allowed()).isFalse();
        });
        assertThat(events).anySatisfy(e -> assertThat(e.action()).isEqualTo("catalog.grant"));
    }

    @Test
    void askingAboutAUserTheNodeCannotIdentifyIsRefusedByName() {
        assertThatThrownBy(() -> rest.access(as(OPS), "nobody", "revenue")).hasMessageContaining("PRV-7037");
    }

    @Test
    void aNodeWithTheCatalogueOffSaysSo() {
        CatalogController off = new CatalogController(
                SecurityPolicy.PERMISSIVE, new HttpAuthorizer(SecurityPolicy.PERMISSIVE, AuditSink.NONE));
        assertThatThrownBy(() -> off.namespaces(as(OPS))).hasMessageContaining("PRV-7030");
    }
}
