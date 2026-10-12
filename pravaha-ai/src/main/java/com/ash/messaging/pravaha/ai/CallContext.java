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

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Who is calling and for which feature; the policy decides on this, never the provider.
 *
 * @param user the signed-in name, {@code anonymous} when none
 * @param roles the caller's roles, for the per-feature role rules
 * @param feature the feature asking ({@code explain}, {@code api:<name>} for the REST endpoint), {@code other} when none
 */
public record CallContext(String user, Set<String> roles, String feature) {

    public CallContext(@Nullable String user, @Nullable Set<String> roles, @Nullable String feature) {
        this.user = user == null || user.isBlank() ? "anonymous" : user;
        this.roles = roles == null ? Set.of() : Set.copyOf(roles);
        this.feature = feature == null || feature.isBlank() ? "other" : feature;
    }
}
