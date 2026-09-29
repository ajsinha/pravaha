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
package com.ash.messaging.pravaha.registry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.Administration;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.sql.ContinuousStatements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A view is administered by its owner, a principal the policy grants it to, or an admin -- not by
 * everyone who may read it (LIFE-040, SX-6) -- and {@code legacy-read} restores the old rule.
 */
class QueryOwnershipTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String SQL = "SELECT user_id, amount FROM txn WHERE amount > 0";

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final Principal ERIN = new Principal("erin", "acme", Set.of("analyst"), Map.of());
    private static final Principal OPS = new Principal("ops", "acme", Set.of("operator"), Map.of());
    private static final Principal ROOT = new Principal("root", "acme", Set.of("admin"), Map.of());

    /** A policy that grants administering to the operator role, and says nothing else about it. */
    private static final SecurityPolicy OPERATORS_ADMINISTER = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return AccessDecision.allow();
        }

        @Override
        public AccessDecision mayAdminister(Principal principal, String view) {
            return principal.hasRole("operator")
                    ? AccessDecision.allow()
                    : AccessDecision.deny("only operators administer views they do not own");
        }
    };

    private static QueryRegistry registry(SecurityPolicy policy) {
        return new QueryRegistry(new ViewCatalog(), policy, AuditSink.NONE, TXN);
    }

    private static void drop(QueryRegistry registry, Principal principal, String name) {
        new ContinuousQueryStatements(registry, registry.policy(), AuditSink.NONE)
                .execute(
                        ContinuousStatements.recognize("DROP CONTINUOUS QUERY " + name)
                                .orElseThrow(),
                        principal);
    }

    @Test
    void theOwnerMayDropWhatTheyRegistered() {
        try (QueryRegistry registry = registry(SecurityPolicy.PERMISSIVE)) {
            registry.register("totals", SQL, List.of(0), DANA);
            assertThat(registry.owners().ownerOf("totals")).contains(DANA);

            drop(registry, DANA, "totals");

            assertThat(registry.find("totals")).isEmpty();
            assertThat(registry.owners().ownerOf("totals")).isEmpty();
        }
    }

    @Test
    void anUnfilteredReaderWhoDoesNotOwnItIsRefusedAsEveryAuthorizationRefusalIs() {
        try (QueryRegistry registry = registry(SecurityPolicy.PERMISSIVE)) {
            registry.register("totals", SQL, List.of(0), DANA);
            assertThat(SecurityPolicy.PERMISSIVE.mayRead(ERIN, "totals").rowFilter())
                    .as("erin may read every row of it")
                    .isEmpty();

            assertThatThrownBy(() -> drop(registry, ERIN, "totals"))
                    .isInstanceOf(PravahaException.class)
                    .satisfies(
                            e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(SecurityErrors.FORBIDDEN))
                    .hasMessageContaining("its owner, a grant to administer it, or the admin role");
            assertThatThrownBy(() -> ContinuousQueryStatements.requireAdministrable(
                            registry, AuditSink.NONE, ERIN, "totals", "pause"))
                    .isInstanceOf(PravahaException.class);
            assertThat(registry.find("totals")).isPresent();
        }
    }

    @Test
    void theSameIdInAnotherTenantIsNotTheOwner() {
        try (QueryRegistry registry = registry(SecurityPolicy.PERMISSIVE)) {
            registry.register("totals", SQL, List.of(0), DANA);
            Principal otherDana = new Principal("dana", "globex", Set.of("analyst"), Map.of());

            assertThat(registry.owners().mayAdminister(otherDana, "totals").allowed())
                    .isFalse();
        }
    }

    @Test
    void aPrincipalThePolicyGrantsItToMayDropSomebodyElsesView() {
        try (QueryRegistry registry = registry(OPERATORS_ADMINISTER)) {
            registry.register("totals", SQL, List.of(0), DANA);

            assertThat(registry.owners().mayAdminister(ERIN, "totals").reason())
                    .contains("only operators administer views they do not own");
            drop(registry, OPS, "totals");

            assertThat(registry.find("totals")).isEmpty();
        }
    }

    @Test
    void anAdminMayDropAnyView() {
        try (QueryRegistry registry = registry(SecurityPolicy.PERMISSIVE)) {
            registry.register("totals", SQL, List.of(0), DANA);

            drop(registry, ROOT, "totals");

            assertThat(registry.find("totals")).isEmpty();
        }
    }

    @Test
    void aNameNothingHoldsIsStillTheRegistrysNoSuchQuery() {
        try (QueryRegistry registry = registry(SecurityPolicy.PERMISSIVE)) {
            assertThatThrownBy(() -> drop(registry, ERIN, "nothing_here"))
                    .isInstanceOf(PravahaException.class)
                    .satisfies(e ->
                            assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.NO_SUCH_QUERY));
        }
    }

    @Test
    void legacyReadRestoresAdministeringByUnrestrictedRead() {
        try (QueryRegistry registry = registry(SecurityPolicy.PERMISSIVE)) {
            registry.owners().administering(Administration.Rule.parse("legacy-read"));
            registry.register("totals", SQL, List.of(0), DANA);

            drop(registry, ERIN, "totals");

            assertThat(registry.find("totals")).isEmpty();
        }
    }

    @Test
    void anUnknownRuleIsRefusedAsAMisconfiguration() {
        assertThatThrownBy(() -> Administration.Rule.parse("owner"))
                .isInstanceOf(PravahaException.class)
                .satisfies(e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(SecurityErrors.MISCONFIGURED));
        assertThat(Administration.Rule.parse(" Ownership ")).isEqualTo(Administration.Rule.OWNERSHIP);
        assertThat(Administration.Rule.parse(null)).isEqualTo(Administration.Rule.OWNERSHIP);
    }

    @Test
    void theOwnerSurvivesARestart(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");
        try (QueryRegistry before = registry(SecurityPolicy.PERMISSIVE).journalTo(new RegistryJournal(journal))) {
            before.register("totals", SQL, List.of(0), DANA);
        }
        Map<String, Principal> known = Map.of(DANA.id(), DANA, ERIN.id(), ERIN);
        try (QueryRegistry after = registry(SecurityPolicy.PERMISSIVE).journalTo(new RegistryJournal(journal))) {
            QueryRegistry.Recovery recovery = after.recover(id -> Optional.ofNullable(known.get(id)));

            assertThat(recovery.complete()).isTrue();
            assertThat(after.owners().ownerOf("totals")).contains(DANA);
            assertThatThrownBy(() -> drop(after, ERIN, "totals")).isInstanceOf(PravahaException.class);
            drop(after, DANA, "totals");
            assertThat(after.find("totals")).isEmpty();
        }
    }

    @Test
    void eachNameOfASharedComputationKeepsItsOwnOwner() {
        try (QueryRegistry registry = registry(SecurityPolicy.PERMISSIVE)) {
            registry.register("danas", SQL, List.of(0), DANA);
            registry.register("erins", SQL, List.of(0), ERIN);
            assertThat(registry.require("danas")).isSameAs(registry.require("erins"));

            assertThat(registry.owners().mayAdminister(ERIN, "danas").allowed()).isFalse();
            drop(registry, ERIN, "erins");

            assertThat(registry.find("danas")).isPresent();
            assertThat(registry.owners().ownerOf("danas")).contains(DANA);
        }
    }
}
