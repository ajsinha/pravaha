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

/**
 * What a principal may see.
 *
 * <p>An SPI, because the answer lives somewhere else in every real deployment: an OIDC token's
 * claims, an LDAP group, a table somebody's platform team owns. Pravaha's job is to <em>enforce</em>
 * the answer, not to be the system of record for it.
 *
 * <p><strong>Authorization is enforced here, not in the store.</strong> That is not a preference. A
 * maintained view is derived data the persistence layer has never seen -- it cannot express "rows of
 * this aggregate" -- a change feed is read once and shared by every query over it, so per-user
 * filtering at the source would mean a change feed per user, and Aerospike Community has no
 * row-level security to delegate to in the first place. See ADR-031.
 *
 * <p>Called on the path of every query, so an implementation should be fast and should cache. The
 * engine does not cache decisions on its behalf: a policy that has just revoked someone's access
 * expects that to take effect, and a cache Pravaha owned would decide the revocation window
 * without asking.
 */
public interface SecurityPolicy {

    /** Everyone sees everything. The default for a single-tenant deployment behind its own wall. */
    SecurityPolicy PERMISSIVE = (principal, view) -> AccessDecision.allow();

    /**
     * May this principal read this view, and under what restriction?
     *
     * @param view the registered view name, as the caller wrote it
     */
    AccessDecision mayRead(Principal principal, String view);

    /**
     * May this principal register a continuous query at all?
     *
     * <p>Separate from reading because it is a different risk: registering costs the cluster state
     * and threads for as long as it runs, so it is the decision a resource quota hangs off (§21.4),
     * while reading costs one query.
     */
    default AccessDecision mayRegisterQuery(Principal principal) {
        return principal.isAnonymous()
                ? AccessDecision.deny("anonymous callers may read but not register continuous queries")
                : AccessDecision.allow();
    }
}
