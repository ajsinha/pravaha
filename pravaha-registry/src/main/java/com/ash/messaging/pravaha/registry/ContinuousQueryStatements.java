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

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ViewQuery;
import com.ash.messaging.pravaha.sql.ContinuousStatement;

/**
 * Runs a {@link ContinuousStatement} against a registry, answering as a result set.
 *
 * <p>The statements are another spelling of the Flight actions {@code pravaha.register}, {@code
 * drop}, {@code pause}, {@code resume} and {@code list}, and they decide exactly as those do,
 * because the deciding is written once, here, and the actions call it too: a registration is
 * authorized by the registry itself, the three lifecycle verbs by {@link #requireAdministrable}
 * with the verb as the audit action, and a listing by {@link QueryListing} -- the rules {@code GET
 * /api/v1/queries} applies. A principal refused through one spelling is refused through the other,
 * with the same code and the same audit record.
 *
 * <p>Every statement answers with rows, not an update count, because every surface that carries SQL
 * here carries result sets: a {@code CREATE} says which computation the name landed on, which is how
 * a user learns that their query shares one with somebody else's.
 */
public final class ContinuousQueryStatements {

    /** What {@code CREATE CONTINUOUS QUERY} answers: one row, as {@code pravaha.register} does. */
    public static final StreamSchema CREATED = StreamSchema.builder("created")
            .field("name", Types.string())
            .field("state", Types.string())
            .field("fingerprint", Types.string())
            .field("sink", Types.string().withNullable(true))
            .build();

    /** What {@code DROP}, {@code PAUSE} and {@code RESUME} answer: the name and the state it is now in. */
    public static final StreamSchema CHANGED = StreamSchema.builder("changed")
            .field("name", Types.string())
            .field("state", Types.string())
            .build();

    /**
     * What {@code SHOW CONTINUOUS QUERIES} answers: a row per name the caller may learn exists, with
     * the fields {@code pravaha.list} carries. {@code rows_in} is {@code -1} when withheld (SX-18);
     * the key is given by column name rather than by ordinal, since that is how it was written.
     */
    public static final StreamSchema LISTING = StreamSchema.builder("continuous_queries")
            .field("name", Types.string())
            .field("state", Types.string())
            .field("sql", Types.string())
            .field("fingerprint", Types.string())
            .field("rows_in", Types.int64())
            .field("key_columns", Types.string())
            .field("sink", Types.string().withNullable(true))
            .field("retention", Types.string())
            .build();

    private final QueryRegistry registry;
    private final SecurityPolicy policy;
    private final AuditSink audit;

    /**
     * @param policy the policy the surface running the statement authorizes with -- the one its
     *     actions use -- so that the SQL spelling cannot be decided by a different rule
     */
    public ContinuousQueryStatements(QueryRegistry registry, SecurityPolicy policy, AuditSink audit) {
        this.registry = registry;
        this.policy = policy == null ? SecurityPolicy.PERMISSIVE : policy;
        this.audit = audit == null ? AuditSink.NONE : audit;
    }

    /** The columns {@code statement} answers with, known before it runs. */
    public static StreamSchema resultSchemaOf(ContinuousStatement statement) {
        return switch (statement) {
            case ContinuousStatement.Create create -> CREATED;
            case ContinuousStatement.Show show -> LISTING;
            default -> CHANGED;
        };
    }

    /** Runs {@code statement} as {@code principal}. */
    public ViewQuery.Result execute(ContinuousStatement statement, Principal principal) {
        return switch (statement) {
            case ContinuousStatement.Create create -> create(create, principal);
            case ContinuousStatement.Drop drop -> {
                requireAdministrable(policy, audit, principal, drop.name(), "drop");
                registry.drop(drop.name());
                yield changed(drop.name(), "DROPPED");
            }
            case ContinuousStatement.Pause pause -> {
                requireAdministrable(policy, audit, principal, pause.name(), "pause");
                registry.pause(pause.name());
                yield changed(pause.name(), "PAUSED");
            }
            case ContinuousStatement.Resume resume -> {
                requireAdministrable(policy, audit, principal, resume.name(), "resume");
                registry.resume(resume.name());
                yield changed(resume.name(), "RUNNING");
            }
            case ContinuousStatement.Show show -> listing(principal);
        };
    }

    private ViewQuery.Result create(ContinuousStatement.Create create, Principal principal) {
        // The key by name, resolved against the columns the view would have. Planned the way register
        // plans it, so the ordinal is the one register will use whatever order the SELECT list is in.
        List<Integer> keys = create.keyOrdinals(registry.outputSchemaOf(create.select()));
        Retention retention = create.retain()
                .map(retain -> retain.age().map(Retention::ofAge).orElseGet(Retention::forever))
                .orElse(null);
        RegisteredQuery query = register(
                registry,
                create.name(),
                create.select(),
                keys,
                principal,
                create.sink().orElse(null),
                retention);
        return new ViewQuery.Result(CREATED, List.<Object[]>of(new Object[] {
            create.name(),
            query.state().name(),
            query.fingerprint().shortForm(),
            create.sink().orElse(null)
        }));
    }

    private ViewQuery.Result listing(Principal principal) {
        List<Object[]> rows = new ArrayList<>();
        for (QueryListing.Entry entry : new QueryListing(registry, policy, audit).list(principal, "list")) {
            RegisteredQuery query = entry.query();
            StringJoiner keys = new StringJoiner(",");
            StreamSchema output = query.outputSchema();
            for (int ordinal : query.view().keyOrdinals()) {
                keys.add(ordinal < output.fieldCount() ? output.field(ordinal).name() : Integer.toString(ordinal));
            }
            rows.add(new Object[] {
                entry.name(),
                query.state().name(),
                query.sql(),
                query.fingerprint().shortForm(),
                entry.rowsIn(),
                keys.toString(),
                entry.sink().orElse(null),
                query.view().retention().toString()
            });
        }
        return new ViewQuery.Result(LISTING, rows);
    }

    private static ViewQuery.Result changed(String name, String state) {
        return new ViewQuery.Result(CHANGED, List.<Object[]>of(new Object[] {name, state}));
    }

    /**
     * Registers with an optional sink and an optional retention -- the one place that chooses among
     * the registry's overloads, for the {@code pravaha.register} action and for {@code CREATE
     * CONTINUOUS QUERY} alike.
     *
     * @param sink the sink's binding name, or null to write only the view
     * @param retention the view's retention, or null for the registry's default
     */
    public static RegisteredQuery register(
            QueryRegistry registry,
            String name,
            String sql,
            List<Integer> keys,
            Principal principal,
            String sink,
            Retention retention) {
        if (sink != null) {
            return retention == null
                    ? registry.registerWritingTo(name, sql, keys, principal, sink)
                    : registry.registerWritingTo(name, sql, keys, principal, sink, retention);
        }
        return retention == null
                ? registry.register(name, sql, keys, principal)
                : registry.register(name, sql, keys, principal, retention);
    }

    /**
     * Refuses a control verb the principal may not use on this view.
     *
     * <p>These three verbs authorized nothing whatsoever. An unauthenticated caller dropped every
     * continuous query on a node configured to serve only verified callers, and an authenticated but
     * denied principal dropped another principal's payroll query -- destroying its accumulated state
     * and taking the view away from everyone holding a name for it.
     *
     * @param verb the audit action and the word in the refusal: {@code drop}, {@code pause} or {@code
     *     resume}
     * @throws PravahaException {@code PRV-7002} when the policy denies it
     */
    public static void requireAdministrable(
            SecurityPolicy policy, AuditSink audit, Principal principal, String view, String verb) {
        AccessDecision decision = policy.mayAdminister(principal, view);
        audit.record(AuditEvent.of(principal, verb, view, decision, ""));
        if (!decision.allowed()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN,
                    principal.id() + " may not " + verb + " '" + view + "': " + decision.reason());
        }
    }
}
