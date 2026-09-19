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
package com.ash.messaging.pravaha.plugin.pgcdc;

import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * One committed transaction's changes to the captured table, as the reader hands them over.
 *
 * <p>Also the shape of a bare position marker: a transaction with no changes, which moves the
 * reader's position to {@code endLsn} once everything queued before it has been delivered. That is
 * how a heartbeat on a quiet table becomes a position the engine can checkpoint, and so a position
 * the slot may be confirmed at.
 *
 * @param endLsn where streaming resumes after this transaction
 * @param alreadyDelivered changes at the front of this transaction a restored checkpoint already
 *     holds, and which were therefore dropped on the way in (see {@link CdcOffset})
 * @param changes what remains to deliver, in the order the database applied them
 * @param failure set when the transaction cannot be delivered at all -- a {@code TRUNCATE} of the
 *     table, a key-only before-image. Refused whole: delivering the part before the problem would
 *     publish a state the database never committed.
 */
record CdcTransaction(long endLsn, int alreadyDelivered, List<Change> changes, PravahaException failure) {

    /**
     * One row with its weight.
     *
     * @param values one value per stream field, in the form {@link PgValues#write} takes
     * @param rejected why the row could not be converted, or null; offered to the dead-letter queue
     * @param raw the row's text as it arrived, for the dead-letter queue
     */
    record Change(Object[] values, long weight, long eventTimeNanos, String rejected, byte[] raw) {}

    static CdcTransaction marker(long lsn) {
        return new CdcTransaction(lsn, 0, List.of(), null);
    }

    int size() {
        return changes.size();
    }
}
