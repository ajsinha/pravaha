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

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.security.AuditSink;

import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.ANA;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.CLOCK;
import static com.ash.messaging.pravaha.catalog.CatalogAccessTest.OPS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the catalogue counts for the node's meters: decisions at the enforcement points by privilege
 * and outcome, the decision cache's hits and misses, and changes by kind -- and that a listing's
 * per-object checks are not counted as refusals.
 */
class CatalogStatisticsTest {

    private Catalog catalog;
    private CatalogPolicy policy;
    private CatalogStatistics counted;

    @BeforeEach
    void setUp() {
        catalog = Catalog.inMemory(CLOCK);
        catalog.infrastructure(() -> Map.<ObjectKind, Collection<String>>of(ObjectKind.STREAM, List.of("orders")));
        policy = new CatalogPolicy(new CatalogService(catalog, AuditSink.NONE), List.of());
        counted = policy.service().access().statistics();
    }

    @Test
    void allowsAndDenialsAreCountedByPrivilege() {
        policy.registered(OPS, "revenue");
        assertThat(policy.mayRead(ANA, "revenue").allowed()).isFalse();
        assertThat(counted.decisions(Privilege.SELECT, false)).isEqualTo(1);
        assertThat(counted.decisions(Privilege.SELECT, true)).isZero();

        catalog.grant(
                "acme.default.revenue",
                Privilege.SELECT,
                com.ash.messaging.pravaha.catalog.Grantee.role("analyst"),
                "ops");
        assertThat(policy.mayRead(ANA, "revenue").allowed()).isTrue();
        assertThat(counted.decisions(Privilege.SELECT, true)).isEqualTo(1);

        assertThat(policy.mayAdminister(ANA, "revenue").allowed()).isFalse();
        assertThat(policy.mayAdminister(OPS, "revenue").allowed()).isTrue();
        assertThat(counted.decisions(Privilege.MODIFY, false)).isEqualTo(1);
        assertThat(counted.decisions(Privilege.MODIFY, true)).isEqualTo(1);
    }

    @Test
    void aRepeatedDecisionIsACacheHitAndAChangeEmptiesTheCache() {
        policy.registered(OPS, "revenue");
        policy.mayRead(ANA, "revenue");
        long missesAfterFirst = counted.cacheMisses();
        policy.mayRead(ANA, "revenue");
        assertThat(counted.cacheHits()).isPositive();
        assertThat(counted.cacheMisses()).isEqualTo(missesAfterFirst);

        catalog.grant("acme.default.revenue", Privilege.SELECT, Grantee.role("analyst"), "ops");
        policy.mayRead(ANA, "revenue");
        assertThat(counted.cacheMisses()).isGreaterThan(missesAfterFirst);
    }

    @Test
    void changesAreCountedByKindAndAReplayCountsNothing(@TempDir Path dir) {
        Catalog journalled = Catalog.open(dir.resolve("catalog.journal"), CLOCK);
        journalled.registerView("acme.default.revenue", OPS);
        journalled.grant("acme.default.revenue", Privilege.SELECT, Grantee.role("analyst"), "ops");
        journalled.grant("acme.default.revenue", Privilege.SELECT, Grantee.role("analyst"), "ops"); // no change
        journalled.revoke("acme.default.revenue", Privilege.SELECT, Grantee.role("analyst"));
        journalled.setOwner("acme.default.revenue", Grantee.user("ana"), "ops");
        assertThat(journalled.changes("grant")).isEqualTo(1);
        assertThat(journalled.changes("revoke")).isEqualTo(1);
        assertThat(journalled.changes("owner")).isEqualTo(1);
        assertThat(journalled.changes("object")).isPositive();
        assertThat(CatalogStatistics.CHANGE_KINDS).contains("grant", "revoke", "owner", "object", "policy_create");

        Catalog replayed = Catalog.open(dir.resolve("catalog.journal"), CLOCK);
        assertThat(replayed.changes("grant")).isZero();
        assertThat(replayed.object("acme.default.revenue")).isPresent();
    }
}
