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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the catalogue has decided since this node started, counted for a meter registry to read: access
 * decisions by privilege and outcome, and how often a decision came out of the cache (ADR-059).
 *
 * <p>Plain counters, published by the server through function counters, as every engine number is.
 * The labels are the privilege and the outcome -- never the principal or the object, which would make
 * every user and every view a time series.
 *
 * <p>Counted at the enforcement points ({@link CatalogPolicy}'s questions), not at every {@link
 * CatalogAccess#check}: a listing filtered to what the caller may see checks each object and refuses
 * most of them, and counting those as denials would make a {@code SHOW} look like an attack.
 */
public final class CatalogStatistics {

    /** The kinds of change {@link Catalog#changes(String)} counts, as the {@code kind} label says them. */
    public static final List<String> CHANGE_KINDS = List.of(
            "grant",
            "revoke",
            "policy_create",
            "policy_drop",
            "policy_bind",
            "policy_unbind",
            "owner",
            "move",
            "object",
            "import");

    private final Map<Privilege, LongAdder> allowed = new EnumMap<>(Privilege.class);
    private final Map<Privilege, LongAdder> denied = new EnumMap<>(Privilege.class);
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public CatalogStatistics() {
        for (Privilege privilege : Privilege.values()) {
            allowed.put(privilege, new LongAdder());
            denied.put(privilege, new LongAdder());
        }
    }

    void decided(Privilege privilege, boolean allow) {
        (allow ? allowed : denied).get(privilege).increment();
    }

    void cacheHit() {
        hits.increment();
    }

    void cacheMiss() {
        misses.increment();
    }

    /** Decisions on {@code privilege} that allowed ({@code allow}) or refused. */
    public long decisions(Privilege privilege, boolean allow) {
        return (allow ? allowed : denied).get(privilege).sum();
    }

    /** Decisions answered from the cache. */
    public long cacheHits() {
        return hits.sum();
    }

    /** Decisions worked out afresh: the first of their kind, or the first since the catalogue changed. */
    public long cacheMisses() {
        return misses.sum();
    }
}
