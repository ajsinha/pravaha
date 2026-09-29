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
package com.ash.messaging.pravaha.catalog;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.Administration;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.security.ViewNames;

/**
 * The built-in policy that asks the catalogue (ADR-059 §8).
 *
 * <p>Every enforcement point the engine already has asks a {@link SecurityPolicy}, and this answers
 * each from grants the catalogue holds -- so nothing about where checks happen changes, only where the
 * answers come from:
 *
 * <table>
 *   <caption>What each question asks of the catalogue</caption>
 *   <tr><th>Question</th><th>Privilege</th><th>On</th></tr>
 *   <tr><td>{@code mayRead}</td><td>SELECT</td><td>the view or stream</td></tr>
 *   <tr><td>{@code maySubscribe}</td><td>SUBSCRIBE</td><td>the view</td></tr>
 *   <tr><td>{@code mayBuildOn}</td><td>BUILD_ON</td><td>each input a registration names</td></tr>
 *   <tr><td>{@code mayRegisterQuery}</td><td>CREATE</td><td>the caller's {@code <tenant>.default}</td></tr>
 *   <tr><td>{@code mayAdminister}</td><td>MODIFY or MANAGE</td><td>the view</td></tr>
 *   <tr><td>{@code mayWriteTo}</td><td>WRITE</td><td>the sink</td></tr>
 *   <tr><td>{@code mayReadAudit}</td><td>MANAGE</td><td>the catalogue ({@code *}), or an audit-reader role</td></tr>
 * </table>
 *
 * <p>A registration makes its registrant the owner of the new view ({@link #registered}); a drop forgets
 * it and its grants ({@link #dropped}). Row filters and masks are catalogue objects ({@link PolicyService})
 * and reach every enforcement point through {@link #narrowing}.
 */
public final class CatalogPolicy implements SecurityPolicy {

    private final CatalogService service;
    private final CatalogAccess access;
    private final Set<String> auditRoles;

    /**
     * @param auditRoles roles whose holders may read the audit trail besides {@code admin} and those
     *     holding {@code MANAGE} on the catalogue ({@code pravaha.security.audit-readers})
     */
    public CatalogPolicy(CatalogService service, Collection<String> auditRoles) {
        this.service = service;
        this.access = service.access();
        this.auditRoles = auditRoles == null
                ? Set.of()
                : auditRoles.stream()
                        .filter(r -> r != null && !r.isBlank())
                        .map(String::strip)
                        .collect(Collectors.toUnmodifiableSet());
    }

    /** The service the catalogue statements and endpoints run through. */
    public CatalogService service() {
        return service;
    }

    @Override
    public AccessDecision mayRead(Principal principal, String view) {
        return decide(principal, Privilege.SELECT, target(principal, view), "read '" + view + "'");
    }

    /**
     * The row filters and masks bound to {@code object} -- directly or through one of its tags -- that
     * apply to {@code principal} (ADR-059 §4), bound to their session. Cached until the catalogue changes.
     */
    @Override
    public Narrowing narrowing(Principal principal, String object) {
        return service.policies().narrowing(principal, target(principal, object));
    }

    @Override
    public AccessDecision maySubscribe(Principal principal, String view) {
        return decide(principal, Privilege.SUBSCRIBE, target(principal, view), "subscribe to '" + view + "'");
    }

    @Override
    public AccessDecision mayBuildOn(Principal principal, String input) {
        return decide(principal, Privilege.BUILD_ON, target(principal, input), "build a query on '" + input + "'");
    }

    /**
     * Allowed: a source reached only through an upstream view is not asked again. The registrant
     * holds {@code BUILD_ON} on the view, and the view's owner held it on the source when the view was
     * built -- reading a view needs the view's grant and not its sources', which is the point of a view
     * (ADR-059 §2). What a source's policy narrows is carried into the view by the planner (phase 2).
     */
    @Override
    public AccessDecision mayBuildThrough(Principal principal, String source) {
        return AccessDecision.allow();
    }

    /**
     * Allowed: reading a view needs {@code SELECT} on the view and not on the streams behind it -- that
     * is the point of a view (ADR-059 §2) -- and the view's owner needed {@code BUILD_ON} on each of them
     * to register it, which is where SX-11's "a view named for one thing reading another" is now refused.
     */
    @Override
    public AccessDecision mayReadThrough(Principal principal, String source) {
        return AccessDecision.allow();
    }

    @Override
    public AccessDecision mayRegisterQuery(Principal principal) {
        String namespace = CatalogNames.defaultNamespaceOf(principal.tenant());
        return decide(principal, Privilege.CREATE, namespace, "register a query in " + namespace);
    }

    /**
     * {@code CREATE} on the caller's default namespace -- or, for a name the catalogue already records
     * as a view the caller may modify, that: the journal bringing a query back at start re-registers it
     * as its owner, and an owner does not need {@code CREATE} to keep what they already made.
     */
    @Override
    public AccessDecision mayRegisterQuery(Principal principal, String name) {
        // The engine name (ADR-060): the view's catalogue name is its bare name in its tenant's default.
        String wouldBe = ViewNames.catalogueName(name);
        Optional<CatalogObject> taken = service.catalog().object(wouldBe);
        if (taken.isPresent() && taken.get().kind() == ObjectKind.ALERT) {
            // One name, one object: the view would land on the alert's catalogue name (ADR-057).
            return AccessDecision.deny("'" + wouldBe + "' is an alert's name; a query cannot be registered as it");
        }
        // Recorded under its engine name, or -- registered before names were per tenant -- its bare one.
        Optional<CatalogObject> existing = service.catalog()
                .byEngineName(ObjectKind.VIEW, name)
                .or(() -> taken.filter(o -> o.kind() == ObjectKind.VIEW));
        if (existing.isPresent()
                && access.check(principal, Privilege.MODIFY, existing.get().fullName())
                        .allowed()) {
            return AccessDecision.allow();
        }
        return mayRegisterQuery(principal);
    }

    /** Pausing, resuming, replacing and dropping: {@code MODIFY}, or {@code MANAGE}, on the view. */
    @Override
    public AccessDecision mayAdminister(Principal principal, String view) {
        String target = target(principal, view);
        CatalogAccess.Verdict modify = access.check(principal, Privilege.MODIFY, target);
        if (modify.allowed()) {
            access.statistics().decided(Privilege.MODIFY, true);
            return AccessDecision.allow();
        }
        CatalogAccess.Verdict manage = access.check(principal, Privilege.MANAGE, target);
        access.statistics().decided(Privilege.MODIFY, manage.allowed());
        return manage.allowed()
                ? AccessDecision.allow()
                : AccessDecision.deny(principal.id() + " may not administer '" + view + "': " + modify.via());
    }

    @Override
    public AccessDecision mayWriteTo(Principal principal, String sink) {
        String target = CatalogNames.object(CatalogNames.infrastructureNamespace(ObjectKind.SINK), sink);
        return decide(principal, Privilege.WRITE, target, "write to the sink '" + sink + "'");
    }

    @Override
    public AccessDecision mayReadAudit(Principal principal) {
        if (principal == null || principal.isAnonymous()) {
            if (principal != null
                    && access.check(principal, Privilege.MANAGE, CatalogNames.ROOT)
                            .allowed()) {
                return AccessDecision.allow();
            }
            return AccessDecision.deny("the audit trail is served only to authenticated callers");
        }
        for (String role : auditRoles) {
            if (principal.hasRole(role)) {
                return AccessDecision.allow();
            }
        }
        CatalogAccess.Verdict manage = access.check(principal, Privilege.MANAGE, CatalogNames.ROOT);
        return manage.allowed()
                ? AccessDecision.allow()
                : AccessDecision.deny("reading the audit trail needs the admin role, one of "
                        + new java.util.TreeSet<>(auditRoles) + ", or MANAGE on the catalogue");
    }

    /**
     * Records the registrant as the new view's owner. A catalogue journal that cannot be written is
     * logged rather than thrown: the registry has journalled the registration already, so throwing would
     * leave a query that comes back at restart -- when this is asked again and records it then.
     */
    @Override
    public void registered(Principal owner, String view) {
        try {
            service.catalog().registerView(view, owner);
        } catch (PravahaException e) {
            LOG.log(System.Logger.Level.ERROR, "the catalogue could not record '" + view + "': " + e.getMessage());
        }
    }

    /** Forgets a dropped view and its grants; a failure is logged and repaired at the next start. */
    @Override
    public void dropped(String view) {
        try {
            service.catalog().dropView(view);
        } catch (PravahaException e) {
            LOG.log(System.Logger.Level.ERROR, "the catalogue could not forget '" + view + "': " + e.getMessage());
        }
    }

    private static final System.Logger LOG = System.getLogger(CatalogPolicy.class.getName());

    // ------------------------------------------------------------------------ alerts (ADR-057)

    /** {@code WRITE} on a notifier channel: what naming it in {@code NOTIFY} needs. */
    public AccessDecision mayNotify(Principal principal, String channel) {
        String target = CatalogNames.object(CatalogNames.infrastructureNamespace(ObjectKind.NOTIFIER), channel);
        return decide(principal, Privilege.WRITE, target, "notify through the channel '" + channel + "'");
    }

    /**
     * {@code privilege} on an alert: {@code SELECT} to see it and its state, {@code MODIFY} to pause,
     * resume, snooze and acknowledge it, {@code MANAGE} to change or drop it. Whoever holds {@code
     * MANAGE} holds {@code MODIFY} too, as for a view.
     */
    public AccessDecision mayOnAlert(Principal principal, Privilege privilege, String alert) {
        // By engine name, as a view is (ADR-060): tenant.default.name outside the default tenant.
        Optional<CatalogObject> object = service.catalog().byEngineName(ObjectKind.ALERT, alert);
        String target = object.map(CatalogObject::fullName)
                .orElse(
                        ViewNames.tenantOf(alert).equals(ViewNames.DEFAULT_TENANT)
                                ? CatalogNames.defaultNamespaceOf(principal.tenant()) + "." + alert
                                : ViewNames.catalogueName(alert));
        if (privilege == Privilege.MODIFY
                && access.check(principal, Privilege.MANAGE, target).allowed()) {
            return AccessDecision.allow();
        }
        return decide(principal, privilege, target, "hold " + privilege.name() + " on the alert '" + alert + "'");
    }

    /** Records a new alert, by its engine name, owned by its creator, in the creator's default namespace. */
    public void alertCreated(Principal owner, String alert) {
        service.catalog().registerObject(ObjectKind.ALERT, alert, owner);
    }

    /** Forgets a dropped alert and its grants; a failure is logged and repaired at the next start. */
    public void alertDropped(String alert) {
        try {
            service.catalog().dropObject(ObjectKind.ALERT, alert);
        } catch (PravahaException e) {
            LOG.log(
                    System.Logger.Level.ERROR,
                    "the catalogue could not forget the alert '" + alert + "': " + e.getMessage());
        }
    }

    /**
     * The catalogue name an engine name stands for, for {@code principal}: a recorded view, a recorded
     * or configured stream, or -- for a name nothing records, such as one about to be refused as
     * unknown -- the name it would have in its tenant's default namespace, whose grants then decide. An
     * engine name is resolved in the caller's tenant already (ADR-060), so that is the caller's own
     * default namespace unless an admin named another tenant.
     */
    public String target(Principal principal, String engineName) {
        Catalog catalog = service.catalog();
        Optional<CatalogObject> view = catalog.byEngineName(ObjectKind.VIEW, engineName);
        if (view.isPresent()) {
            return view.get().fullName();
        }
        for (ObjectKind kind : List.of(ObjectKind.STREAM, ObjectKind.LOOKUP)) {
            Optional<CatalogObject> known = catalog.byEngineName(kind, engineName);
            if (known.isPresent()) {
                return known.get().fullName();
            }
            if (catalog.configured(kind, engineName)) {
                return CatalogNames.infrastructureNamespace(kind) + "." + engineName;
            }
        }
        return ViewNames.tenantOf(engineName).equals(ViewNames.DEFAULT_TENANT)
                ? CatalogNames.defaultNamespaceOf(principal.tenant()) + "." + engineName
                : ViewNames.catalogueName(engineName);
    }

    private AccessDecision decide(Principal principal, Privilege privilege, String target, String what) {
        CatalogAccess.Verdict verdict = access.check(principal, privilege, target);
        access.statistics().decided(privilege, verdict.allowed());
        return verdict.allowed()
                ? AccessDecision.allow()
                : AccessDecision.deny(principal.id() + " may not " + what + ": " + verdict.via());
    }

    /**
     * The grants a shipped policy means, for importing it once (ADR-059's migration).
     *
     * <ul>
     *   <li>{@code permissive}: every privilege on the whole catalogue to {@code ROLE public} -- every
     *       caller, anonymous included -- which is exactly what the policy did.
     *   <li>{@code authenticated}: using, reading, subscribing, building, registering, writing and
     *       administering, on the whole catalogue, to {@code ROLE authenticated} -- what
     *       {@code AuthenticatedOnlyPolicy} allowed a verified caller. Changing grants and reading the
     *       audit trail stay with {@code admin} and the configured audit readers, as before.
     * </ul>
     *
     * <p>Under {@code pravaha.security.administer=ownership} (the default) {@code authenticated} is
     * imported without {@code MODIFY}: that grant was the policy's administer rule -- anyone who may read
     * may drop -- written down, and it is the rule ownership replaces. A view is then administered by its
     * owner, a principal granted {@code MODIFY} or {@code MANAGE} on it, or an admin. {@code permissive}
     * is imported whole either way: it makes every caller a manager of the catalogue, and a principal who
     * may grant themselves {@code MODIFY} is not one that withholding it restricts.
     */
    public static List<Grant> importedGrants(String policy, String by, Instant at) {
        return importedGrants(policy, by, at, Administration.Rule.OWNERSHIP);
    }

    /** As {@link #importedGrants(String, String, Instant)}, under the given administer rule. */
    public static List<Grant> importedGrants(String policy, String by, Instant at, Administration.Rule administer) {
        String canonical = policy == null ? "" : policy.strip().toLowerCase(Locale.ROOT);
        boolean byReading = administer == Administration.Rule.LEGACY_READ;
        return switch (canonical) {
            case "permissive" ->
                EnumSet.complementOf(EnumSet.of(Privilege.OWN)).stream()
                        .map(p -> new Grant(CatalogNames.ROOT, p, Grantee.role(Grantee.PUBLIC), by, at))
                        .toList();
            case "authenticated", "authenticated-only" ->
                EnumSet.of(
                                Privilege.USE,
                                Privilege.SELECT,
                                Privilege.SUBSCRIBE,
                                Privilege.BUILD_ON,
                                Privilege.CREATE,
                                Privilege.WRITE,
                                Privilege.MODIFY)
                        .stream()
                        .filter(p -> byReading || p != Privilege.MODIFY)
                        .map(p -> new Grant(CatalogNames.ROOT, p, Grantee.role(Grantee.AUTHENTICATED), by, at))
                        .toList();
            default ->
                throw new PravahaException(
                        CatalogErrors.INVALID_REQUEST,
                        "there is no shipped policy called '" + policy + "' to import into the catalogue");
        };
    }

    @Override
    public String toString() {
        return "catalog";
    }
}
