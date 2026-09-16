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
package com.ash.messaging.pravaha.pgwire;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * View name to {@code pg_class.oid}, and back.
 *
 * <p>A real PostgreSQL server assigns an oid when an object is created and keeps it for the object's
 * life; a Pravaha view has no such moment, so this registry mints one the first time a view is asked
 * about and keeps it for as long as this server process runs. That is enough for what an oid is used
 * for here: {@code psql}'s {@code \d <view>} resolves a name to an oid in one query and looks the
 * same oid up in the next three, on whatever connection each happens to land on, so the mapping has
 * to outlive any one session and stay one-to-one for the life of the server.
 *
 * <p>Starting at 16384 matches where a real PostgreSQL's first user object lands, after the ones the
 * template database ships with -- a cosmetic detail with one real purpose: it keeps this server's
 * oids out of the range {@code psql} and drivers associate with system catalog objects, so a person
 * reading one in a transcript does not mistake a view for a system table.
 */
final class PgOidRegistry {

    private static final int FIRST_OID = 16_384;

    private final Map<String, Integer> byName = new ConcurrentHashMap<>();
    private final Map<Integer, String> byOid = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger(FIRST_OID);

    /** The oid for this view, minting one if this is the first time it has been asked about. */
    int oidOf(String viewName) {
        return byName.computeIfAbsent(viewName, name -> {
            int oid = next.getAndIncrement();
            byOid.put(oid, name);
            return oid;
        });
    }

    /**
     * The view name for this oid, or empty if this registry never minted it.
     *
     * <p>Deliberately not a check on its own for whether the caller may know that: a stale or
     * guessed oid and an oid for a view this principal may not read both have to come back exactly
     * the same way, which is "not found" -- see {@link PgCatalogShim}, which is where that check is
     * made.
     */
    Optional<String> nameOf(int oid) {
        return Optional.ofNullable(byOid.get(oid));
    }
}
