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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * A position in the binary log: a file and an offset at a transaction boundary, when the server runs
 * with {@code gtid_mode = ON} the set of transactions executed up to there, and when a transaction was
 * too large to hand over in one poll, how many of its changes the engine holds.
 *
 * <p>{@code file:position} is where the next undelivered transaction begins -- the end of the last
 * one delivered whole. Reading resumes there, and a binlog read from a transaction boundary decodes
 * to the same changes in the same order, so skipping the first {@code partial} of them delivers
 * exactly the rest.
 *
 * <p><strong>GTID positions</strong> (MYC-2). With {@code gtid}, the executed GTID set, reading resumes
 * by asking the server for every transaction not in the set ({@code COM_BINLOG_DUMP_GTID}) -- which
 * any server holding those transactions can answer, so a checkpoint survives a failover to a replica.
 * {@code file:position} is then only what the operator reads beside {@code SHOW BINARY LOGS}. A
 * partial transaction is named by its own GTID ({@code partialGtid}), since another server may order
 * the transactions after the set differently.
 *
 * <p>Written as text an operator can read: {@code binlog=mysql-bin.000003:1547}, {@code
 * binlog=mysql-bin.000003:1547;partial=4096}, or {@code gtid=3E11FA47-...:1-5;binlog=mysql-bin.000003:1547}
 * and {@code ...;partial=4096@3E11FA47-...:6}.
 *
 * @param gtidSet the executed GTID set, or null for a file-and-offset position
 * @param partialGtid the GTID of the transaction {@code partial} counts into, or null
 */
record BinlogOffset(
        String file,
        long position,
        long partial,
        @Nullable String gtidSet,
        @Nullable String partialGtid) {

    private static final Pattern TOKEN = Pattern.compile("(?:gtid=([A-Za-z0-9_:,-]*);)?binlog=([^:;]+):(\\d+)"
            + "(?:;partial=(\\d+)(?:@([A-Za-z0-9_-]+:[A-Za-z0-9_:]+))?)?");

    BinlogOffset(String file, long position, long partial) {
        this(file, position, partial, null, null);
    }

    static BinlogOffset at(String file, long position) {
        return new BinlogOffset(file, position, 0L);
    }

    /** A transaction boundary in GTID mode, or in file mode when {@code gtidSet} is null. */
    static BinlogOffset at(String file, long position, @Nullable String gtidSet) {
        return new BinlogOffset(file, position, 0L, gtidSet, null);
    }

    boolean isPartial() {
        return partial > 0;
    }

    boolean isGtid() {
        return gtidSet != null;
    }

    SourceOffset toSourceOffset() {
        return new SourceOffset((isGtid() ? "gtid=" + gtidSet + ";" : "") + "binlog=" + file + ":" + position
                + (isPartial() ? ";partial=" + partial + (partialGtid == null ? "" : "@" + partialGtid) : ""));
    }

    /** The stored offset, or null for the beginning. */
    static @Nullable BinlogOffset parse(@Nullable SourceOffset offset) {
        if (offset == null || offset.isBeginning()) {
            return null;
        }
        Matcher matcher = TOKEN.matcher(offset.token());
        try {
            if (!matcher.matches()) {
                throw new IllegalArgumentException(offset.token());
            }
            long position = Long.parseLong(matcher.group(3));
            long partial = matcher.group(4) == null ? 0L : Long.parseLong(matcher.group(4));
            if (position < 4 || (matcher.group(4) != null && partial < 1)) {
                throw new IllegalArgumentException(offset.token());
            }
            if (matcher.group(5) != null && matcher.group(1) == null) {
                throw new IllegalArgumentException(offset.token());
            }
            return new BinlogOffset(matcher.group(2), position, partial, matcher.group(1), matcher.group(5));
        } catch (RuntimeException e) {
            throw new PravahaException(
                    MySqlCdcErrors.MALFORMED_OFFSET,
                    "'" + offset.token() + "' is not a mysql-cdc offset. This source writes 'binlog=FILE:POSITION', "
                            + "optionally preceded by 'gtid=SET;' and followed by ';partial=N'; a token in any other "
                            + "shape came from another source or was edited, and resuming from a guess would lose or "
                            + "repeat changes.");
        }
    }

    @Override
    public String toString() {
        return toSourceOffset().token();
    }
}
