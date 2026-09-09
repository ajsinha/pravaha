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
package com.ash.messaging.pravaha.api.plugin;

import java.util.Objects;

/**
 * A plugin's self-reported health.
 *
 * <p>{@link State#DEGRADED} is the one that earns its place. A plugin that is technically connected
 * but failing 40 % of writes is neither healthy nor down, and collapsing that into a boolean means
 * an operator sees "up" while their data quietly goes missing.
 */
public record HealthStatus(State state, String detail) {

    public enum State {
        HEALTHY,
        DEGRADED,
        UNHEALTHY
    }

    public HealthStatus {
        Objects.requireNonNull(state, "state");
        detail = detail == null ? "" : detail;
    }

    public static HealthStatus healthy() {
        return new HealthStatus(State.HEALTHY, "");
    }

    public static HealthStatus degraded(String detail) {
        return new HealthStatus(State.DEGRADED, detail);
    }

    public static HealthStatus unhealthy(String detail) {
        return new HealthStatus(State.UNHEALTHY, detail);
    }

    public boolean isUsable() {
        return state != State.UNHEALTHY;
    }
}
