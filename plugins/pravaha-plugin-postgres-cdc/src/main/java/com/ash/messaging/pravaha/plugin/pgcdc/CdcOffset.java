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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * A position in the change stream: an LSN, and when a transaction was too large to hand over in one
 * poll, how far into the next one the engine got.
 *
 * <p>{@code lsn} is where streaming resumes: the end of the last transaction delivered whole, or a
 * position marker the reader read with nothing undelivered before it. PostgreSQL skips every
 * transaction whose commit precedes the requested start, and the reader skips anything ending at or
 * before it as well, so resuming here re-delivers nothing that was delivered.
 *
 * <p>{@code partialEnd} and {@code partialDelivered} say the engine holds the first {@code
 * partialDelivered} changes of the transaction ending at {@code partialEnd}. Replay is deterministic
 * -- the same transaction decodes to the same changes in the same order -- so resuming skips exactly
 * those and delivers the rest. Without this, a checkpoint cut between two parts of one transaction
 * would restore state holding the first part and replay it again.
 *
 * <p>Written as text an operator can read beside {@code pg_replication_slots}:
 * {@code lsn=0/16B3748} or {@code lsn=0/16B3748;partial=0/16B9F00+4096}.
 */
record CdcOffset(long lsn, long partialEnd, long partialDelivered) {

    static final CdcOffset BEGINNING = new CdcOffset(0L, 0L, 0L);

    static CdcOffset at(long lsn) {
        return new CdcOffset(lsn, 0L, 0L);
    }

    boolean isBeginning() {
        return lsn == 0L && partialEnd == 0L;
    }

    boolean isPartial() {
        return partialEnd != 0L;
    }

    SourceOffset toSourceOffset() {
        if (isBeginning()) {
            return SourceOffset.BEGINNING;
        }
        String token = "lsn=" + format(lsn);
        if (isPartial()) {
            token += ";partial=" + format(partialEnd) + "+" + partialDelivered;
        }
        return new SourceOffset(token);
    }

    static CdcOffset parse(SourceOffset offset) {
        if (offset == null || offset.isBeginning()) {
            return BEGINNING;
        }
        String token = offset.token();
        try {
            String[] parts = token.split(";", -1);
            if (!parts[0].startsWith("lsn=") || parts.length > 2) {
                throw new IllegalArgumentException(token);
            }
            long lsn = parseLsn(parts[0].substring(4));
            if (parts.length == 1) {
                return at(lsn);
            }
            if (!parts[1].startsWith("partial=")) {
                throw new IllegalArgumentException(token);
            }
            String[] partial = parts[1].substring(8).split("\\+", -1);
            if (partial.length != 2) {
                throw new IllegalArgumentException(token);
            }
            long end = parseLsn(partial[0]);
            long delivered = Long.parseLong(partial[1]);
            if (end <= lsn || delivered < 1) {
                throw new IllegalArgumentException(token);
            }
            return new CdcOffset(lsn, end, delivered);
        } catch (RuntimeException e) {
            throw new PravahaException(
                    CdcErrors.MALFORMED_OFFSET,
                    "'" + token + "' is not a postgres-cdc offset. This source writes 'lsn=X/Y', optionally "
                            + "followed by ';partial=X/Y+N'; a token in any other shape came from another source "
                            + "or was edited, and resuming from a guess would lose or repeat changes.");
        }
    }

    /** PostgreSQL's own spelling of an LSN: two hexadecimal halves. */
    static String format(long lsn) {
        return Long.toHexString(lsn >>> 32).toUpperCase(java.util.Locale.ROOT) + "/"
                + Long.toHexString(lsn & 0xFFFFFFFFL).toUpperCase(java.util.Locale.ROOT);
    }

    static long parseLsn(String text) {
        int slash = text.indexOf('/');
        if (slash < 1 || slash == text.length() - 1) {
            throw new IllegalArgumentException(text);
        }
        long high = Long.parseLong(text.substring(0, slash), 16);
        long low = Long.parseLong(text.substring(slash + 1), 16);
        if (high > 0xFFFFFFFFL || low > 0xFFFFFFFFL || high < 0 || low < 0) {
            throw new IllegalArgumentException(text);
        }
        return (high << 32) | low;
    }

    @Override
    public String toString() {
        return toSourceOffset().toString();
    }
}
