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

    /**
     * Everyone sees everything, and everyone may register. The default for a single-tenant
     * deployment behind its own wall.
     *
     * <p>Written out rather than as a lambda, and that is not style. A lambda implements only
     * {@link #mayRead} and silently keeps the default {@link #mayRegisterQuery}, which refuses
     * anonymous callers -- so a policy named PERMISSIVE would have permitted every read and then
     * refused registration on an embedded server with no authentication at all. It said one thing
     * and did another, which is the worst property a security default can have.
     */
    SecurityPolicy PERMISSIVE = new SecurityPolicy() {
        @Override
        public AccessDecision mayRead(Principal principal, String view) {
            return AccessDecision.allow();
        }

        @Override
        public AccessDecision mayRegisterQuery(Principal principal) {
            return AccessDecision.allow();
        }
    };

    /**
     * May this principal read this view, and under what restriction?
     *
     * @param view the registered view name, as the caller wrote it
     */
    AccessDecision mayRead(Principal principal, String view);

    /**
     * May this principal drop, pause or resume this view?
     *
     * <p>Defaults to {@link #mayRead}: you may administer what you may read. That is the weakest
     * defensible rule and it is a default rather than the answer, because these verbs are not reads.
     * Dropping a continuous query destroys the state it has accumulated and takes the view away from
     * every other client holding a name for it -- data loss and an outage, from one call. A
     * deployment that separates operators from readers should override this and say so.
     *
     * <p>It exists because the Flight control verbs authorized nothing at all: an unauthenticated
     * caller could drop every query on a node configured to serve only verified callers. The default
     * closes that without inventing a permission model nobody asked for.
     *
     * <p>Unrestricted reading, not merely reading. Deferring wholesale to {@link #mayRead} made a
     * row filter into an administrative right: a principal shown one row of a view could drop it
     * for every other reader, including those entitled to all of it. A restricted read is the
     * weakest claim on a thing there is, and it does not carry the right to destroy it.
     */
    default AccessDecision mayAdminister(Principal principal, String view) {
        AccessDecision read = mayRead(principal, view);
        if (!read.allowed()) {
            return read;
        }
        // Reading is not the same right as destroying, and this used to treat them as one. A
        // principal entitled to a single row of a view could drop, pause or resume it -- for every
        // other reader of it, including the ones who could see all of it. Being shown a slice of
        // something is the weakest claim on it there is.
        if (read.rowFilter().isPresent()) {
            return AccessDecision.deny(principal.id() + " may read '" + view + "' only through a row filter ("
                    + read.rowFilter().get() + "), which is not a claim on the whole view. Administering it "
                    + "affects every reader, so it needs a policy that says so rather than inheriting a "
                    + "restricted read.");
        }
        // No anonymous clause here, and that is deliberate. Adding one broke a permissive
        // single-tenant node, where every caller is anonymous and pausing a query is an ordinary
        // thing to do -- and it was redundant besides: a node that requires credentials refuses an
        // anonymous *read* already, which this has just deferred to.
        return read;
    }

    /**
     * May this principal register a continuous query at all?
     *
     * <p>Separate from reading because it is a different risk: registering costs the cluster state
     * and threads for as long as it runs, so it is the decision a resource quota hangs off (§21.4),
     * while reading costs one query.
     *
     * <p><strong>A lambda does not override this.</strong> {@code SecurityPolicy} is a functional
     * interface on {@link #mayRead}, so {@code (principal, view) -> allow()} keeps the default below
     * and refuses anonymous registration -- which is correct far more often than not, and surprising
     * exactly when somebody meant to write a permissive policy for a development server. Implement
     * the interface explicitly when you mean to change this.
     */
    default AccessDecision mayRegisterQuery(Principal principal) {
        return principal.isAnonymous()
                ? AccessDecision.deny("anonymous callers may read but not register continuous queries")
                : AccessDecision.allow();
    }
}
