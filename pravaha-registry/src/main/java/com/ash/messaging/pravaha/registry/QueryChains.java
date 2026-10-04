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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.runtime.exec.QueryExecution;
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.runtime.plan.ScanOperator;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.ViewNames;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.serving.ServedView;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;
import com.ash.messaging.pravaha.sql.plan.MaintainedViews;
import com.ash.messaging.pravaha.sql.plan.PreparedContinuousQuery;

/**
 * Queries over queries (ADR-056): which views a registration may read, what it may not do with them,
 * and the feed that follows one.
 *
 * <p>Everything a chain needs of the registry, kept out of {@link QueryRegistry} so the registry
 * stays the place a registration's steps are ordered in rather than the place each step is
 * implemented. Called under the registry's monitor.
 *
 * <p>A chain is derived, never stored: a query's upstreams are the scans in its plan that name a
 * registered view rather than a stream, and its dependants are the queries whose plans scan its
 * name. Nothing has to be kept in step with a drop, a restart or a shared computation, because
 * nothing is kept.
 *
 * <p><strong>Names (ADR-060).</strong> A plan scans a view by the bare name its SQL wrote, and an
 * upstream is resolved in the downstream registrant's tenant: the scan {@code orders} of a query in
 * tenant {@code acme} is the view {@code acme.default.orders}. Everything here that answers with a
 * view answers with its engine name; a plan's scan names are what the planner saw.
 */
final class QueryChains {

    /** How many levels of queries over queries one chain may have (PRV-8027). */
    static final int MAX_DEPTH = 8;

    /** The checkpoint entry a downstream's consumed answer is carried in (ADR-056 §2). */
    static final String INPUT_STATE = "upstream-input";

    private final QueryRegistry registry;

    /** One identity per engine name for the life of the process, so identical SQL fingerprints alike. */
    private final Map<String, Integer> viewIds = new ConcurrentHashMap<>();

    QueryChains(QueryRegistry registry) {
        this.registry = registry;
    }

    /** Whether a blue/green replacement of {@code name} is in flight (ADR-046). */
    private boolean replacing(String name) {
        QueryReplacements running = registry.replacements;
        return running != null && running.isReplacing(name);
    }

    // ------------------------------------------------------------------ planning

    /**
     * Plans {@code sql} over the registry's streams and lookups and over the views the principal's
     * tenant registered that the text mentions.
     *
     * <p>Only views of the caller's own tenant are offered (ADR-050): another tenant's plans as a name
     * that does not exist, so the refusal says nothing about it. Only views the text names are
     * offered, so a registry of thousands of queries does not make every planner a thousand tables
     * wide. A view whose name is a stream's or a lookup's is not offered: the stream is what the name
     * has always meant.
     */
    PreparedContinuousQuery plan(
            String sql,
            BoundParameters parameters,
            Principal principal,
            List<StreamSchema> streams,
            List<StreamSchema> lookups) {
        return plan(sql, parameters, principal, streams, lookups, java.util.Map.of(), List.of());
    }

    /**
     * As {@link #plan(String, BoundParameters, Principal, List, List)}, reading each input {@code
     * narrowings} names through that narrowing (ADR-059 §4), and refusing a masked column used as one of
     * {@code keyColumns} or compared.
     */
    PreparedContinuousQuery plan(
            String sql,
            BoundParameters parameters,
            Principal principal,
            List<StreamSchema> streams,
            List<StreamSchema> lookups,
            java.util.Map<String, com.ash.messaging.pravaha.security.Narrowing> narrowings,
            List<Integer> keyColumns) {
        Set<String> taken = new java.util.HashSet<>();
        streams.forEach(stream -> taken.add(stream.name()));
        lookups.forEach(lookup -> taken.add(lookup.name()));
        List<StreamSchema> views = new ArrayList<>();
        for (String engine : registry.names()) {
            // ADR-060: the caller's tenant's views, each under the bare name its SQL would write.
            if (!principal.tenant().equals(ViewNames.tenantOf(engine))) {
                continue;
            }
            String name = ViewNames.localName(engine);
            if (taken.contains(name) || !mentions(sql, name)) {
                continue;
            }
            registry.find(engine).ifPresent(query -> views.add(inputSchema(name, engine, query.view())));
        }
        if (views.isEmpty()) {
            return PreparedContinuousQuery.of(
                    sql, parameters, streams, lookups, MaintainedViews.NONE, narrowings, keyColumns);
        }
        List<StreamSchema> all = new ArrayList<>(streams);
        all.addAll(views);
        return PreparedContinuousQuery.of(
                sql,
                parameters,
                all,
                lookups,
                MaintainedViews.of(views.stream().map(StreamSchema::name).toList()),
                narrowings,
                keyColumns);
    }

    private static boolean mentions(String sql, String name) {
        // The cheap test first: a registry of a thousand names is a thousand of these per
        // registration, and almost all of them are not in the text at all.
        if (!sql.toLowerCase(java.util.Locale.ROOT).contains(name.toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        return Pattern.compile(
                        "(?<![\\p{L}\\p{N}_])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE)
                .matcher(sql)
                .find();
    }

    /**
     * A view's columns as an input: its name, its fields, an identity of its own, and no event-time
     * column -- a view's rows carry the upstream's frontier, which is not a column of them (§6).
     */
    private StreamSchema inputSchema(String name, String engine, ServedView view) {
        StreamSchema.Builder builder = StreamSchema.builder(name);
        view.schema().fields().forEach(field -> builder.field(field.name(), field.type()));
        return builder.build().withStreamId(viewIds.computeIfAbsent(engine, n -> StreamIdentities.nextViewId()));
    }

    // ------------------------------------------------------------------ what a plan reads

    /**
     * The engine name of a scan in a plan of {@code tenant}'s: a view the tenant holds under that bare
     * name, or the scan itself -- a stream, a lookup, or nothing registered.
     */
    String engineNameOf(String source, String tenant) {
        if (streamNames().contains(source)) {
            return source;
        }
        String engine = ViewNames.engineName(tenant, source);
        return registry.find(engine).isPresent() ? engine : source;
    }

    /** What {@code plan} scans, a view by its engine name: what the policy is asked about. */
    List<String> sources(PhysicalOperator plan, String tenant) {
        return PlanSources.of(plan).stream()
                .map(source -> engineNameOf(source, tenant))
                .toList();
    }

    /** The registered views a plan of {@code tenant}'s reads, by engine name, in plan order. */
    List<String> upstreamsOf(PhysicalOperator plan, String tenant) {
        Set<String> streams = streamNames();
        List<String> upstreams = new ArrayList<>();
        for (String source : PlanSources.of(plan)) {
            String engine = ViewNames.engineName(tenant, source);
            if (!streams.contains(source) && registry.find(engine).isPresent()) {
                upstreams.add(engine);
            }
        }
        return upstreams;
    }

    /** The upstreams of a registered computation, resolved in the tenant its names are in. */
    private List<String> upstreamsOf(RegisteredQuery query) {
        return upstreamsOf(query.plan(), ViewNames.tenantOf(query.name()));
    }

    /**
     * What {@code plan} derives from: each name it scans and, for a view, everything that view
     * derives from -- so authorization follows the data to the base streams (SX-11). Views by engine name.
     */
    List<String> provenance(PhysicalOperator plan, String tenant) {
        Set<String> found = new LinkedHashSet<>(sources(plan, tenant));
        for (String upstream : upstreamsOf(plan, tenant)) {
            registry.find(upstream).ifPresent(query -> found.addAll(query.view().derivedFrom()));
        }
        return List.copyOf(found);
    }

    /**
     * Each upstream's name and the fingerprint of the computation answering it, for a fingerprint. The
     * name as the plan scans it: the tenant is in the fingerprint already, and a registration made
     * before per-tenant names keeps the fingerprint its checkpoints were written under (ADR-060).
     */
    List<String> identities(PhysicalOperator plan, String tenant) {
        List<String> identities = new ArrayList<>();
        for (String upstream : upstreamsOf(plan, tenant)) {
            registry.find(upstream)
                    .ifPresent(query -> identities.add(ViewNames.localName(upstream) + "="
                            + query.fingerprint().value()));
        }
        return identities;
    }

    /** Whether rows from {@code stream} can be retractions: every view's can (HLP-3's question). */
    boolean retracts(String stream, String tenant) {
        return !streamNames().contains(stream)
                && registry.find(ViewNames.engineName(tenant, stream)).isPresent();
    }

    /** The queries whose plans read {@code name}, by every name each answers to, sorted. */
    List<String> dependantsOf(String name) {
        Set<String> dependants = new java.util.TreeSet<>();
        for (RegisteredQuery query : registry.queries()) {
            if (query.state() != QueryState.DROPPED && upstreamsOf(query).contains(name)) {
                dependants.addAll(query.names());
            }
        }
        return List.copyOf(dependants);
    }

    /**
     * {@link #dependantsOf}, then the alerts following {@code name}'s answer as {@code ALERT <name>}
     * (ALERTDEPS-1): everything a drop of it is refused for (PRV-8024), which is the lineage a person
     * reading the view's page needs.
     */
    List<String> dependantsWithAlerts(String name) {
        List<String> dependants = new ArrayList<>(dependantsOf(name));
        dependants.addAll(registry.alerting().followersOf(name));
        return List.copyOf(dependants);
    }

    /** The views {@code name}'s computation reads, or empty when it reads only streams. */
    List<String> readsFrom(String name) {
        return registry.find(name).map(this::upstreamsOf).orElse(List.of());
    }

    private int depthOf(String name, int guard) {
        if (guard > MAX_DEPTH + 1) {
            return guard;
        }
        int deepest = 0;
        for (String upstream : readsFrom(name)) {
            deepest = Math.max(deepest, 1 + depthOf(upstream, guard + 1));
        }
        return deepest;
    }

    private Set<String> streamNames() {
        Set<String> names = new java.util.HashSet<>();
        Arrays.stream(registry.streams()).forEach(stream -> names.add(stream.name()));
        return names;
    }

    // ------------------------------------------------------------------ refusals

    /**
     * Refuses a registration over views that cannot be made exact (ADR-056): one being replaced, a
     * retention of its own, a column type a row cannot carry, a chain too deep, and any replacement.
     */
    void requireChainable(String name, PhysicalOperator plan, Retention retention, String action) {
        List<String> upstreams = upstreamsOf(plan, ViewNames.tenantOf(name));
        if (upstreams.isEmpty()) {
            return;
        }
        if ("replace".equals(action)) {
            throw unsupported("a new version of '" + name + "' would read " + upstreams + ", and a replacement "
                    + "cuts over at a source position both versions have consumed; a view has no such position, "
                    + "so a query over a view cannot be put behind a name by CREATE OR REPLACE. Drop the query and "
                    + "register the new version.");
        }
        for (String upstream : upstreams) {
            if (replacing(upstream)) {
                throw unsupported("'" + upstream + "' is being replaced, and a cutover would move that name to "
                        + "another computation while '" + name + "' follows the first. Register it after the "
                        + "replacement is cut over, rolled back or abandoned.");
            }
            for (var field :
                    registry.find(upstream).orElseThrow().view().schema().fields()) {
                if (!ViewRowValues.CARRIED.contains(field.type().typeName())) {
                    throw unsupported(
                            "'" + upstream + "' has the " + field.type().typeName() + " column '"
                                    + field.name() + "', which a query over a view cannot be fed. Project it away "
                                    + "in '" + upstream + "' or read the view with a plain SELECT.");
                }
            }
        }
        if (retention != null && !retention.isForever()) {
            throw unsupported("'" + name + "' reads " + upstreams + " and asks to keep rows for " + retention
                    + ". Its rows carry the upstream's frontier as their time, so a row unchanged upstream "
                    + "would be evicted here while still in the upstream's answer. Retention belongs to the "
                    + "upstream: say RETAIN FOREVER here, or retain less in '" + upstreams.get(0) + "'.");
        }
        int depth = 0;
        for (String upstream : upstreams) {
            depth = Math.max(depth, 1 + depthOf(upstream, 1));
        }
        if (depth > MAX_DEPTH) {
            throw new PravahaException(
                    RegistryErrors.CHAIN_TOO_DEEP,
                    "'" + name + "' would be " + depth + " queries deep over its streams, and a chain may be at most "
                            + MAX_DEPTH + ". Each level adds a commit's latency and a copy of its input's answer; "
                            + "fold some of the steps into one query.");
        }
    }

    /** Refuses dropping a name other queries read (PRV-8024), naming them. */
    void refuseDrop(String name) {
        // An alert follows the answer as a query over it does (ADR-057), and is refused for alike.
        List<String> dependants = dependantsWithAlerts(name);
        if (!dependants.isEmpty()) {
            throw new PravahaException(
                    RegistryErrors.QUERY_HAS_DEPENDANTS,
                    "'" + name + "' cannot be dropped: " + dependants + " read" + (dependants.size() == 1 ? "s" : "")
                            + " its answer. Drop " + (dependants.size() == 1 ? "it" : "them") + " first. There is "
                            + "no cascade: it would take answers away from queries somebody else registered.");
        }
    }

    /**
     * Refuses a replacement that a chain makes inexact (ADR-056 §4), the loop first: a new version
     * of {@code name} reading, through other queries, {@code name} itself (PRV-8025).
     */
    void refuseReplacement(String name, String sql, Principal principal) {
        PhysicalOperator next = null;
        try {
            next = registry.planAs(sql, BoundParameters.none(), principal).plan();
        } catch (RuntimeException e) {
            // The replacement's own path plans it again and refuses it there, for its SQL.
        }
        if (next != null) {
            for (String upstream : upstreamsOf(next, principal.tenant())) {
                List<String> loop = pathTo(upstream, name, 0);
                if (loop != null) {
                    List<String> shown = new ArrayList<>();
                    shown.add(name);
                    shown.addAll(loop);
                    throw new PravahaException(
                            RegistryErrors.QUERY_CYCLE,
                            "the new version of '" + name + "' would read its own answer: "
                                    + String.join(" reads ", shown) + ". A query can read only answers that do not "
                                    + "depend on it.");
                }
            }
        }
        List<String> dependants = dependantsWithAlerts(name);
        if (!dependants.isEmpty()) {
            throw unsupported("'" + name + "' is read by " + dependants + ", which follow the computation answering "
                    + "it now. A cutover would move the name to another computation behind them. Drop them, replace "
                    + "'" + name + "', and register them again.");
        }
        List<String> reads = readsFrom(name);
        if (!reads.isEmpty()) {
            throw unsupported("'" + name + "' reads " + reads + ", and a query over a view has no source position a "
                    + "new version could be cut over at. Drop it and register the new version.");
        }
    }

    /** The names from {@code from} down its upstreams to {@code to}, or null when it does not reach. */
    private @Nullable List<String> pathTo(String from, String to, int guard) {
        if (from.equals(to)) {
            return List.of(from);
        }
        if (guard > MAX_DEPTH + 1) {
            return null;
        }
        for (String upstream : readsFrom(from)) {
            List<String> rest = pathTo(upstream, to, guard + 1);
            if (rest != null) {
                List<String> path = new ArrayList<>();
                path.add(from);
                path.addAll(rest);
                return path;
            }
        }
        return null;
    }

    private static PravahaException unsupported(String why) {
        return new PravahaException(RegistryErrors.CHAIN_UNSUPPORTED, why + " (ADR-056)");
    }

    // ------------------------------------------------------------------ the feed

    /**
     * Opens the feed of a query that reads a view, or empty when it reads only streams.
     *
     * <p>After the restore: the reader starts from the consumed answer the checkpoint carried, and
     * a restored view with none is refused rather than fed the whole answer again on top of it.
     */
    Optional<SourceFeed> open(String name, QueryExecution execution, PhysicalOperator plan, RegisteredQuery query) {
        List<String> upstreams = upstreamsOf(plan, ViewNames.tenantOf(name));
        if (upstreams.isEmpty()) {
            return Optional.empty();
        }
        // The engine name to follow, and the bare name the plan scans it by (ADR-060).
        String followedName = upstreams.get(0);
        String upstream = ViewNames.localName(followedName);
        RegisteredQuery followed = registry.find(followedName).orElseThrow(() -> QueryNames.noSuchQuery(followedName));
        byte[] image = query.restoredUpstreamInput();
        if (image == null && query.restored() && query.view().size() > 0) {
            throw unsupported("'" + name + "' was restored from a checkpoint that holds its view and not what it had "
                    + "consumed from '" + upstream + "'. Following '" + upstream
                    + "' from nothing would feed its whole "
                    + "answer again on top of what the view already counted, so the restore is refused");
        }
        StreamSchema schema = scanOf(plan, upstream);
        UpstreamReader reader = new UpstreamReader(
                upstream,
                followed.view(),
                java.util.Objects.requireNonNull(schema, "the plan reads what it follows"),
                image);
        var pump = execution.pumpInto(0, upstream, 0, reader, BackpressurePolicy.defaults());
        query.cuttingUpstreamWith(reader::cut);
        return Optional.of(new UpstreamFeed(name, upstream, followed, pump, reader, query).start());
    }

    private static @Nullable StreamSchema scanOf(PhysicalOperator plan, String name) {
        if (plan instanceof ScanOperator scan && scan.streamName().equals(name)) {
            return scan.outputSchema();
        }
        for (PhysicalOperator input : plan.inputs()) {
            StreamSchema found = scanOf(input, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
