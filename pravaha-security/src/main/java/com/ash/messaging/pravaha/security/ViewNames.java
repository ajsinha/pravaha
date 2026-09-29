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
package com.ash.messaging.pravaha.security;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * A view's name within its tenant, and the engine name the node keys it by (ADR-060).
 *
 * <p>A view name is unique within its tenant. Inside the engine a view is known by its <strong>engine
 * name</strong>: for the default tenant ({@value #DEFAULT_TENANT}) the name itself, so a node that has
 * only ever had one tenant has the names, checkpoint directories and journal entries it always had; for
 * any other tenant its catalogue name, {@code tenant.default.name} (ADR-059). A view name is a plain
 * identifier and cannot hold a dot, so no engine name of one tenant is ever the engine name of another's.
 *
 * <p>Every surface that takes a name from a caller {@linkplain #resolve resolves} it here, in the
 * caller's tenant, before asking the registry, the catalogue or the policy anything -- so a name another
 * tenant holds is, to the caller, a name nothing holds. An admin addresses a view in another tenant by
 * its catalogue name. Every surface that hands a name back {@linkplain #shown shows} it: the bare name
 * within the caller's tenant, the catalogue name outside it.
 *
 * <p>Resolution is syntax only. It never asks what exists, so its answer -- and a refusal -- is the same
 * for a name that is registered and one that is not.
 */
public final class ViewNames {

    /** The tenant a principal with none is in, whose engine names are its view names. */
    public static final String DEFAULT_TENANT = "public";

    /** The namespace a registration lands in within its tenant's catalogue (ADR-059). */
    public static final String NAMESPACE = "default";

    private static final String QUALIFIER = "." + NAMESPACE + ".";

    private ViewNames() {}

    /** The engine name of {@code name} in {@code tenant}: the name itself in the default tenant. */
    public static String engineName(String tenant, String name) {
        if (name == null || tenant == null || tenant.isBlank() || DEFAULT_TENANT.equals(tenant)) {
            return name;
        }
        return tenant + QUALIFIER + name;
    }

    /** The tenant an engine name is in; {@value #DEFAULT_TENANT} for a bare name. */
    public static String tenantOf(String engineName) {
        int at = qualifierAt(engineName);
        return at < 0 ? DEFAULT_TENANT : engineName.substring(0, at);
    }

    /** The name an engine name has within its tenant: the part after the last dot. */
    public static String localName(String engineName) {
        if (engineName == null) {
            return null;
        }
        int dot = engineName.lastIndexOf('.');
        return dot < 0 ? engineName : engineName.substring(dot + 1);
    }

    /** The catalogue name, {@code tenant.default.name}, of an engine name -- the default tenant's included. */
    public static String catalogueName(String engineName) {
        return tenantOf(engineName) + QUALIFIER + localName(engineName);
    }

    /** Whether {@code principal} addresses this engine name at all: its own tenant's, or any, for an admin. */
    public static boolean visibleTo(Principal principal, String engineName) {
        return principal.hasRole(Administration.ADMIN_ROLE)
                || principal.tenant().equals(tenantOf(engineName));
    }

    /** How {@code engineName} is shown to {@code principal}: bare in its own tenant, qualified outside it. */
    public static String shown(Principal principal, String engineName) {
        if (engineName == null) {
            return null;
        }
        return principal.tenant().equals(tenantOf(engineName)) ? localName(engineName) : catalogueName(engineName);
    }

    /**
     * The engine name {@code typed} means to {@code principal}.
     *
     * <p>A bare name is the caller's own tenant's. A catalogue name, {@code tenant.default.name}, is that
     * tenant's -- for the caller's own tenant, or for an admin; anyone else is refused, in words that do
     * not depend on whether the name is registered. Anything else with a dot is read as a name in the
     * caller's tenant, which no registration can hold.
     *
     * @throws PravahaException {@code PRV-7002} when a caller who is not an admin names another tenant
     */
    public static String resolve(Principal principal, String typed) {
        if (typed == null || typed.isBlank() || principal == null) {
            return typed;
        }
        String name = typed.strip();
        int at = qualifierAt(name);
        if (at < 0) {
            return engineName(principal.tenant(), name);
        }
        String tenant = name.substring(0, at);
        if (tenant.equals(principal.tenant()) || principal.hasRole(Administration.ADMIN_ROLE)) {
            return engineName(tenant, localName(name));
        }
        throw new PravahaException(
                SecurityErrors.FORBIDDEN,
                principal.id() + " may not address '" + name + "': a qualified name reaches outside the caller's "
                        + "tenant, which only an admin may do. Name a view of your own tenant without a qualifier.");
    }

    /**
     * Where {@code .default.} separates a qualified name's tenant from its name, or {@code -1} when
     * {@code name} is not {@code tenant.default.identifier}.
     */
    private static int qualifierAt(String name) {
        if (name == null) {
            return -1;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || !name.regionMatches(dot - NAMESPACE.length() - 1, QUALIFIER, 0, QUALIFIER.length())) {
            return -1;
        }
        int at = dot - NAMESPACE.length() - 1;
        return at > 0 && isIdentifier(name.substring(dot + 1)) ? at : -1;
    }

    private static boolean isIdentifier(String part) {
        return part.matches("[\\p{L}_][\\p{L}\\p{N}_]*");
    }
}
