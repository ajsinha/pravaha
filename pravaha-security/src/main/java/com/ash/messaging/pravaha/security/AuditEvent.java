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

import java.time.Instant;
import java.util.Optional;

/**
 * One line of the record of who asked what.
 *
 * <p>§25 requires an append-only audit of every lifecycle and authorization decision: actor, action,
 * target, timestamp, result. This is that record, and it is emitted for <strong>allowed</strong>
 * decisions as well as denied ones -- an audit log that only contains refusals answers "who was
 * stopped" and not "who read the salary view", which is the question that actually gets asked.
 *
 * @param at when, from the clock rather than from event time: this is a fact about the system, not
 *     about the data
 * @param principal who
 * @param action what they tried to do
 * @param target what they tried it on
 * @param allowed the outcome
 * @param reason why, carried from the decision
 * @param detail the query text or other context, when the deployment wants it recorded
 */
public record AuditEvent(
        Instant at,
        Principal principal,
        String action,
        String target,
        boolean allowed,
        String reason,
        Optional<String> detail) {

    public AuditEvent {
        detail = detail == null ? Optional.empty() : detail;
    }

    public static AuditEvent of(
            Principal principal, String action, String target, AccessDecision decision, String detail) {
        return new AuditEvent(
                Instant.now(),
                principal,
                action,
                target,
                decision.allowed(),
                decision.reason(),
                Optional.ofNullable(detail));
    }

    @Override
    public String toString() {
        return at + " " + (allowed ? "ALLOW" : "DENY") + " " + principal.id() + " " + action + " " + target + " ("
                + reason + ")";
    }
}
