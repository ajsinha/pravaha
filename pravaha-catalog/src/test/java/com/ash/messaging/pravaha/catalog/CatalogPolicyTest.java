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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;

import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.ANA;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.BOB;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.CLOCK;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.OPS;
import static org.assertj.core.api.Assertions.assertThat;

/** Every question the engine asks a policy, answered from grants (ADR-059 §8). */
class CatalogPolicyTest {

    private Catalog catalog;
    private CatalogPolicy policy;

    @BeforeEach
    void setUp() {
        catalog = Catalog.inMemory(CLOCK);
        catalog.infrastructure(() -> Map.<ObjectKind, Collection<String>>of(
                ObjectKind.STREAM, List.of("orders"), ObjectKind.SINK, List.of("warehouse")));
        policy = new CatalogPolicy(new CatalogService(catalog, AuditSink.NONE), List.of("auditor"));
    }

    @Test
    void readAndSubscribeAreDistinct() {
        policy.registered(OPS, "revenue");
        catalog.grant("acme.default.revenue", Privilege.SELECT, Grantee.role("analyst"), "ops");
        assertThat(policy.mayRead(ANA, "revenue").allowed()).isTrue();
        assertThat(policy.maySubscribe(ANA, "revenue").allowed()).isFalse();
        assertThat(policy.maySubscribe(ANA, "revenue").reason()).contains("holds no SUBSCRIBE on acme.default.revenue");
        catalog.grant("acme.default.revenue", Privilege.SUBSCRIBE, Grantee.role("analyst"), "ops");
        assertThat(policy.maySubscribe(ANA, "revenue").allowed()).isTrue();
    }

    @Test
    void registeringMakesTheCreatorTheOwnerAndDroppingForgetsItsGrants() {
        policy.registered(ANA, "mine");
        assertThat(policy.mayAdminister(ANA, "mine").allowed()).isTrue();
        assertThat(policy.mayAdminister(BOB, "mine").allowed()).isFalse();
        catalog.grant("acme.default.mine", Privilege.SELECT, Grantee.user("bob"), "ana");
        policy.dropped("mine");
        policy.registered(BOB, "mine");
        assertThat(catalog.grantsOn("acme.default.mine")).isEmpty();
        assertThat(policy.mayAdminister(ANA, "mine").allowed()).isFalse();
        // Recovery asks again for a name already recorded, and the owner is not reset.
        policy.registered(OPS, "mine");
        assertThat(catalog.object("acme.default.mine").orElseThrow().owner()).isEqualTo(Grantee.user("bob"));
    }

    @Test
    void buildingOnAChainNeedsBuildOnEveryInputTheQueryNamesAndNotOnTheirSources() {
        policy.registered(OPS, "cleaned");
        assertThat(policy.mayBuildOn(ANA, "cleaned").allowed()).isFalse();
        catalog.grant("acme.default.cleaned", Privilege.BUILD_ON, Grantee.role("analyst"), "ops");
        assertThat(policy.mayBuildOn(ANA, "cleaned").allowed()).isTrue();
        // The stream behind the view is not asked again; a query over the stream itself is.
        assertThat(policy.mayBuildThrough(ANA, "orders").allowed()).isTrue();
        assertThat(policy.mayBuildOn(ANA, "orders").allowed()).isFalse();
        catalog.grant("node.streams", Privilege.BUILD_ON, Grantee.role("analyst"), "ops");
        assertThat(policy.mayBuildOn(ANA, "orders").allowed()).isTrue();
        // Reading a view is not building on it.
        assertThat(policy.mayRead(ANA, "cleaned").allowed()).isFalse();
    }

    @Test
    void registeringNeedsCreateOnTheDefaultNamespace() {
        assertThat(policy.mayRegisterQuery(ANA).allowed()).isFalse();
        catalog.grant("acme.default", Privilege.CREATE, Grantee.role("analyst"), "ops");
        assertThat(policy.mayRegisterQuery(ANA, "anything").allowed()).isTrue();
        assertThat(policy.mayRegisterQuery(OPS).allowed()).isTrue();
    }

    @Test
    void writingToASinkIsWriteOnTheSink() {
        assertThat(policy.mayWriteTo(ANA, "warehouse").allowed()).isFalse();
        catalog.grant("node.sinks.warehouse", Privilege.WRITE, Grantee.user("ana"), "ops");
        assertThat(policy.mayWriteTo(ANA, "warehouse").allowed()).isTrue();
    }

    @Test
    void theAuditTrailIsForAdminsAuditReadersAndCatalogueManagers() {
        assertThat(policy.mayReadAudit(OPS).allowed()).isTrue();
        assertThat(policy.mayReadAudit(ANA).allowed()).isFalse();
        assertThat(policy.mayReadAudit(new Principal("aud", "acme", Set.of("auditor"), Map.of()))
                        .allowed())
                .isTrue();
        catalog.grant(CatalogNames.ROOT, Privilege.MANAGE, Grantee.user("ana"), "ops");
        assertThat(policy.mayReadAudit(ANA).allowed()).isTrue();
        assertThat(policy.mayReadAudit(Principal.ANONYMOUS).allowed()).isFalse();
    }

    @Test
    void importingPermissiveMeansWhatItMeantForEveryone() {
        catalog.importPolicy("permissive", CatalogPolicy.importedGrants("permissive", "import", CLOCK.instant()));
        policy.registered(Principal.ANONYMOUS, "v");
        Principal stranger = new Principal("x", "globex", Set.of(), Map.of());
        for (Principal who : List.of(Principal.ANONYMOUS, stranger, ANA)) {
            assertThat(policy.mayRead(who, "v").allowed()).isTrue();
            assertThat(policy.maySubscribe(who, "v").allowed()).isTrue();
            assertThat(policy.mayRegisterQuery(who).allowed()).isTrue();
            assertThat(policy.mayAdminister(who, "v").allowed()).isTrue();
            assertThat(policy.mayWriteTo(who, "warehouse").allowed()).isTrue();
            assertThat(policy.mayReadAudit(who).allowed()).isTrue();
        }
        assertThat(catalog.grantsToEveryone()).isTrue();
    }

    @Test
    void importingAuthenticatedServesIdentifiedCallersOnly() {
        catalog.importPolicy("authenticated", CatalogPolicy.importedGrants("authenticated", "import", CLOCK.instant()));
        policy.registered(ANA, "v");
        assertThat(policy.mayRead(BOB, "v").allowed()).isTrue();
        assertThat(policy.mayAdminister(BOB, "v").allowed()).isTrue();
        assertThat(policy.mayRead(Principal.ANONYMOUS, "v").allowed()).isFalse();
        assertThat(policy.mayRegisterQuery(Principal.ANONYMOUS).allowed()).isFalse();
        assertThat(policy.mayReadAudit(BOB).allowed()).isFalse();
        assertThat(catalog.grantsToEveryone()).isFalse();
        // Once: a second import changes nothing.
        catalog.importPolicy("permissive", CatalogPolicy.importedGrants("permissive", "import", CLOCK.instant()));
        assertThat(catalog.importedPolicy()).contains("authenticated");
        assertThat(catalog.grantsToEveryone()).isFalse();
    }
}
