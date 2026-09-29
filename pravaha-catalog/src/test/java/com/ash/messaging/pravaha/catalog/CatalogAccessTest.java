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
package com.ash.messaging.pravaha.catalog;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;

/** Inheritance, ownership, the tenant wall, USE, and the decision cache (ADR-059 §2). */
class CatalogAccessTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);

    static final Principal ANA = new Principal("ana", "acme", Set.of("analyst"), Map.of());
    static final Principal BOB = new Principal("bob", "acme", Set.of(), Map.of());
    static final Principal OPS = new Principal("ops", "acme", Set.of("admin"), Map.of());
    static final Principal EVE = new Principal("eve", "globex", Set.of("analyst"), Map.of());

    private Catalog catalog;
    private CatalogAccess access;

    @BeforeEach
    void setUp() {
        catalog = Catalog.inMemory(CLOCK);
        access = new CatalogAccess(catalog);
        catalog.createNamespace("acme.sales", Grantee.user("ops"), "", false, "ops");
        catalog.registerView("acme.default.revenue", OPS);
        catalog.move("acme.default.revenue", "acme.sales", "ops");
    }

    @Test
    void unqualifiedRegistrationsLandInTheRegistrantsDefaultNamespaceOwnedByTheRegistrant() {
        CatalogObject orders = catalog.registerView("acme.default.orders_by_region", ANA);
        assertThat(orders.fullName()).isEqualTo("acme.default.orders_by_region");
        assertThat(orders.owner()).isEqualTo(Grantee.user("ana"));
        // The owner holds everything on it, and needs no grant to use their own default namespace.
        for (Privilege privilege : ObjectKind.VIEW.applicable()) {
            assertThat(access.check(ANA, privilege, orders.fullName()).allowed())
                    .as(privilege.name())
                    .isTrue();
        }
        assertThat(access.check(BOB, Privilege.SELECT, orders.fullName()).allowed())
                .isFalse();
    }

    @Test
    void aGrantOnANamespaceIsInheritedByEveryObjectInItNowAndLater() {
        catalog.grant("acme.sales", Privilege.USE, Grantee.role("analyst"), "ops");
        catalog.grant("acme.sales", Privilege.SELECT, Grantee.role("analyst"), "ops");
        assertThat(access.check(ANA, Privilege.SELECT, "acme.sales.revenue").allowed())
                .isTrue();
        assertThat(access.check(ANA, Privilege.SELECT, "acme.sales.revenue").via())
                .isEqualTo("grant SELECT on acme.sales to ROLE analyst");

        catalog.registerView("acme.default.margin", OPS);
        catalog.move("acme.default.margin", "acme.sales", "ops");
        assertThat(access.check(ANA, Privilege.SELECT, "acme.sales.margin").allowed())
                .isTrue();
        // SELECT is not SUBSCRIBE.
        assertThat(access.check(ANA, Privilege.SUBSCRIBE, "acme.sales.revenue").allowed())
                .isFalse();
    }

    @Test
    void useIsNecessaryButNotSufficient() {
        catalog.grant("acme.sales.revenue", Privilege.SELECT, Grantee.user("ana"), "ops");
        CatalogAccess.Verdict withoutUse = access.check(ANA, Privilege.SELECT, "acme.sales.revenue");
        assertThat(withoutUse.allowed()).isFalse();
        assertThat(withoutUse.via()).contains("not USE on the namespace acme.sales");

        catalog.grant("acme.sales", Privilege.USE, Grantee.user("ana"), "ops");
        assertThat(access.check(ANA, Privilege.SELECT, "acme.sales.revenue").allowed())
                .isTrue();
        assertThat(access.check(ANA, Privilege.SUBSCRIBE, "acme.sales.revenue").allowed())
                .isFalse();
    }

    @Test
    void theAdminRoleHoldsEveryRightAndAGrantOnTheRootReachesEveryTenant() {
        assertThat(access.check(OPS, Privilege.MANAGE, "globex.default.anything")
                        .allowed())
                .isTrue();
        catalog.grant(CatalogNames.ROOT, Privilege.SELECT, Grantee.role("analyst"), "ops");
        catalog.grant(CatalogNames.ROOT, Privilege.USE, Grantee.role("analyst"), "ops");
        assertThat(access.check(EVE, Privilege.SELECT, "acme.sales.revenue").allowed())
                .isTrue();
    }

    @Test
    void aTenantIsAWallThatARoleOfTheSameNameDoesNotCross() {
        catalog.grant("acme.sales", Privilege.USE, Grantee.role("analyst"), "ops");
        catalog.grant("acme.sales", Privilege.SELECT, Grantee.role("analyst"), "ops");
        assertThat(access.check(ANA, Privilege.SELECT, "acme.sales.revenue").allowed())
                .isTrue();
        // eve holds analyst too, in globex: the grant inside acme does not reach her.
        assertThat(access.check(EVE, Privilege.SELECT, "acme.sales.revenue").allowed())
                .isFalse();
    }

    @Test
    void theNodesOwnObjectsAreOutsideEveryTenant() {
        catalog.ensureInfrastructure(ObjectKind.STREAM, "orders");
        catalog.grant("node.streams.orders", Privilege.BUILD_ON, Grantee.role("analyst"), "ops");
        assertThat(access.check(ANA, Privilege.BUILD_ON, "node.streams.orders").allowed())
                .isTrue();
        assertThat(access.check(EVE, Privilege.BUILD_ON, "node.streams.orders").allowed())
                .isTrue();
        assertThat(access.check(BOB, Privilege.BUILD_ON, "node.streams.orders").allowed())
                .isFalse();
    }

    @Test
    void anOwnerOfANamespaceHoldsEveryRightOnWhatItHolds() {
        catalog.createNamespace("acme.risk", Grantee.role("risk_team"), "", false, "ops");
        catalog.registerView("acme.default.exposure", OPS);
        catalog.move("acme.default.exposure", "acme.risk", "ops");
        Principal riskAnalyst = new Principal("rita", "acme", Set.of("risk_team"), Map.of());
        assertThat(access.check(riskAnalyst, Privilege.MANAGE, "acme.risk.exposure")
                        .allowed())
                .isTrue();
        assertThat(access.check(riskAnalyst, Privilege.SELECT, "acme.risk.exposure")
                        .via())
                .isEqualTo("owner of acme.risk");
    }

    @Test
    void theImplicitRolesMeanEveryoneAndEveryoneIdentified() {
        catalog.grant(CatalogNames.ROOT, Privilege.SELECT, Grantee.role(Grantee.AUTHENTICATED), "ops");
        catalog.grant(CatalogNames.ROOT, Privilege.USE, Grantee.role(Grantee.AUTHENTICATED), "ops");
        assertThat(access.check(BOB, Privilege.SELECT, "acme.sales.revenue").allowed())
                .isTrue();
        assertThat(access.check(Principal.ANONYMOUS, Privilege.SELECT, "acme.sales.revenue")
                        .allowed())
                .isFalse();
        catalog.grant(CatalogNames.ROOT, Privilege.SELECT, Grantee.role(Grantee.PUBLIC), "ops");
        catalog.grant(CatalogNames.ROOT, Privilege.USE, Grantee.role(Grantee.PUBLIC), "ops");
        assertThat(access.check(Principal.ANONYMOUS, Privilege.SELECT, "acme.sales.revenue")
                        .allowed())
                .isTrue();
    }

    @Test
    void aRevocationIsSeenByTheVeryNextDecisionThoughDecisionsAreCached() {
        catalog.grant("acme.sales", Privilege.USE, Grantee.role("analyst"), "ops");
        catalog.grant("acme.sales.revenue", Privilege.SELECT, Grantee.role("analyst"), "ops");
        assertThat(access.check(ANA, Privilege.SELECT, "acme.sales.revenue").allowed())
                .isTrue();
        assertThat(access.check(ANA, Privilege.SELECT, "acme.sales.revenue").allowed())
                .isTrue();
        assertThat(access.cached()).isEqualTo(1);

        catalog.revoke("acme.sales.revenue", Privilege.SELECT, Grantee.role("analyst"));
        assertThat(access.check(ANA, Privilege.SELECT, "acme.sales.revenue").allowed())
                .isFalse();
        assertThat(access.cached()).isEqualTo(1);
    }

    @Test
    void aMoveTakesTheObjectsGrantsWithIt() {
        catalog.grant("acme.sales.revenue", Privilege.SELECT, Grantee.user("bob"), "ops");
        catalog.createNamespace("acme.finance", Grantee.user("ops"), "", false, "ops");
        catalog.move("acme.sales.revenue", "acme.finance", "ops");
        assertThat(catalog.grantsOn("acme.finance.revenue")).hasSize(1);
        assertThat(catalog.grantsOn("acme.sales.revenue")).isEmpty();
        assertThat(catalog.byEngineName(ObjectKind.VIEW, "acme.default.revenue")
                        .orElseThrow()
                        .fullName())
                .isEqualTo("acme.finance.revenue");
    }

    @Test
    void reasonsNameEveryGrantThatReaches() {
        catalog.grant("acme.sales", Privilege.USE, Grantee.role("analyst"), "ops");
        catalog.grant("acme.sales", Privilege.SELECT, Grantee.role("analyst"), "ops");
        catalog.grant("acme.sales.revenue", Privilege.SELECT, Grantee.user("ana"), "ops");
        assertThat(access.reasons(ANA, Privilege.SELECT, "acme.sales.revenue"))
                .containsExactly(
                        "grant SELECT on acme.sales.revenue to USER ana", "grant SELECT on acme.sales to ROLE analyst");
    }
}
