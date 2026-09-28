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
            default -> CHANGED;
        };
    }

    public Answer execute(CatalogStatement statement, Principal caller) {
        return switch (statement) {
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
