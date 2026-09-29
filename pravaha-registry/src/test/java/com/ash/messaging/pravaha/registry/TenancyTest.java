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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.ViewCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-050: what a tenant scopes, the two admission quotas, and sharing within a tenant only.
 *
 * <p>Every refusal here is asserted three ways -- by code, by nothing having been registered, and
 * by the count and audit record an operator reads -- because a quota that refuses without saying so
 * anywhere an operator looks is the silent degradation the ADR rules out.
 */
class TenancyTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .build();

    private static final String BY_USER = "SELECT 'all' AS bucket, SUM(amount) AS total FROM txn";
    private static final String ROWS = "SELECT user_id, amount FROM txn";

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final Principal DEV = new Principal("dev", "acme", Set.of("analyst"), Map.of());
    private static final Principal OMAR = new Principal("omar", "globex", Set.of("analyst"), Map.of());

    private final List<QueryRegistry> registries = new CopyOnWriteArrayList<>();
    private final List<AuditEvent> audited = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        registries.forEach(QueryRegistry::close);
    }

    private QueryRegistry registry(TenantQuotas quotas) {
        QueryRegistry registry = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, audited::add, TXN)
                .limitingTenants(quotas);
        registries.add(registry);
        return registry;
    }

    /** The engine name of acme's {@code name} (ADR-060): what the registry, audit and quotas key it by. */
    private static String acme(String name) {
        return "acme.default." + name;
    }

    private static TenantQuotas maxQueries(long max) {
        return new TenantQuotas(TenantQuotas.Limits.of(max, null), Map.of());
    }

    // ------------------------------------------------------------------ isolation

    @Test
    void identicalSqlInTwoTenantsIsTwoComputations() {
        QueryRegistry registry = registry(TenantQuotas.unbounded());
        RegisteredQuery acme = registry.register("acme_totals", BY_USER, List.of(0), DANA);
        RegisteredQuery globex = registry.register("globex_totals", BY_USER, List.of(0), OMAR);

        assertThat(globex)
                .as("shared across tenants, a pause of one tenant's name stops the other's, and the "
                        + "state is charged to whichever tenant happened to register first")
                .isNotSameAs(acme);
        assertThat(globex.fingerprint()).isNotEqualTo(acme.fingerprint());
        assertThat(registry.size()).isEqualTo(2);

        registry.pause(acme("acme_totals"));
        assertThat(globex.state()).isNotEqualTo(QueryState.PAUSED);
    }

    @Test
    void identicalSqlWithinATenantStillSharesOneComputation() {
        QueryRegistry registry = registry(TenantQuotas.unbounded());
        RegisteredQuery first = registry.register("dana_totals", BY_USER, List.of(0), DANA);
        RegisteredQuery second = registry.register("dev_totals", BY_USER, List.of(0), DEV);

        assertThat(second).isSameAs(first);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void aTenantNameThatSmugglesANewlineDoesNotHashAsARowFilter() {
        // The tenant is length-prefixed in the canonical form, or "t\nsecurity:p" with no filter would
        // be the same computation as tenant "t" holding row filter p.
        QueryFingerprint smuggled = QueryFingerprint.of(
                com.ash.messaging.pravaha.sql.plan.PreparedContinuousQuery.of(
                                ROWS,
                                com.ash.messaging.pravaha.sql.plan.BoundParameters.none(),
                                List.of(TXN),
                                List.of())
                        .plan(),
                List.of(),
                List.of(0),
                null,
                "t\nsecurity:p");
        QueryFingerprint filtered = QueryFingerprint.of(
                com.ash.messaging.pravaha.sql.plan.PreparedContinuousQuery.of(
                                ROWS,
                                com.ash.messaging.pravaha.sql.plan.BoundParameters.none(),
                                List.of(TXN),
                                List.of())
                        .plan(),
                List.of("p"),
                List.of(0),
                null,
                "t");
        assertThat(smuggled).isNotEqualTo(filtered);
    }

    // ------------------------------------------------------------------ the query quota

    @Test
    void aTenantAtItsQueryQuotaIsRefusedByNameAndAnotherTenantIsNot() {
        QueryRegistry registry = registry(maxQueries(2));
        registry.register("a", BY_USER, List.of(0), DANA);
        registry.register("b", ROWS, List.of(0), DANA);

        assertThatThrownBy(() -> registry.register("c", ROWS, List.of(0, 1), DEV))
                .isInstanceOf(PravahaException.class)
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.TENANT_QUERY_QUOTA))
                .hasMessageContaining("tenant 'acme' already holds 2 of its 2 queries");
        assertThat(registry.names()).containsExactly(acme("a"), acme("b"));

        // The quota is the tenant's, not the node's.
        registry.register("g", BY_USER, List.of(0), OMAR);
        assertThat(registry.names()).containsExactly(acme("a"), acme("b"), "globex.default.g");

        assertThat(registry.tenantQuotas().refusals("acme", TenantQuotas.Quota.QUERIES))
                .isEqualTo(1);
        assertThat(registry.tenantQuotas().refusals("globex", TenantQuotas.Quota.QUERIES))
                .isZero();
        assertThat(audited)
                .filteredOn(event -> event.action().equals("register:quota") && !event.allowed())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.principal().id()).isEqualTo("dev");
                    assertThat(event.target()).isEqualTo("acme");
                });
    }

    @Test
    void aNameAttachedToAComputationTheTenantRunsStillCountsAsAQuery() {
        QueryRegistry registry = registry(maxQueries(1));
        registry.register("a", BY_USER, List.of(0), DANA);

        assertThatThrownBy(() -> registry.register("a_again", BY_USER, List.of(0), DEV))
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.TENANT_QUERY_QUOTA));
        assertThat(registry.names()).containsExactly(acme("a"));
    }

    @Test
    void droppingANameGivesItsQueryBack() {
        QueryRegistry registry = registry(maxQueries(1));
        registry.register("a", BY_USER, List.of(0), DANA);
        registry.drop(acme("a"));

        registry.register("b", ROWS, List.of(0), DANA);
        assertThat(registry.tenantOf(acme("b"))).contains("acme");
        assertThat(registry.tenantOf(acme("a"))).isEmpty();
    }

    @Test
    void aTenantsOwnEntryOverridesTheDefaultAndLeavesTheRest() {
        TenantQuotas quotas =
                new TenantQuotas(TenantQuotas.Limits.of(10L, 500L), Map.of("globex", TenantQuotas.Limits.of(1L, null)));

        assertThat(quotas.limitsFor("globex").maxQueries()).hasValue(1);
        assertThat(quotas.limitsFor("globex").maxStateKeys())
                .as("a limit the tenant's entry leaves unset is the default's")
                .hasValue(500);
        assertThat(quotas.limitsFor("acme").maxQueries()).hasValue(10);

        QueryRegistry registry = registry(quotas);
        registry.register("g", BY_USER, List.of(0), OMAR);
        assertThatThrownBy(() -> registry.register("g2", ROWS, List.of(0), OMAR))
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.TENANT_QUERY_QUOTA));
        registry.register("a2", ROWS, List.of(0), DANA);
    }

    @Test
    void aNegativeQuotaOrABlankTenantIsRefusedAtConfiguration() {
        assertThatThrownBy(() -> TenantQuotas.Limits.of(-1L, null))
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.TENANCY_MISCONFIGURED))
                .hasMessageContaining("max-queries is -1");
        assertThatThrownBy(() -> TenantQuotas.Limits.of(null, -5L))
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.TENANCY_MISCONFIGURED));
        assertThatThrownBy(() ->
                        new TenantQuotas(TenantQuotas.Limits.UNBOUNDED, Map.of(" ", TenantQuotas.Limits.UNBOUNDED)))
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.TENANCY_MISCONFIGURED));
    }

    @Test
    void aZeroQuotaAllowsNone() {
        QueryRegistry registry = registry(maxQueries(0));
        assertThatThrownBy(() -> registry.register("a", ROWS, List.of(0), DANA))
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.TENANT_QUERY_QUOTA));
    }

    // ------------------------------------------------------------------ the state quota

    @Test
    void aTenantHoldingItsStateIsRefusedANewComputationButMayNameOneItRuns() {
        ReplayableLog log = new ReplayableLog(TXN);
        for (int i = 0; i < 3; i++) {
            log.append("u" + i, (long) (i + 1));
        }
        QueryRegistry registry = registry(new TenantQuotas(TenantQuotas.Limits.of(null, 3L), Map.of()))
                .feedingFrom(log);
        registry.register("txn_rows", ROWS, List.of(0, 1), DANA);
        await(() -> registry.find(acme("txn_rows")).orElseThrow().view().size() == 3);

        assertThatThrownBy(() -> registry.register("totals", BY_USER, List.of(0), DANA))
                .satisfies(e ->
                        assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.TENANT_STATE_QUOTA))
                .hasMessageContaining("already holds 3 view keys against its quota of 3");
        assertThat(registry.names()).containsExactly(acme("txn_rows"));
        assertThat(registry.tenantQuotas().refusals("acme", TenantQuotas.Quota.STATE))
                .isEqualTo(1);

        // The same question under a second name adds a name and no state.
        registry.register("txn_rows_too", ROWS, List.of(0, 1), DEV);
        // And another tenant's state is its own.
        registry.register("globex_rows", ROWS, List.of(0, 1), OMAR);

        TenantQuotas.Usage acme = usage(registry, "acme");
        assertThat(acme.queries()).isEqualTo(2);
        assertThat(acme.computations()).isEqualTo(1);
        assertThat(acme.stateKeys()).isEqualTo(3);
        assertThat(acme.limits().maxStateKeys()).hasValue(3);
        assertThat(acme.stateRefusals()).isEqualTo(1);
        assertThat(usage(registry, "globex").queries()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ replacement and recovery

    @Test
    void aReplacementFromAnotherTenantIsRefusedByName() {
        ReplayableLog log = new ReplayableLog(TXN);
        log.append("u1", 5L);
        QueryRegistry registry = registry(TenantQuotas.unbounded()).feedingFrom(log);
        registry.register("totals", BY_USER, List.of(0), DANA);

        // ADR-060: to omar, "totals" is his own tenant's name, which nothing holds.
        assertThatThrownBy(() -> registry.replacements()
                        .replace(
                                QueryRegistry.engineName(OMAR, "totals"),
                                ROWS,
                                List.of(0, 1),
                                OMAR,
                                ReplacementOptions.defaults()))
                .satisfies(e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.NO_SUCH_QUERY));
        // Nor may he reach dana's by its catalogue name: only an admin may, whether or not it exists.
        assertThatThrownBy(() -> QueryRegistry.engineName(OMAR, acme("totals")))
                .satisfies(e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(SecurityErrors.FORBIDDEN));
        // An admin of another tenant may address and administer it, and is still refused a replacement
        // across tenants.
        Principal omarAdmin = new Principal(OMAR.id(), OMAR.tenant(), Set.of("admin"), Map.of());
        assertThatThrownBy(() -> registry.replacements()
                        .replace(
                                QueryRegistry.engineName(omarAdmin, acme("totals")),
                                ROWS,
                                List.of(0, 1),
                                omarAdmin,
                                ReplacementOptions.defaults()))
                .satisfies(
                        e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.TENANT_MISMATCH))
                .hasMessageContaining("registered outside tenant 'globex'");
        assertThat(registry.replacements().of(acme("totals"))).isEmpty();
        assertThat(audited).anySatisfy(event -> {
            assertThat(event.action()).isEqualTo("replace:tenant");
            assertThat(event.allowed()).isFalse();
            assertThat(event.reason()).contains("held by tenant 'acme'");
        });
    }

    // ------------------------------------------------------------------ names (ADR-060)

    @Test
    void aNameAnotherTenantHoldsIsFreeAndARefusalInTheCallersOwnTenantSaysNothingOfOthers() {
        QueryRegistry registry = registry(TenantQuotas.unbounded());
        RegisteredQuery danas = registry.register("totals", BY_USER, List.of(0), DANA);

        // Another tenant's name is, to the caller, a name nothing holds: registering it succeeds.
        RegisteredQuery omars = registry.register("totals", BY_USER, List.of(0), OMAR);
        assertThat(omars).isNotSameAs(danas);
        assertThat(registry.names()).containsExactly(acme("totals"), "globex.default.totals");

        // Within a tenant a name is still unique, and the refusal is the one it always was.
        assertThatThrownBy(() -> registry.register("totals", ROWS, List.of(0, 1), DEV))
                .satisfies(e -> assertThat(((PravahaException) e).errorCode()).isEqualTo(RegistryErrors.NAME_IN_USE))
                .hasMessageStartingWith("PRV-8001  'totals' is already registered.")
                .satisfies(
                        e -> assertThat(e.getMessage()).doesNotContain("acme").doesNotContain("globex"));
        assertThat(audited)
                .as("no registration is a probe of another tenant's names any more")
                .noneMatch(event -> event.action().equals("register:name"));
    }

    @Test
    void aJournalReplayedUnderALoweredQuotaRefusesTheExcessByName(@TempDir Path directory) {
        Path journal = directory.resolve("registry.journal");
        try (QueryRegistry before = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, audited::add, TXN)
                .journalTo(new RegistryJournal(journal))) {
            before.register("first", BY_USER, List.of(0), DANA);
            before.register("later", ROWS, List.of(0), DANA);
        }
        try (QueryRegistry after = new QueryRegistry(new ViewCatalog(), SecurityPolicy.PERMISSIVE, audited::add, TXN)
                .limitingTenants(maxQueries(1))
                .journalTo(new RegistryJournal(journal))) {
            QueryRegistry.Recovery recovery = after.recover(id -> Optional.of(DANA));

            assertThat(recovery.recovered()).containsExactly(acme("first"));
            assertThat(recovery.refused()).singleElement().satisfies(refusal -> {
                assertThat(refusal.query()).isEqualTo(acme("later"));
                assertThat(refusal.code()).contains(RegistryErrors.TENANT_QUERY_QUOTA);
            });
        }
    }

    // ------------------------------------------------------------------ what an operator reads

    @Test
    void everyConfiguredTenantIsListedEvenBeforeItRegistersAnything() {
        QueryRegistry registry = registry(
                new TenantQuotas(TenantQuotas.Limits.UNBOUNDED, Map.of("initech", TenantQuotas.Limits.of(4L, 100L))));
        registry.register("a", ROWS, List.of(0), DANA);

        assertThat(registry.tenantUsage())
                .extracting(TenantQuotas.Usage::tenant)
                .containsExactly("acme", "initech");
        TenantQuotas.Usage initech = usage(registry, "initech");
        assertThat(initech.queries()).isZero();
        assertThat(initech.limits().maxQueries()).hasValue(4);
    }

    private static TenantQuotas.Usage usage(QueryRegistry registry, String tenant) {
        return registry.tenantUsage().stream()
                .filter(each -> each.tenant().equals(tenant))
                .findFirst()
                .orElseThrow();
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
