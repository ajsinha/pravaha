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

        /**
         * Allowed, and consistently so: a node that lets every caller read every view, register
         * and drop anything has no reader the audit trail could be kept from that is not already
         * entitled to everything it describes. A deployment that wants the trail kept from its
         * readers wants a policy other than this one.
         */
        @Override
        public AccessDecision mayReadAudit(Principal principal) {
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

    /**
     * May this principal have a registration write to this sink?
     *
     * <p>Naming a sink in a registration is a <em>standing write</em> to a store outside Pravaha,
     * under the credentials this node holds for it, for as long as the query runs. The rows are
     * ones the registrant may already read -- {@link #mayRead} is asked for every stream the plan
     * names -- so this is not a disclosure. It is a placement: it decides where permitted data
     * comes to rest and who can see it there, which is a question about the destination rather
     * than about the query, and the policy is the only thing that knows the answer.
     *
     * <p><strong>Allowed by default</strong>, and that is a considered default rather than an
     * omission. A sink is a binding an operator wrote into this node's own configuration, so
     * binding it is already most of the way to saying it may be written to, and a default that
     * refused would turn every existing deployment's sinks off in one release. What the default
     * buys is the <em>question being asked</em>: a deployment whose bindings are not all equally
     * trusted now has somewhere to say so, where before {@code SECURITY.md} could only advise
     * binding sinks every registrant may write to (SINK-3).
     *
     * <p>A row filter has no meaning here and is refused rather than ignored, with {@link
     * SecurityErrors#SINK_WRITE_NOT_FILTERABLE}: a sink receives the query's whole changelog or
     * none of it, so half an answer would have had the other half written to the table anyway.
     *
     * <p>A lambda does not override this, as with every other verb on this interface.
     *
     * @param sink the sink binding's name, as the registration wrote it
     */
    default AccessDecision mayWriteTo(Principal principal, String sink) {
        return AccessDecision.allow();
    }

    /**
     * May this principal read the audit trail -- who asked for what, and with which SQL?
     *
     * <p><strong>Denied unless a policy says otherwise</strong>, and deliberately not derived from
     * {@link #mayRead}. The trail names every principal that read anything on the node and the text
     * they read it with, so it discloses more than any one view does: a principal entitled to read
     * every view is still not entitled to learn who else read them. Answering this by passing a
     * pseudo-view name to {@code mayRead} would apply a check to the wrong noun -- and under a policy
     * that allows every read, hand the trail to everyone.
     *
     * <p>A lambda does not override this either, so a policy written as one keeps the trail closed,
     * which is the safe way for that surprise to go.
     */
    default AccessDecision mayReadAudit(Principal principal) {
        return AccessDecision.deny(
                "reading the audit trail is a permission of its own, and this node's " + "policy grants it to nobody");
    }

    /**
     * May this principal receive this view's changes live?
     *
     * <p>Separate from {@link #mayRead} since ADR-059: a dashboard may read without holding a stream
     * open, and a feed consumer may subscribe to a view it may not scan. Defaults to {@link #mayRead},
     * so a policy that never heard of the distinction answers as it always did.
     */
    default AccessDecision maySubscribe(Principal principal, String view) {
        return mayRead(principal, view);
    }

    /**
     * May this principal register a query that reads {@code input} -- a stream or a view the plan names
     * directly (ADR-056's queries on queries)?
     *
     * <p>Defaults to {@link #mayRead}, which is what registration asked before ADR-059 made building on
     * something a right of its own.
     */
    default AccessDecision mayBuildOn(Principal principal, String input) {
        return mayRead(principal, input);
    }

    /**
     * May this principal register a query over a view that derives from {@code source}, which the plan
     * does not name itself?
     *
     * <p>Defaults to {@link #mayRead}: before ADR-059 a registration was a standing read of everything
     * its chain reads, and a policy that says nothing else keeps that rule. A policy that grants
     * building on a view as a right of its own (the catalogue's) answers this for itself.
     */
    default AccessDecision mayBuildThrough(Principal principal, String source) {
        return mayRead(principal, source);
    }

    /**
     * May this principal read the rows of {@code source} that reach them through a view they are
     * reading -- a stream in the view's provenance?
     *
     * <p>Defaults to {@link #mayRead}: SX-11 made a read follow the data a view reads rather than the
     * name it was registered under, and a policy that says nothing else keeps that rule. The catalogue
     * (ADR-059) answers it differently -- reading a view needs the view's grant, not its sources',
     * because registering the view already needed {@code BUILD_ON} on them.
     */
    default AccessDecision mayReadThrough(Principal principal, String source) {
        return mayRead(principal, source);
    }

    /**
     * May this principal register a continuous query under {@code name}? Defaults to {@link
     * #mayRegisterQuery(Principal)}; a policy that knows where the name will live answers here.
     */
    default AccessDecision mayRegisterQuery(Principal principal, String name) {
        return mayRegisterQuery(principal);
    }

    /**
     * Told that {@code owner} has registered {@code view} and the registration is journalled. A policy
     * that keeps a record of objects -- the catalogue's (ADR-059), which makes the registrant the
     * owner -- records it here; the default keeps nothing. Called at recovery too, for every
     * registration the journal brings back.
     */
    default void registered(Principal owner, String view) {}

    /**
     * What policies narrow of {@code object} for {@code principal}: the row filter and column masks that
     * apply to every read, subscription and registration through it (ADR-059 §4). Asked after the
     * principal has been allowed; a narrowing never allows anything.
     *
     * <p>Defaults to {@link Narrowing#NONE}. A policy that answers with {@link AccessDecision#rowFilter}
     * keeps doing so; this is where a policy that keeps filters and masks as objects -- the catalogue's --
     * says what applies.
     *
     * @param object the view or stream, as the engine names it
     * @throws com.ash.messaging.pravaha.api.PravahaException when a policy that applies cannot be bound
     *     to this principal -- a claim it reads that their credential does not carry
     */
    default Narrowing narrowing(Principal principal, String object) {
        return Narrowing.NONE;
    }

    /** Told that {@code view} has been dropped. The default keeps nothing, so forgets nothing. */
    default void dropped(String view) {}
}
