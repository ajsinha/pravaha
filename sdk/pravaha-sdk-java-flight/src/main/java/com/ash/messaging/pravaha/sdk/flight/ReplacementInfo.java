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
 * A blue/green replacement as the server reports it (ADR-046).
 *
 * <p>One answer rather than three calls: a screen that has to ask separately for the state, the
 * progress and the rollback window shows three moments instead of one.
 *
 * @param name the query being replaced
 * @param state {@code BACKFILLING}, {@code CAUGHT_UP}, {@code CUT_OVER}, {@code ROLLED_BACK},
 *     {@code ABANDONED}, {@code FAILED} or {@code FINISHED}
 * @param sql the new version's SQL
 * @param candidate the fingerprint of the computation being prepared
 * @param replacing the fingerprint of the one serving the name
 * @param sink the sink the name writes to, or null
 * @param options the options the replacement was started with
 * @param owner the principal who started it
 * @param startedAt when, as an ISO-8601 instant
 * @param cutOverAt when it cut over, or null
 * @param rollbackUntil when the rollback window closes, or null
 * @param rollbackAvailable whether the replaced version is still retained
 * @param historyRows records of history read so far
 * @param liveRows records read from the live stream since the seam
 * @param rowsPerSecond what the backfill is reading at
 * @param partitions how many partitions the backfill has, and how many have reached the live stream
 * @param historyComplete whether every partition has
 * @param rateLimit the ceiling in records a second, or zero for none
 * @param paused whether the backfill is paused
 * @param lagNanos how far behind the running version the candidate's event time is
 * @param failureCode the code of what went wrong, or null
 * @param failure what went wrong, or null
 */
public record ReplacementInfo(
        String name,
        String state,
        String sql,
        String candidate,
        String replacing,
        String sink,
        String options,
        String owner,
        String startedAt,
        String cutOverAt,
        String rollbackUntil,
        boolean rollbackAvailable,
        long historyRows,
        long liveRows,
        long rowsPerSecond,
        int partitions,
        int partitionsLive,
        boolean historyComplete,
        long rateLimit,
        boolean paused,
        long lagNanos,
        String failureCode,
        String failure) {

    /** True while the replacement is still doing something: backfilling, caught up, or cut over. */
    public boolean active() {
        return "BACKFILLING".equals(state) || "CAUGHT_UP".equals(state) || "CUT_OVER".equals(state);
    }

    /**
     * Reads one from the wire's positional fields, which are append-only: a field this client does
     * not know about is ignored, and one the server is too old to send reads as empty.
     */
    static ReplacementInfo of(List<String> row) {
        return new ReplacementInfo(
                at(row, 0),
                at(row, 1),
                at(row, 2),
                blankToNull(at(row, 3)),
                blankToNull(at(row, 4)),
                blankToNull(at(row, 5)),
                at(row, 6),
                blankToNull(at(row, 7)),
                blankToNull(at(row, 8)),
                blankToNull(at(row, 9)),
                blankToNull(at(row, 10)),
                Boolean.parseBoolean(at(row, 11)),
                number(at(row, 12)),
                number(at(row, 13)),
                number(at(row, 14)),
                (int) number(at(row, 15)),
                (int) number(at(row, 16)),
                Boolean.parseBoolean(at(row, 17)),
                number(at(row, 18)),
                Boolean.parseBoolean(at(row, 19)),
                number(at(row, 20)),
                blankToNull(at(row, 21)),
                blankToNull(at(row, 22)));
    }

    private static String at(List<String> row, int index) {
        return index < row.size() ? row.get(index) : "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
