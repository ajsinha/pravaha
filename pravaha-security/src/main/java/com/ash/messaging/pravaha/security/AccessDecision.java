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

import java.util.Optional;

/**
 * Whether a principal may do something, and why.
 *
 * <p>The reason is not decoration. It goes in the audit log, where "denied" without a why is a line
 * nobody can act on, and it goes to the caller, where a refusal that does not say what would have
 * been allowed produces a support ticket rather than a fix.
 *
 * <p><strong>The reason must not leak what it is protecting.</strong> "no access to view
 * 'salaries'" tells an unauthorised caller that a view called salaries exists. Where that matters,
 * a deployment says so with {@link #deniedWithoutDetail()}, which is the security trade written
 * down rather than argued about per call site.
 *
 * @param allowed whether to proceed
 * @param reason why, for the audit log and, when it is safe, for the caller
 * @param rowFilter a predicate the planner must AND into the query, unremovably (§25)
 */
public record AccessDecision(boolean allowed, String reason, Optional<String> rowFilter) {

    public AccessDecision {
        rowFilter = rowFilter == null ? Optional.empty() : rowFilter;
    }

    public static AccessDecision allow() {
        return new AccessDecision(true, "allowed", Optional.empty());
    }

    /**
     * Allowed, but only these rows.
     *
     * @param predicate SQL that is ANDed into the query above the scan. It is injected into the
     *     plan rather than concatenated into the SQL text, so no amount of clever SQL from the
     *     caller can remove it (§25)
     */
    public static AccessDecision allowWithRowFilter(String predicate) {
        return new AccessDecision(true, "allowed with a row filter", Optional.of(predicate));
    }

    public static AccessDecision deny(String reason) {
        return new AccessDecision(false, reason, Optional.empty());
    }

    /** Denied without saying what exists, for deployments where the name is itself sensitive. */
    public static AccessDecision deniedWithoutDetail() {
        return new AccessDecision(false, "not authorised", Optional.empty());
    }
}
