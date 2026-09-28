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
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.ash.messaging.pravaha.security.Principal;

/**
 * Runs a {@link CatalogStatement} through {@link CatalogService}, answering as rows of text -- every
 * surface that carries SQL here carries result sets, so a {@code GRANT} answers with what it granted.
 *
 * <p>The columns are fixed by the statement's kind and known before it runs ({@link #columnsOf}), so
 * a Flight client can be told the result's shape at {@code getFlightInfo}.
 */
public final class CatalogStatementExecutor {

    /** A result: column names and rows of text (a null cell is SQL NULL). */
    public record Answer(List<String> columns, List<List<String>> rows) {}

    /** What a change answers: the object, what was done, and a detail. */
    public static final List<String> CHANGED = List.of("object", "kind", "action", "detail");

    /** What {@code SHOW GRANTS} answers. */
    public static final List<String> GRANTS =
            List.of("object", "privilege", "grantee_type", "grantee", "granted_by", "granted_at");

    /** What {@code SHOW EFFECTIVE ACCESS} answers: a row per privilege. */
    public static final List<String> EFFECTIVE = List.of("object", "user", "privilege", "allowed", "via");

    /** What {@code SHOW NAMESPACES} answers. */
    public static final List<String> NAMESPACES = List.of("namespace", "owner", "description", "tags");

    /** What {@code SHOW POLICIES} answers: a row per binding, or one with an empty {@code bound_to}. */
    public static final List<String> POLICIES =
            List.of("policy", "kind", "column", "expression", "except_roles", "bound_to", "owner");

    private final CatalogService service;

    public CatalogStatementExecutor(CatalogService service) {
        this.service = service;
    }

    /** The columns {@code statement} answers with. */
    public static List<String> columnsOf(CatalogStatement statement) {
        return switch (statement) {
            case CatalogStatement.ShowGrantsOn on -> GRANTS;
            case CatalogStatement.ShowGrantsTo to -> GRANTS;
            case CatalogStatement.ShowEffectiveAccess effective -> EFFECTIVE;
            case CatalogStatement.ShowNamespaces namespaces -> NAMESPACES;
            case CatalogStatement.ShowPolicies policies -> POLICIES;
            default -> CHANGED;
        };
    }

    public Answer execute(CatalogStatement statement, Principal caller) {
        return switch (statement) {
            case CatalogStatement.CreatePolicy create -> policies(create, caller);
            case CatalogStatement.SetPolicy set -> policies(set, caller);
            case CatalogStatement.UnsetPolicy unset -> policies(unset, caller);
            case CatalogStatement.DropPolicy drop -> policies(drop, caller);
            case CatalogStatement.ShowPolicies show -> policies(show, caller);
            case CatalogStatement.CreateNamespace create -> {
                CatalogObject made = service.createNamespace(
                        caller,
                        create.parts(),
                        create.ifNotExists(),
                        create.comment().orElse(""));
                yield changed(made, "CREATED", "owner " + made.owner());
            }
            case CatalogStatement.GrantPrivileges grant -> {
                CatalogObject object = service.resolve(caller, grant.target());
                List<Grant> made = service.grant(caller, object, grant.privileges(), grant.grantee());
                yield changed(object, "GRANTED", privileges(made) + " to " + grant.grantee());
            }
            case CatalogStatement.RevokePrivileges revoke -> {
                CatalogObject object = service.resolve(caller, revoke.target());
                boolean any = service.revoke(caller, object, revoke.privileges(), revoke.grantee());
                yield changed(
                        object,
                        any ? "REVOKED" : "NOT_HELD",
                        (revoke.privileges().isEmpty()
                                        ? "ALL"
                                        : revoke.privileges().toString()) + " from " + revoke.grantee());
            }
            case CatalogStatement.Comment comment -> {
                CatalogObject object = service.resolve(caller, comment.target());
                yield changed(
                        service.comment(caller, object, comment.text().orElse("")),
                        "COMMENTED",
                        comment.text().orElse(""));
            }
            case CatalogStatement.SetTags tags -> {
                CatalogObject object = service.resolve(caller, tags.target());
                CatalogObject changed = service.setTags(caller, object, tags.tags());
                yield changed(changed, "TAGGED", tags(changed.tags()));
            }
            case CatalogStatement.UnsetTags tags -> {
                CatalogObject object = service.resolve(caller, tags.target());
                CatalogObject changed = service.unsetTags(caller, object, tags.keys());
                yield changed(changed, "UNTAGGED", tags(changed.tags()));
            }
            case CatalogStatement.SetOwner owner -> {
                CatalogObject object = service.resolve(caller, owner.target());
                yield changed(
                        service.setOwner(caller, object, owner.owner()),
                        "OWNER_CHANGED",
                        owner.owner().toString());
            }
            case CatalogStatement.SetNamespace move -> {
                CatalogObject object = service.resolve(caller, move.target());
                CatalogObject moved = service.move(caller, object, move.namespace());
                yield changed(moved, "MOVED", "from " + object.fullName());
            }
            case CatalogStatement.ShowGrantsOn on ->
                grants(service.grantsOn(caller, service.resolve(caller, on.target())));
            case CatalogStatement.ShowGrantsTo to -> grants(service.grantsTo(caller, to.grantee()));
            case CatalogStatement.ShowEffectiveAccess effective -> {
                CatalogObject object = service.resolve(caller, effective.target());
                CatalogService.EffectiveAccess answer = service.effectiveAccess(caller, effective.user(), object);
                List<List<String>> rows = new ArrayList<>();
                for (CatalogService.AccessLine line : answer.lines()) {
                    rows.add(List.of(
                            answer.object(),
                            answer.user(),
                            line.privilege().name(),
                            Boolean.toString(line.allowed()),
                            line.allowed() ? String.join("; ", line.via()) : line.refusal()));
                }
                // ADR-059 §4: after the grants, each policy reaching the object -- "true" where it narrows
                // this user, with what it binds to for them, "false" where an EXCEPT ROLE exempts them.
                for (PolicyService.PolicyLine line : answer.policies()) {
                    rows.add(List.of(
                            answer.object(),
                            answer.user(),
                            policyLabel(line.policy()),
                            Boolean.toString(line.applies()),
                            line.detail() + " (" + line.boundVia() + ")"));
                }
                yield new Answer(EFFECTIVE, rows);
            }
            case CatalogStatement.ShowNamespaces namespaces -> {
                List<List<String>> rows = new ArrayList<>();
                for (CatalogObject namespace : service.namespaces(caller)) {
                    rows.add(List.of(
                            namespace.fullName(),
                            namespace.owner().toString(),
                            namespace.description(),
                            tags(namespace.tags())));
                }
                yield new Answer(NAMESPACES, rows);
            }
        };
    }

    private Answer policies(CatalogStatement statement, Principal caller) {
        PolicyService policies = service.policies();
        return switch (statement) {
            case CatalogStatement.CreatePolicy create -> {
                CatalogObject made = policies.create(
                        caller,
                        create.type(),
                        create.parts(),
                        create.column(),
                        create.expression(),
                        create.exceptRoles(),
                        "");
                yield changed(
                        made,
                        "CREATED",
                        create.type().words()
                                + (create.column().isEmpty() ? "" : " ON COLUMN " + create.column())
                                + "; bind it with ALTER STREAM|VIEW <name> SET POLICY " + made.fullName());
            }
            case CatalogStatement.SetPolicy set -> {
                PolicyBinding bound = policies.bind(caller, set.policy(), set.target());
                yield new Answer(
                        CHANGED,
                        List.of(List.of(bound.policy(), ObjectKind.POLICY.name(), "BOUND", "to " + bound.target())));
            }
            case CatalogStatement.UnsetPolicy unset -> {
                String name = policies.fullNameOf(caller, unset.policy());
                boolean was = policies.unbind(caller, unset.policy(), unset.target());
                yield new Answer(
                        CHANGED,
                        List.of(List.of(
                                name,
                                ObjectKind.POLICY.name(),
                                was ? "UNBOUND" : "NOT_BOUND",
                                "from " + unset.target().written())));
            }
            case CatalogStatement.DropPolicy drop -> {
                boolean dropped = policies.drop(caller, drop.type(), drop.parts(), drop.ifExists());
                yield new Answer(
                        CHANGED,
                        List.of(List.of(
                                String.join(".", drop.parts()),
                                ObjectKind.POLICY.name(),
                                dropped ? "DROPPED" : "NOT_FOUND",
                                drop.type().words())));
            }
            case CatalogStatement.ShowPolicies show -> {
                List<PolicyService.PolicyView> views = show.on().isPresent()
                        ? policies.on(caller, service.resolve(caller, show.on().get()))
                        : policies.list(caller);
                yield new Answer(POLICIES, policyRows(views));
            }
            default -> throw new IllegalStateException("not a policy statement: " + statement.verb());
        };
    }

    /** A row per binding of each policy, or one row with an empty {@code bound_to} for an unbound one. */
    public static List<List<String>> policyRows(List<PolicyService.PolicyView> views) {
        List<List<String>> rows = new ArrayList<>();
        for (PolicyService.PolicyView view : views) {
            PolicyDefinition policy = view.definition();
            List<String> targets = view.bindings().isEmpty()
                    ? List.of("")
                    : view.bindings().stream().map(PolicyBinding::target).toList();
            for (String target : targets) {
                rows.add(List.of(
                        policy.fullName(),
                        policy.type().name(),
                        policy.column(),
                        policy.expression(),
                        String.join(", ", policy.exceptRoles()),
                        target,
                        view.object().owner().toString()));
            }
        }
        return rows;
    }

    private static String policyLabel(PolicyDefinition policy) {
        return policy.type() == PolicyDefinition.Type.MASK
                ? "MASK " + policy.fullName() + " ON " + policy.column()
                : "ROW FILTER " + policy.fullName();
    }

    private static Answer changed(CatalogObject object, String action, String detail) {
        return new Answer(
                CHANGED, List.of(List.of(object.fullName(), object.kind().name(), action, detail)));
    }

    private static Answer grants(List<Grant> grants) {
        List<List<String>> rows = new ArrayList<>();
        for (Grant grant : grants) {
            rows.add(List.of(
                    grant.object(),
                    grant.privilege().name(),
                    grant.grantee().type(),
                    grant.grantee().name(),
                    grant.grantedBy(),
                    grant.grantedAt().toString()));
        }
        return new Answer(GRANTS, rows);
    }

    private static String privileges(List<Grant> grants) {
        StringJoiner joined = new StringJoiner(", ");
        grants.forEach(g -> joined.add(g.privilege().name()));
        return joined.toString();
    }

    /** {@code domain=finance, certified}. */
    public static String tags(Map<String, String> tags) {
        StringJoiner joined = new StringJoiner(", ");
        tags.forEach((key, value) -> joined.add(value.isEmpty() ? key : key + "=" + value));
        return joined.toString();
    }
}
