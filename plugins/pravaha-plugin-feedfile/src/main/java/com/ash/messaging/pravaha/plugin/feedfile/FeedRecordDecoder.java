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

import java.nio.file.Path;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;

/**
 * Turns one file's bytes into records.
 *
 * <p>Format is separate from transport on purpose. Completion detection, ordering, offsets and
 * quarantine are the same problem whether the bytes are CSV, Parquet or a fixed-width extract from a
 * mainframe -- and they are the parts that are hard. A decoder knows only how to walk one file, so
 * adding a format is adding one of these, and the same set will serve an object store or an SFTP
 * drop when those transports arrive (design section 19.9).
 */
interface FeedRecordDecoder extends AutoCloseable {

    /** Opens a file for reading, positioned at its first record. */
    void open(Path file, StreamSchema schema);

    /**
     * Positions on the next record.
     *
     * <p>Separate from {@link #write} so that a row is never begun for a record that turns out not
     * to exist. Beginning and aborting one per end-of-file sounds harmless and is not: the row is
     * claimed from the lane's arena before the abort, so a feed of many small files would churn
     * arena space in proportion to file count for no records at all.
     *
     * @return {@code false} at end of file
     */
    boolean advance();

    /** Writes the record {@link #advance} positioned on. */
    void write(RowWriter writer);

    /**
     * The event time of the record {@link #write} last wrote: the value of the schema's event-time
     * column, or {@link Long#MIN_VALUE} when the schema marks none or the record's is null (HLP-6).
     */
    long lastEventTimeNanos();

    /** Skips {@code count} records, for resuming mid-file. */
    void skip(long count);

    @Override
    void close();
}
