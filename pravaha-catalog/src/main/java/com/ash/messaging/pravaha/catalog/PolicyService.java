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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Narrowing;
import com.ash.messaging.pravaha.security.Principal;

/**
 * Row filters and column masks as catalogue objects (ADR-059 §4): creating, binding, unbinding and
 * dropping them, and deciding what they narrow for one principal on one object.
 *
 * <p><strong>The rules, as built.</strong>
 *
 * <ul>
 *   <li>A policy is created with {@code CREATE} on its namespace, owned by its creator, and bound
 *       separately: to an object with {@code MANAGE} on that object, to a tag with {@code MANAGE} on the
 *       policy's tenant (a tag binding reaches every object of the tenant carrying the tag, now and
 *       later, so it is a tenant-wide change).
 *   <li>A direct binding narrows every reader of the object, whatever their tenant; a tag binding
 *       reaches only objects of the policy's own tenant -- tenants are walls, and one tenant's tags do
 *       not narrow another's objects or the node's.
 *   <li>Every row filter that applies is ANDed; a principal holding one of a policy's {@code EXCEPT
 *       ROLE}s is not narrowed by it. Two masks on one column for the same reader are refused with
 *       {@code PRV-7040} rather than one chosen.
 *   <li>A mask bound by tag applies to the tagged object's column of that name; an object without the
 *       column has nothing to mask.
 *   <li>A policy still bound is not dropped ({@code PRV-7040}): dropping it would widen what its
 *       objects show, which is a decision to make by unbinding, where it is audited as one.
 * </ul>
 */
public final class PolicyService {

    /**
     * Checks a policy against an object it is being bound to: that a filter plans over the object's
     * columns and is not true for every row, that a mask's column exists and the mask keeps its type.
     * The node supplies one that knows schemas and the planner; the catalogue module knows neither.
     */
    @FunctionalInterface
    public interface Checker {
        /**
         * Refuses a policy this target cannot take.
         *
         * @throws PravahaException {@code PRV-7038} naming what is wrong
         */
        void check(PolicyDefinition policy, CatalogObject target);
    }

    /** One policy and where it is bound -- or, for a listing {@code ON} an object, the bindings reaching it. */
    public record PolicyView(CatalogObject object, PolicyDefinition definition, List<PolicyBinding> bindings) {}

    /**
     * Whether one policy narrows one principal's view of one object, and why.
     *
     * @param applies false when the principal holds an {@code EXCEPT ROLE}
     * @param detail the bound expression when it applies; the exempting role when it does not
     */
    public record PolicyLine(PolicyDefinition policy, String boundVia, boolean applies, String detail) {}

    private final CatalogService service;
    private final Catalog catalog;
    private final CatalogAccess access;
    private final AuditSink audit;
    private volatile Checker checker = (policy, target) -> {};
    private final ConcurrentHashMap<String, Narrowing> cache = new ConcurrentHashMap<>();
    private volatile long cachedGeneration = -1;

    PolicyService(CatalogService service, AuditSink audit) {
        this.service = service;
        this.catalog = service.catalog();
        this.access = service.access();
        this.audit = audit;
    }

    /** How a binding is checked against the object's columns; the node supplies one that plans. */
    public void checkingWith(Checker checker) {
        this.checker = checker == null ? (policy, target) -> {} : checker;
    }

    // ------------------------------------------------------------------------------ changes

    /**
     * {@code CREATE ROW FILTER} / {@code CREATE MASK}: needs {@code CREATE} on the namespace; the
     * creator owns it. Nothing is narrowed until it is bound.
     */
    public CatalogObject create(
            Principal caller,
            PolicyDefinition.Type type,
            List<String> parts,
            String column,
            String expression,
            Collection<String> exceptRoles,
            String description) {
        String fullName = nameOf(caller, parts);
        String namespace = java.util.Objects.requireNonNull(CatalogNames.parentOf(fullName), fullName);
        if (catalog.object(namespace).isEmpty()) {
            if (!CatalogAccess.implicitlyUsable(caller, namespace)) {
                throw noSuch("NAMESPACE " + namespace);
            }
            catalog.ensureNamespace(namespace);
        }
        String masked = type == PolicyDefinition.Type.MASK ? requireColumn(column) : "";
        PolicyExpression.of(expression, type == PolicyDefinition.Type.MASK ? masked : null);
        Set<String> roles = new TreeSet<>();
        for (String role : exceptRoles == null ? List.<String>of() : exceptRoles) {
            if (role == null || role.isBlank()) {
                throw new PravahaException(CatalogErrors.INVALID_REQUEST, "EXCEPT ROLE needs a role's name");
            }
            roles.add(CatalogNames.part(role.strip(), "a role"));
        }
        CatalogAccess.Verdict create = access.check(caller, Privilege.CREATE, namespace);
        record(caller, "catalog.policy.create", fullName, create, type.words() + " AS " + expression);
        if (!create.allowed()) {
            throw new PravahaException(
                    CatalogErrors.MANAGE_REQUIRED,
                    caller.id() + " may not create a policy in " + namespace + ": that needs CREATE on the "
                            + "namespace (" + create.via() + ")");
        }
        return catalog.createPolicy(
                new PolicyDefinition(fullName, type, masked, expression.strip(), roles),
                Grantee.user(caller.id()),
                description == null ? "" : description,
                caller.id());
    }

    /**
     * {@code ALTER STREAM|VIEW name SET POLICY p} or {@code ALTER TAG 'k[=v]' SET POLICY p}. Binding
     * where it is bound already changes nothing.
     */
    public PolicyBinding bind(Principal caller, List<String> policyParts, CatalogStatement.Target target) {
        return target.kind().equals("TAG")
                ? bindTag(
                        caller,
                        policyParts,
                        target.parts().isEmpty() ? "" : target.parts().get(0))
                : bindObject(caller, policyParts, service.resolve(caller, target));
    }

    /** Binds to every object of the policy's tenant carrying {@code tag} ({@code key} or {@code key=value}). */
    public PolicyBinding bindTag(Principal caller, List<String> policyParts, String tag) {
        PolicyDefinition policy = resolve(caller, policyParts);
        String[] parsed = tagOf(tag);
        requireTenantManage(caller, policy, "catalog.policy.bind", "TAG '" + tag + "'");
        return catalog.bind(PolicyBinding.toTag(policy.fullName(), parsed[0], parsed[1], caller.id(), catalog.now()));
    }

    /** Binds to one stream or view: {@code MANAGE} on it, and the node's check against its columns. */
    public PolicyBinding bindObject(Principal caller, List<String> policyParts, CatalogObject target) {
        PolicyDefinition policy = resolve(caller, policyParts);
        CatalogObject object = narrowable(target);
        requireManage(caller, object, "catalog.policy.bind", policy.fullName());
        if (policy.type() == PolicyDefinition.Type.MASK) {
            for (PolicyBinding existing : catalog.bindings()) {
                Optional<PolicyDefinition> other = catalog.policy(existing.policy());
                if (existing.object().equals(object.fullName())
                        && other.isPresent()
                        && !other.get().fullName().equals(policy.fullName())
                        && other.get().type() == PolicyDefinition.Type.MASK
                        && other.get().column().equalsIgnoreCase(policy.column())) {
                    throw new PravahaException(
                            CatalogErrors.POLICY_CONFLICT,
                            object.fullName() + "'s column " + policy.column() + " is masked already, by "
                                    + other.get().fullName() + "; a column has one mask. Unbind that one first");
                }
            }
        }
        checker.check(policy, object);
        return catalog.bind(PolicyBinding.toObject(policy.fullName(), object.fullName(), caller.id(), catalog.now()));
    }

    /** {@code ... UNSET POLICY p}: whether it was bound there. The same rights as binding. */
    public boolean unbind(Principal caller, List<String> policyParts, CatalogStatement.Target target) {
        return target.kind().equals("TAG")
                ? unbindTag(
                        caller,
                        policyParts,
                        target.parts().isEmpty() ? "" : target.parts().get(0))
                : unbindObject(caller, policyParts, service.resolve(caller, target));
    }

    /** Removes a tag binding; whether there was one. */
    public boolean unbindTag(Principal caller, List<String> policyParts, String tag) {
        PolicyDefinition policy = resolve(caller, policyParts);
        String[] parsed = tagOf(tag);
        requireTenantManage(caller, policy, "catalog.policy.unbind", "TAG '" + tag + "'");
        return catalog.unbind(PolicyBinding.toTag(policy.fullName(), parsed[0], parsed[1], "", catalog.now()));
    }

    /** Removes a binding to one object; whether there was one. */
    public boolean unbindObject(Principal caller, List<String> policyParts, CatalogObject target) {
        PolicyDefinition policy = resolve(caller, policyParts);
        CatalogObject object = narrowable(target);
        requireManage(caller, object, "catalog.policy.unbind", policy.fullName());
        return catalog.unbind(PolicyBinding.toObject(policy.fullName(), object.fullName(), "", catalog.now()));
    }

    /**
     * {@code DROP ROW FILTER|MASK name}: needs {@code MANAGE} on the policy, and no binding left.
     *
     * @return whether there was one to drop ({@code IF EXISTS} answers false for a missing one)
     */
    public boolean drop(Principal caller, PolicyDefinition.Type type, List<String> parts, boolean ifExists) {
        PolicyDefinition policy;
        try {
            policy = resolve(caller, parts);
        } catch (PravahaException missing) {
            if (ifExists && missing.errorCode().number() == CatalogErrors.NO_SUCH_OBJECT.number()) {
                return false;
            }
            throw missing;
        }
        if (policy.type() != type) {
            throw noSuch(type.words() + " " + String.join(".", parts));
        }
        CatalogObject object = catalog.object(policy.fullName()).orElseThrow();
        requireManage(caller, object, "catalog.policy.drop", policy.type().words());
        List<PolicyBinding> bound = catalog.bindingsOf(policy.fullName());
        if (!bound.isEmpty()) {
            throw new PravahaException(
                    CatalogErrors.POLICY_CONFLICT,
                    policy.fullName() + " is still bound to "
                            + bound.stream().map(PolicyBinding::target).toList()
                            + ". Dropping it would widen what those objects show; unbind it from each with ALTER ... "
                            + "UNSET POLICY first, so the widening is a decision the trail records");
        }
        catalog.dropPolicy(policy.fullName());
        return true;
    }

    // -------------------------------------------------------------------------------- reads

    /** Every policy {@code caller} may see, with where it is bound. */
    public List<PolicyView> list(Principal caller) {
        List<PolicyView> views = new ArrayList<>();
        for (PolicyDefinition policy : catalog.policies()) {
            catalog.object(policy.fullName())
                    .filter(o -> service.visible(caller, o))
                    .ifPresent(o -> views.add(new PolicyView(o, policy, catalog.bindingsOf(policy.fullName()))));
        }
        return views;
    }

    /** The policies reaching {@code object} -- directly or by one of its tags -- with the bindings that do. */
    public List<PolicyView> on(Principal caller, CatalogObject object) {
        Map<String, List<PolicyBinding>> reaching = new LinkedHashMap<>();
        for (PolicyBinding binding : reaching(object)) {
            reaching.computeIfAbsent(binding.policy(), p -> new ArrayList<>()).add(binding);
        }
        List<PolicyView> views = new ArrayList<>();
        reaching.forEach((name, bindings) -> catalog.policy(name)
                .ifPresent(
                        policy -> views.add(new PolicyView(catalog.object(name).orElseThrow(), policy, bindings))));
        return views;
    }

    /** The full name {@code parts} gives a policy {@code caller} may see, or {@code PRV-7031}. */
    public String fullNameOf(Principal caller, List<String> parts) {
        return resolve(caller, parts).fullName();
    }

    /** The policy {@code written} names for {@code caller}, as a person types it. */
    public PolicyView show(Principal caller, String written) {
        PolicyDefinition policy = resolve(caller, List.of((written == null ? "" : written.strip()).split("\\.", -1)));
        return new PolicyView(
                catalog.object(policy.fullName()).orElseThrow(), policy, catalog.bindingsOf(policy.fullName()));
    }

    /**
     * Every policy reaching {@code object}, and whether each narrows {@code principal} -- what {@code SHOW
     * EFFECTIVE ACCESS} adds to the grants.
     */
    public List<PolicyLine> lines(Principal principal, CatalogObject object) {
        List<PolicyLine> lines = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PolicyBinding binding : reaching(object)) {
            if (!seen.add(binding.policy())) {
                continue;
            }
            PolicyDefinition policy = catalog.policy(binding.policy()).orElseThrow();
            Optional<String> exempt = policy.exemption(principal);
            String detail;
            if (exempt.isPresent()) {
                detail = "exempt: holds the role " + exempt.get();
            } else {
                try {
                    detail = policy.parsed().boundTo(principal);
                } catch (PravahaException unbindable) {
                    detail = String.valueOf(unbindable.getMessage()); // a PravahaException's is never null
                }
            }
            lines.add(new PolicyLine(policy, via(binding), exempt.isEmpty(), detail));
        }
        return lines;
    }

    /**
     * What policies narrow of {@code fullName} for {@code principal}: every applying row filter ANDed,
     * every applying mask by column. Cached per principal and object until the catalogue changes.
     *
     * @throws PravahaException {@code PRV-7039} when a policy reads a claim the principal lacks;
     *     {@code PRV-7040} when two masks fall on one column
     */
    public Narrowing narrowing(Principal principal, String fullName) {
        long generation = catalog.generation();
        if (generation != cachedGeneration || cache.size() > CatalogAccess.CACHE_LIMIT) {
            cache.clear();
            cachedGeneration = generation;
        }
        String key = principal.id()
                + '\u0000'
                + principal.tenant()
                + '\u0000'
                + new TreeSet<>(principal.roles())
                + '\u0000'
                + new TreeMap<>(principal.claims())
                + '\u0000'
                + fullName;
        Narrowing cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Narrowing decided = decide(principal, fullName);
        if (catalog.generation() == generation) {
            cache.put(key, decided);
        }
        return decided;
    }

    private Narrowing decide(Principal principal, String fullName) {
        Optional<CatalogObject> object = catalog.object(fullName);
        if (object.isEmpty() || catalog.bindings().isEmpty()) {
            return Narrowing.NONE;
        }
        Map<String, String> filters = new TreeMap<>();
        Map<String, String> masks = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> maskedBy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> because = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PolicyBinding binding : reaching(object.get())) {
            if (!seen.add(binding.policy())) {
                continue;
            }
            PolicyDefinition policy = catalog.policy(binding.policy()).orElseThrow();
            if (policy.exemption(principal).isPresent()) {
                continue;
            }
            String bound = policy.parsed().boundTo(principal);
            if (policy.type() == PolicyDefinition.Type.ROW_FILTER) {
                filters.put(policy.fullName(), bound);
            } else {
                String other = maskedBy.putIfAbsent(policy.column(), policy.fullName());
                if (other != null) {
                    throw new PravahaException(
                            CatalogErrors.POLICY_CONFLICT,
                            "two masks fall on " + fullName + "'s column " + policy.column() + " for " + principal.id()
                                    + ": " + other + " and " + policy.fullName() + " (" + via(binding) + "). A column "
                                    + "has one mask; which of two applies is not the engine's to guess. Unbind one");
                }
                masks.put(policy.column(), bound);
            }
            because.add(policy.type().words() + " " + policy.fullName() + " (" + via(binding) + "): " + bound);
        }
        if (filters.isEmpty() && masks.isEmpty()) {
            return Narrowing.NONE;
        }
        Optional<String> filter = filters.isEmpty()
                ? Optional.empty()
                : Optional.of(
                        filters.size() == 1
                                ? filters.values().iterator().next()
                                : "(" + String.join(") AND (", filters.values()) + ")");
        return new Narrowing(filter, masks, because);
    }

    /** The bindings reaching {@code object}: its own, and its tenant's tag bindings its tags match. */
    private List<PolicyBinding> reaching(CatalogObject object) {
        List<PolicyBinding> reaching = new ArrayList<>();
        for (PolicyBinding binding : catalog.bindings()) {
            if (binding.object().equals(object.fullName())) {
                reaching.add(binding);
            } else if (binding.matchesTags(object.tags())
                    && CatalogNames.tenantOf(binding.policy()).equals(object.tenant())) {
                reaching.add(binding);
            }
        }
        return reaching;
    }

    private static String via(PolicyBinding binding) {
        return binding.byTag() ? "bound to TAG '" + binding.tag() + "'" : "bound to " + binding.object();
    }

    // ------------------------------------------------------------------------------ internals

    private PolicyDefinition resolve(Principal caller, List<String> parts) {
        if (parts.isEmpty() || parts.size() > 3 || parts.stream().anyMatch(String::isBlank)) {
            throw noSuch("POLICY " + String.join(".", parts));
        }
        String fullName = nameOf(caller, parts);
        Optional<CatalogObject> object = catalog.object(fullName).filter(o -> o.kind() == ObjectKind.POLICY);
        Optional<PolicyDefinition> policy = catalog.policy(fullName);
        if (object.isEmpty() || policy.isEmpty() || !service.visible(caller, object.get())) {
            throw noSuch("POLICY " + String.join(".", parts));
        }
        return policy.get();
    }

    private static String nameOf(Principal caller, List<String> parts) {
        for (String part : parts) {
            CatalogNames.part(part, "a policy's name");
        }
        return switch (parts.size()) {
            case 1 -> CatalogNames.defaultNamespaceOf(caller.tenant()) + "." + parts.get(0);
            case 2 -> caller.tenant() + "." + parts.get(0) + "." + parts.get(1);
            case 3 -> String.join(".", parts);
            default ->
                throw new PravahaException(
                        CatalogErrors.INVALID_REQUEST,
                        "'" + String.join(".", parts) + "' is not a policy name: <name>, <namespace>.<name> or "
                                + "<tenant>.<namespace>.<name>");
        };
    }

    private static CatalogObject narrowable(CatalogObject object) {
        if (object.kind() != ObjectKind.STREAM && object.kind() != ObjectKind.VIEW) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST,
                    "a row filter or mask narrows what is read of a stream or a view; " + object.fullName() + " is a "
                            + object.kind());
        }
        return object;
    }

    private static String[] tagOf(String tag) {
        String text = tag == null ? "" : tag.strip();
        int equals = text.indexOf('=');
        String key = (equals < 0 ? text : text.substring(0, equals)).strip();
        String value = equals < 0 ? "" : text.substring(equals + 1).strip();
        if (key.isEmpty() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw new PravahaException(
                    CatalogErrors.INVALID_REQUEST, "'" + text + "' is not a tag: write 'key' or 'key=value'");
        }
        return new String[] {key, value};
    }

    private static String requireColumn(String column) {
        if (column == null || column.isBlank()) {
            throw new PravahaException(
                    CatalogErrors.POLICY_INVALID, "a mask names the column it masks: ON COLUMN <name>");
        }
        return CatalogNames.part(column.strip(), "a column");
    }

    private void requireManage(Principal caller, CatalogObject object, String action, String detail) {
        CatalogAccess.Verdict verdict = access.check(caller, Privilege.MANAGE, object.fullName());
        record(caller, action, object.fullName(), verdict, detail);
        if (!verdict.allowed()) {
            throw new PravahaException(
                    CatalogErrors.MANAGE_REQUIRED,
                    caller.id() + " may not change the policies of " + object.fullName()
                            + ": that needs MANAGE or ownership (" + verdict.via() + ")");
        }
    }

    private void requireTenantManage(Principal caller, PolicyDefinition policy, String action, String tag) {
        String tenant = policy.tenant();
        catalog.ensureNamespace(tenant);
        CatalogAccess.Verdict verdict = access.check(caller, Privilege.MANAGE, tenant);
        record(caller, action, tenant, verdict, policy.fullName() + " to " + tag);
        if (!verdict.allowed()) {
            throw new PravahaException(
                    CatalogErrors.MANAGE_REQUIRED,
                    caller.id() + " may not bind a policy to " + tag + ": a tag binding narrows every object of the "
                            + "tenant '" + tenant + "' carrying it, now and later, so it needs MANAGE on TENANT "
                            + tenant + " (" + verdict.via() + ")");
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

    private static PravahaException noSuch(String written) {
        return new PravahaException(
                CatalogErrors.NO_SUCH_OBJECT,
                "there is no " + written + " that you may see; SHOW POLICIES lists what you may");
    }
}
