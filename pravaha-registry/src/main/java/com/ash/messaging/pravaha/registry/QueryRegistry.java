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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.exec.InterpretedPipeline;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewSink;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;
import com.ash.messaging.pravaha.sql.plan.ParameterPlacement;
import com.ash.messaging.pravaha.sql.plan.PreparedContinuousQuery;

/**
 * Where a piece of SQL becomes a computation with a name, a state and an end (ADR-025).
 *
 * <p>Registration is the surface that was missing. Everything that makes Pravaha a system rather
 * than a library needs one: a subscription attaches to a registered query, the console lists them,
 * a cluster assigns them to nodes, and a continuous query's parameters can only be classified
 * against one.
 *
 * <p><strong>Sharing is by fingerprint, never by name or text.</strong> Two registrations whose
 * plans normalise to the same thing are one computation with two names, holding one copy of the
 * state. That is the mechanism behind the claim that ten analysts opening the same dashboard cost
 * one query rather than ten, and it is enforced here rather than left to whoever writes the SQL.
 *
 * <p>Registration is authorized, and not as an afterthought. It is a more consequential act than a
 * read: a read costs a scan and ends, while a registration takes memory and a share of every lane
 * for as long as it exists. A principal who may not read a stream must not be able to commit the
 * cluster to maintaining an answer over it.
 */
public final class QueryRegistry implements AutoCloseable {

    /** The default ceiling on keys in a view a registration creates. */
    public static final int DEFAULT_MAX_KEYS = 1_000_000;

    private final ViewCatalog views;
    private final SecurityPolicy policy;
    private final AuditSink audit;
    private final StreamSchema[] streams;
    private Retention defaultRetention = Retention.DEFAULT;

    // Insertion-ordered so that listing a registry is stable, which matters for a console that
    // renders the list and for a test that asserts on it.
    private final Map<String, RegisteredQuery> byName = new LinkedHashMap<>();
    private RegistryJournal journal;
    private final Map<QueryFingerprint, RegisteredQuery> byFingerprint = new LinkedHashMap<>();

    public QueryRegistry(ViewCatalog views, StreamSchema... streams) {
        this(views, SecurityPolicy.PERMISSIVE, AuditSink.NONE, streams);
    }

    public QueryRegistry(ViewCatalog views, SecurityPolicy policy, AuditSink audit, StreamSchema... streams) {
        this.views = views;
        this.policy = policy;
        this.audit = audit;
        this.streams = streams.clone();
    }

    /**
     * Sets the retention every subsequent registration gets unless it chooses its own.
     *
     * <p>Configurable because the right answer is a deployment's, not ours: an intraday trade feed
     * wants a day, a fraud view wants an hour, a reference-data mirror may genuinely want forever.
     * What is not configurable is that there <em>is</em> one -- see {@link Retention}.
     *
     * <p>Expressed in event time, because that is what a streaming answer is about. A row count
     * would make the view's meaning depend on throughput; the row bound that does exist is the
     * view's capacity ceiling, which is a different question with a different answer.
     */
    public QueryRegistry retaining(Retention retention) {
        this.defaultRetention = retention == null ? Retention.DEFAULT : retention;
        return this;
    }

    /** The retention a registration gets when it does not ask for one. */
    public Retention defaultRetention() {
        return defaultRetention;
    }

    /**
     * Registers {@code sql} under {@code name}, or attaches the name to the computation that already
     * answers it.
     *
     * @param keyColumns the output columns the view is keyed by, as ordinals. A view with no key is
     *     a log rather than a view, so at least one is required
     */
    public synchronized RegisteredQuery register(
            String name, String sql, List<Integer> keyColumns, Principal principal) {
        return register(name, sql, keyColumns, principal, defaultRetention);
    }

    /**
     * Says where each of a query's {@code ?} parameters would have to be applied, and what it costs
     * (ADR-032).
     *
     * <p>Worth asking before registering a parameterised continuous query, because the answer
     * decides how many computations a deployment runs. A parameter the view carries is free: one
     * computation, and every subscriber filters at its own tap. A parameter the query aggregates
     * away needs a computation per distinct value.
     */
    public synchronized List<ParameterPlacement> classify(String sql) {
        return PreparedContinuousQuery.classify(sql, streams);
    }

    /**
     * Registers a parameterised continuous query with values bound into it.
     *
     * <p>Binding into the query is always <em>correct</em> and is sometimes wasteful: each distinct
     * binding is a separate computation with its own state, because the bound values are part of the
     * plan and therefore part of the fingerprint. When the view carries the parameter's column the
     * same effect is available for nothing -- register once, and let each subscriber filter at its
     * tap -- so this reports which parameters were in that position rather than leaving the cost to
     * be found later.
     *
     * @return the registration, whose {@link RegisteredQuery#parameterPlacements()} says what was
     *     decided and why
     */
    public synchronized RegisteredQuery register(
            String name, String sql, List<Integer> keyColumns, Principal principal, BoundParameters parameters) {
        return register(name, sql, keyColumns, principal, defaultRetention, parameters);
    }

    /** Registers with an explicit retention, overriding this registry's default. */
    public synchronized RegisteredQuery register(
            String name, String sql, List<Integer> keyColumns, Principal principal, Retention retention) {
        return register(name, sql, keyColumns, principal, retention, BoundParameters.none());
    }

    /** Registers with both an explicit retention and bound parameters. */
    public synchronized RegisteredQuery register(
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            Retention retention,
            BoundParameters parameters) {
        requireName(name);
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "a registration needs at least one key column: a view with no key is a log, and a "
                            + "point read against it has nothing to look up");
        }

        PreparedContinuousQuery prepared = PreparedContinuousQuery.of(sql, parameters, streams);
        List<ParameterPlacement> placements = prepared.placements();
        PhysicalOperator plan = prepared.plan();

        // Bound values are in the plan, so they are in the fingerprint: two bindings of the same SQL
        // are two computations. That is the truth rather than a policy, and it is precisely why a
        // parameter the view carries should be a tap filter instead -- same answer, one computation.
        QueryFingerprint fingerprint = QueryFingerprint.of(plan);

        AccessDecision decision = policy.mayRegisterQuery(principal);
        audit.record(AuditEvent.of(principal, "register", name, decision, sql));
        if (!decision.allowed()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN, principal.id() + " may not register a query: " + decision.reason());
        }

        RegisteredQuery existing = byFingerprint.get(fingerprint);
        if (existing != null && !existing.state().isTerminal()) {
            // The same question, asked again. One computation, one copy of the state, two names.
            existing.addName(name);
            byName.put(name, existing);
            return existing;
        }

        RegisteredQuery query = start(name, sql, plan, keyColumns, fingerprint, retention, placements);
        byName.put(name, query);
        byFingerprint.put(fingerprint, query);
        views.register(query.view());
        journalRegistration(name, sql, keyColumns, principal, retention, parameters);
        return query;
    }

    /**
     * Writes the registration down before it is acknowledged.
     *
     * <p>Ordering matters here and is the opposite of what it looks like. The journal append happens
     * after the query is running, so a query that cannot start is not recorded as if it had. But it
     * happens before {@code register} returns, so a client never gets an acknowledgement for a
     * registration that would vanish at the next restart. If the append fails the registration fails
     * with it, loudly, rather than succeeding in a way that will be silently undone later.
     */
    private void journalRegistration(
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            Retention retention,
            BoundParameters parameters) {
        if (journal == null) {
            return;
        }
        List<String> encoded = new ArrayList<>();
        for (int index = 0; index < parameters.size(); index++) {
            encoded.add(RegistryJournal.encodeParameter(parameters.at(index)));
        }
        journal.recordRegistration(name, sql, keyColumns, principal.id(), retention, encoded);
    }

    /** Registration during recovery: the journal is being read, so nothing is written back to it. */
    private RegisteredQuery registerWithoutJournalling(
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            Retention retention,
            BoundParameters parameters) {
        RegistryJournal suspended = journal;
        journal = null;
        try {
            return register(name, sql, keyColumns, principal, retention, parameters);
        } finally {
            journal = suspended;
        }
    }

    private RegisteredQuery start(
            String name,
            String sql,
            PhysicalOperator plan,
            List<Integer> keyColumns,
            QueryFingerprint fingerprint,
            Retention retention,
            List<ParameterPlacement> placements) {
        StreamSchema schema = plan.outputSchema();
        for (int ordinal : keyColumns) {
            if (ordinal < 0 || ordinal >= schema.fieldCount()) {
                throw new IllegalArgumentException("key column " + ordinal + " is not in the query's output, "
                        + "which has " + schema.fieldCount() + " columns");
            }
        }
        // The view carries the registration's name, because that is what a reader will write in a
        // FROM clause. The fingerprint names the computation; the name names the answer.
        ServedView view = new ServedView(name, schema, keyColumns, DEFAULT_MAX_KEYS, retention);
        ViewSink sink = new ViewSink(view, schema);
        InterpretedPipeline pipeline = InterpretedPipeline.compile(plan, (RowOutput) sink::begin);
        return new RegisteredQuery(fingerprint, sql, name, view, sink, pipeline, Instant.now(), placements);
    }

    /**
     * Writes registrations to {@code journal} so they survive a restart.
     *
     * <p>Without this a registry is entirely in memory: restart the server and every continuous
     * query a client registered is gone, with no error and nothing to look at. The client finds out
     * at its next subscribe, as "no such view", and the only fix is for every client to know to
     * register again.
     *
     * <p>What is journalled is the <em>registration</em> -- name, SQL, key columns, owner, retention,
     * bound values -- and not the state. The registration is small, rarely changes, and cannot be
     * recomputed because it came from a client that may never speak again. State is large, changes
     * constantly, and can be rebuilt by reading the stream. So a restart costs a warm-up rather than
     * an outage: the views are there immediately and fill as data arrives, and a windowed query's
     * first window or two are partial.
     */
    public synchronized QueryRegistry journalTo(RegistryJournal journal) {
        this.journal = journal;
        return this;
    }

    /**
     * Re-registers everything the journal remembers.
     *
     * <p>Authorization is checked again, for each entry, against the policy as it is now. A
     * registration is not a standing permission: if the principal who registered a query has since
     * lost access, the query does not quietly come back. Replaying blindly would make the journal a
     * way to keep an entitlement after it was revoked, by having registered before it was.
     *
     * @param principals resolves a recorded owner id back to a principal. Recovery needs the identity
     *     the registration was made under, and only the deployment knows how to look one up
     * @return what was recovered, and what was refused and why. Both matter: a query that did not
     *     come back is a view some client is about to ask for
     */
    public synchronized Recovery recover(java.util.function.Function<String, Optional<Principal>> principals) {
        if (journal == null) {
            return new Recovery(List.of(), List.of());
        }
        List<String> recovered = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (RegistryJournal.Entry entry : journal.replay()) {
            Optional<Principal> owner = principals.apply(entry.owner());
            if (owner.isEmpty()) {
                refused.add(entry.name() + ": its owner '" + entry.owner()
                        + "' is not a principal this deployment knows, so there is nobody to authorize it as");
                continue;
            }
            try {
                List<Object> values = entry.parameters().stream()
                        .map(RegistryJournal::decodeParameter)
                        .toList();
                registerWithoutJournalling(
                        entry.name(),
                        entry.sql(),
                        entry.keyColumns(),
                        owner.get(),
                        entry.retention(),
                        values.isEmpty() ? BoundParameters.none() : BoundParameters.of(values));
                recovered.add(entry.name());
            } catch (RuntimeException failure) {
                // One bad entry must not stop the rest. A deployment recovering forty queries should
                // not lose thirty-nine because the fortieth names a stream that has since been removed.
                refused.add(entry.name() + ": " + failure.getMessage());
            }
        }
        return new Recovery(recovered, refused);
    }

    /**
     * What a {@link #recover} put back, and what it would not.
     *
     * @param recovered names that are registered again
     * @param refused names that are not, each with the reason. These are views clients expect to
     *     exist, so this belongs in a log an operator reads, not in a return value nobody looks at
     */
    public record Recovery(List<String> recovered, List<String> refused) {

        public Recovery {
            recovered = List.copyOf(recovered);
            refused = List.copyOf(refused);
        }

        public boolean complete() {
            return refused.isEmpty();
        }

        @Override
        public String toString() {
            return "Recovery[" + recovered.size() + " recovered, " + refused.size() + " refused]";
        }
    }

    /** The query answering to {@code name}. */
    public synchronized Optional<RegisteredQuery> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public synchronized RegisteredQuery require(String name) {
        return find(name)
                .orElseThrow(() -> new PravahaException(
                        RegistryErrors.NO_SUCH_QUERY,
                        "no query named '" + name + "' is registered; this node has " + names()));
    }

    /** Every name registered, in registration order. */
    public synchronized Set<String> names() {
        return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(byName.keySet()));
    }

    /** Every distinct computation, which is fewer than {@link #names()} when queries are shared. */
    public synchronized List<RegisteredQuery> queries() {
        return List.copyOf(new java.util.LinkedHashSet<>(byFingerprint.values()));
    }

    public synchronized int size() {
        return byFingerprint.size();
    }

    /** Stops a query without releasing it; its view keeps answering at the frontier it reached. */
    public synchronized void pause(String name) {
        require(name).pause();
    }

    public synchronized void resume(String name) {
        require(name).resume();
    }

    /**
     * Removes a name, releasing the computation when it was the last one.
     *
     * <p>The refcount is the whole reason sharing is safe to do implicitly. Dropping on the first
     * name would take the answer away from everybody else who registered the same question and has
     * no idea anyone else exists.
     */
    public synchronized void drop(String name) {
        RegisteredQuery query = require(name);
        byName.remove(name);
        if (query.removeName(name)) {
            byFingerprint.remove(query.fingerprint());
            query.close();
        }
        if (journal != null) {
            // Recorded even when other names still hold the computation open: the journal is about
            // names, and this name is gone whatever happens to the computation behind it.
            journal.recordDrop(name);
        }
    }

    @Override
    public synchronized void close() {
        List<RegisteredQuery> all = new ArrayList<>(byFingerprint.values());
        byName.clear();
        byFingerprint.clear();
        all.forEach(RegisteredQuery::close);
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a registration needs a name");
        }
        if (byName.containsKey(name)) {
            throw new PravahaException(
                    RegistryErrors.NAME_IN_USE,
                    "'" + name + "' is already registered. Drop it first, or register under another name -- "
                            + "silently replacing a running query would take its answers away from whoever "
                            + "is reading them");
        }
    }
}
