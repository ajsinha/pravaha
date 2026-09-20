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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

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

        /**
         * A ring in all but name, under a monitor (SX-9).
         *
         * <p>This was a {@code CopyOnWriteArrayList} with {@code events.remove(0)} past the limit,
         * and both halves of that are O(n) per append: the copy-on-write array is copied whole on
         * every {@code add}, and {@code remove(0)} shifts it again. Measured at a 10,000-event
         * limit, the first 10,000 records took 144 ms and the next 10,000 took 498 ms -- 3.5x
         * slower for the same volume, arriving exactly when a node is under the load that
         * generates the most events to audit.
         *
         * <p>A deque under a lock is not obviously the faster choice and is: the reads here are a
         * snapshot copy taken by an investigator, not a hot path, while {@code record} is called
         * on every authorization decision. Copy-on-write optimises the wrong one of the two.
         */
        private final ArrayDeque<AuditEvent> events;

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
            if (limit < 1) {
                throw new IllegalArgumentException("an in-memory audit sink must keep at least one event, got " + limit
                        + ": a limit of zero records nothing while reporting that it audits");
            }
            this.limit = limit;
            this.events = new ArrayDeque<>(Math.min(limit, 1024));
        }

        @Override
        public void record(AuditEvent event) {
            synchronized (events) {
                events.addLast(event);
                while (events.size() > limit) {
                    events.removeFirst();
                }
            }
        }

        public List<AuditEvent> events() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }

        /** Events for one principal, which is the question an investigation starts with. */
        public List<AuditEvent> forPrincipal(String principalId) {
            return events().stream()
                    .filter(event -> event.principal().id().equals(principalId))
                    .toList();
        }

        public List<AuditEvent> denials() {
            return events().stream().filter(event -> !event.allowed()).toList();
        }
    }
}
