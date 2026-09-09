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
package com.ash.messaging.pravaha.api;

/**
 * An engine's lifecycle state.
 *
 * <p>{@link #STARTING} and {@link #STOPPING} exist as distinct states rather than being collapsed
 * into a boolean because both take real time -- restoring checkpointed state on the way up, draining
 * lanes on the way down -- and a health probe that cannot tell "starting" from "broken" produces
 * Kubernetes restart loops (design section 26.2).
 */
public enum EngineState {
    CREATED,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED;

    public boolean isRunning() {
        return this == RUNNING;
    }

    /** Whether the engine has finished with its resources. */
    public boolean isTerminal() {
        return this == STOPPED || this == FAILED;
    }
}
