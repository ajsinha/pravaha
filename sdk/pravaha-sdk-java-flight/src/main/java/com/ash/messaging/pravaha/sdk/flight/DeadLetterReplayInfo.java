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
 * What replaying one dead letter did.
 *
 * @param id the entry that was replayed
 * @param outcome {@code REPLAYED} -- the record decoded and is a row of the view now, applied at
 *     the frontier the query has reached -- or {@code FAILED_AGAIN}
 * @param detail the server's sentence, which says what that means for this query
 * @param newId when it failed again, the entry it went back on the queue as. That is the id a
 *     caller would replay next, and replaying {@link #id()} again would decode the same bytes
 *     with the same decoder
 */
public record DeadLetterReplayInfo(String id, String outcome, String detail, String newId) {

    /** True when the record is a row of the view now. */
    public boolean succeeded() {
        return "REPLAYED".equals(outcome);
    }
}
