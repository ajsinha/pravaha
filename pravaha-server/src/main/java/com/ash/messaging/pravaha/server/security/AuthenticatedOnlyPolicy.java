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
package com.ash.messaging.pravaha.server.security;

import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;

/**
 * Anyone who proved who they are may read and register; nobody else may do either.
 *
 * <p>The smallest policy that is not {@code PERMISSIVE}, and deliberately not more than that. It
 * would be easy to ship something here that looked like multi-tenancy -- matching a view's name
 * against a principal's tenant, say -- and it would be a guess: views carry a name and a schema and
 * no notion of who owns them, so any mapping from one to the other would be this class inventing a
 * convention and enforcing it on deployments that never agreed to it.
 *
 * <p>What a deployment with real rules does instead is implement {@link SecurityPolicy}, which is
 * three methods and gets the principal, the view name and the chance to return a row filter. This
 * exists so that "only authenticated callers reach data" is reachable from configuration alone,
 * which is the property most deployments actually need first.
 */
public final class AuthenticatedOnlyPolicy implements SecurityPolicy {

    @Override
    public AccessDecision mayRead(Principal principal, String view) {
        if (principal == null || principal.isAnonymous()) {
            return AccessDecision.deny("this server serves data only to authenticated callers, and this "
                    + "call presented no credential Pravaha could verify");
        }
        return AccessDecision.allow();
    }

    @Override
    public AccessDecision mayRegisterQuery(Principal principal) {
        if (principal == null || principal.isAnonymous()) {
            return AccessDecision.deny("registering a continuous query costs threads and state for as long "
                    + "as it runs, so it is refused to unauthenticated callers");
        }
        return AccessDecision.allow();
    }

    @Override
    public String toString() {
        return "authenticated-only";
    }
}
