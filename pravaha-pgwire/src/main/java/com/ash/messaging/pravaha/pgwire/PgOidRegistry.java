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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * View name to {@code pg_class.oid}, and back.
 *
 * <p>A real PostgreSQL server assigns an oid when an object is created and keeps it for the object's
 * life; a Pravaha view has no such moment, so this registry gives one the first time a view is asked
 * about and keeps it for as long as this server process runs. That is enough for what an oid is used
 * for here: {@code psql}'s {@code \d <view>} resolves a name to an oid in one query and looks the
 * same oid up in the next three, on whatever connection each happens to land on, so the mapping has
 * to outlive any one session and stay one-to-one for the life of the server.
 *
 * <p><strong>Derived from the name, not counted (ADR-060).</strong> An oid was the next value of one
 * node-wide counter, so the number a caller was handed said how many relations every tenant had
 * described before -- and a jump between two of its own said another tenant had been busy. The oid is
 * now a hash of the view's engine name into the user range, the same for that name in every process,
 * and says nothing about any other name. Two names that hash alike are told apart by probing to the
 * next free oid; with 2^31 values that is a collision in the tens of thousands of views, and it tells
 * the second caller only that some name somewhere hashed where theirs did.
 *
 * <p>The range starts at 16384, where a real PostgreSQL's first user object lands, after the ones the
 * template database ships with: it keeps this server's oids out of the range {@code psql} and drivers
 * associate with system catalog objects, so a person reading one in a transcript does not mistake a
 * view for a system table.
 */
final class PgOidRegistry {

    private static final int FIRST_OID = 16_384;

    /** How many oids the hash spreads names over: every positive {@code int} from {@link #FIRST_OID}. */
    private static final long SPAN = (long) Integer.MAX_VALUE - FIRST_OID + 1;

    private final Map<String, Integer> byName = new ConcurrentHashMap<>();
    private final Map<Integer, String> byOid = new ConcurrentHashMap<>();

    /** The oid for this view: its name's hash, or the next free oid after it when another name has that. */
    synchronized int oidOf(String viewName) {
        Integer known = byName.get(viewName);
        if (known != null) {
            return known;
        }
        int oid = hashed(viewName);
        while (byOid.containsKey(oid)) {
            oid = oid == Integer.MAX_VALUE ? FIRST_OID : oid + 1;
        }
        byOid.put(oid, viewName);
        byName.put(viewName, oid);
        return oid;
    }

    /** Where {@code viewName} lands in the user range, before any probing. */
    static int hashed(String viewName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(viewName.getBytes(StandardCharsets.UTF_8));
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits = (bits << 8) | (digest[i] & 0xFF);
            }
            return (int) (FIRST_OID + Long.remainderUnsigned(bits, SPAN));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }

    /**
     * The view name for this oid, or empty if this registry never gave it.
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
