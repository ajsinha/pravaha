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

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;

/**
 * Every question and change the catalogue answers, with its rules, written once -- so the SQL
 * statements, the REST endpoints, the CLI and the console decide identically (ADR-059 §3).
 *
 * <p>Every change is authorized here, recorded as an {@link AuditEvent} whatever it answers, and
 * journalled by {@link Catalog} before it is acknowledged. A name the caller may not see is answered
 * exactly as a name that does not exist ({@code PRV-7031}), so no answer here is an oracle for what
 * exists.
 */
public final class CatalogService {

    /**
     * One privilege's answer for one principal on one object.
     *
     * @param via every grant, ownership or rule that gives it; empty when refused
     * @param refusal why it is refused, or empty when held
     */
    public record AccessLine(Privilege privilege, boolean allowed, List<String> via, String refusal) {}

    /** What {@code SHOW EFFECTIVE ACCESS} answers. */
    public record EffectiveAccess(String object, String user, List<AccessLine> lines) {}

    private final Catalog catalog;
    private final CatalogAccess access;
    private final AuditSink audit;
    private volatile Function<String, Optional<Principal>> users = id -> Optional.empty();

    public CatalogService(Catalog catalog, AuditSink audit) {
        this.catalog = catalog;
        this.access = new CatalogAccess(catalog);
        this.audit = audit == null ? AuditSink.NONE : audit;
    }

    public Catalog catalog() {
        return catalog;
    }

    public CatalogAccess access() {
        return access;
    }

    /**
     * How a user id becomes the principal it signs in as -- roles and tenant -- for {@code SHOW
     * EFFECTIVE ACCESS FOR USER}. The node supplies its identity store or token table.
     */
    public void resolvingUsersWith(Function<String, Optional<Principal>> resolver) {
        this.users = resolver == null ? id -> Optional.empty() : resolver;
    }

    // ------------------------------------------------------------------------- name resolution

    /** The object a statement's target names for {@code caller}, or {@code PRV-7031}. */
    public CatalogObject resolve(Principal caller, CatalogStatement.Target target) {
        List<String> parts = target.parts();
        Optional<CatalogObject> found =
                switch (target.kind()) {
                    case "CATALOG" -> catalog.object(CatalogNames.ROOT);
                    case "TENANT" -> {
                        String tenant = CatalogNames.part(parts.get(0), "a tenant");
                        Optional<CatalogObject> known = catalog.object(tenant);
                        // A tenant's root is recorded the first time somebody entitled to it names it.
                        yield known.isPresent()
                                        || !(caller.hasRole(Catalog.ADMIN_ROLE) || tenant.equals(caller.tenant()))
                                ? known
                                : Optional.of(catalog.ensureNamespace(tenant));
                    }
                    case "NAMESPACE" ->
                        catalog.object(
                                parts.size() == 1
                                        ? CatalogNames.namespace(caller.tenant(), parts.get(0))
                                        : CatalogNames.namespace(parts.get(0), parts.get(1)));
                    case "VIEW" -> resolveView(caller, parts);
                    default -> resolveInfrastructure(ObjectKind.named(target.kind()), parts);
                };
        CatalogObject object = found.filter(o -> visible(caller, o)).orElseThrow(() -> noSuch(target.written()));
        if (!target.kind().equals("CATALOG")
                && !target.kind().equals("TENANT")
                && !object.kind().name().equals(target.kind())) {
            throw noSuch(target.written());
        }
        return object;
    }

    /**
     * An object named the way a person types it on a command line or in a URL: a full name
     * ({@code acme.sales.revenue}, {@code acme.sales}, {@code *}), a view as {@code ns.name} or
     * {@code name}, a namespace as {@code ns}, or a stream, sink, source or lookup by its name.
     */
    public CatalogObject resolveWritten(Principal caller, String written) {
        String name = written == null ? "" : written.strip();
        Optional<CatalogObject> exact = catalog.object(name);
        if (exact.isPresent() && visible(caller, exact.get())) {
            return exact.get();
        }
        List<String> parts = List.of(name.split("\\.", -1));
        List<Optional<CatalogObject>> candidates = new ArrayList<>();
        if (parts.size() <= 3 && parts.stream().noneMatch(String::isBlank)) {
            candidates.add(resolveView(caller, parts));
            if (parts.size() == 1) {
                candidates.add(catalog.object(caller.tenant() + "." + parts.get(0)));
                for (ObjectKind kind :
                        List.of(ObjectKind.STREAM, ObjectKind.SINK, ObjectKind.SOURCE, ObjectKind.LOOKUP)) {
                    candidates.add(resolveInfrastructure(kind, parts));
                }
            }
        }
        for (Optional<CatalogObject> candidate : candidates) {
            if (candidate.isPresent() && visible(caller, candidate.get())) {
                return candidate.get();
            }
        }
        throw noSuch(name);
    }

    private Optional<CatalogObject> resolveView(Principal caller, List<String> parts) {
        return switch (parts.size()) {
            case 1 -> catalog.byEngineName(ObjectKind.VIEW, parts.get(0));
            case 2 -> catalog.object(caller.tenant() + "." + parts.get(0) + "." + parts.get(1));
            default -> catalog.object(String.join(".", parts));
        };
    }

    private Optional<CatalogObject> resolveInfrastructure(ObjectKind kind, List<String> parts) {
        if (!CatalogNames.isInfrastructure(kind)) {
            return Optional.empty();
        }
        if (parts.size() == 3) {
            return catalog.object(String.join(".", parts));
        }
        if (parts.size() != 1) {
            return Optional.empty();
        }
        Optional<CatalogObject> known = catalog.byEngineName(kind, parts.get(0));
        if (known.isPresent()) {
            return known;
        }
        return catalog.configured(kind, parts.get(0))
                ? Optional.of(catalog.ensureInfrastructure(kind, parts.get(0)))
                : Optional.empty();
    }

    /**
     * Whether {@code caller} may learn that {@code object} exists: they may use the namespace holding
     * it, or hold anything on it. The root is visible to everyone; a tenant to its own principals.
     */
    public boolean visible(Principal caller, CatalogObject object) {
        if (caller.hasRole(Catalog.ADMIN_ROLE)) {
            return true;
        }
        String name = object.fullName();
        return switch (CatalogNames.depth(name)) {
            case 0 -> true;
            case 1 -> name.equals(caller.tenant()) || name.equals(CatalogNames.NODE) || holdsAnything(caller, object);
            case 2 -> access.check(caller, Privilege.USE, name).allowed() || holdsAnything(caller, object);
            default -> access.check(caller, Privilege.USE, object.parent()).allowed() || holdsAnything(caller, object);
        };
    }

    private boolean holdsAnything(Principal caller, CatalogObject object) {
        for (Privilege privilege : object.kind().applicable()) {
            if (!access.reasons(caller, privilege, object.fullName()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------- reads

    /** Every object {@code caller} may see, optionally within one namespace or of one kind. */
    public List<CatalogObject> objects(Principal caller, String namespace, String kind) {
        ObjectKind wanted = kind == null || kind.isBlank() ? null : ObjectKind.named(kind);
        return catalog.objects().stream()
                .filter(o -> namespace == null || namespace.isBlank() || namespace.equals(o.parent()))
                .filter(o -> wanted == null || o.kind() == wanted)
                .filter(o -> visible(caller, o))
                .toList();
    }

    /** The namespaces {@code caller} may use. */
    public List<CatalogObject> namespaces(Principal caller) {
        return catalog.objects().stream()
                .filter(o -> o.kind() == ObjectKind.NAMESPACE && CatalogNames.depth(o.fullName()) == 2)
                .filter(o -> visible(caller, o))
                .toList();
    }

    /**
     * Objects whose name, description, owner or a tag key or value contains {@code text}, ignoring
     * case -- only those {@code caller} may see (ADR-059 §10: a search never confirms that an object
     * you may not see exists).
     */
    public List<CatalogObject> search(Principal caller, String text) {
        String needle = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return catalog.objects().stream()
                .filter(o -> CatalogNames.depth(o.fullName()) > 0)
                .filter(o -> needle.isEmpty() || matches(o, needle))
                .filter(o -> visible(caller, o))
                .toList();
    }

    private static boolean matches(CatalogObject o, String needle) {
        if (o.fullName().toLowerCase(Locale.ROOT).contains(needle)
                || o.description().toLowerCase(Locale.ROOT).contains(needle)
                || o.owner().name().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (Map.Entry<String, String> tag : o.tags().entrySet()) {
            if (tag.getKey().toLowerCase(Locale.ROOT).contains(needle)
                    || tag.getValue().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The grants made on {@code object}: all of them to a caller who may manage it, and otherwise only
     * those that reach the caller -- what somebody else was granted is the manager's business.
     */
    public List<Grant> grantsOn(Principal caller, CatalogObject object) {
        boolean manager =
                access.check(caller, Privilege.MANAGE, object.fullName()).allowed();
        return catalog.grantsOn(object.fullName()).stream()
                .filter(g -> manager || g.grantee().covers(caller))
                .toList();
    }

    /**
     * The grants made to {@code grantee}: all of them when the caller is that grantee (or holds that
     * role) or is an administrator, and otherwise only those on objects the caller may manage.
     */
    public List<Grant> grantsTo(Principal caller, Grantee grantee) {
        boolean self = caller.hasRole(Catalog.ADMIN_ROLE) || grantee.covers(caller);
        return catalog.grants().stream()
                .filter(g -> g.grantee().equals(grantee))
                .filter(g -> self
                        || access.check(caller, Privilege.MANAGE, g.object()).allowed())
                .toList();
    }

    /**
     * What {@code user} may do to {@code object}, privilege by privilege, and through which grant,
     * ownership, role or namespace. Asked by the user themselves or by whoever may manage the object.
     */
    public EffectiveAccess effectiveAccess(Principal caller, String user, CatalogObject object) {
        boolean self = !caller.isAnonymous() && caller.id().equals(user);
        AccessDecision decision = self
                        || access.check(caller, Privilege.MANAGE, object.fullName())
                                .allowed()
                ? AccessDecision.allow()
                : AccessDecision.deny(caller.id() + " may ask what they themselves may do, or what anyone may do to "
                        + "an object they manage; " + object.fullName() + " is not one they manage");
        audit.record(AuditEvent.of(caller, "catalog.access", object.fullName(), decision, "user=" + user));
        if (!decision.allowed()) {
            throw new PravahaException(CatalogErrors.MANAGE_REQUIRED, decision.reason());
        }
        Principal subject = self
                ? caller
                : users.apply(user)
                        .orElseThrow(() -> new PravahaException(
                                CatalogErrors.INVALID_REQUEST,
                                "there is no user '" + user
                                        + "' this node knows of, so what they may do cannot be said"));
        List<AccessLine> lines = new ArrayList<>();
        for (Privilege privilege : object.kind().applicable()) {
            CatalogAccess.Verdict verdict = access.check(subject, privilege, object.fullName());
            lines.add(new AccessLine(
                    privilege,
                    verdict.allowed(),
                    verdict.allowed() ? access.reasons(subject, privilege, object.fullName()) : List.of(),
                    verdict.allowed() ? "" : verdict.via()));
        }
        return new EffectiveAccess(object.fullName(), subject.id(), lines);
    }

    // -------------------------------------------------------------------------------- changes

    /** {@code CREATE NAMESPACE}: needs {@code CREATE} on the tenant (the {@code admin} role holds it). */
    public CatalogObject createNamespace(
            Principal caller, List<String> parts, boolean ifNotExists, String description) {
        String name = parts.size() == 1
                ? CatalogNames.namespace(caller.tenant(), parts.get(0))
                : CatalogNames.namespace(parts.get(0), parts.get(1));
        String tenant = CatalogNames.tenantOf(name);
        if (tenant.equals(CatalogNames.NODE)) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "'node' holds what this node is configured with and takes no namespaces of its own");
        }
        CatalogAccess.Verdict verdict = access.check(caller, Privilege.CREATE, tenant);
        record(caller, "catalog.namespace.create", name, verdict, description);
        if (!verdict.allowed()) {
            throw new PravahaException(
                    CatalogErrors.MANAGE_REQUIRED,
                    caller.id() + " may not create a namespace in the tenant '" + tenant + "': it needs CREATE on "
                            + "TENANT " + tenant + " (" + verdict.via() + ")");
        }
        return catalog.createNamespace(
                name, callerAsOwner(caller), description == null ? "" : description, ifNotExists, caller.id());
    }

    /**
     * Grants each privilege; {@code privileges} empty means every one that applies to the object. Needs
     * {@code MANAGE} on the object. {@code OWN} is not granted: it is transferred with {@code OWNER TO}.
     */
    public List<Grant> grant(Principal caller, CatalogObject object, Set<Privilege> privileges, Grantee grantee) {
        Set<Privilege> wanted = applicable(object, privileges);
        requireManage(caller, object, "catalog.grant", wanted + " to " + grantee);
        List<Grant> made = new ArrayList<>();
        for (Privilege privilege : wanted) {
            made.add(catalog.grant(object.fullName(), privilege, grantee, caller.id()));
        }
        return made;
    }

    /** Revokes each privilege; whether any was held. Needs {@code MANAGE}. */
    public boolean revoke(Principal caller, CatalogObject object, Set<Privilege> privileges, Grantee grantee) {
        Set<Privilege> wanted = applicable(object, privileges);
        requireManage(caller, object, "catalog.revoke", wanted + " from " + grantee);
        boolean any = false;
        for (Privilege privilege : wanted) {
            any |= catalog.revoke(object.fullName(), privilege, grantee);
        }
        return any;
    }

    public CatalogObject comment(Principal caller, CatalogObject object, String description) {
        requireManage(caller, object, "catalog.comment", description);
        return catalog.comment(object.fullName(), description == null ? "" : description, caller.id());
    }

    public CatalogObject setTags(Principal caller, CatalogObject object, Map<String, String> tags) {
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            requireTag(tag.getKey());
            if (tag.getValue() == null || tag.getValue().length() > 256) {
                throw new PravahaException(
                        CatalogErrors.INVALID_REQUEST, "a tag's value is at most 256 characters: " + tag.getKey());
            }
        }
        requireManage(caller, object, "catalog.tags.set", tags.toString());
        return catalog.setTags(object.fullName(), tags, caller.id());
    }

    public CatalogObject unsetTags(Principal caller, CatalogObject object, Collection<String> keys) {
        requireManage(caller, object, "catalog.tags.unset", keys.toString());
        return catalog.unsetTags(object.fullName(), keys, caller.id());
    }

    /** {@code OWNER TO}: only the owner (or an owner above it, or {@code admin}) may give an object away. */
    public CatalogObject setOwner(Principal caller, CatalogObject object, Grantee owner) {
        CatalogAccess.Verdict verdict = access.check(caller, Privilege.OWN, object.fullName());
        record(caller, "catalog.owner", object.fullName(), verdict, "to " + owner);
        if (!verdict.allowed()) {
            throw new PravahaException(
                    CatalogErrors.MANAGE_REQUIRED,
                    caller.id() + " may not change who owns " + object.fullName() + ": only its owner may give it "
                            + "away (" + verdict.via() + ")");
        }
        return catalog.setOwner(object.fullName(), owner, caller.id());
    }

    /** Moves a view into a namespace: {@code MANAGE} on the view and {@code CREATE} on the namespace. */
    public CatalogObject move(Principal caller, CatalogObject view, List<String> namespaceParts) {
        String namespace = namespaceParts.size() == 1
                ? CatalogNames.namespace(view.tenant(), namespaceParts.get(0))
                : CatalogNames.namespace(namespaceParts.get(0), namespaceParts.get(1));
        requireManage(caller, view, "catalog.move", "to " + namespace);
        CatalogAccess.Verdict create = access.check(caller, Privilege.CREATE, namespace);
        record(caller, "catalog.move:create", namespace, create, view.fullName());
        if (!create.allowed()) {
            throw new PravahaException(
                    CatalogErrors.MANAGE_REQUIRED,
                    caller.id() + " may not move " + view.fullName() + " into " + namespace + ": that needs CREATE "
                            + "on the namespace (" + create.via() + ")");
        }
        return catalog.move(view.fullName(), namespace, caller.id());
    }

    // ------------------------------------------------------------------------------ internals

    private Set<Privilege> applicable(CatalogObject object, Set<Privilege> privileges) {
        Set<Privilege> applicable = object.kind().applicable();
        if (privileges == null || privileges.isEmpty()) {
            Set<Privilege> all = EnumSet.copyOf(applicable);
            all.remove(Privilege.OWN);
            return all;
        }
        if (privileges.contains(Privilege.OWN)) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "OWN is not granted: an object has exactly one owner, changed with ALTER " + object.kind()
                            + " ... OWNER TO ROLE|USER <name>");
        }
        for (Privilege privilege : privileges) {
            if (!applicable.contains(privilege)) {
                throw new PravahaException(
                        CatalogErrors.PRIVILEGE_NOT_APPLICABLE,
                        privilege + " means nothing on a " + object.kind() + " (" + object.fullName()
                                + "); what applies " + "there is " + applicable);
            }
        }
        return EnumSet.copyOf(privileges);
    }

    private void requireManage(Principal caller, CatalogObject object, String action, String detail) {
        CatalogAccess.Verdict verdict = access.check(caller, Privilege.MANAGE, object.fullName());
        record(caller, action, object.fullName(), verdict, detail);
        if (!verdict.allowed()) {
            throw new PravahaException(
                    CatalogErrors.MANAGE_REQUIRED,
                    caller.id() + " may not change " + object.fullName() + ": that needs MANAGE or ownership ("
                            + verdict.via() + ")");
        }
    }

    private void record(Principal caller, String action, String target, CatalogAccess.Verdict verdict, String detail) {
        audit.record(AuditEvent.of(
                caller,
                action,
                target,
                verdict.allowed() ? AccessDecision.allow() : AccessDecision.deny(verdict.via()),
                detail));
    }

    private static void requireTag(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST, "'" + key + "' is not a tag: a tag's key is 1 to 128 characters");
        }
    }

    private static Grantee callerAsOwner(Principal caller) {
        return Grantee.user(caller.id());
    }

    private static PravahaException noSuch(String written) {
        return new PravahaException(
                CatalogErrors.NO_SUCH_OBJECT,
                "there is no object " + written + " that you may see; SHOW NAMESPACES and pravaha catalog ls list "
                        + "what you may");
    }
}
