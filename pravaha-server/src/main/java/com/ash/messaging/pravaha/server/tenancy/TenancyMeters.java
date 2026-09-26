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
package com.ash.messaging.pravaha.server.tenancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.TenantQuotas;

/**
 * Each tenant's quotas, use and refusals as meters (ADR-050), tagged {@code tenant}.
 *
 * <ul>
 *   <li>{@code pravaha.tenant.queries} and {@code pravaha.tenant.state.keys} -- what the tenant holds;
 *   <li>{@code pravaha.tenant.quota.queries} and {@code pravaha.tenant.quota.state.keys} -- its limits,
 *       NaN where it has none, so that "no limit" is never read as a limit of zero;
 *   <li>{@code pravaha.tenant.refusals}, tagged {@code quota} ({@code queries} or {@code state}) --
 *       registrations refused since the node started.
 * </ul>
 *
 * <p>Published for a tenant the first time the registry lists it and never removed while the node
 * runs: a tenant's refusal count is the history an alert fires on, and removing it when the
 * tenant's last query is dropped would reset it. The set of tenants is the configured ones plus
 * every tenant that has registered or been refused, which is bounded by the identity provider's
 * tenants rather than by traffic.
 */
public final class TenancyMeters {

    private final MeterRegistry meters;
    private final Supplier<java.util.Optional<QueryRegistry>> registry;
    private final Map<String, List<Meter.Id>> published = new ConcurrentHashMap<>();

    public TenancyMeters(MeterRegistry meters, Supplier<java.util.Optional<QueryRegistry>> registry) {
        this.meters = meters;
        this.registry = registry;
    }

    /** Publishes meters for every tenant the registry now lists that has none yet. */
    public void sync(QueryRegistry current) {
        for (TenantQuotas.Usage usage : current.tenantUsage()) {
            published.computeIfAbsent(usage.tenant(), this::publish);
        }
    }

    /** The tenants published, for a test. */
    public java.util.Set<String> tenants() {
        return published.keySet();
    }

    private List<Meter.Id> publish(String tenant) {
        List<Meter.Id> ids = new ArrayList<>();
        ids.add(gauge("pravaha.tenant.queries", tenant, "names the tenant holds", u -> u.queries()));
        ids.add(gauge(
                "pravaha.tenant.state.keys", tenant, "keys the tenant's views hold", TenantQuotas.Usage::stateKeys));
        ids.add(gauge(
                "pravaha.tenant.quota.queries",
                tenant,
                "the tenant's max-queries; NaN for none",
                u -> limit(u.limits().maxQueries())));
        ids.add(gauge(
                "pravaha.tenant.quota.state.keys",
                tenant,
                "the tenant's max-state-keys; NaN for none",
                u -> limit(u.limits().maxStateKeys())));
        for (TenantQuotas.Quota quota : TenantQuotas.Quota.values()) {
            ids.add(FunctionCounter.builder("pravaha.tenant.refusals", this, self -> self.refusals(tenant, quota))
                    .description("registrations refused for the tenant by this quota")
                    .tag("tenant", tenant)
                    .tag("quota", quota.tag())
                    .register(meters)
                    .getId());
        }
        return ids;
    }

    private Meter.Id gauge(
            String name,
            String tenant,
            String description,
            java.util.function.ToDoubleFunction<TenantQuotas.Usage> read) {
        return Gauge.builder(
                        name,
                        this,
                        self -> self.usage(tenant).map(read::applyAsDouble).orElse(Double.NaN))
                .description(description)
                .tag("tenant", tenant)
                .register(meters)
                .getId();
    }

    private java.util.Optional<TenantQuotas.Usage> usage(String tenant) {
        return registry.get()
                .flatMap(open -> open.tenantUsage().stream()
                        .filter(each -> each.tenant().equals(tenant))
                        .findFirst());
    }

    private double refusals(String tenant, TenantQuotas.Quota quota) {
        return registry.get()
                .map(open -> (double) open.tenantQuotas().refusals(tenant, quota))
                .orElse(0.0);
    }

    private static double limit(OptionalLong limit) {
        return limit.isPresent() ? limit.getAsLong() : Double.NaN;
    }

    public void close() {
        published.values().forEach(ids -> ids.forEach(meters::remove));
        published.clear();
    }
}
