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
package com.ash.messaging.pravaha.registry.alert;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.catalog.Catalog;
import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.CatalogService;
import com.ash.messaging.pravaha.catalog.Grantee;
import com.ash.messaging.pravaha.catalog.ObjectKind;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may do what to an alert (ADR-057). Under the catalogue: an alert is an {@code ALERT} object owned
 * by its creator; creating one needs {@code WRITE} on each channel and {@code SELECT} on the view; seeing
 * it needs {@code SELECT}, pausing it {@code MODIFY}, changing or dropping it {@code MANAGE}. Under any
 * other policy the view's rights stand in, and a tenant never sees another's alerts.
 */
@Timeout(60)
class AlertPrivilegesTest {

    private static final Principal OPS = new Principal("ops", "acme", Set.of("admin"), Map.of());
    private static final Principal ANA = new Principal("ana", "acme", Set.of("analyst"), Map.of());
    private static final Principal SAM = new Principal("sam", "acme", Set.of("support"), Map.of());

    @SuppressWarnings("NullAway.Init") // a test sets it before reading it
    private AlertFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void underTheCatalogueEveryVerbAsksItsPrivilegeOnTheAlert() {
        Catalog catalog = Catalog.inMemory(Clock.systemUTC());
        catalog.infrastructure(() -> Map.<ObjectKind, Collection<String>>of(
                ObjectKind.STREAM, List.of("stock"), ObjectKind.NOTIFIER, List.of("buyers", "ops")));
        CatalogPolicy policy = new CatalogPolicy(new CatalogService(catalog, AuditSink.NONE), List.of());
        fixture = new AlertFixture(policy, OPS);
        fixture.open(null);
        AlertFixture f = fixture;
        f.sql(OPS, "GRANT CREATE ON NAMESPACE default TO ROLE analyst");
        f.sql(OPS, "GRANT SELECT ON VIEW low_stock TO ROLE analyst");

        // A NOTIFY needs WRITE on the channel.
        assertThatThrownBy(() -> f.sql(ANA, "CREATE ALERT low ON low_stock NOTIFY buyers"))
                .hasMessageContaining("PRV-7002")
                .hasMessageContaining("WRITE on node.notifiers.buyers");
        f.sql(OPS, "GRANT WRITE ON NOTIFIER buyers TO ROLE analyst");
        f.sql(ANA, "CREATE ALERT low ON low_stock NOTIFY buyers");
        assertThat(catalog.object("acme.default.low").orElseThrow().owner()).isEqualTo(Grantee.user("ana"));
        assertThat(catalog.object("acme.default.low").orElseThrow().kind()).isEqualTo(ObjectKind.ALERT);

        // Changing channels asks again, of each one named.
        assertThatThrownBy(() -> f.sql(ANA, "ALTER ALERT low NOTIFY buyers, ops"))
                .hasMessageContaining("WRITE on node.notifiers.ops");

        // sam may not see it: it does not exist for him.
        assertThat(f.service.list(SAM)).isEmpty();
        assertThatThrownBy(() -> f.service.detail(SAM, "low")).hasMessageContaining("PRV-8040");
        assertThatThrownBy(() -> f.sql(SAM, "PAUSE ALERT low")).hasMessageContaining("PRV-8040");

        // SELECT: he sees it and its state, and may not pause it.
        f.sql(ANA, "GRANT SELECT ON ALERT low TO USER sam");
        assertThat(f.service.list(SAM)).extracting(AlertStatus.Summary::name).containsExactly("low");
        assertThat(f.service.detail(SAM, "low").alert().view()).isEqualTo("low_stock");
        assertThatThrownBy(() -> f.sql(SAM, "PAUSE ALERT low"))
                .hasMessageContaining("PRV-7002")
                .hasMessageContaining("MODIFY");

        // MODIFY: pause, resume, snooze, acknowledge -- and not alter or drop.
        f.sql(ANA, "GRANT MODIFY ON ALERT low TO USER sam");
        f.sql(SAM, "PAUSE ALERT low");
        f.sql(SAM, "RESUME ALERT low");
        f.sql(SAM, "SNOOZE ALERT low FOR '10m'");
        f.sql(SAM, "ACK ALERT low");
        assertThatThrownBy(() -> f.sql(SAM, "ALTER ALERT low SET (severity = 'info')"))
                .hasMessageContaining("MANAGE");
        assertThatThrownBy(() -> f.sql(SAM, "DROP ALERT low")).hasMessageContaining("MANAGE");

        // MANAGE: alter and drop; the drop forgets the object and its grants.
        f.sql(ANA, "GRANT MANAGE ON ALERT low TO USER sam");
        f.sql(SAM, "ALTER ALERT low SET (severity = 'info')");
        f.sql(SAM, "DROP ALERT low");
        assertThat(catalog.object("acme.default.low")).isEmpty();
        assertThat(catalog.grantsOn("acme.default.low")).isEmpty();
    }

    @Test
    void anAlertAndAViewCannotShareACatalogueName() {
        Catalog catalog = Catalog.inMemory(Clock.systemUTC());
        catalog.infrastructure(() -> Map.<ObjectKind, Collection<String>>of(
                ObjectKind.STREAM, List.of("stock"), ObjectKind.NOTIFIER, List.of("buyers")));
        CatalogPolicy policy = new CatalogPolicy(new CatalogService(catalog, AuditSink.NONE), List.of());
        fixture = new AlertFixture(policy, OPS);
        fixture.open(null);
        fixture.sql(OPS, "CREATE ALERT watch ON low_stock NOTIFY buyers");
        assertThatThrownBy(() -> fixture.registry.register("watch", AlertFixture.LOW_STOCK, List.of(0, 1), OPS))
                .hasMessageContaining("an alert's name");
    }

    @Test
    void underAnotherPolicyTheViewsRightsStandInAndTenantsAreWalls() {
        SecurityPolicy policy = new SecurityPolicy() {
            @Override
            public AccessDecision mayRead(Principal principal, String view) {
                return principal.hasRole("support")
                        ? AccessDecision.deny("support reads nothing")
                        : AccessDecision.allow();
            }

            @Override
            public AccessDecision mayAdminister(Principal principal, String view) {
                return principal.hasRole("admin") ? AccessDecision.allow() : AccessDecision.deny("not an admin");
            }

            @Override
            public AccessDecision mayWriteTo(Principal principal, String sink) {
                return sink.equals("ops") && !principal.hasRole("admin")
                        ? AccessDecision.deny("ops is the admins' channel")
                        : AccessDecision.allow();
            }
        };
        fixture = new AlertFixture(policy, OPS);
        fixture.open(null);
        AlertFixture f = fixture;
        assertThatThrownBy(() -> f.sql(ANA, "CREATE ALERT low ON low_stock NOTIFY ops"))
                .hasMessageContaining("PRV-7002")
                .hasMessageContaining("ops is the admins' channel");
        f.sql(ANA, "CREATE ALERT low ON low_stock NOTIFY buyers");

        // The creator changes it; an administrator of the view does; anybody else who reads it may only look.
        Principal reader = new Principal("rae", "acme", Set.of("analyst"), Map.of());
        assertThat(f.service.list(reader)).hasSize(1);
        assertThatThrownBy(() -> f.sql(reader, "PAUSE ALERT low")).hasMessageContaining("PRV-7002");
        f.sql(ANA, "PAUSE ALERT low");
        f.sql(OPS, "RESUME ALERT low");

        // Support may not read the view, so the alert is not there for them.
        assertThatThrownBy(() -> f.service.detail(SAM, "low")).hasMessageContaining("PRV-8040");
        // Another tenant's analyst neither sees it nor can reach it by name.
        Principal other = new Principal("oli", "globex", Set.of("analyst"), Map.of());
        assertThat(f.service.list(other)).isEmpty();
        assertThatThrownBy(() -> f.sql(other, "DROP ALERT low")).hasMessageContaining("PRV-8040");
        // Nor alert on a view of another tenant: it is not there to be named.
        assertThatThrownBy(() -> f.sql(other, "CREATE ALERT mine ON low_stock NOTIFY buyers"))
                .hasMessageContaining("PRV-8042");
    }
}
