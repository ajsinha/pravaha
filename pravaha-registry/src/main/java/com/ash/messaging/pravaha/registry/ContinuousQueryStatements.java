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
import java.util.Optional;
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
            case ContinuousStatement.Governance governance -> GovernanceStatements.schemaOf(governance.statement());
            case ContinuousStatement.Alert alert ->
                com.ash.messaging.pravaha.registry.alert.AlertStatementRunner.schemaOf(alert.statement());
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
            case ContinuousStatement.Governance governance ->
                GovernanceStatements.execute(policy, governance.statement(), principal);
            // ADR-057: the node's alert service, or PRV-8047 where there is none.
            case ContinuousStatement.Alert alert -> registry.alerting().execute(alert.statement(), principal);
        };
    }

    /**
     * A {@code CREATE CONTINUOUS QUERY} read as registration reads it: the WITH list folded in, the
     * key, range and index resolved against the columns the view would have (VALIDATEREG-1).
     */
    private record Parsed(
            ContinuousStatement.Create statement,
            Retention retention,
            boolean dedicatedLane,
            List<Integer> keys,
            Optional<Integer> index) {}

    private ViewQuery.Result create(ContinuousStatement.Create create, Principal principal) {
        Parsed parsed = parse(create, principal);
        ContinuousStatement.Create statement = parsed.statement();
        List<Integer> keys = parsed.keys();
        Optional<Integer> index = parsed.index();
        if (statement.orReplace() && registry.find(statement.name()).isPresent()) {
            requireReplaceable(statement, index);
            return replace(statement, keys, principal);
        }
        String sink = statement.sink().orElse(null);
        String name = statement.name();
        String select = statement.select();
        Retention kept = parsed.retention();
        RegisteredQuery query = registry.declaring(
                new Declaring(index.map(List::of).orElse(List.<Integer>of()), parsed.dedicatedLane()),
                () -> register(registry, name, select, keys, principal, sink, kept));
        return new ViewQuery.Result(CREATED, List.<Object[]>of(new Object[] {
            statement.name(),
            query.state().name(),
            query.fingerprint().shortForm(),
            statement.sink().orElse(null)
        }));
    }

    /**
     * Every refusal registering {@code create} would give, without registering it (VALIDATEREG-1):
     * empty when it would be accepted.
     *
     * <p>The statement's own clauses first -- its WITH list, its key, {@code RANGE} and {@code INDEX}
     * against the columns the view would have -- and, when those are readable, each independent
     * question registration asks after them: whether the name is free (or, for {@code CREATE OR
     * REPLACE} of a name that exists, whether a replacement may keep its sink, retention and indexes
     * and follow its readers), and registration's own preparation -- the sink's shape, key and
     * changelog, the policy's answers for the sources and the sink, the chain rules. Nothing is
     * started, no sink is opened and no name is taken; the policy's answers are audited as {@code
     * explain}. A tenant's quota is not judged: it is the node's state at the moment of registering,
     * not the statement's.
     */
    public List<PravahaException> validate(ContinuousStatement.Create create, Principal principal) {
        Parsed parsed;
        try {
            parsed = parse(create, principal);
        } catch (PravahaException e) {
            return List.of(e);
        }
        ContinuousStatement.Create statement = parsed.statement();
        String name = statement.name();
        List<PravahaException> refusals = new ArrayList<>();
        boolean replacing = statement.orReplace() && registry.find(name).isPresent();
        String sink = replacing
                ? registry.sinkOf(name).orElse(null)
                : statement.sink().orElse(null);
        if (replacing) {
            refusedBy(refusals, () -> requireReplaceable(statement, parsed.index()));
            refusedBy(refusals, () -> {
                synchronized (registry) {
                    registry.chains.refuseReplacement(name, statement.select(), principal);
                }
            });
        } else {
            refusedBy(refusals, () -> QueryNames.require(name, registry.names()));
        }
        Retention retention =
                replacing ? registry.find(name).orElseThrow().view().retention() : parsed.retention();
        refusedBy(
                refusals,
                () -> DraftFingerprint.of(
                        registry, name, statement.select(), parsed.keys(), principal, retention, sink));
        return List.copyOf(refusals);
    }

    private static void refusedBy(List<PravahaException> refusals, Runnable check) {
        try {
            check.run();
        } catch (PravahaException e) {
            refusals.add(e);
        }
    }

    private Parsed parse(ContinuousStatement.Create create, Principal principal) {
        ContinuousStatement.Create statement = create;
        Retention retention = statement
                .retain()
                .map(retain -> retain.age().map(Retention::ofAge).orElseGet(Retention::forever))
                .orElse(null);

        // A plain CREATE's WITH (...) list, read by the same parser that reads a replacement's and
        // judged by the vocabulary a registration has (B8). A replacement's list is read below, by
        // ReplacementOptions, because the two statements do not take the same options and an option
        // that belongs to the other one is refused by name rather than accepted and dropped.
        // lane = 'dedicated': a lane of its own whatever the node's lane-sharing mode.
        boolean dedicatedLane = false;
        if (!statement.orReplace() && !statement.options().isEmpty()) {
            RegistrationOptions options = RegistrationOptions.of(statement.options());
            dedicatedLane = options.dedicatedLane();
            if (options.retention().isPresent()) {
                if (retention != null) {
                    throw new PravahaException(
                            RegistryErrors.OPTION_UNKNOWN,
                            "'" + statement.name() + "' says its retention twice: RETAIN before AS and retention "
                                    + "in the WITH list. They are the same setting, and which of two answers wins "
                                    + "is not something to leave to the order they are written in.");
                }
                retention = options.retention().get();
            }
            if (options.sink().isPresent()) {
                String named = options.sink().get();
                if (statement.sink().isPresent() && !statement.sink().get().equals(named)) {
                    throw new PravahaException(
                            RegistryErrors.OPTION_UNKNOWN,
                            "'" + statement.name() + "' names two different sinks: '"
                                    + statement.sink().get() + "' with WRITING TO and '" + named
                                    + "' in the WITH list. A query's changelog goes to one place.");
                }
                statement = statement.withSink(named);
            }
            if (!options.keyColumns().isEmpty()) {
                statement = statement.withKeyColumns(options.keyColumns());
            }
            if (options.indexColumn().isPresent()) {
                if (statement.indexColumn().isPresent()) {
                    throw new PravahaException(
                            RegistryErrors.OPTION_UNKNOWN,
                            "'" + statement.name() + "' names its index twice: INDEX before AS and index in "
                                    + "the WITH list. They are the same setting; say it once.");
                }
                statement = statement.withIndexColumn(options.indexColumn().get());
            }
        } else if (statement.orReplace() && statement.options().containsKey("lane")) {
            // CREATE OR REPLACE of a name that turns out to be new registers it, on the lane it asked for.
            dedicatedLane = ReplacementOptions.with(
                                    ReplacementOptions.defaults(),
                                    "lane",
                                    statement.options().get("lane"))
                            .lane()
                    == ReplacementOptions.Lane.DEDICATED;
        }

        // The key by name, resolved against the columns the view would have. Planned the way register
        // plans it, so the ordinal is the one register will use whatever order the SELECT list is in.
        StreamSchema output = registry.outputSchemaOf(statement.select(), principal);
        List<Integer> keys = statement.keyOrdinals(output);
        // RANGE (column): checked here, against the columns the view will actually have, so a
        // column this engine cannot order is refused at registration rather than at the first read
        // that wanted the index (PRV-2073, ADR-049).
        statement.rangeOrdinal(output);
        // INDEX (column): the same, for an equality index over a column outside the key (PRV-2074,
        // ADR-055). Kept on the view before the registration is acknowledged, and journalled with it.
        Optional<Integer> index = statement.indexOrdinal(output);
        return new Parsed(statement, retention, dedicatedLane, keys, index);
    }

    /**
     * Refuses what a replacement may not change: its indexes, its sink or its retention. Asked of
     * {@code CREATE OR REPLACE} over a name that exists, by {@link #create} and {@link #validate}.
     */
    private void requireReplaceable(ContinuousStatement.Create create, Optional<Integer> index) {
        if (index.isPresent()) {
            throw new PravahaException(
                    com.ash.messaging.pravaha.sql.SqlErrors.CLAUSE_NOT_BUILT,
                    "'" + create.name() + "' already exists, and a replacement keeps the equality "
                            + "indexes its view keeps -- carried to the new version at the cutover, by "
                            + "column name -- rather than changing them. Drop the INDEX clause; to index "
                            + "a different column, drop the query and register it again.");
        }
        String existingSink = registry.sinkOf(create.name()).orElse(null);
        create.sink().ifPresent(named -> {
            if (!named.equals(existingSink)) {
                throw new PravahaException(
                        com.ash.messaging.pravaha.backfill.BackfillErrors.SOURCE_UNSUPPORTED,
                        "'" + create.name() + "' writes to "
                                + (existingSink == null ? "no sink" : "the sink '" + existingSink + "'")
                                + " and this statement names '" + named + "'. A replacement changes the query "
                                + "behind a name, not where its output goes: moving a sink is a drop and a "
                                + "fresh registration, so that what the old sink holds is somebody's decision "
                                + "rather than a side effect.");
            }
        });
        create.retain().ifPresent(retain -> {
            throw new PravahaException(
                    com.ash.messaging.pravaha.backfill.BackfillErrors.SOURCE_UNSUPPORTED,
                    "'" + create.name() + "' keeps "
                            + registry.find(create.name()).orElseThrow().view().retention()
                            + ", and a replacement keeps what the name keeps: a retention that changed at a "
                            + "cutover would change what the view means at the same moment as the query, and "
                            + "nothing downstream could tell which had done what.");
        });
    }

    /**
     * {@code CREATE OR REPLACE} over a name that already exists: a blue/green replacement (ADR-046).
     *
     * <p>It does not take the name from its readers and hand it to something that has not caught
     * up. The new version is registered beside the running one and backfilled, and the statement
     * answers with the state it is in -- {@code BACKFILLING} -- and the fingerprint of the
     * computation being prepared. The cutover is a separate act, by design: it is the moment the
     * answer changes, and {@code cutover = 'auto'} is how a caller says it does not want to be
     * asked.
     *
     * <p>A sink is the name's, not the statement's. {@code WRITING TO} naming a different one is
     * refused rather than quietly moving the query's output somewhere else.
     */
    private ViewQuery.Result replace(ContinuousStatement.Create create, List<Integer> keys, Principal principal) {
        String existingSink = registry.sinkOf(create.name()).orElse(null);
        ReplacementOptions options = ReplacementOptions.defaults();
        for (java.util.Map.Entry<String, String> option : create.options().entrySet()) {
            options = ReplacementOptions.with(options, option.getKey(), option.getValue());
        }
        QueryReplacement.Status status =
                registry.replacements().replace(create.name(), create.select(), keys, principal, options);
        return new ViewQuery.Result(CREATED, List.<Object[]>of(new Object[] {
            create.name(), status.state().name(), status.candidate(), existingSink
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
