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
package com.ash.messaging.pravaha.sdk.flight;

/**
 * What a server says about one registered continuous query.
 *
 * @param name the name it answers to. A computation may have several; this is the one you asked
 *     about
 * @param state {@code RUNNING}, {@code PAUSED}, {@code FAILED} or {@code DROPPED}
 * @param sql the SQL it was registered with
 * @param fingerprint the short form of its plan fingerprint. Two names sharing a fingerprint are
 *     one computation with one copy of the state, which is worth being able to see
 * @param rowsIn rows it has accepted
 */
public record RegisteredQueryInfo(String name, String state, String sql, String fingerprint, long rowsIn) {

    public boolean isRunning() {
        return "RUNNING".equals(state);
    }

    @Override
    public String toString() {
        return name + " [" + state + ", " + fingerprint + ", " + rowsIn + " rows]";
    }
}
