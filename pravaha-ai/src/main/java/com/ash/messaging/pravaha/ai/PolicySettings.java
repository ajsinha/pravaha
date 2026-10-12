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
package com.ash.messaging.pravaha.ai;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What the default policy enforces (all from {@code pravaha.ai.policy.*}). A limit of 0 means none.
 *
 * @param maskRedacted mask the fields named in {@code pravaha.ai.policy.redact} (and {@code maskPatterns}) in every prompt
 * @param maskPatterns regular expressions whose matches are masked in every prompt
 * @param audit write one audit record per call (metadata only: never the prompt or the answer)
 * @param perUserPerMinute calls one user may make per feature per minute
 * @param perUserPerDay calls one user may make per day
 * @param costPerUserPerDay money (in the models' price unit) one user may spend a day
 * @param featureRoles roles that may use a feature; a feature not listed is open to every signed-in user
 * @param featurePerUserPerMinute per-feature override of the per-user minute limit
 */
public record PolicySettings(
        boolean maskRedacted,
        List<String> maskPatterns,
        boolean audit,
        int perUserPerMinute,
        int perUserPerDay,
        double costPerUserPerDay,
        Map<String, Set<String>> featureRoles,
        Map<String, Integer> featurePerUserPerMinute) {

    public PolicySettings(
            boolean maskRedacted,
            @Nullable List<String> maskPatterns,
            boolean audit,
            int perUserPerMinute,
            int perUserPerDay,
            double costPerUserPerDay,
            @Nullable Map<String, Set<String>> featureRoles,
            @Nullable Map<String, Integer> featurePerUserPerMinute) {
        this.maskRedacted = maskRedacted;
        this.maskPatterns = maskPatterns == null ? List.of() : List.copyOf(maskPatterns);
        this.audit = audit;
        this.perUserPerMinute = perUserPerMinute;
        this.perUserPerDay = perUserPerDay;
        this.costPerUserPerDay = costPerUserPerDay;
        this.featureRoles = featureRoles == null ? Map.of() : Map.copyOf(featureRoles);
        this.featurePerUserPerMinute = featurePerUserPerMinute == null ? Map.of() : Map.copyOf(featurePerUserPerMinute);
    }

    /** Masking and audit on, no limits, every feature open. */
    public static PolicySettings defaults() {
        return new PolicySettings(true, List.of(), true, 0, 0, 0, Map.of(), Map.of());
    }
}
