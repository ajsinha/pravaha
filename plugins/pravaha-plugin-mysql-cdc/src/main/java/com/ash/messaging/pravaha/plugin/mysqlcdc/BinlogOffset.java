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

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * A position in the binary log: a file and an offset at a transaction boundary, and when a
 * transaction was too large to hand over in one poll, how many of its changes the engine holds.
 *
 * <p>{@code file:position} is where the next undelivered transaction begins -- the end of the last
 * one delivered whole. Reading resumes there, and a binlog read from a transaction boundary decodes
 * to the same changes in the same order, so skipping the first {@code partial} of them delivers
 * exactly the rest.
 *
 * <p>Written as text an operator can read beside {@code SHOW BINARY LOGS}: {@code
 * binlog=mysql-bin.000003:1547}, or {@code binlog=mysql-bin.000003:1547;partial=4096}.
 */
record BinlogOffset(String file, long position, long partial) {

    private static final Pattern TOKEN = Pattern.compile("binlog=([^:;]+):(\\d+)(?:;partial=(\\d+))?");

    static BinlogOffset at(String file, long position) {
        return new BinlogOffset(file, position, 0L);
    }

    boolean isPartial() {
        return partial > 0;
    }

    SourceOffset toSourceOffset() {
        return new SourceOffset("binlog=" + file + ":" + position + (isPartial() ? ";partial=" + partial : ""));
    }

    /** The stored offset, or null for the beginning. */
    static BinlogOffset parse(SourceOffset offset) {
        if (offset == null || offset.isBeginning()) {
            return null;
        }
        Matcher matcher = TOKEN.matcher(offset.token());
        try {
            if (!matcher.matches()) {
                throw new IllegalArgumentException(offset.token());
            }
            long position = Long.parseLong(matcher.group(2));
            long partial = matcher.group(3) == null ? 0L : Long.parseLong(matcher.group(3));
            if (position < 4 || (matcher.group(3) != null && partial < 1)) {
                throw new IllegalArgumentException(offset.token());
            }
            return new BinlogOffset(matcher.group(1), position, partial);
        } catch (RuntimeException e) {
            throw new PravahaException(
                    MySqlCdcErrors.MALFORMED_OFFSET,
                    "'" + offset.token() + "' is not a mysql-cdc offset. This source writes 'binlog=FILE:POSITION', "
                            + "optionally followed by ';partial=N'; a token in any other shape came from another "
                            + "source or was edited, and resuming from a guess would lose or repeat changes.");
        }
    }

    @Override
    public String toString() {
        return toSourceOffset().token();
    }
}
