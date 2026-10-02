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

import java.util.List;

import com.ash.messaging.pravaha.runtime.plan.PhysicalOperator;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.Retention;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;
import com.ash.messaging.pravaha.sql.plan.ParameterPlacement;
import com.ash.messaging.pravaha.sql.plan.PreparedContinuousQuery;

/**
 * Plans a registration and decides whether its principal may have it, without starting anything.
 *
 * <p>Shared by {@code register} and by a blue/green replacement's shadow (ADR-046), which has to be
 * judged by exactly the same rules: a principal who may not read what the new version reads must not
 * be able to put it behind a name whose readers would then be served by it. And by {@link
 * DraftFingerprint}, for the fingerprint a registration would get (EXPLAINFP-1).
 *
 * <p>Lives beside {@link QueryRegistry} because that class had reached the project's size ceiling;
 * called under the registry's monitor.
 *
 * <p><strong>Names (ADR-060).</strong> The planner sees each view the registrant's tenant holds under
 * its bare name, because that is what the SQL says; the policy is asked about each input by its engine
 * name, because that is what it -- and the catalogue behind it -- keys a view by. A view of the default
 * tenant has one name for both.
 */
final class RegistrationPlanning {

    private RegistrationPlanning() {}

    /**
     * @param name the engine name the registration would have
     * @param action the audit action: {@code register}, {@code replace} or {@code explain}
     */
    static QueryRegistry.Preparation prepare(
            QueryRegistry registry,
            String name,
            String sql,
            List<Integer> keyColumns,
            Principal principal,
            Retention retention,
            BoundParameters parameters,
            String sinkName,
            String action) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "a registration needs at least one key column: a view with no key is a log, and a "
                            + "point read against it has nothing to look up");
        }
        QueryChains chains = registry.chains;
        SourceFeedFactory feeds = registry.feeds();
        String tenant = principal.tenant();

        PreparedContinuousQuery prepared = registry.planAs(sql, parameters, principal);
        List<ParameterPlacement> placements = prepared.placements();
        PhysicalOperator plan = prepared.plan();

        // SCAN-1. Every registration, sink or not: a COUNT over a source that re-reads its rows grows
        // on every pass, and the view is where that was first wrong. The sink, when there is one, is
        // described here too, so a sink that would write every copy as a row is refused with it.
        SinkFactory.Description sink =
                sinkName == null ? null : registry.sinks().describe(sinkName);
        com.ash.messaging.pravaha.sql.plan.RepeatedRowsAnalysis.check(
                plan, feeds::repeatingSource, sink == null ? null : sink.capabilities(), sinkName);

        if (sink != null) {
            // Before the feed, before the view, before a row can exist. capabilitiesOf configures
            // the plugin and asks it, without opening a connection, so a refusal costs nothing --
            // and a query whose changelog the sink cannot take is refused as a PAIR: the query may
            // be perfectly good against a different sink, and the fix is usually the sink rather
            // than the SQL.
            // Knowing which streams delete (HLP-3): a join or a filter over a change feed passes its
            // deletes on as retractions, and a sink that can only append would write them as rows.
            com.ash.messaging.pravaha.sql.plan.ChangelogAnalysis.checkAgainst(
                    plan,
                    stream -> chains.retracts(stream, tenant) || feeds.retracts(stream),
                    sink.capabilities(),
                    sinkName);
            SinkShape.require(sink, plan.outputSchema(), keyColumns, sinkName);
        }

        // The policy's three questions, in RegistrationAuthorization: may they register, may they
        // read each source, and -- below -- may they write to the sink.
        List<String> rowFilters = RegistrationAuthorization.requireReads(
                registry.policy(),
                registry.audit(),
                principal,
                action,
                name,
                sql,
                chains.sources(plan, tenant),
                chains.provenance(plan, tenant));
        var narrowings = RegistrationAuthorization.narrowings(
                registry.policy(),
                registry.audit(),
                principal,
                action,
                sql,
                PlanSources.of(plan),
                input -> chains.engineNameOf(input, tenant),
                rowFilters);
        if (!narrowings.isEmpty()) { // ADR-059 §4: read each input as the registrant is shown it
            prepared = chains.plan(
                    sql,
                    parameters,
                    principal,
                    List.of(registry.streams()),
                    registry.lookupSchemas(),
                    narrowings,
                    keyColumns);
            plan = prepared.plan();
            placements = prepared.placements();
        }
        chains.requireChainable(name, plan, retention, action);
        // FINEHOP-1: a window whose rows each land in more windows, or whose windows each combine
        // more slices, than the node allows is refused before it runs -- once running, one row of it
        // holds the lane and every push to the stream behind it.
        com.ash.messaging.pravaha.runtime.window.WindowLimits.require(plan, registry.maxWindowsPerRow);

        // SINK-3, and the reason it is asked here rather than beside mayRegisterQuery, is in
        // SinkAuthorization's own javadoc.
        RegistrationAuthorization.requireSink(
                registry.policy(), registry.audit(), principal, action, name, sinkName, sql);

        // Bound values are in the plan, so in the fingerprint: two bindings are two computations,
        // which is why a parameter the view carries should be a tap filter instead. The principal's
        // row filters are in it too: without them a principal restricted to one region and one
        // restricted to none shared one computation, and only the read path stood between them.
        // I-3: the key columns and the retention are part of what makes a computation itself.
        // Without them `--keys 1` and `--keys 0,1` over identical SQL shared one view, keyed as the
        // first registrant asked and with the second one's retention dropped, silently.
        return new QueryRegistry.Preparation(
                plan,
                placements,
                QueryFingerprint.of(plan, rowFilters, keyColumns, retention, tenant, chains.identities(plan, tenant)));
    }
}
