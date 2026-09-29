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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.Administration;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;

/**
 * Who owns each registered name, and so who may administer it: drop, pause, resume, replace, debug,
 * replay a dead letter into it.
 *
 * <p>The owner is the principal who registered the name, or who replaced it last -- the new version
 * runs on the replacer's authority, and the journal has always recorded the cutover as theirs. Kept
 * per name, because a shared computation's names were registered by different principals.
 *
 * <p><strong>It survives a restart without a journal of its own.</strong> Every registration the
 * journal holds records its registrant, and has since the journal was written; recovery registers
 * each one again as that principal, which records the owner here as a new registration does. There is
 * therefore no view registered before ownership was enforced that comes back without an owner.
 *
 * <p>The decision itself is {@link Administration}'s, which every surface reaches through {@link
 * QueryRegistry#owners()} rather than asking the policy: the policy is asked about a name and has no
 * record of who registered it.
 */
public final class QueryOwners {

    private final SecurityPolicy policy;
    private final Map<String, Principal> owners = new ConcurrentHashMap<>();
    private volatile Administration.Rule rule = Administration.Rule.OWNERSHIP;

    QueryOwners(SecurityPolicy policy) {
        this.policy = policy == null ? SecurityPolicy.PERMISSIVE : policy;
    }

    /** The rule in force ({@code pravaha.security.administer}): {@link Administration.Rule#OWNERSHIP} by default. */
    public QueryOwners administering(Administration.Rule rule) {
        this.rule = Objects.requireNonNull(rule, "rule");
        return this;
    }

    public Administration.Rule rule() {
        return rule;
    }

    /** Who registered {@code name}, or replaced it last; empty when nothing is registered under it. */
    public Optional<Principal> ownerOf(String name) {
        return Optional.ofNullable(owners.get(name));
    }

    /**
     * May {@code principal} administer {@code view}? Its owner, a principal the policy grants it to, or
     * an admin; under {@code legacy-read}, the policy's own answer. A name nothing holds is the policy's
     * to answer, so a permitted caller then meets the registry's "no such query".
     */
    public AccessDecision mayAdminister(Principal principal, String view) {
        Principal owner = owners.get(view);
        return Administration.decide(rule, policy, principal, view, owner != null, Optional.ofNullable(owner));
    }

    /** A registration was recorded -- a new one, a second name on a shared computation, or a replay. */
    void registered(Principal owner, String name) {
        owners.put(name, owner);
    }

    /** A cutover or a rollback gave the name to another version, and so to whoever that version is by. */
    void transferred(String name, Principal owner) {
        if (owner != null && owners.containsKey(name)) {
            owners.put(name, owner);
        }
    }

    void dropped(String name) {
        owners.remove(name);
    }
}
