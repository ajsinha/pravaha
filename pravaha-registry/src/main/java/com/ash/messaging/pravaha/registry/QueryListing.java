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
import com.ash.messaging.pravaha.security.ViewNames;

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
 *
 * <p><strong>In the caller's tenant (ADR-060).</strong> A name is looked up in the caller's tenant,
 * and a listing holds the caller's tenant's names only -- every tenant's for an admin, the others' by
 * catalogue name. Another tenant's name is not a name this principal is refused: it is one nothing
 * holds, so it is neither listed nor audited as a refusal.
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
     * @param name the name as this principal is shown it: bare in its own tenant, the catalogue name
     *     outside it (ADR-060)
     * @param restricted the principal's access is conditional on a row filter somewhere on or behind
     *     the view, so its totals are not theirs to know
     * @param owner the id of the principal who registered it, or replaced it last: who may administer it
     *     without a grant. Empty only when the registry recorded nobody
     * @param engineName what the registry, the policy and the catalogue key it by
     */
    public record Entry(
            String name,
            RegisteredQuery query,
            boolean restricted,
            Optional<String> sink,
            Optional<PravahaException> sinkFailure,
            long sinkRowsWritten,
            Optional<String> owner,
            String engineName) {

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
        for (String name : registry.names(principal)) {
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
     * @param name the name as the caller wrote it, resolved in the caller's tenant
     * @throws PravahaException {@code PRV-7002} when the policy denies the name, or a caller who is not
     *     an admin names another tenant
     */
    public Optional<Entry> find(Principal principal, String name, String action) {
        name = QueryRegistry.engineName(principal, name);
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
            if (other.equals(entry.engineName())) {
                continue;
            }
            AccessDecision decision = policy.mayRead(principal, other);
            audit.record(AuditEvent.of(
                    principal, action, other, decision, "shares a computation with " + entry.engineName()));
            if (decision.allowed()) {
                shared.add(ViewNames.shown(principal, other));
            }
        }
        return List.copyOf(shared.stream().sorted().toList());
    }

    /**
     * Of {@code names} -- engine names -- the ones this caller may see, each decided exactly as {@link
     * #find} decides it and shown as the listing shows it, so the links between queries over queries
     * (ADR-056) disclose no name the listing would not.
     */
    public List<String> visible(Principal principal, List<String> names, String action) {
        return names.stream()
                .filter(name -> ViewNames.visibleTo(principal, name))
                .map(name -> ViewNames.shown(principal, name))
                .filter(name -> find(principal, name, action).isPresent())
                .sorted()
                .toList();
    }

    /** The queries whose answers {@code entry} follows, that this caller may see (ADR-056). */
    public List<String> readsFrom(Principal principal, Entry entry, String action) {
        return visible(principal, registry.readsFrom(entry.engineName()), action);
    }

    /**
     * The queries that follow {@code entry}'s answer, that this caller may see (ADR-056), then the
     * alerts on it the caller may see, as {@code ALERT <name>} (ALERTDEPS-1).
     */
    public List<String> dependants(Principal principal, Entry entry, String action) {
        List<String> shown =
                new java.util.ArrayList<>(visible(principal, registry.chains.dependantsOf(entry.engineName()), action));
        shown.addAll(registry.alerting().followersOf(entry.engineName(), principal));
        return List.copyOf(shown);
    }

    private Optional<Entry> decide(
            Principal principal, String name, RegisteredQuery query, AccessDecision byName, String action) {
        boolean restricted = byName.rowFilter().isPresent() || narrowedByRows(principal, name);
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
                ViewNames.shown(principal, name),
                query,
                restricted,
                registry.sinkOf(name),
                registry.sinkFailure(name),
                registry.rowsWrittenToSink(name),
                registry.owners().ownerOf(name).map(Principal::id),
                name));
    }

    /**
     * Whether a row filter the policy keeps as an object -- the catalogue's {@code CREATE ROW FILTER}
     * (ADR-059 §4) -- narrows what this principal reads of {@code name} (LISTCOUNT-1).
     *
     * <p>SX-18 withheld the totals only for a filter carried on the {@link AccessDecision}, which is how
     * {@code pravaha.security.policy} expresses one; the catalogue answers {@code mayRead} with a plain
     * allow and narrows through {@link SecurityPolicy#narrowing}, so a reader its filter cut to two rows
     * of three was told "3". A narrowing that cannot be bound to this principal (a claim their
     * credential does not carry) refuses their read, so their totals are withheld too.
     */
    private boolean narrowedByRows(Principal principal, String name) {
        try {
            return policy.narrowing(principal, name).rowFilter().isPresent();
        } catch (PravahaException unbindable) {
            return true;
        }
    }
}
