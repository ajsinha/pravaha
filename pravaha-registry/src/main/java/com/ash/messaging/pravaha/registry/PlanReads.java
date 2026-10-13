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

import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.sql.plan.BoundParameters;

/**
 * What a query would read if {@code principal} planned it here, from the plan rather than the text: every
 * stream and view it scans, a view by its engine name -- what the security policy is asked about.
 *
 * <p>For a caller outside this package that shows a plan without registering it (the AI layer's explain,
 * docs/guides/AI.md): matching names in the SQL text misses a qualified {@code pravaha.secret_view}, which
 * the planner resolves all the same (AIQA-5). Kept out of {@link QueryRegistry}, which is at the
 * project's file-size ceiling.
 */
public final class PlanReads {

    private PlanReads() {}

    /** The engine names of what {@code sql} scans, planned as {@code principal}'s registration would plan it. */
    public static List<String> of(QueryRegistry registry, String sql, Principal principal) {
        return registry.chains.sources(
                registry.planAs(sql, BoundParameters.none(), principal).plan(), principal.tenant());
    }
}
