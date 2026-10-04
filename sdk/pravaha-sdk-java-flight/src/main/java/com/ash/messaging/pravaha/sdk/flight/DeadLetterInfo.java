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

import org.jspecify.annotations.Nullable;

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
// An array component on purpose, and made safe: a public accessor of this shape has shipped, so the
// bytes are cloned in and out and equals/hashCode below compare their contents rather than the
// array's identity, which is what the check warns a record would otherwise do.
@SuppressWarnings("ArrayRecordComponent")
public record DeadLetterInfo(
        String id,
        long sequence,
        String stream,
        String offset,
        String code,
        String reason,
        @Nullable Instant at,
        int size,
        byte[] raw,
        String withheld,
        String replay,
        @Nullable Instant replayedAt) {

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

    /** Equal when every field is, the record's bytes by content. */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof DeadLetterInfo that
                && sequence == that.sequence
                && size == that.size
                && id.equals(that.id)
                && stream.equals(that.stream)
                && offset.equals(that.offset)
                && code.equals(that.code)
                && reason.equals(that.reason)
                && java.util.Objects.equals(at, that.at)
                && java.util.Arrays.equals(raw, that.raw)
                && withheld.equals(that.withheld)
                && replay.equals(that.replay)
                && java.util.Objects.equals(replayedAt, that.replayedAt);
    }

    @Override
    public int hashCode() {
        return 31
                        * java.util.Objects.hash(
                                id, sequence, stream, offset, code, reason, at, size, withheld, replay, replayedAt)
                + java.util.Arrays.hashCode(raw);
    }

    @Override
    public String toString() {
        return "DeadLetterInfo[" + id + " at " + offset + (code.isEmpty() ? "" : " " + code) + "]";
    }
}
