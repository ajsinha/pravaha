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

import java.util.List;

import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

/**
 * The fingerprint a registration of some SQL would get, for the principal asking, without
 * registering it (EXPLAINFP-1).
 *
 * <p>Computed by the registration's own preparation -- planned by the registry, authorized as the
 * principal (their row filters are part of the identity), chain-checked, with the key columns, the
 * retention the registration would keep and the principal's tenant -- so it is the value {@code
 * register} would answer, not an approximation of it. Nothing is started, no sink is opened and no
 * name is taken; the policy's answers are audited as {@code explain}. A statement registration would
 * refuse -- for its SQL, its keys, its sink or its principal -- is refused here with the same code.
 *
 * <p>It used to be that only a registered query had a fingerprint, so a client comparing a draft with
 * the queries already running (the assistant's reuse offer, its evaluation) compared normalised plan
 * text: evidence, not the computation's identity (ADR-025).
 */
public final class DraftFingerprint {

    private DraftFingerprint() {}

    /**
     * The fingerprint a registration of this statement would have.
     *
     * @param registry the registry the statement would be registered with
     * @param name the name it would be registered under, which a policy may judge; not part of the
     *     fingerprint
     * @param keys the key columns, in order; at least one, as registration requires
     * @param retention the retention asked for, or null for what registration would keep: {@link
     *     Retention#forever()} when writing to a sink, the registry's default otherwise
     * @param sink the sink it would write to, or null; not part of the fingerprint, but judged, and it
     *     decides the default retention
     */
    public static QueryFingerprint of(
            QueryRegistry registry,
            String name,
            String sql,
            List<Integer> keys,
            Principal principal,
            Retention retention,
            String sink) {
        Retention kept =
                retention != null ? retention : sink != null ? Retention.forever() : registry.defaultRetention();
        synchronized (registry) {
            // In the caller's tenant, as registering it would be (ADR-060).
            String engine = com.ash.messaging.pravaha.security.ViewNames.engineName(principal.tenant(), name);
            return registry.prepare(engine, sql, keys, principal, kept, BoundParameters.none(), sink, "explain")
                    .fingerprint();
        }
    }
}
