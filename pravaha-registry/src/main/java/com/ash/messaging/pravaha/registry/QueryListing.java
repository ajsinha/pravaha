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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;

/**
 * What one principal may learn about the registered queries: which exist, and how much of each.
 *
 * <p>This was a private block inside the Flight producer's {@code LIST} action, and it is the one
 * decision every surface that describes a registered query has to make identically. The HTTP API grew
 * a listing and a per-query description; a second copy of these rules there would have been the same
 * question decided twice, and two copies of an authorization rule diverge in the direction of
 * whichever one somebody forgot to change -- which is how the HTTP surface came to consult no policy
 * at all before {@code HttpAuthorizer}. So the rules live here, once, and both transports call them.
 *
 * <p>Three rules, each with the finding that made it one:
 *
 * <ul>
 *   <li><strong>By name.</strong> A principal the policy denies a view's name does not learn it
 *       exists. Recorded per refusal (SX-8): a probe refused a hundred times in one call must leave a
 *       hundred traces.
 *   <li><strong>By provenance (SX-11).</strong> The name is chosen by whoever registered the query,
 *       so the streams it actually reads are decided on too, and a denial behind the view hides it --
 *       audited against the stream, which is the noun "who tried to reach payroll" is asked about.
 *   <li><strong>Counts withheld (SX-18).</strong> A principal entitled only to a row-filtered slice
 *       may know the view exists and may not have its totals: rows in, rows written to a sink, and a
 *       sink's failure text (which can quote a row) describe data beyond their entitlement.
 * </ul>
 */
public final class QueryListing {

    private final QueryRegistry registry;
    private final SecurityPolicy policy;
    private final AuditSink audit;

    public QueryListing(QueryRegistry registry, SecurityPolicy policy, AuditSink audit) {
        this.registry = registry;
        this.policy = policy == null ? SecurityPolicy.PERMISSIVE : policy;
        this.audit = audit == null ? AuditSink.NONE : audit;
    }

    /**
     * One registered name as a principal may see it.
     *
     * @param restricted the principal's access is conditional on a row filter somewhere on or behind
     *     the view, so its totals are not theirs to know
     */
    public record Entry(
            String name,
            RegisteredQuery query,
            boolean restricted,
            Optional<String> sink,
            Optional<PravahaException> sinkFailure,
            long sinkRowsWritten) {

        /**
         * Rows in, or {@code -1} when withheld.
         *
         * <p>{@code -1} rather than empty or zero, and that is the Flight wire's contract (SX-18): the
         * counter is never negative, so the value cannot be mistaken for a count, and both SDKs parse
         * it. Zero would be a plausible lie about a view that has rows.
         */
        public long rowsIn() {
            return restricted ? -1 : query.rowsIn();
        }

        /** Rows this name's sink has accepted, {@code -1} when withheld, zero when there is no sink. */
        public long rowsWrittenToSink() {
            return restricted ? -1 : sinkRowsWritten;
        }
    }

    /**
     * Every name this principal may learn exists, in registration order, each decision audited.
     *
     * @param action the audit verb, which names the surface: {@code list} on Flight
     */
    public List<Entry> list(Principal principal, String action) {
        List<Entry> visible = new ArrayList<>();
        for (String name : registry.names()) {
            AccessDecision byName = policy.mayRead(principal, name);
            if (!byName.allowed()) {
                audit.record(AuditEvent.of(principal, action, name, byName, ""));
                continue;
            }
            // Found rather than required: a name dropped between the two calls is simply not listed,
            // rather than failing the listing of every other name.
            Optional<RegisteredQuery> query = registry.find(name);
            if (query.isEmpty()) {
                continue;
            }
            decide(principal, name, query.get(), byName, action).ifPresent(visible::add);
        }
        return visible;
    }

    /**
     * One name, exactly as {@link #list} would show it.
     *
     * <p>Refused when the policy denies the name -- whether or not it exists, so the refusal is not an
     * existence oracle. Empty when the name is allowed and either nothing is registered under it or
     * the listing would hide it by provenance: those two answer identically, because telling them
     * apart would tell a principal denied {@code payroll} that a view reading it exists.
     *
     * @throws PravahaException {@code PRV-7002} when the policy denies the name
     */
    public Optional<Entry> find(Principal principal, String name, String action) {
        AccessDecision byName = policy.mayRead(principal, name);
        if (!byName.allowed()) {
            audit.record(AuditEvent.of(principal, action, name, byName, ""));
            throw new PravahaException(SecurityErrors.FORBIDDEN, principal.id() + " may not read '" + name + "'.");
        }
        Optional<RegisteredQuery> query = registry.find(name);
        if (query.isEmpty()) {
            // Allowed, and nothing there. Recorded, because "who asked about payroll_v2" is a question
            // an auditor asks whether or not it existed.
            audit.record(AuditEvent.of(principal, action, name, byName, "no such query"));
            return Optional.empty();
        }
        return decide(principal, name, query.get(), byName, action);
    }

    /**
     * The other names on the same computation that this principal may also see.
     *
     * <p>Filtered by name: sharing is decided by the plan, not by who registered it, so the names on a
     * shared computation can belong to people who never met -- and one of them may be a name this
     * principal is not allowed to know. Provenance is the same for every name on one computation and
     * was decided already.
     */
    public List<String> sharedNames(Principal principal, Entry entry, String action) {
        List<String> shared = new ArrayList<>();
        for (String other : entry.query().names()) {
            if (other.equals(entry.name())) {
                continue;
            }
            AccessDecision decision = policy.mayRead(principal, other);
            audit.record(
                    AuditEvent.of(principal, action, other, decision, "shares a computation with " + entry.name()));
            if (decision.allowed()) {
                shared.add(other);
            }
        }
        return List.copyOf(shared.stream().sorted().toList());
    }

    /**
     * Of {@code names}, the ones this caller may see, each decided exactly as {@link #find} decides
     * it -- so the links between queries over queries (ADR-056) disclose no name the listing would
     * not.
     */
    public List<String> visible(Principal principal, List<String> names, String action) {
        return names.stream()
                .filter(name -> find(principal, name, action).isPresent())
                .sorted()
                .toList();
    }

    /** The queries whose answers {@code entry} follows, that this caller may see (ADR-056). */
    public List<String> readsFrom(Principal principal, Entry entry, String action) {
        return visible(principal, registry.readsFrom(entry.name()), action);
    }

    /** The queries that follow {@code entry}'s answer, that this caller may see (ADR-056). */
    public List<String> dependants(Principal principal, Entry entry, String action) {
        return visible(principal, registry.dependantsOf(entry.name()), action);
    }

    private Optional<Entry> decide(
            Principal principal, String name, RegisteredQuery query, AccessDecision byName, String action) {
        boolean restricted = byName.rowFilter().isPresent();
        for (String stream : query.view().derivedFrom()) {
            if (stream.equals(query.view().name())) {
                continue;
            }
            AccessDecision behind = policy.mayReadThrough(principal, stream);
            if (!behind.allowed()) {
                audit.record(AuditEvent.of(
                        principal,
                        action,
                        stream,
                        behind,
                        "hidden from the listing: " + query.view().name()));
                return Optional.empty();
            }
            // A filter anywhere behind the view restricts what this principal may read through it, so
            // the view's totals are not theirs either -- withheld on the same evidence the rows are.
            restricted = restricted || behind.rowFilter().isPresent();
        }
        audit.record(AuditEvent.of(principal, action, name, byName, ""));
        return Optional.of(new Entry(
                name,
                query,
                restricted,
                registry.sinkOf(name),
                registry.sinkFailure(name),
                registry.rowsWrittenToSink(name)));
    }
}
