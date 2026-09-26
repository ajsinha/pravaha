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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;

/**
 * What each tenant may hold on this node, what it holds, and the registrations refused for it
 * (ADR-050).
 *
 * <p>A tenant is the {@link Principal#tenant()} a registration was made under. It owns the names
 * it registered, the computations behind them and the keys their views hold. Two limits are
 * enforced, both at registration and never afterwards: how many names a tenant may hold, and how
 * many view keys its computations may already hold when it asks for another one. A registration
 * over either is refused by name ({@code PRV-8020}, {@code PRV-8021}), audited, and counted.
 *
 * <p>Not scoped by a tenant, and said here so nobody reads it into the class: the name space (a
 * view name is unique on the node, whichever tenant holds it), the lanes (every tenant's queries
 * share the node's lane threads and shared lanes), the sources and sinks (the policy decides those)
 * and the reads (the policy decides those too). What a tenant changes about sharing is in {@link
 * QueryFingerprint}: identical SQL shares one computation within a tenant and not across tenants.
 *
 * <p>The ledger of which tenant holds which name is kept under the registry's monitor, which is
 * where every change to it is made; the refusal counters are read by metrics from other threads
 * and are concurrent for that reason.
 */
public final class TenantQuotas {

    /** Which of the two limits refused a registration; a metric's {@code quota} tag. */
    public enum Quota {
        QUERIES("queries"),
        STATE("state");

        private final String tag;

        Quota(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /**
     * One tenant's limits. An empty limit is no limit: a node configured with none behaves as it did
     * before tenancy existed. Zero is a limit, and a tenant limited to zero queries registers none.
     */
    public record Limits(OptionalLong maxQueries, OptionalLong maxStateKeys) {

        public static final Limits UNBOUNDED = new Limits(OptionalLong.empty(), OptionalLong.empty());

        public Limits {
            maxQueries = maxQueries == null ? OptionalLong.empty() : maxQueries;
            maxStateKeys = maxStateKeys == null ? OptionalLong.empty() : maxStateKeys;
            requireNonNegative("max-queries", maxQueries);
            requireNonNegative("max-state-keys", maxStateKeys);
        }

        /** From nullable values, which is how a configuration binding hands them over. */
        public static Limits of(Long maxQueries, Long maxStateKeys) {
            return new Limits(
                    maxQueries == null ? OptionalLong.empty() : OptionalLong.of(maxQueries),
                    maxStateKeys == null ? OptionalLong.empty() : OptionalLong.of(maxStateKeys));
        }

        /** These limits, with any that {@code override} sets taking its value. */
        Limits overriddenBy(Limits override) {
            return new Limits(
                    override.maxQueries.isPresent() ? override.maxQueries : maxQueries,
                    override.maxStateKeys.isPresent() ? override.maxStateKeys : maxStateKeys);
        }

        private static void requireNonNegative(String what, OptionalLong limit) {
            if (limit.isPresent() && limit.getAsLong() < 0) {
                throw new PravahaException(
                        RegistryErrors.TENANCY_MISCONFIGURED,
                        "pravaha.tenancy " + what + " is " + limit.getAsLong() + ", and a quota cannot be "
                                + "negative. Leave it unset for no limit, or set 0 to allow none.");
            }
        }
    }

    /**
     * What one tenant holds against its limits, and how often it has been refused.
     *
     * @param queries the names the tenant holds, which is what {@code max-queries} counts
     * @param computations the distinct computations behind those names, fewer when names share one
     * @param stateKeys the keys the tenant's views hold now, which is what {@code max-state-keys}
     *     counts
     */
    public record Usage(
            String tenant,
            int queries,
            int computations,
            long stateKeys,
            Limits limits,
            long queryRefusals,
            long stateRefusals) {}

    private final Limits defaults;
    private final Map<String, Limits> perTenant;
    private final Map<String, String> tenantOfName = new LinkedHashMap<>();
    private final Map<String, LongAdder> queryRefusals = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> stateRefusals = new ConcurrentHashMap<>();

    /**
     * @param defaults the limits every tenant has unless {@code perTenant} says otherwise
     * @param perTenant limits by tenant name; a limit a tenant's entry leaves unset is the default's
     */
    public TenantQuotas(Limits defaults, Map<String, Limits> perTenant) {
        this.defaults = defaults == null ? Limits.UNBOUNDED : defaults;
        Map<String, Limits> copy = new TreeMap<>();
        (perTenant == null ? Map.<String, Limits>of() : perTenant).forEach((tenant, limits) -> {
            if (tenant == null || tenant.isBlank()) {
                throw new PravahaException(
                        RegistryErrors.TENANCY_MISCONFIGURED,
                        "pravaha.tenancy.tenants has an entry with no tenant name, so there is no tenant for "
                                + "its limits to apply to");
            }
            copy.put(tenant, limits == null ? Limits.UNBOUNDED : limits);
        });
        this.perTenant = Map.copyOf(copy);
    }

    /** No limits for anybody: what a registry has until a deployment configures some. */
    public static TenantQuotas unbounded() {
        return new TenantQuotas(Limits.UNBOUNDED, Map.of());
    }

    public Limits defaults() {
        return defaults;
    }

    /** The tenants configured by name, sorted. */
    public Map<String, Limits> configured() {
        return perTenant;
    }

    /** The limits in force for {@code tenant}: its own entry over the defaults. */
    public Limits limitsFor(String tenant) {
        Limits own = perTenant.get(tenant);
        return own == null ? defaults : defaults.overriddenBy(own);
    }

    /** Registrations refused for {@code tenant} by one quota since this node started. */
    public long refusals(String tenant, Quota quota) {
        LongAdder count = (quota == Quota.QUERIES ? queryRefusals : stateRefusals).get(tenant);
        return count == null ? 0 : count.sum();
    }

    // ------------------------------------------------------------------ under the registry's monitor

    /**
     * Admits a registration for its principal's tenant, or refuses it by name.
     *
     * <p>Asked after the registration is authorized and planned, and before anything is opened.
     * After authorization so a principal who may not register learns nothing about a tenant's use;
     * before the sink so a refusal costs nothing.
     *
     * @param computations the registry's running computations, from which the tenant's state is
     *     counted
     * @param addsName whether the registration adds a name (a replacement does not)
     * @param startsComputation whether it starts a computation rather than attaching to one its
     *     tenant already runs
     */
    void admit(
            AuditSink audit,
            Principal principal,
            String action,
            String name,
            String sql,
            Collection<RegisteredQuery> computations,
            boolean addsName,
            boolean startsComputation) {
        String tenant = principal.tenant();
        Limits limits = limitsFor(tenant);
        if (limits.maxQueries().isEmpty() && limits.maxStateKeys().isEmpty()) {
            return;
        }
        if (addsName && limits.maxQueries().isPresent()) {
            long held = queriesOf(tenant);
            long max = limits.maxQueries().getAsLong();
            if (held >= max) {
                refuse(
                        audit,
                        principal,
                        action,
                        name,
                        sql,
                        Quota.QUERIES,
                        new PravahaException(
                                RegistryErrors.TENANT_QUERY_QUOTA,
                                "tenant '" + tenant + "' already holds " + held + " of its " + max + " queries, so '"
                                        + name + "' is refused. Drop one of the tenant's queries, or give it a "
                                        + "higher max-queries under pravaha.tenancy; nothing running was changed."));
            }
        }
        if (startsComputation && limits.maxStateKeys().isPresent()) {
            long held = stateKeysOf(tenant, computations);
            long max = limits.maxStateKeys().getAsLong();
            if (held >= max) {
                refuse(
                        audit,
                        principal,
                        action,
                        name,
                        sql,
                        Quota.STATE,
                        new PravahaException(
                                RegistryErrors.TENANT_STATE_QUOTA,
                                "tenant '" + tenant + "' already holds " + held + " view keys against its quota of "
                                        + max + ", so '" + name + "', which would start another computation, is "
                                        + "refused. Drop or narrow one of the tenant's queries, or give it a "
                                        + "higher max-state-keys under pravaha.tenancy; the queries already running keep running."));
            }
        }
        audit.record(AuditEvent.of(
                principal, action + ":quota", tenant, AccessDecision.allow(), describe(tenant, computations)));
    }

    /**
     * Refuses a replacement of {@code name} by a principal of another tenant (ADR-050), recording the
     * decision. A name nobody has assigned belongs to nobody yet and asks nothing.
     */
    void requireSameTenant(AuditSink audit, Principal principal, String name, String sql) {
        String owner = tenantOfName.get(name);
        if (owner == null || owner.equals(principal.tenant())) {
            return;
        }
        String reason = "'" + name + "' belongs to tenant '" + owner + "', and " + principal.id() + " is in tenant '"
                + principal.tenant() + "'. A new version would be charged to one tenant and read by the "
                + "other, so a name is replaced only from within the tenant that registered it.";
        audit.record(AuditEvent.of(principal, "replace:tenant", name, AccessDecision.deny(reason), sql));
        throw new PravahaException(RegistryErrors.TENANT_MISMATCH, reason);
    }

    void assign(String name, String tenant) {
        tenantOfName.put(name, tenant);
    }

    void release(String name) {
        tenantOfName.remove(name);
    }

    Optional<String> tenantOf(String name) {
        return Optional.ofNullable(tenantOfName.get(name));
    }

    /**
     * Every tenant that has a configured limit, holds a name, or has been refused, in name order.
     */
    List<Usage> usage(Collection<RegisteredQuery> computations) {
        TreeSet<String> tenants = new TreeSet<>(perTenant.keySet());
        tenants.addAll(tenantOfName.values());
        tenants.addAll(queryRefusals.keySet());
        tenants.addAll(stateRefusals.keySet());
        List<Usage> all = new ArrayList<>(tenants.size());
        for (String tenant : tenants) {
            int count = 0;
            long keys = 0;
            for (RegisteredQuery computation : computations) {
                if (tenant.equals(tenantOfComputation(computation))) {
                    count++;
                    keys += computation.view().size();
                }
            }
            all.add(new Usage(
                    tenant,
                    queriesOf(tenant),
                    count,
                    keys,
                    limitsFor(tenant),
                    refusals(tenant, Quota.QUERIES),
                    refusals(tenant, Quota.STATE)));
        }
        return all;
    }

    private int queriesOf(String tenant) {
        int held = 0;
        for (String owner : tenantOfName.values()) {
            if (owner.equals(tenant)) {
                held++;
            }
        }
        return held;
    }

    private long stateKeysOf(String tenant, Collection<RegisteredQuery> computations) {
        long keys = 0;
        for (RegisteredQuery computation : computations) {
            if (tenant.equals(tenantOfComputation(computation))) {
                keys += computation.view().size();
            }
        }
        return keys;
    }

    /**
     * The tenant a computation belongs to: that of any of its names, since the fingerprint keeps a
     * computation within one tenant.
     */
    private String tenantOfComputation(RegisteredQuery computation) {
        for (String name : computation.names()) {
            String owner = tenantOfName.get(name);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    private String describe(String tenant, Collection<RegisteredQuery> computations) {
        Limits limits = limitsFor(tenant);
        return "queries " + queriesOf(tenant) + "/" + text(limits.maxQueries()) + ", state keys "
                + stateKeysOf(tenant, computations) + "/" + text(limits.maxStateKeys());
    }

    private static String text(OptionalLong limit) {
        return limit.isPresent() ? Long.toString(limit.getAsLong()) : "unlimited";
    }

    private void refuse(
            AuditSink audit,
            Principal principal,
            String action,
            String name,
            String sql,
            Quota quota,
            PravahaException refusal) {
        (quota == Quota.QUERIES ? queryRefusals : stateRefusals)
                .computeIfAbsent(principal.tenant(), ignored -> new LongAdder())
                .increment();
        audit.record(AuditEvent.of(
                principal, action + ":quota", principal.tenant(), AccessDecision.deny(refusal.getMessage()), sql));
        throw refusal;
    }
}
