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
package com.ash.messaging.pravaha.server;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ash.messaging.pravaha.identity.IdentityService;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.server.security.SecurityProperties;

/**
 * Resolves a journalled owner id back to a principal for recovery (RECOVERYOWNER-1).
 *
 * <p>Deliberately minimal, and deliberately not a lookup that invents authority: it reconstructs
 * the identity the registration was made under and lets the policy decide, rather than recovering
 * everything as an administrator.
 *
 * <p>Asked in the order a caller is identified in: the <strong>identity store</strong> (ADR-052)
 * first, then the static token table. It used to ask only the token table, so on a node whose people
 * live in the identity store every query they had registered was refused at the next restart,
 * {@code PRV-8007} "not a principal this deployment knows" -- about a user the node signs in every
 * day. The principal is the one the user signs in as now: their tenant and their current roles, so
 * the policy re-checks the registration against what they hold today, exactly as it does for a token.
 *
 * <p><strong>A disabled user's queries keep running</strong>, under the principal the store still
 * holds for them, with a warning naming the owner and the query. Disabling an account ends its
 * sessions and keys; it does not stop the queries it registered while the node runs, and a restart
 * must not change what is running -- a view its readers depend on disappearing at the next restart,
 * weeks after the account was disabled, is the surprise this avoids. The warning says how to stop
 * them: drop the queries, or change their owner. A user the store has never heard of (the store
 * replaced, or the name edited) is not a principal this node knows, and is refused with {@code
 * PRV-8007} as before. Users are disabled, never deleted, so "deleted" is that case.
 *
 * <p>The catalogue's owner of a view (ADR-059, {@code OWNER TO}) is not the principal a registration
 * is replayed as: the journal records who registered the computation, and the catalogue's ownership
 * is what its policy asks about when it authorizes the replay, which it does whoever the owner is.
 */
final class RecoveryOwners implements Function<String, Optional<Principal>> {

    private static final Logger log = LoggerFactory.getLogger(RecoveryOwners.class);

    private final SecurityProperties security;
    private final Supplier<Optional<IdentityService>> identity;

    RecoveryOwners(SecurityProperties security, Supplier<Optional<IdentityService>> identity) {
        this.security = security;
        this.identity = identity;
    }

    @Override
    public Optional<Principal> apply(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Optional<IdentityService> users = identity.get();
        if (users.isPresent()) {
            Optional<Principal> user = users.get().principalOfUser(id);
            if (user.isPresent()) {
                String status = users.get().me(user.get()).status();
                if (!"active".equals(status)) {
                    log.warn(
                            "recovering a query owned by '{}', whose account is {}: it keeps running under that "
                                    + "account's tenant and roles, as it did before the restart. Drop the account's "
                                    + "queries, or change their owner, to stop them (RECOVERYOWNER-1)",
                            id,
                            status);
                }
                return user;
            }
        }
        Optional<Principal> configured = security.principalFor(id);
        if (configured.isPresent()) {
            return configured;
        }
        if (security.authenticates()) {
            // An owner this node cannot identify is one whose entitlements it cannot check, so the
            // registration is refused and named in the recovery report rather than resurrected under
            // an invented identity. Refusing is visible; inventing is not.
            return Optional.empty();
        }
        // No identity source configured at all, so there is nothing to reconstruct from and nothing
        // that could be checked against it either. The anonymous principal is honest about that,
        // where a fabricated one with a made-up tenant was not.
        return Optional.of(Principal.ANONYMOUS);
    }
}
