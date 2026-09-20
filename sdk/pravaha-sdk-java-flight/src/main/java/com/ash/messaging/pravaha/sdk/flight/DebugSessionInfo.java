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

import java.util.List;

/**
 * A time-travel debug session as the server reports it (ADR-048).
 *
 * @param id the session's own id, which every other debug call names
 * @param query the query it was forked from
 * @param sql that query's SQL, as it was when the fork was taken
 * @param checkpointId the checkpoint the fork was restored from
 * @param owner the principal who opened it
 * @param startedAt when, as an ISO-8601 instant
 * @param lastUsedAt when it was last stepped or read, which is what its expiry is measured from
 * @param steps how many steps it has taken
 * @param rowsConsumed how many input rows it has fed in
 * @param viewSize how many rows its own view holds
 * @param watermarkNanos where its event time stands, or null if no step has moved it
 * @param sinksDisabled always true, and carried anyway: a screen has to be able to say so
 * @param streams the streams the forked query reads
 */
public record DebugSessionInfo(
        String id,
        String query,
        String sql,
        long checkpointId,
        String owner,
        String startedAt,
        String lastUsedAt,
        long steps,
        long rowsConsumed,
        int viewSize,
        Long watermarkNanos,
        boolean sinksDisabled,
        List<String> streams) {

    /** Reads one from the control wire's positional fields. */
    static DebugSessionInfo of(List<String> fields) {
        return new DebugSessionInfo(
                Wire.text(fields, 0),
                Wire.text(fields, 1),
                Wire.text(fields, 2),
                Wire.number(fields, 3),
                Wire.text(fields, 4),
                Wire.text(fields, 5),
                Wire.text(fields, 6),
                Wire.number(fields, 7),
                Wire.number(fields, 8),
                (int) Wire.number(fields, 9),
                Wire.optionalNumber(fields, 10),
                Boolean.parseBoolean(Wire.text(fields, 11)),
                Wire.list(fields, 12));
    }
}
