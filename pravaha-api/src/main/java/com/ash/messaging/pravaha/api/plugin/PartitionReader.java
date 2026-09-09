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
package com.ash.messaging.pravaha.api.plugin;

import com.ash.messaging.pravaha.api.data.RowWriter;

/**
 * Reads one partition.
 *
 * <p>{@link #poll} is <strong>non-blocking</strong>: it returns zero when nothing is available and
 * lets the engine's wait strategy decide what to do. A reader that blocks internally takes that
 * decision away from the engine and makes the CPU-for-latency dial (design section 13.3) meaningless.
 */
public interface PartitionReader extends AutoCloseable {

    /**
     * Decodes up to {@code maxRecords} into {@code sink}.
     *
     * @return how many were written; zero when nothing is available
     */
    int poll(RecordSink sink, int maxRecords);

    /** The offset of the last record handed to {@link #poll}. Must be durably restartable. */
    SourceOffset position();

    /**
     * Stops fetching.
     *
     * <p>The engine calls this when a downstream is backpressured. A reader that ignores it turns
     * flow control into an out-of-memory error somewhere further along (design section 13.5).
     */
    void pause();

    void resume();

    @Override
    void close();

    /** Where a reader writes decoded records: straight into the lane's arena, with no intermediate object. */
    interface RecordSink {
        /** Begins a row. The caller must {@code commit()} or {@code abort()} it. */
        RowWriter beginRow();
    }
}
