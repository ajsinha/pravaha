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

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * A position in the change stream: an LSN, and when a transaction was too large to hand over in one
 * poll, how far into the next one the engine got; and while an initial snapshot is being read, how
 * far through the table.
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
 * <p>{@code snapshot}, when present, says an initial snapshot is not finished, and what the engine
 * holds is exactly <em>the table's rows whose primary key is at or below {@link Snapshot#after}, as
 * of {@code lsn}</em> -- no row above it, and nothing at all when {@code after} is empty (see {@link
 * InitialSnapshot} for why that statement is enough to resume from exactly). Absent, the snapshot is
 * done or was never asked for, which is how every offset written before snapshots existed reads.
 *
 * <p>Written as text an operator can read beside {@code pg_replication_slots}: {@code
 * lsn=0/16B3748}, {@code lsn=0/16B3748;partial=0/16B9F00+4096}, {@code lsn=0/16B3748;snapshot=start}
 * or {@code lsn=0/16B3748;snapshot=20000@20417} -- twenty thousand rows delivered, the last with key
 * 20417. Key values are PostgreSQL's text for them, percent-encoded, comma-separated for a composite
 * key.
 */
record CdcOffset(long lsn, long partialEnd, long partialDelivered, Snapshot snapshot) {

    static final CdcOffset BEGINNING = new CdcOffset(0L, 0L, 0L);

    /**
     * How far an unfinished initial snapshot has got.
     *
     * @param rows rows of the snapshot delivered so far, for progress; not used for positioning
     * @param after the primary key of the last snapshot row delivered, one text value per key column;
     *     empty when none has been
     */
    record Snapshot(long rows, List<String> after) {

        static final Snapshot START = new Snapshot(0L, List.of());

        Snapshot {
            after = List.copyOf(after);
        }

        boolean started() {
            return !after.isEmpty();
        }

        String token() {
            if (!started()) {
                return "start";
            }
            StringBuilder token = new StringBuilder().append(rows).append('@');
            for (int i = 0; i < after.size(); i++) {
                token.append(i == 0 ? "" : ",").append(URLEncoder.encode(after.get(i), StandardCharsets.UTF_8));
            }
            return token.toString();
        }

        static Snapshot parse(String token) {
            if (token.equals("start")) {
                return START;
            }
            int at = token.indexOf('@');
            if (at < 1) {
                throw new IllegalArgumentException(token);
            }
            long rows = Long.parseLong(token.substring(0, at));
            if (rows < 1) {
                throw new IllegalArgumentException(token);
            }
            List<String> after = new ArrayList<>();
            for (String part : token.substring(at + 1).split(",", -1)) {
                after.add(URLDecoder.decode(part, StandardCharsets.UTF_8));
            }
            return new Snapshot(rows, after);
        }
    }

    CdcOffset(long lsn, long partialEnd, long partialDelivered) {
        this(lsn, partialEnd, partialDelivered, null);
    }

    static CdcOffset at(long lsn) {
        return new CdcOffset(lsn, 0L, 0L);
    }

    boolean isBeginning() {
        return lsn == 0L && partialEnd == 0L && snapshot == null;
    }

    boolean isPartial() {
        return partialEnd != 0L;
    }

    boolean inSnapshot() {
        return snapshot != null;
    }

    CdcOffset withSnapshot(Snapshot next) {
        return new CdcOffset(lsn, partialEnd, partialDelivered, next);
    }

    SourceOffset toSourceOffset() {
        if (isBeginning()) {
            return SourceOffset.BEGINNING;
        }
        String token = "lsn=" + format(lsn);
        if (isPartial()) {
            token += ";partial=" + format(partialEnd) + "+" + partialDelivered;
        }
        if (inSnapshot()) {
            token += ";snapshot=" + snapshot.token();
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
            if (!parts[0].startsWith("lsn=") || parts.length > 3) {
                throw new IllegalArgumentException(token);
            }
            long lsn = parseLsn(parts[0].substring(4));
            long end = 0L;
            long delivered = 0L;
            Snapshot snapshot = null;
            int next = 1;
            if (next < parts.length && parts[next].startsWith("partial=")) {
                String[] partial = parts[next].substring(8).split("\\+", -1);
                if (partial.length != 2) {
                    throw new IllegalArgumentException(token);
                }
                end = parseLsn(partial[0]);
                delivered = Long.parseLong(partial[1]);
                if (end <= lsn || delivered < 1) {
                    throw new IllegalArgumentException(token);
                }
                next++;
            }
            if (next < parts.length && parts[next].startsWith("snapshot=")) {
                snapshot = Snapshot.parse(parts[next].substring(9));
                next++;
            }
            if (next != parts.length) {
                throw new IllegalArgumentException(token);
            }
            return new CdcOffset(lsn, end, delivered, snapshot);
        } catch (RuntimeException e) {
            throw new PravahaException(
                    CdcErrors.MALFORMED_OFFSET,
                    "'" + token + "' is not a postgres-cdc offset. This source writes 'lsn=X/Y', optionally "
                            + "followed by ';partial=X/Y+N' and ';snapshot=start' or ';snapshot=N@key'; a token in "
                            + "any other shape came from another source or was edited, and resuming from a guess would "
                            + "lose or repeat changes.");
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
