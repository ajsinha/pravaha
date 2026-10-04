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
package com.ash.messaging.pravaha.plugin.feedfile;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * Where a reader is in a feed: a file, and a record within it.
 *
 * <p>Two fields are enough only because the file order is <em>declared</em> rather than discovered
 * (see {@link FeedFileSourcePlugin}). Given a fixed order, "this file, this record" also says
 * "every file before it, in full", which is what makes the offset a position in the stream rather
 * than a position in one file. A feed whose order changed between runs would make this token a lie,
 * which is why the ordering policy is configuration and not a heuristic.
 *
 * @param fileName the file being read, relative to the feed directory
 * @param recordIndex how many of its records have been emitted
 */
public record FeedFileOffset(String fileName, long recordIndex) {

    /** Before anything has been read. */
    public static final FeedFileOffset BEGINNING = new FeedFileOffset("", 0L);

    public FeedFileOffset {
        java.util.Objects.requireNonNull(fileName, "fileName");
        if (recordIndex < 0) {
            throw new IllegalArgumentException("record index must be non-negative, got " + recordIndex);
        }
    }

    /**
     * Parses a token this plugin wrote.
     *
     * <p>Strict, for the reason every offset parser in this codebase is strict: a token from another
     * plugin would otherwise resume somewhere plausible and wrong, and the symptom arrives much
     * later as missing or duplicated records.
     */
    public static FeedFileOffset parse(@Nullable SourceOffset offset) {
        if (offset == null || offset.isBeginning()) {
            return BEGINNING;
        }
        String token = offset.token();
        int separator = token.lastIndexOf(";r=");
        if (!token.startsWith("f=") || separator < 0) {
            throw new PravahaException(
                    FeedFileErrors.MALFORMED_OFFSET,
                    "cannot resume from offset '" + token + "': it is not a feed-file offset of the form "
                            + "f=<fileName>;r=<recordIndex>.");
        }
        try {
            return new FeedFileOffset(token.substring(2, separator), Long.parseLong(token.substring(separator + 3)));
        } catch (NumberFormatException e) {
            throw new PravahaException(
                    FeedFileErrors.MALFORMED_OFFSET, "offset '" + token + "' has a non-numeric record index", e);
        }
    }

    public SourceOffset toSourceOffset() {
        return new SourceOffset("f=" + fileName + ";r=" + recordIndex);
    }

    public boolean isBeginning() {
        return fileName.isEmpty();
    }

    public FeedFileOffset withRecord(long record) {
        return new FeedFileOffset(fileName, record);
    }

    public FeedFileOffset at(String file) {
        return new FeedFileOffset(file, 0L);
    }
}
