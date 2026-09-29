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
import com.ash.messaging.pravaha.registry.ContinuousQueryStatements;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.alert.AlertStatus;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.AuditTrail;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.alerts.AlertProperties;
import com.ash.messaging.pravaha.server.alerts.NotifierBindingProperties;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.BearerTokenFilter;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code /api/v1/alerts} (ADR-057): the statements' rules, over REST, on a node with a {@code log} channel. */
class AlertEndpointsTest {

    private static final Principal BEA = new Principal("bea", "acme", Set.of("buyer"), Map.of());

    private PravahaNode node;
    private AlertController rest;
    private ContinuousQueryStatements statements;

    @BeforeEach
    void start() {
        StreamCatalog streams = new StreamCatalog();
        streams.register(StreamSchema.builder("stock")
                .field("sku", Types.string())
                .field("on_hand", Types.int64())
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
                .withNodeId("alerts-rest")
                .build();
        NotifierBindingProperties notifiers = new NotifierBindingProperties();
        NotifierBindingProperties.Spec log = new NotifierBindingProperties.Spec();
        log.setPlugin("log");
        notifiers.getNotifiers().put("ops-log", log);
        node.setAlerts(new AlertProperties(), notifiers);
        node.start();
        SecurityPolicy policy = node.securityPolicy();
        rest = new AlertController(node, new HttpAuthorizer(policy, AuditSink.NONE));
        QueryRegistry registry = node.registry().orElseThrow();
        registry.register("low", "SELECT sku, on_hand FROM stock WHERE on_hand < 5", List.of(0), BEA);
        statements = new ContinuousQueryStatements(registry, policy, AuditSink.NONE);
        statements.execute(
                ContinuousStatements.recognize("CREATE ALERT low_alert ON low NOTIFY \"ops-log\" WITH (dedupe = '5m')")
                        .orElseThrow(),
                BEA);
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
    void listDetailPauseResumeSnoozeAndAck() {
        assertThat(rest.list(as(BEA)).items()).singleElement().satisfies(alert -> {
            assertThat(alert.name()).isEqualTo("low_alert");
            assertThat(alert.view()).isEqualTo("low");
            assertThat(alert.state()).isEqualTo("ACTIVE");
            assertThat(alert.following()).isEqualTo("FOLLOWING");
            assertThat(alert.options()).containsEntry("dedupe", "PT5M");
        });
        assertThat(rest.channels(as(BEA)).items()).containsExactly(new AlertController.ChannelDto("ops-log", "log"));

        AlertStatus.Detail detail = rest.detail(as(BEA), "low_alert");
        assertThat(detail.keys()).isEmpty();
        assertThat(detail.notifications()).isEmpty();

        assertThat(rest.pause(as(BEA), "low_alert").state()).isEqualTo("PAUSED");
        assertThat(rest.resume(as(BEA), "low_alert").state()).isEqualTo("ACTIVE");
        AlertStatus.Summary snoozed = rest.snooze(as(BEA), "low_alert", new AlertController.SnoozeRequest("30m"));
        assertThat(snoozed.state()).isEqualTo("SNOOZED");
        assertThat(snoozed.snoozedUntil()).isNotNull();
        assertThat(rest.ack(as(BEA), "low_alert", null).acknowledged()).isZero();

        List<AuditEvent> events = node.auditTrail().orElseThrow().read(AuditTrail.Filter.ANY, 500, 0).entries().stream()
                .map(AuditTrail.Entry::event)
                .toList();
        assertThat(events)
                .extracting(AuditEvent::action)
                .contains("alert.create", "alert.pause", "alert.resume", "alert.snooze", "alert.ack");
    }

    @Test
    void anUnknownAlertIsNotFoundAndABadSnoozeIsTheCallers() {
        assertThatThrownBy(() -> rest.detail(as(BEA), "nothing"))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(ApiExceptionHandler.statusFor(e.errorCode()))
                                .isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("PRV-8040");
        assertThatThrownBy(() -> rest.snooze(as(BEA), "low_alert", new AlertController.SnoozeRequest("soon")))
                .isInstanceOfSatisfying(
                        PravahaException.class,
                        e -> assertThat(ApiExceptionHandler.statusFor(e.errorCode()))
                                .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("PRV-8042");
        Principal other = new Principal("oli", "globex", Set.of("buyer"), Map.of());
        assertThat(rest.list(as(other)).items()).isEmpty();
        assertThatThrownBy(() -> rest.pause(as(other), "low_alert")).hasMessageContaining("PRV-8040");
    }

    @Test
    void dropping() {
        assertThatThrownBy(() -> node.registry().orElseThrow().drop("acme.default.low"))
                .hasMessageContaining("PRV-8024")
                .hasMessageContaining("ALERT low_alert");
        statements.execute(
                ContinuousStatements.recognize("DROP ALERT low_alert").orElseThrow(), BEA);
        node.registry().orElseThrow().drop("acme.default.low");
        assertThat(rest.list(as(BEA)).items()).isEmpty();
    }
}
