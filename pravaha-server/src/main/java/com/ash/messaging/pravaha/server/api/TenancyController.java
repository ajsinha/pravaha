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

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.TenantQuotas;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.server.PravahaNode;
import com.ash.messaging.pravaha.server.security.HttpAuthorizer;

/**
 * {@code GET /api/v1/tenants}: the quotas in force, each tenant's use against them and the
 * registrations refused for it (ADR-050).
 *
 * <p>A principal who may read the audit trail sees every tenant, because the same principal can
 * already read every refusal in the trail. Anybody else sees their own tenant and no other: which
 * tenants a node serves, and how busy each is, is itself something one tenant should not learn about
 * another. Each read is recorded, with how much it showed.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "Tenancy", description = "Admission quotas per tenant, use against them, and refusals")
public class TenancyController {

    /** One limit: {@code null} is no limit, which is not the same as zero. */
    public record Limits(Long maxQueries, Long maxStateKeys) {

        static Limits of(TenantQuotas.Limits limits) {
            return new Limits(boxed(limits.maxQueries()), boxed(limits.maxStateKeys()));
        }

        private static Long boxed(OptionalLong limit) {
            return limit.isPresent() ? limit.getAsLong() : null;
        }
    }

    /** One tenant. {@code refusals} are since this node started. */
    public record Tenant(
            String tenant,
            int queries,
            int computations,
            long stateKeys,
            Limits limits,
            long queryRefusals,
            long stateRefusals) {}

    /**
     * @param scope {@code all} for a principal shown every tenant, {@code own} for one shown their own
     * @param defaults the limits a tenant has when it has no entry of its own
     */
    public record Page(String scope, Limits defaults, List<Tenant> tenants) {}

    private final SecurityPolicy policy;
    private final AuditSink audit;
    private final HttpAuthorizer authorizer;
    private final Supplier<Optional<QueryRegistry>> registry;

    @Autowired
    public TenancyController(SecurityPolicy policy, AuditSink audit, HttpAuthorizer authorizer, PravahaNode node) {
        this(policy, audit, authorizer, node::registry);
    }

    /** For a test, or anything holding a registry directly. */
    public TenancyController(
            SecurityPolicy policy, AuditSink audit, HttpAuthorizer authorizer, QueryRegistry registry) {
        this(policy, audit, authorizer, () -> Optional.ofNullable(registry));
    }

    private TenancyController(
            SecurityPolicy policy,
            AuditSink audit,
            HttpAuthorizer authorizer,
            Supplier<Optional<QueryRegistry>> registry) {
        this.policy = policy;
        this.audit = audit;
        this.authorizer = authorizer;
        this.registry = registry;
    }

    @GetMapping
    @Operation(summary = "The quotas in force, each tenant's use against them, and the refusals fired")
    public Page read(HttpServletRequest http) {
        Principal principal = authorizer.principalOf(http);
        boolean all = policy.mayReadAudit(principal).allowed();
        audit.record(AuditEvent.of(
                principal,
                "http.tenants.read",
                all ? "*" : principal.tenant(),
                AccessDecision.allow(),
                all ? "every tenant" : "own tenant only"));
        Optional<QueryRegistry> open = registry.get();
        TenantQuotas quotas = open.map(QueryRegistry::tenantQuotas).orElse(TenantQuotas.unbounded());
        List<Tenant> tenants = open.map(QueryRegistry::tenantUsage).orElse(List.of()).stream()
                .filter(usage -> all || usage.tenant().equals(principal.tenant()))
                .map(usage -> new Tenant(
                        usage.tenant(),
                        usage.queries(),
                        usage.computations(),
                        usage.stateKeys(),
                        Limits.of(usage.limits()),
                        usage.queryRefusals(),
                        usage.stateRefusals()))
                .toList();
        return new Page(all ? "all" : "own", Limits.of(quotas.defaults()), tenants);
    }
}
