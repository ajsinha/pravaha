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
package com.ash.messaging.pravaha.security;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where audit records go.
 *
 * <p>An SPI for the same reason the policy is one: every deployment already has somewhere audit
 * belongs, and it is never the place the engine would have picked.
 *
 * <p><strong>An audit sink must not fail a query it is auditing.</strong> A remote sink that is down
 * would otherwise take the query path down with it, which turns an observability outage into a
 * production one. Implementations buffer and drop rather than block, and the engine calls them
 * outside the answer's critical path.
 */
public interface AuditSink {

    /** Discards everything. For a deployment that has decided it does not audit, said out loud. */
    AuditSink NONE = event -> {};

    void record(AuditEvent event);

    /** Keeps events in memory. For tests, and for a single-node deployment with nowhere else yet. */
    final class InMemory implements AuditSink {

        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();
        private final int limit;

        public InMemory() {
            this(10_000);
        }

        /**
         * @param limit the oldest events are dropped past this. Bounded because an audit log in
         *     memory is still memory, and the alternative to dropping is an engine that dies of its
         *     own record-keeping
         */
        public InMemory(int limit) {
            this.limit = limit;
        }

        @Override
        public void record(AuditEvent event) {
            events.add(event);
            while (events.size() > limit) {
                events.remove(0);
            }
        }

        public List<AuditEvent> events() {
            return new ArrayList<>(events);
        }

        /** Events for one principal, which is the question an investigation starts with. */
        public List<AuditEvent> forPrincipal(String principalId) {
            return events.stream()
                    .filter(event -> event.principal().id().equals(principalId))
                    .toList();
        }

        public List<AuditEvent> denials() {
            return events.stream().filter(event -> !event.allowed()).toList();
        }
    }
}
