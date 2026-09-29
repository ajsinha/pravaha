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

import java.util.Locale;
import java.util.Optional;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * Who may drop, pause, resume, replace or debug a registered view: its owner, a principal the policy
 * grants it to, or an admin.
 *
 * <p><strong>Why this is not {@link SecurityPolicy#mayAdminister} alone.</strong> The policy is asked
 * about a name and knows nothing of who registered it: {@code PERMISSIVE} and {@code
 * authenticated-only} have no record of objects at all. Its default therefore had to fall back on
 * reading, and "anyone who may read a view unfiltered may drop it" is what that produced -- one
 * reader destroying the state every other reader depends on (LIFE-040, SX-6). The registry
 * <em>does</em> know who registered each view: its journal has recorded the registrant with every
 * registration since the first one, and a restart registers the view again as them. So the owner
 * is decided here, where it is known, and the policy is asked only for what it can answer -- whether
 * it grants this principal the right over somebody else's view.
 *
 * <p><strong>What counts as a grant.</strong> A policy that implements {@code mayAdminister} itself
 * is taken at its word: the catalogue's ({@code MODIFY} or {@code MANAGE} on the view, ADR-059) and
 * any deployment's own. A policy that inherits the interface's default grants nothing beyond
 * ownership, because that default is the unrestricted-read rule this replaces.
 *
 * <p><strong>{@code pravaha.security.administer=legacy-read}</strong> restores the policy's own
 * answer for every view, for one release, so a deployment whose operators relied on the old rule
 * can move to grants first.
 */
public final class Administration {

    /** The role that administers everything, as it holds every right in the catalogue. */
    public static final String ADMIN_ROLE = "admin";

    /** The setting that chooses the rule. */
    public static final String SETTING = "pravaha.security.administer";

    private Administration() {}

    /** Which rule decides who may administer a registered view. */
    public enum Rule {
        /** Its owner, a principal the policy grants it to, or an admin. The default. */
        OWNERSHIP("ownership"),
        /**
         * The policy's own {@code mayAdminister}, as before ownership was recorded -- by default, anyone
         * whose read of the view carries no row filter. Kept for one release.
         */
        LEGACY_READ("legacy-read");

        private final String setting;

        Rule(String setting) {
            this.setting = setting;
        }

        /** How the rule is written in configuration. */
        public String setting() {
            return setting;
        }

        /**
         * The rule a setting names; empty or null means {@link #OWNERSHIP}.
         *
         * @throws PravahaException {@code PRV-7004} for anything else, naming both values
         */
        public static Rule parse(String text) {
            String value = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                return OWNERSHIP;
            }
            for (Rule rule : values()) {
                if (rule.setting.equals(value)) {
                    return rule;
                }
            }
            throw new PravahaException(
                    SecurityErrors.MISCONFIGURED,
                    SETTING + " is '" + text + "'; it is 'ownership' (a view is administered by its owner, a "
                            + "principal the policy grants it to, or an admin) or 'legacy-read' (anyone who may read "
                            + "it unfiltered, as before; kept for one release)");
        }
    }

    /**
     * May {@code principal} administer {@code view}?
     *
     * @param registered whether a view is registered under the name. A name nothing holds has no owner
     *     to ask about, so the policy answers as it always did -- a permitted caller then meets the
     *     registry's own "no such query", and a stream (which has no owner either) is decided as before
     * @param owner who registered it, when known
     */
    public static AccessDecision decide(
            Rule rule,
            SecurityPolicy policy,
            Principal principal,
            String view,
            boolean registered,
            Optional<Principal> owner) {
        if (rule == Rule.LEGACY_READ || !registered) {
            return policy.mayAdminister(principal, view);
        }
        if (principal.hasRole(ADMIN_ROLE)) {
            return AccessDecision.allow();
        }
        if (owner.isPresent() && owns(owner.get(), principal)) {
            return AccessDecision.allow();
        }
        String needs = "administering a view takes its owner, a grant to administer it, or the " + ADMIN_ROLE + " role";
        if (grantsForItself(policy)) {
            AccessDecision granted = policy.mayAdminister(principal, view);
            return granted.allowed()
                    ? AccessDecision.allow()
                    : AccessDecision.deny(principal.id() + " may not administer '" + view + "': " + needs + ", and "
                            + "the policy says: " + granted.reason());
        }
        // Not said whose it is. The owner's id is shown to a reader who asks for it, and only there.
        return AccessDecision.deny(principal.id() + " may not administer '" + view + "': " + needs + ", and "
                + principal.id() + " holds none of them. Being able to read a view is not a claim on it: "
                + "dropping it destroys the state every other reader depends on.");
    }

    /**
     * Whether {@code owner} and {@code principal} are the same principal: the same id in the same
     * tenant. Anonymous callers are one principal, so on an open node an anonymous registration is
     * administered by anonymous callers, as it was.
     */
    public static boolean owns(Principal owner, Principal principal) {
        return owner.id().equals(principal.id()) && owner.tenant().equals(principal.tenant());
    }

    /**
     * Whether {@code policy} answers {@code mayAdminister} itself rather than inheriting the
     * unrestricted-read default. A lambda cannot, so this is a question about the class.
     */
    static boolean grantsForItself(SecurityPolicy policy) {
        return DECLARES.get(policy.getClass());
    }

    private static final ClassValue<Boolean> DECLARES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("mayAdminister", Principal.class, String.class)
                                .getDeclaringClass()
                        != SecurityPolicy.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    };
}
