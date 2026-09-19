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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.server.catalog.StreamCatalog;
import com.ash.messaging.pravaha.server.security.SecurityProperties;
import com.ash.messaging.pravaha.server.state.PersistenceProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both transports of one node record into one audit sink.
 *
 * <p>CFG-5. {@code PravahaServerApplication.pravahaAuditSink()} returned {@code AuditSink.NONE}
 * unconditionally, and that bean is what {@code HttpAuthorizer} takes — the only thing enforcing
 * authorization on {@code /api/v1/**}. So a node configured {@code audit: memory} recorded every
 * Flight read and no HTTP read, no HTTP stream declaration and no HTTP refusal, with nothing at
 * startup saying so.
 *
 * <p>The test asserts <strong>identity, not configuration</strong>, and that is the point. Resolving
 * {@code pravaha.security.audit} a second time inside the bean would make this look fixed while
 * leaving it broken: {@code memory} builds an {@code InMemory} sink, so the HTTP surface would get a
 * second one that nothing can reach. The events would still be invisible and the configuration would
 * now read as correct, which is worse than the bug.
 */
class AuditSinkSharingTest {

    private static PersistenceProperties persistence() {
        PersistenceProperties persistence = new PersistenceProperties();
        persistence.getRegistry().setJournal("");
        return persistence;
    }

    private static PravahaNode node(String audit) {
        SecurityProperties security = new SecurityProperties();
        security.setAudit(audit);
        return PravahaNode.builder()
                .withCatalog(new StreamCatalog())
                .withSecurity(security)
                .withWatermark(java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence())
                .withNodeId("audit-sharing-node")
                .build();
    }

    @Test
    void theHttpSurfaceRecordsIntoTheSameSinkTheEngineDoes() {
        PravahaNode node = node("memory");

        AuditSink http = new PravahaServerApplication().pravahaAuditSink(node);

        assertThat(http)
                .as("the same object, not merely the same setting. A second InMemory sink would record "
                        + "every HTTP decision somewhere nobody can read, and look configured doing it")
                .isSameAs(node.auditSink());
        assertThat(((com.ash.messaging.pravaha.security.AuditTrail) http).delegate())
                .isInstanceOf(AuditSink.InMemory.class);
    }

    @Test
    void anHttpDecisionIsVisibleThroughTheNodesOwnSink() {
        PravahaNode node = node("memory");
        AuditSink http = new PravahaServerApplication().pravahaAuditSink(node);

        http.record(com.ash.messaging.pravaha.security.AuditEvent.of(
                com.ash.messaging.pravaha.security.Principal.ANONYMOUS,
                "read",
                "salaries",
                com.ash.messaging.pravaha.security.AccessDecision.deny("not permitted"),
                "SELECT * FROM salaries"));

        assertThat(((AuditSink.InMemory) ((com.ash.messaging.pravaha.security.AuditTrail) node.auditSink()).delegate())
                        .events())
                .as("what the HTTP surface records has to be readable where the rest of the node's audit is, "
                        + "or 'audit: memory' is a setting that records into nothing")
                .anyMatch(event -> event.target().equals("salaries") && !event.allowed());
    }

    @Test
    void whatTheHttpSurfaceRecordsCanBeReadBackFromTheNodesTrail() {
        PravahaNode node = node("memory");
        new PravahaServerApplication()
                .pravahaAuditSink(node)
                .record(com.ash.messaging.pravaha.security.AuditEvent.of(
                        com.ash.messaging.pravaha.security.Principal.ANONYMOUS,
                        "http.read",
                        "orders",
                        com.ash.messaging.pravaha.security.AccessDecision.allow(),
                        ""));

        assertThat(node.auditTrail())
                .as("GET /api/v1/audit reads this object, so it must be the one both transports record into")
                .hasValueSatisfying(
                        trail -> assertThat(trail.read(com.ash.messaging.pravaha.security.AuditTrail.Filter.ANY, 10, 0)
                                        .entries())
                                .extracting(entry -> entry.event().target())
                                .containsExactly("orders"));
    }

    @Test
    void aReadableWindowOfNothingIsRefusedAtStartupRatherThanServedEmpty() {
        SecurityProperties security = new SecurityProperties();
        security.setAudit("memory");
        security.setAuditRecent(0);
        PravahaNode node = PravahaNode.builder()
                .withCatalog(new StreamCatalog())
                .withSecurity(security)
                .withWatermark(java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(1))
                .withFlight(false, "127.0.0.1", 0)
                .withPersistence(persistence())
                .withNodeId("audit-sharing-node")
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(node::auditSink)
                .hasMessageContaining("PRV-7004")
                .hasMessageContaining("pravaha.security.audit-recent");
    }

    @Test
    void auditNoneStillMeansNone() {
        // The other half of honouring the key: a node that did not ask for auditing must not start
        // accumulating events in memory because this wiring changed.
        PravahaNode node = node("none");

        assertThat(new PravahaServerApplication().pravahaAuditSink(node)).isSameAs(AuditSink.NONE);
        assertThat(node.auditTrail()).as("and there is no trail to read back").isEmpty();
    }
}
