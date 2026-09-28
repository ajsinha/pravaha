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
import java.util.Collections;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.AuditEvent;
import com.ash.messaging.pravaha.security.AuditSink;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityErrors;
import com.ash.messaging.pravaha.security.SecurityPolicy;

/**
 * The three questions a registration asks of the security policy, in the order it asks them.
 *
 * <p>Lives beside {@link QueryRegistry} rather than inside it because that class had reached the
 * project's 1500-line ceiling, and because these belong together: may this principal register
 * anything, may they read what the query reads, and may they have the answer written where they
 * asked for it to be written. Each is audited whatever it answers.
 *
 * <p>It is asked <em>after</em> the source reads, and the order is the shape of the question. The
 * reads decide what the rows are and are the check that can refuse a disclosure; this one decides
 * where permitted rows come to rest. Writing to a sink is not itself a disclosure -- a registrant
 * can only write what the reads let them read -- but a sink is read by people outside Pravaha and
 * written under this node's own credentials, so "who put this there" has to be answerable from the
 * trail. Which is the other half of this: the sink is a target in the audit, and before SINK-3 the
 * register event recorded the SQL and never the destination.
 */
final class RegistrationAuthorization {

    private RegistrationAuthorization() {}

    /**
     * May this principal register anything at all, and may they read what this query reads?
     *
     * <p>Registration used to ask only the first. So a principal who could register but could not
     * read {@code payroll} could register {@code SELECT * FROM payroll} under a name of their
     * choosing and then read that view -- because the read check is against the view's name, and
     * the policy was never told what the view derives from. A careful policy author could not have
     * refused it; the engine gave them nothing to refuse on.
     *
     * @return the row filters the reads carry, sorted, so that two principals holding the same
     *     filters in a different order share a computation and two holding different ones do not
     */
    static List<String> requireReads(
            SecurityPolicy policy,
            AuditSink audit,
            Principal principal,
            String action,
            String name,
            String sql,
            java.util.Collection<String> direct,
            Iterable<String> sources) {
        AccessDecision decision = policy.mayRegisterQuery(principal, name);
        audit.record(AuditEvent.of(principal, action, name, decision, sql));
        if (!decision.allowed()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN, principal.id() + " may not register a query: " + decision.reason());
        }
        List<String> rowFilters = new ArrayList<>();
        for (String source : sources) {
            // ADR-059: building on what the plan names is BUILD_ON; a source reached only through an
            // upstream view is asked separately, so a policy can grant a view without its sources.
            AccessDecision read = direct.contains(source)
                    ? policy.mayBuildOn(principal, source)
                    : policy.mayBuildThrough(principal, source);
            audit.record(AuditEvent.of(principal, action + ":source", source, read, sql));
            if (!read.allowed()) {
                throw new PravahaException(
                        SecurityErrors.FORBIDDEN,
                        principal.id() + " may not " + action + " '" + name + "' because it reads '" + source
                                + "', which they may not read: " + read.reason()
                                + ". A registration is a standing read of everything the query names, so it "
                                + "is refused here rather than at the first row.");
            }
            read.rowFilter().ifPresent(rowFilters::add);
        }
        Collections.sort(rowFilters);
        return rowFilters;
    }

    /**
     * Records the decision and throws if it refuses. A null {@code sinkName} asks nothing: a query
     * that writes nowhere has nothing to authorize.
     *
     * @param action {@code register} or {@code replace}, which is what the audit records it as
     */
    static void requireSink(
            SecurityPolicy policy,
            AuditSink audit,
            Principal principal,
            String action,
            String name,
            String sinkName,
            String sql) {
        if (sinkName == null) {
            return;
        }
        AccessDecision write = policy.mayWriteTo(principal, sinkName);
        audit.record(AuditEvent.of(principal, action + ":sink", sinkName, write, sql));
        if (!write.allowed()) {
            throw new PravahaException(
                    SecurityErrors.FORBIDDEN,
                    principal.id() + " may not " + action + " '" + name + "' writing to sink '" + sinkName
                            + "': " + write.reason()
                            + ". A sink is written under this node's credentials and read outside it, so "
                            + "naming one is a permission of its own rather than part of registering.");
        }
        if (write.rowFilter().isPresent()) {
            // Refused rather than ignored: ignoring it writes the excluded rows anyway, which is
            // the silent half of a permission nobody would find until they read the table.
            throw new PravahaException(
                    SecurityErrors.SINK_WRITE_NOT_FILTERABLE,
                    "the policy would let " + principal.id() + " write to sink '" + sinkName
                            + "' only through the row filter ("
                            + write.rowFilter().get()
                            + "), and a sink takes the query's whole changelog or none of it. Refused rather "
                            + "than written unfiltered: answer mayWriteTo with allow() or deny().");
        }
    }
}
