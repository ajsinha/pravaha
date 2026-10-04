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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.ViewNames;

/**
 * The catalogue's state: every object, every grant, and whether a policy was imported -- held in
 * memory, changed only through methods that journal the change first (ADR-059).
 *
 * <p>Decisions are not made here; {@link CatalogAccess} makes them from this state, and keys its cache
 * on {@link #generation()}, which every change moves on. A read therefore costs a map lookup, and a
 * revocation is seen by the very next decision.
 *
 * <p>Thread-safe: every method is synchronized, and a change is journalled before it is applied, so a
 * change the journal refuses is not half-made in memory.
 */
public final class Catalog {

    /** Who the catalogue records as having made a change nobody asked for: an auto-created namespace. */
    public static final String SYSTEM = "system";

    /** The role that owns what nobody else does, and holds every right (ADR-059's bootstrap). */
    public static final String ADMIN_ROLE = "admin";

    /** Compact at open once the file holds this many more records than are live. */
    private static final int COMPACT_SLACK = 256;

    private final CatalogJournal journal;
    private final Clock clock;
    private final Map<String, CatalogObject> objects = new LinkedHashMap<>();
    private final Map<ObjectKind, Map<String, String>> byEngineName = new EnumMap<>(ObjectKind.class);
    private final List<Grant> grants = new ArrayList<>();
    private final Map<String, PolicyDefinition> policies = new LinkedHashMap<>();
    private final List<PolicyBinding> bindings = new ArrayList<>();
    private @Nullable String importedPolicy;
    private volatile long generation;
    private final Map<String, java.util.concurrent.atomic.LongAdder> changes =
            new java.util.concurrent.ConcurrentHashMap<>();
    private Supplier<Map<ObjectKind, Collection<String>>> infrastructure = Map::of;

    private Catalog(CatalogJournal journal, Clock clock) {
        this.journal = journal;
        this.clock = clock;
    }

    /** A catalogue kept only in memory: an embedded engine, a test. */
    public static Catalog inMemory(Clock clock) {
        Catalog catalog = new Catalog(CatalogJournal.inMemory(), clock);
        catalog.bootstrap();
        return catalog;
    }

    /** The catalogue journalled at {@code file}, replayed, and compacted when it has grown stale. */
    public static Catalog open(Path file, Clock clock) {
        Catalog catalog = new Catalog(CatalogJournal.at(file), clock);
        catalog.journal.replay(catalog::apply);
        catalog.bootstrap();
        if (catalog.journal.records() > catalog.liveRecordCount() + COMPACT_SLACK) {
            catalog.compact();
        }
        return catalog;
    }

    /** Where the journal is, or empty for an in-memory catalogue. */
    public Optional<Path> journalFile() {
        return Optional.ofNullable(journal.file());
    }

    /** The catalogue's clock, for a record made outside this class. */
    Instant now() {
        return clock.instant();
    }

    /** Moves on at every change; {@link CatalogAccess} drops its cache when it does. */
    public long generation() {
        return generation;
    }

    /**
     * What the node is configured with, by kind -- streams, sinks, sources, lookups -- so a statement can
     * name one the catalogue has not recorded yet. Asked lazily, never at a read.
     */
    public synchronized void infrastructure(Supplier<Map<ObjectKind, Collection<String>>> configured) {
        this.infrastructure = configured == null ? Map::of : configured;
    }

    // ------------------------------------------------------------------------------------ reads

    public synchronized Optional<CatalogObject> object(String fullName) {
        return Optional.ofNullable(objects.get(fullName));
    }

    /** Every object, in the order it was recorded. */
    public synchronized List<CatalogObject> objects() {
        return List.copyOf(objects.values());
    }

    /** The object the engine knows as {@code engineName}, of {@code kind}. */
    public synchronized Optional<CatalogObject> byEngineName(ObjectKind kind, String engineName) {
        String full = byEngineName.getOrDefault(kind, Map.of()).get(engineName);
        return full == null ? Optional.empty() : Optional.ofNullable(objects.get(full));
    }

    /** Whether the node is configured with a {@code kind} called {@code name}, recorded or not. */
    public synchronized boolean configured(ObjectKind kind, String name) {
        if (byEngineName.getOrDefault(kind, Map.of()).containsKey(name)) {
            return true;
        }
        Collection<String> names = infrastructure.get().get(kind);
        return names != null && names.contains(name);
    }

    /** The grants made directly on {@code fullName}. */
    public synchronized List<Grant> grantsOn(String fullName) {
        return grants.stream().filter(g -> g.object().equals(fullName)).toList();
    }

    /** Every grant, in the order made. */
    public synchronized List<Grant> grants() {
        return List.copyOf(grants);
    }

    /** The row filter or mask called {@code fullName} (ADR-059 §4). */
    public synchronized Optional<PolicyDefinition> policy(String fullName) {
        return Optional.ofNullable(policies.get(fullName));
    }

    /** Every row filter and mask, in the order created. */
    public synchronized List<PolicyDefinition> policies() {
        return List.copyOf(policies.values());
    }

    /** Every binding of every policy, in the order made. */
    public synchronized List<PolicyBinding> bindings() {
        return List.copyOf(bindings);
    }

    /** Where {@code policy} is bound. */
    public synchronized List<PolicyBinding> bindingsOf(String policy) {
        return bindings.stream().filter(b -> b.policy().equals(policy)).toList();
    }

    /** The policy imported into grants once, if any (ADR-059's migration). */
    public synchronized Optional<String> importedPolicy() {
        return Optional.ofNullable(importedPolicy);
    }

    /** Whether any grant reaches a caller who proved nothing -- the catalogue's form of "open". */
    public synchronized boolean grantsToEveryone() {
        return grants.stream()
                .anyMatch(g ->
                        g.grantee().role() && Grantee.PUBLIC.equals(g.grantee().name()));
    }

    // -------------------------------------------------------------------------------- changes

    /**
     * A namespace, created if it is not recorded yet. For the namespaces the catalogue makes on its own
     * account -- a tenant's {@code default}, the node's -- owned by the {@code admin} role.
     */
    public synchronized CatalogObject ensureNamespace(String fullName) {
        CatalogObject existing = objects.get(fullName);
        if (existing != null) {
            return existing;
        }
        String parent = CatalogNames.parentOf(fullName);
        if (parent != null && !objects.containsKey(parent)) {
            ensureNamespace(parent);
        }
        Instant now = clock.instant();
        CatalogObject created = new CatalogObject(
                fullName,
                ObjectKind.NAMESPACE,
                "",
                Grantee.role(ADMIN_ROLE),
                "",
                Map.of(),
                now,
                SYSTEM,
                now,
                SYSTEM,
                1);
        put(created);
        return created;
    }

    /** {@code CREATE NAMESPACE}: refused if it exists. */
    public synchronized CatalogObject createNamespace(
            String fullName, Grantee owner, String description, boolean ifNotExists, String by) {
        if (CatalogNames.depth(fullName) != 2) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "'" + fullName + "' is not a namespace name; a namespace is <namespace> or <tenant>.<namespace>");
        }
        CatalogObject existing = objects.get(fullName);
        if (existing != null) {
            if (ifNotExists) {
                return existing;
            }
            throw new PravahaException(CatalogErrors.OBJECT_EXISTS, "the namespace '" + fullName + "' exists already");
        }
        ensureNamespace(java.util.Objects.requireNonNull(CatalogNames.parentOf(fullName), fullName));
        Instant now = clock.instant();
        CatalogObject created = new CatalogObject(
                fullName, ObjectKind.NAMESPACE, "", owner, description, Map.of(), now, by, now, by, 1);
        put(created);
        return created;
    }

    /**
     * Records a view the engine has just registered, owned by its registrant, in the registrant's
     * tenant's default namespace. A name already recorded is left as it is: the journal replaying a
     * registration at start must not reset its owner, its grants or where it was moved to.
     */
    public synchronized CatalogObject registerView(String engineName, Principal owner) {
        return registerObject(ObjectKind.VIEW, engineName, owner);
    }

    /**
     * As {@link #registerView}, for any kind a person creates by statement: a view, or an alert
     * (ADR-057). A name already recorded as that kind is left as it is.
     *
     * <p>A view's catalogue name is its bare name in its owner's tenant's default namespace; its engine
     * name is {@code tenant.default.name} outside the default tenant (ADR-060). A view another tenant
     * than the default registered before names were per tenant was recorded under its bare engine name:
     * the first registration under its engine name re-keys that record, with its owner, grants and
     * bindings, rather than recording a second view.
     */
    public synchronized CatalogObject registerObject(ObjectKind kind, String engineName, Principal owner) {
        Optional<CatalogObject> known = byEngineName(kind, engineName);
        if (known.isPresent()) {
            return known.get();
        }
        String local = ViewNames.localName(engineName);
        Optional<CatalogObject> recordedBare = local.equals(engineName)
                ? Optional.empty()
                : byEngineName(kind, local)
                        .filter(o -> CatalogNames.tenantOf(o.fullName()).equals(ViewNames.tenantOf(engineName)));
        if (recordedBare.isPresent()) {
            CatalogObject was = recordedBare.get();
            return replace(new CatalogObject(
                    was.fullName(),
                    kind,
                    engineName,
                    was.owner(),
                    was.description(),
                    was.tags(),
                    was.createdAt(),
                    was.createdBy(),
                    clock.instant(),
                    SYSTEM,
                    was.version() + 1));
        }
        String namespace = CatalogNames.defaultNamespaceOf(owner.tenant());
        ensureNamespace(namespace);
        Instant now = clock.instant();
        String fullName = CatalogNames.object(namespace, local);
        CatalogObject taken = objects.get(fullName);
        if (taken != null) {
            throw new PravahaException(
                    CatalogErrors.OBJECT_EXISTS,
                    "'" + fullName + "' is already the catalogue's name for a "
                            + taken.kind().name().toLowerCase(java.util.Locale.ROOT)
                            + "; a " + kind.name().toLowerCase(java.util.Locale.ROOT)
                            + " cannot be given the same name in the same namespace");
        }
        CatalogObject created = new CatalogObject(
                fullName,
                kind,
                engineName,
                Grantee.user(owner.id()),
                "",
                Map.of(),
                now,
                owner.id(),
                now,
                owner.id(),
                1);
        put(created);
        return created;
    }

    /** Records a stream, sink, source or lookup the node is configured with, owned by {@code admin}. */
    public synchronized CatalogObject ensureInfrastructure(ObjectKind kind, String engineName) {
        Optional<CatalogObject> known = byEngineName(kind, engineName);
        if (known.isPresent()) {
            return known.get();
        }
        String namespace = CatalogNames.infrastructureNamespace(kind);
        ensureNamespace(namespace);
        Instant now = clock.instant();
        CatalogObject created = new CatalogObject(
                CatalogNames.object(namespace, engineName),
                kind,
                engineName,
                Grantee.role(ADMIN_ROLE),
                "",
                Map.of(),
                now,
                SYSTEM,
                now,
                SYSTEM,
                1);
        put(created);
        return created;
    }

    /** Forgets a view the engine has dropped, and every grant on it: a new view of the same name starts clean. */
    public synchronized void dropView(String engineName) {
        dropObject(ObjectKind.VIEW, engineName);
    }

    /** As {@link #dropView}, for any kind a person creates by statement. */
    public synchronized void dropObject(ObjectKind kind, String engineName) {
        byEngineName(kind, engineName).ifPresent(view -> {
            journal.append(List.of(List.of("x", view.fullName())));
            forget(view.fullName());
            changed("object");
        });
    }

    /**
     * Forgets views the catalogue records and the engine no longer runs -- a drop that happened while
     * the catalogue could not be written, or a registry journal that was removed.
     */
    public synchronized int reconcileViews(Set<String> running) {
        return reconcile(ObjectKind.VIEW, running);
    }

    /** As {@link #reconcileViews}, for any kind a person creates by statement: views, alerts. */
    public synchronized int reconcile(ObjectKind kind, Set<String> running) {
        List<String> stale = byEngineName.getOrDefault(kind, Map.of()).keySet().stream()
                .filter(name -> !running.contains(name))
                .toList();
        stale.forEach(name -> dropObject(kind, name));
        return stale.size();
    }

    /** Records one allow; granting what is already granted changes nothing. */
    public synchronized Grant grant(String object, Privilege privilege, Grantee grantee, String by) {
        Grant grant = new Grant(object, privilege, grantee, by, clock.instant());
        for (Grant existing : grants) {
            if (existing.sameAllow(grant)) {
                return existing;
            }
        }
        journal.append(List.of(encode(grant)));
        grants.add(grant);
        changed("grant");
        return grant;
    }

    /** Removes one allow; whether there was one to remove. */
    public synchronized boolean revoke(String object, Privilege privilege, Grantee grantee) {
        Grant wanted = new Grant(object, privilege, grantee, "", Instant.EPOCH);
        if (grants.stream().noneMatch(wanted::sameAllow)) {
            return false;
        }
        journal.append(List.of(List.of("r", object, privilege.name(), grantee.encode())));
        grants.removeIf(wanted::sameAllow);
        changed("revoke");
        return true;
    }

    public synchronized CatalogObject comment(String fullName, String description, String by) {
        CatalogObject object = require(fullName);
        return replace(object.changed(object.owner(), description, object.tags(), by, clock.instant()));
    }

    public synchronized CatalogObject setTags(String fullName, Map<String, String> tags, String by) {
        CatalogObject object = require(fullName);
        Map<String, String> merged = new LinkedHashMap<>(object.tags());
        merged.putAll(tags);
        return replace(object.changed(object.owner(), object.description(), merged, by, clock.instant()));
    }

    public synchronized CatalogObject unsetTags(String fullName, Collection<String> keys, String by) {
        CatalogObject object = require(fullName);
        Map<String, String> kept = new LinkedHashMap<>(object.tags());
        keys.forEach(kept::remove);
        return replace(object.changed(object.owner(), object.description(), kept, by, clock.instant()));
    }

    public synchronized CatalogObject setOwner(String fullName, Grantee owner, String by) {
        CatalogObject object = require(fullName);
        CatalogObject owned = object.changed(owner, object.description(), object.tags(), by, clock.instant());
        put(owned, "owner");
        return owned;
    }

    /**
     * Moves a view into another namespace of its tenant, taking its grants with it: a grant is to the
     * object, not to the spelling of its name.
     */
    public synchronized CatalogObject move(String fullName, String namespace, String by) {
        CatalogObject object = require(fullName);
        if (object.kind() != ObjectKind.VIEW) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "only a view moves between namespaces; '" + fullName + "' is a " + object.kind());
        }
        CatalogObject target = objects.get(namespace);
        if (target == null || target.kind() != ObjectKind.NAMESPACE || CatalogNames.depth(namespace) != 2) {
            throw new PravahaException(
                    CatalogErrors.NO_SUCH_OBJECT, "there is no namespace '" + namespace + "' to move it into");
        }
        if (!CatalogNames.tenantOf(namespace).equals(object.tenant())) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "'" + fullName + "' belongs to the tenant '" + object.tenant() + "' and '" + namespace
                            + "' does not; tenants are walls (ADR-050), and a view moves only within its own");
        }
        String destination = CatalogNames.object(namespace, object.shortName());
        if (destination.equals(fullName)) {
            return object;
        }
        CatalogObject moved = object.movedTo(destination, by, clock.instant());
        journal.append(List.of(List.of("m", fullName, destination), encode(moved)));
        applyMove(fullName, destination);
        objects.put(destination, moved);
        changed("move");
        return moved;
    }

    /**
     * Records a row filter or mask and the object that carries its owner, description and version, in
     * one journal write: a crash cannot leave a policy object with nothing to enforce.
     */
    public synchronized CatalogObject createPolicy(
            PolicyDefinition definition, Grantee owner, String description, String by) {
        String fullName = definition.fullName();
        if (CatalogNames.depth(fullName) != 3) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "'" + fullName + "' is not a policy name; a policy is <name>, <namespace>.<name> or "
                            + "<tenant>.<namespace>.<name>");
        }
        CatalogObject taken = objects.get(fullName);
        if (taken != null) {
            throw new PravahaException(
                    CatalogErrors.OBJECT_EXISTS,
                    "'" + fullName + "' is already the catalogue's name for a "
                            + taken.kind().name().toLowerCase(java.util.Locale.ROOT));
        }
        Instant now = clock.instant();
        CatalogObject created =
                new CatalogObject(fullName, ObjectKind.POLICY, "", owner, description, Map.of(), now, by, now, by, 1);
        journal.append(List.of(encode(created), encode(definition)));
        index(created);
        policies.put(fullName, definition);
        changed("policy_create");
        return created;
    }

    /** Binds a policy where {@code binding} says; binding it where it is bound already changes nothing. */
    public synchronized PolicyBinding bind(PolicyBinding binding) {
        for (PolicyBinding existing : bindings) {
            if (existing.samePlace(binding)) {
                return existing;
            }
        }
        journal.append(List.of(encode("b", binding)));
        bindings.add(binding);
        changed("policy_bind");
        return binding;
    }

    /** Removes one binding; whether there was one. */
    public synchronized boolean unbind(PolicyBinding place) {
        if (bindings.stream().noneMatch(place::samePlace)) {
            return false;
        }
        journal.append(List.of(encode("ub", place)));
        bindings.removeIf(place::samePlace);
        changed("policy_unbind");
        return true;
    }

    /** Forgets a policy: its object, its definition and its grants. Bindings must be gone already. */
    public synchronized void dropPolicy(String fullName) {
        if (!policies.containsKey(fullName)) {
            return;
        }
        journal.append(List.of(List.of("x", fullName)));
        forget(fullName);
        changed("policy_drop");
    }

    /**
     * Imports a deployment's policy as grants, once (ADR-059's migration): the grants and the marker
     * saying it happened are one journal write, so a crash cannot leave half an import to be repeated.
     */
    public synchronized void importPolicy(String policy, List<Grant> imported) {
        if (importedPolicy != null) {
            return;
        }
        List<List<String>> batch = new ArrayList<>();
        for (Grant grant : imported) {
            if (CatalogNames.depth(grant.object()) <= 2) {
                ensureNamespace(grant.object());
            }
            batch.add(encode(grant));
        }
        batch.add(List.of("i", policy, clock.instant().toString()));
        journal.append(batch);
        for (Grant grant : imported) {
            if (grants.stream().noneMatch(grant::sameAllow)) {
                grants.add(grant);
            }
        }
        importedPolicy = policy;
        changed("import");
    }

    /** Rewrites the journal with only what is live. */
    public synchronized void compact() {
        journal.rewrite(liveRecords());
    }

    // ------------------------------------------------------------------------------- internals

    private void bootstrap() {
        if (!objects.containsKey(CatalogNames.ROOT)) {
            ensureNamespace(CatalogNames.ROOT);
        }
    }

    private CatalogObject require(String fullName) {
        CatalogObject object = objects.get(fullName);
        if (object == null) {
            throw new PravahaException(CatalogErrors.NO_SUCH_OBJECT, "there is no object '" + fullName + "'");
        }
        return object;
    }

    private void put(CatalogObject object) {
        put(object, "object");
    }

    private void put(CatalogObject object, String kind) {
        journal.append(List.of(encode(object)));
        index(object);
        changed(kind);
    }

    private CatalogObject replace(CatalogObject object) {
        put(object);
        return object;
    }

    private void index(CatalogObject object) {
        CatalogObject previous = objects.put(object.fullName(), object);
        if (previous != null
                && !previous.engineName().isEmpty()
                && !previous.engineName().equals(object.engineName())) {
            // Re-keyed (ADR-060): the old engine name no longer names it, and may name another tenant's.
            Map<String, String> index = byEngineName.get(previous.kind());
            if (index != null) {
                index.remove(previous.engineName(), object.fullName());
            }
        }
        if (!object.engineName().isEmpty()) {
            byEngineName
                    .computeIfAbsent(object.kind(), k -> new LinkedHashMap<>())
                    .put(object.engineName(), object.fullName());
        }
    }

    private void forget(String fullName) {
        CatalogObject gone = objects.remove(fullName);
        if (gone != null && !gone.engineName().isEmpty()) {
            Map<String, String> index = byEngineName.get(gone.kind());
            if (index != null) {
                index.remove(gone.engineName(), fullName);
            }
        }
        grants.removeIf(g -> g.object().equals(fullName));
        policies.remove(fullName);
        // A dropped object takes its direct bindings with it: a new object of the same name starts clean,
        // as it does for grants. A dropped policy takes its own.
        bindings.removeIf(b -> b.object().equals(fullName) || b.policy().equals(fullName));
    }

    private void applyMove(String from, String to) {
        CatalogObject object = objects.remove(from);
        List<Grant> moved = new ArrayList<>();
        grants.removeIf(g -> {
            if (g.object().equals(from)) {
                moved.add(new Grant(to, g.privilege(), g.grantee(), g.grantedBy(), g.grantedAt()));
                return true;
            }
            return false;
        });
        grants.addAll(moved);
        List<PolicyBinding> rebound = new ArrayList<>();
        bindings.removeIf(b -> {
            if (b.object().equals(from)) {
                rebound.add(new PolicyBinding(b.policy(), to, "", "", b.boundBy(), b.boundAt()));
                return true;
            }
            return false;
        });
        bindings.addAll(rebound);
        if (object != null && !object.engineName().isEmpty()) {
            byEngineName
                    .computeIfAbsent(object.kind(), k -> new LinkedHashMap<>())
                    .put(object.engineName(), to);
        }
    }

    @SuppressWarnings("NonAtomicVolatileUpdate") // one writer; volatile so that readers on other threads see the count
    private void changed() {
        generation++;
    }

    /** A change made on this node now, counted by kind; a replayed record moves only the generation. */
    private void changed(String kind) {
        changes.computeIfAbsent(kind, k -> new java.util.concurrent.atomic.LongAdder())
                .increment();
        changed();
    }

    /**
     * Changes of {@code kind} made since this node started -- one of {@link CatalogStatistics#CHANGE_KINDS}
     * -- for the {@code pravaha_catalog_changes_total} meter. Replaying the journal at start counts nothing.
     */
    public long changes(String kind) {
        java.util.concurrent.atomic.LongAdder counted = changes.get(kind);
        return counted == null ? 0 : counted.sum();
    }

    private int liveRecordCount() {
        return objects.size() + grants.size() + policies.size() + bindings.size() + (importedPolicy == null ? 0 : 1);
    }

    private List<List<String>> liveRecords() {
        List<List<String>> live = new ArrayList<>();
        objects.values().forEach(o -> live.add(encode(o)));
        grants.forEach(g -> live.add(encode(g)));
        policies.values().forEach(p -> live.add(encode(p)));
        bindings.forEach(b -> live.add(encode("b", b)));
        if (importedPolicy != null) {
            live.add(List.of("i", importedPolicy, clock.instant().toString()));
        }
        return live;
    }

    // ---------------------------------------------------------------------------- the journal

    private static List<String> encode(CatalogObject o) {
        List<String> fields = new ArrayList<>(List.of(
                "o",
                o.fullName(),
                o.kind().name(),
                o.engineName(),
                o.owner().encode(),
                o.description(),
                o.createdAt().toString(),
                o.createdBy(),
                o.updatedAt().toString(),
                o.updatedBy(),
                Long.toString(o.version())));
        o.tags().forEach((key, value) -> {
            fields.add(key);
            fields.add(value);
        });
        return fields;
    }

    private static List<String> encode(Grant g) {
        return List.of(
                "g",
                g.object(),
                g.privilege().name(),
                g.grantee().encode(),
                g.grantedBy(),
                g.grantedAt().toString());
    }

    private static List<String> encode(PolicyDefinition p) {
        List<String> fields =
                new ArrayList<>(List.of("p", p.fullName(), p.type().name(), p.column(), p.expression()));
        fields.addAll(p.exceptRoles());
        return fields;
    }

    private static List<String> encode(String kind, PolicyBinding b) {
        return List.of(
                kind,
                b.policy(),
                b.object(),
                b.tagKey(),
                b.tagValue(),
                b.boundBy(),
                b.boundAt().toString());
    }

    private static PolicyBinding binding(List<String> f) {
        return new PolicyBinding(f.get(1), f.get(2), f.get(3), f.get(4), f.get(5), Instant.parse(f.get(6)));
    }

    private void apply(List<String> f) {
        switch (f.get(0)) {
            case "o" -> {
                Map<String, String> tags = new LinkedHashMap<>();
                for (int i = 11; i + 1 < f.size(); i += 2) {
                    tags.put(f.get(i), f.get(i + 1));
                }
                index(new CatalogObject(
                        f.get(1),
                        ObjectKind.valueOf(f.get(2)),
                        f.get(3),
                        Grantee.decode(f.get(4)),
                        f.get(5),
                        tags,
                        Instant.parse(f.get(6)),
                        f.get(7),
                        Instant.parse(f.get(8)),
                        f.get(9),
                        Long.parseLong(f.get(10))));
            }
            case "x" -> forget(f.get(1));
            case "m" -> applyMove(f.get(1), f.get(2));
            case "g" -> {
                Grant grant = new Grant(
                        f.get(1),
                        Privilege.valueOf(f.get(2)),
                        Grantee.decode(f.get(3)),
                        f.get(4),
                        Instant.parse(f.get(5)));
                if (grants.stream().noneMatch(grant::sameAllow)) {
                    grants.add(grant);
                }
            }
            case "r" -> {
                Grant wanted =
                        new Grant(f.get(1), Privilege.valueOf(f.get(2)), Grantee.decode(f.get(3)), "", Instant.EPOCH);
                grants.removeIf(wanted::sameAllow);
            }
            case "i" -> importedPolicy = f.get(1);
            case "p" ->
                policies.put(
                        f.get(1),
                        new PolicyDefinition(
                                f.get(1),
                                PolicyDefinition.Type.valueOf(f.get(2)),
                                f.get(3),
                                f.get(4),
                                java.util.Set.copyOf(f.subList(5, f.size()))));
            case "b" -> {
                PolicyBinding binding = binding(f);
                if (bindings.stream().noneMatch(binding::samePlace)) {
                    bindings.add(binding);
                }
            }
            case "ub" -> {
                PolicyBinding place = binding(f);
                bindings.removeIf(place::samePlace);
            }
            default ->
                throw new PravahaException(
                        CatalogErrors.JOURNAL_FAILED,
                        "the catalogue journal at " + journal.file() + " has a record of kind '" + f.get(0)
                                + "', which this engine does not know; it was written by a newer version");
        }
        changed();
    }
}
