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

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.registry.TenantQuotas;

/**
 * {@code pravaha.tenancy.*}: the admission quotas each tenant registers under (ADR-050).
 *
 * <pre>
 * pravaha:
 *   tenancy:
 *     defaults:
 *       max-queries: 50
 *       max-state-keys: 5000000
 *     tenants:
 *       globex:
 *         max-queries: 5
 * </pre>
 *
 * <p>Nothing set is no limit, which is how a node behaved before tenancy existed. An unknown key is
 * refused at startup rather than ignored ({@code ignoreUnknownFields = false}): a misspelt quota
 * is a limit the operator believes is in force, and ignoring it is the silent half of that.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.tenancy", ignoreUnknownFields = false)
public class TenancyProperties {

    private final Limit defaults = new Limit();
    private final Map<String, Limit> tenants = new LinkedHashMap<>();

    public Limit getDefaults() {
        return defaults;
    }

    public Map<String, Limit> getTenants() {
        return tenants;
    }

    /** The engine's form, refused with {@code PRV-8023} for a negative limit or a blank tenant. */
    public TenantQuotas quotas() {
        Map<String, TenantQuotas.Limits> perTenant = new LinkedHashMap<>();
        tenants.forEach((tenant, limit) -> perTenant.put(tenant, limit.limits()));
        return new TenantQuotas(defaults.limits(), perTenant);
    }

    /** One tenant's limits, or the defaults'. A null limit is none. */
    public static class Limit {

        private Long maxQueries;
        private Long maxStateKeys;

        public Long getMaxQueries() {
            return maxQueries;
        }

        public void setMaxQueries(Long maxQueries) {
            this.maxQueries = maxQueries;
        }

        public Long getMaxStateKeys() {
            return maxStateKeys;
        }

        public void setMaxStateKeys(Long maxStateKeys) {
            this.maxStateKeys = maxStateKeys;
        }

        TenantQuotas.Limits limits() {
            return TenantQuotas.Limits.of(maxQueries, maxStateKeys);
        }
    }
}
