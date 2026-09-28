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
package com.ash.messaging.pravaha.registry.alert;

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.catalog.CatalogPolicy;
import com.ash.messaging.pravaha.catalog.Privilege;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;

/**
 * Who may do what to an alert (ADR-057), decided once for SQL, REST, the CLI and the console.
 *
 * <p><strong>Under the catalogue</strong> (ADR-059) an alert is an object of kind {@code ALERT}, owned by
 * its creator in their tenant's {@code default} namespace: {@code SELECT} on it to see it and its state,
 * {@code MODIFY} to pause, resume, snooze and acknowledge, {@code MANAGE} to alter or drop. Creating one
 * needs {@code CREATE} where it lands, {@code SELECT} on the view it watches -- an alert shows its
 * receivers the view's rows -- and {@code WRITE} on every channel it names.
 *
 * <p><strong>Under any other policy</strong> there are no grants on an alert to consult, so its view's
 * stand in: seeing it needs {@code mayRead} on the view, changing it needs being its creator or {@code
 * mayAdminister} on the view, and a channel is asked as a sink would be ({@code mayWriteTo}). Either
 * way an alert is never visible outside its tenant (ADR-050).
 *
 * <p>An alert the caller may not see is answered exactly as one that does not exist ({@code PRV-8040});
 * one they may see and not change is refused by name ({@code PRV-7002}). Every decision is audited.
 */
final class AlertAccess {

    private final SecurityPolicy policy;
    private final AuditSink audit;

    AlertAccess(SecurityPolicy policy, AuditSink audit) {
        this.policy = policy == null ? SecurityPolicy.PERMISSIVE : policy;
        this.audit = audit == null ? AuditSink.NONE : audit;
    }

    SecurityPolicy policy() {
        return policy;
    }

    /** Refuses a {@code CREATE ALERT} the caller may not make. */
    void requireCreate(Principal principal, String alert, String view, List<String> channels) {
        AccessDecision create = policy.mayRegisterQuery(principal);
        require(principal, "alert.create", alert, create, "create an alert");
        AccessDecision read = policy.mayRead(principal, view);
        if (read.allowed() && read.rowFilter().isPresent()) {
            // The alert would send its receivers rows its creator is not shown.
            read = AccessDecision.deny("'" + view + "' is read through a row filter ("
                    + read.rowFilter().get() + "), and an alert on it would send whole rows to its channels");
        }
        require(principal, "alert.create", view, read, "alert on '" + view + "'");
        requireChannels(principal, alert, channels);
    }

    void requireChannels(Principal principal, String alert, List<String> channels) {
        for (String channel : channels) {
            AccessDecision write = policy instanceof CatalogPolicy catalog
                    ? catalog.mayNotify(principal, channel)
                    : policy.mayWriteTo(principal, channel);
            require(principal, "alert.notify", channel, write, "notify through '" + channel + "'");
        }
    }

    /** Whether the caller may see the alert at all. Not audited: a listing asks it of every alert. */
    boolean maySee(Principal principal, AlertDefinition alert) {
        return decide(principal, alert, Privilege.SELECT).allowed();
    }

    /**
     * Refuses {@code verb} unless the caller holds {@code privilege} on the alert: {@code PRV-8040} when
     * they may not even see it, {@code PRV-7002} when they may see it and not do this.
     */
    void require(Principal principal, AlertDefinition alert, Privilege privilege, String verb) {
        AccessDecision see = decide(principal, alert, Privilege.SELECT);
        if (!see.allowed()) {
            audit.record(AuditEvent.of(principal, "alert." + verb, alert.name(), see, ""));
            throw AlertService.noSuch(alert.name());
        }
        if (privilege == Privilege.SELECT) {
            audit.record(AuditEvent.of(principal, "alert." + verb, alert.name(), see, ""));
            return;
        }
        AccessDecision decision = decide(principal, alert, privilege);
        require(principal, "alert." + verb, alert.name(), decision, verb + " the alert '" + alert.name() + "'");
    }

    private AccessDecision decide(Principal principal, AlertDefinition alert, Privilege privilege) {
        if (!principal.tenant().equals(alert.tenant()) && !principal.hasRole("admin")) {
            return AccessDecision.deny("the alert belongs to another tenant");
        }
        if (policy instanceof CatalogPolicy catalog) {
            return catalog.mayOnAlert(principal, privilege, alert.name());
        }
        if (privilege == Privilege.SELECT) {
            return policy.mayRead(principal, alert.view());
        }
        if (principal.id().equals(alert.owner())) {
            return AccessDecision.allow();
        }
        return policy.mayAdminister(principal, alert.view());
    }

    private void require(Principal principal, String action, String target, AccessDecision decision, String what) {
        audit.record(AuditEvent.of(principal, action, target, decision, ""));
        if (!decision.allowed()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN, principal.id() + " may not " + what + ": " + decision.reason());
        }
    }
}
