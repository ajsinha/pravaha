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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.ash.messaging.pravaha.security.Principal;

/**
 * Who may do what to which object, decided from the catalogue's grants (ADR-059 §2).
 *
 * <p>The rules, deliberately few:
 *
 * <ul>
 *   <li><strong>The {@code admin} role holds every right</strong> on everything -- the bootstrap, so a
 *       node that had one administrator before the catalogue has the same one after it.
 *   <li><strong>An owner holds every right</strong> on what they own, and on everything under it when
 *       it is a namespace.
 *   <li><strong>A grant is inherited downward</strong>: on the catalogue ({@code *}), a tenant, a
 *       namespace, or the object itself. Allow-only; access is the union.
 *   <li><strong>Tenants are walls</strong> (ADR-050): a grant or an ownership inside tenant {@code T}
 *       reaches only principals of {@code T}. The catalogue root and the node's own objects ({@code
 *       node.*}) are not inside a tenant, so a grant there reaches everyone it names.
 *   <li><strong>{@code USE} is necessary</strong>: every privilege on an object also needs {@code USE}
 *       on the namespace holding it (and {@code CREATE} on a namespace needs {@code USE} on it). A
 *       principal may always use their own tenant's {@code default} namespace and the node's, so
 *       every name that resolved before the catalogue still resolves.
 * </ul>
 *
 * <p>Decisions are cached per principal, privilege and object, and the cache is dropped whenever
 * {@link Catalog#generation()} moves -- that is, at the next decision after any change -- so a read
 * costs a map lookup and a revocation is never served from a stale answer.
 */
public final class CatalogAccess {

    /** How many decisions to hold before starting again; a bound, not a tuning knob. */
    static final int CACHE_LIMIT = 50_000;

    /**
     * A decision and why.
     *
     * @param via the grant, ownership or rule that allowed it, or why it was refused
     */
    public record Verdict(boolean allowed, String via) {}

    private final Catalog catalog;
    private final ConcurrentHashMap<String, Verdict> cache = new ConcurrentHashMap<>();
    private volatile long cachedGeneration = -1;
    private final CatalogStatistics statistics = new CatalogStatistics();

    public CatalogAccess(Catalog catalog) {
        this.catalog = catalog;
    }

    public Catalog catalog() {
        return catalog;
    }

    /** What this catalogue has decided since the node started, for the node's meters. */
    public CatalogStatistics statistics() {
        return statistics;
    }

    /** Whether {@code principal} holds {@code privilege} on {@code fullName}, cached. */
    public Verdict check(Principal principal, Privilege privilege, String fullName) {
        long generation = catalog.generation();
        if (generation != cachedGeneration || cache.size() > CACHE_LIMIT) {
            cache.clear();
            cachedGeneration = generation;
        }
        String key = principal.id()
                + '\u0000'
                + principal.tenant()
                + '\u0000'
                + new TreeSet<>(principal.roles())
                + '\u0000'
                + privilege
                + '\u0000'
                + fullName;
        Verdict cached = cache.get(key);
        if (cached != null) {
            statistics.cacheHit();
            return cached;
        }
        statistics.cacheMiss();
        Verdict decided = decide(principal, privilege, fullName);
        // Stored only if nothing changed while deciding; otherwise the next call decides afresh.
        if (catalog.generation() == generation) {
            cache.put(key, decided);
        }
        return decided;
    }

    /** How many decisions are held, for a test of the invalidation. */
    int cached() {
        return cache.size();
    }

    /** Every way {@code principal} holds {@code privilege} on {@code fullName}, uncached; empty if none. */
    public List<String> reasons(Principal principal, Privilege privilege, String fullName) {
        List<String> found = new ArrayList<>();
        if (principal.hasRole(Catalog.ADMIN_ROLE)) {
            found.add("the admin role, which holds every right");
        }
        if (privilege == Privilege.USE && CatalogNames.depth(fullName) == 2 && implicitlyUsable(principal, fullName)) {
            found.add("every principal may use " + fullName);
        }
        for (String level : CatalogNames.chain(fullName)) {
            if (!reaches(principal, level)) {
                continue;
            }
            Optional<CatalogObject> object = catalog.object(level);
            if (object.isPresent() && object.get().owner().covers(principal)) {
                found.add("owner of " + level + " (" + object.get().owner() + ")");
            }
            for (Grant grant : catalog.grantsOn(level)) {
                if (grant.privilege() == privilege && grant.grantee().covers(principal)) {
                    found.add("grant " + grant.describe());
                }
            }
        }
        return found;
    }

    private Verdict decide(Principal principal, Privilege privilege, String fullName) {
        if (principal.hasRole(Catalog.ADMIN_ROLE)) {
            return new Verdict(true, "the admin role, which holds every right");
        }
        if (privilege == Privilege.USE && CatalogNames.depth(fullName) == 2 && implicitlyUsable(principal, fullName)) {
            return new Verdict(true, "every principal may use " + fullName);
        }
        Optional<String> held = firstReason(principal, privilege, fullName);
        if (held.isEmpty()) {
            return new Verdict(
                    false,
                    principal.id() + " holds no " + privilege + " on " + fullName + ": no grant on it, its namespace, "
                            + "its tenant or the catalogue reaches " + describe(principal)
                            + ", and they do not own it");
        }
        if (privilege == Privilege.USE) {
            return new Verdict(true, held.get());
        }
        Optional<CatalogObject> object = catalog.object(fullName);
        boolean namespace = object.map(o -> o.kind() == ObjectKind.NAMESPACE).orElse(CatalogNames.depth(fullName) <= 2);
        String usedNamespace = namespace ? fullName : CatalogNames.parentOf(fullName);
        if (usedNamespace != null && CatalogNames.depth(usedNamespace) == 2 && !mayUse(principal, usedNamespace)) {
            return new Verdict(
                    false,
                    principal.id() + " holds " + privilege + " on " + fullName + " (" + held.get() + ") but not USE on "
                            + "the namespace " + usedNamespace + ", which every name in it needs");
        }
        return new Verdict(true, held.get());
    }

    /** USE on a namespace: granted, owned, or implicit for one's own default and for the node's. */
    private boolean mayUse(Principal principal, String namespace) {
        if (implicitlyUsable(principal, namespace)) {
            return true;
        }
        return firstReason(principal, Privilege.USE, namespace).isPresent();
    }

    /** Whether {@code namespace} is usable by {@code principal} without a grant. */
    public static boolean implicitlyUsable(Principal principal, String namespace) {
        return CatalogNames.NODE.equals(CatalogNames.tenantOf(namespace))
                || namespace.equals(principal.tenant() + "." + CatalogNames.DEFAULT_NAMESPACE);
    }

    private Optional<String> firstReason(Principal principal, Privilege privilege, String fullName) {
        for (String level : CatalogNames.chain(fullName)) {
            if (!reaches(principal, level)) {
                continue;
            }
            Optional<CatalogObject> object = catalog.object(level);
            if (object.isPresent() && object.get().owner().covers(principal)) {
                return Optional.of("owner of " + level);
            }
            for (Grant grant : catalog.grantsOn(level)) {
                if (grant.privilege() == privilege && grant.grantee().covers(principal)) {
                    return Optional.of("grant " + grant.describe());
                }
            }
        }
        return Optional.empty();
    }

    /** The tenant wall: a level inside a tenant reaches only that tenant's principals. */
    private static boolean reaches(Principal principal, String level) {
        String tenant = CatalogNames.tenantOf(level);
        return tenant.equals(CatalogNames.ROOT)
                || tenant.equals(CatalogNames.NODE)
                || tenant.equals(principal.tenant());
    }

    private static String describe(Principal principal) {
        return principal.isAnonymous()
                ? "an anonymous caller"
                : "USER " + principal.id()
                        + (principal.roles().isEmpty() ? "" : " or the roles " + new TreeSet<>(principal.roles()));
    }
}
