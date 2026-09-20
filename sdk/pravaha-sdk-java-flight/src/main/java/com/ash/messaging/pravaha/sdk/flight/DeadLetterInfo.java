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

import java.time.Instant;
import java.util.Optional;

/**
 * One record a query's feed could not decode, as a client sees it.
 *
 * <p>{@link #raw()} may be empty, and {@link #withheld()} says when and why. That is not the same
 * as an empty record: a dead letter's bytes are a row of the source, a record that failed to
 * decode has no row for a row filter to be evaluated against, and a caller entitled to a slice of
 * the view is therefore shown everything about the record except the record. A client rendering
 * this must check {@link #withheld()} rather than drawing an empty cell.
 *
 * @param id what addresses this entry -- the correlation id, the same string the node's log lines
 *     carry
 * @param sequence its position in the file, oldest first
 * @param stream which of the query's streams it arrived on, or empty
 * @param offset where it came from in the source's own terms: {@code line 812}, {@code orders/3@1041}
 * @param code the {@code PRV-} code of the decode failure, or empty
 * @param reason the decoder's sentence, or empty when withheld
 * @param at when it was rejected, or null for an entry written before that was recorded
 * @param size how many bytes the record is; disclosed even when the record is not
 * @param raw the record, or empty when withheld
 * @param withheld why the record is absent, or empty when it is not
 * @param replay {@code NEW}, {@code REPLAYED} or {@code FAILED_AGAIN}
 * @param replayedAt when it was replayed, or null
 */
public record DeadLetterInfo(
        String id,
        long sequence,
        String stream,
        String offset,
        String code,
        String reason,
        Instant at,
        int size,
        byte[] raw,
        String withheld,
        String replay,
        Instant replayedAt) {

    public DeadLetterInfo {
        raw = raw == null ? new byte[0] : raw.clone();
    }

    @Override
    public byte[] raw() {
        return raw.clone();
    }

    /** True when the server would not give this caller the record itself. */
    public boolean isWithheld() {
        return !withheld.isEmpty();
    }

    public Optional<Instant> rejectedAt() {
        return Optional.ofNullable(at);
    }

    @Override
    public String toString() {
        return "DeadLetterInfo[" + id + " at " + offset + (code.isEmpty() ? "" : " " + code) + "]";
    }
}
