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

/**
 * A reader that can stop exactly at a position (ADR-054).
 *
 * <p>{@link #poll} bounds how many records it reads; this bounds <em>where</em> it stops. The two are
 * different, and the difference is the point: a source whose positions have gaps -- a Kafka partition's
 * transaction markers -- cannot be stopped at a position by counting records, because the count between
 * two offsets is not their difference.
 */
public interface BoundedPartitionReader extends PartitionReader {

    /**
     * Like {@link #poll}, reading only records that come before {@code bound}.
     *
     * <p>Once no record before {@code bound} remains, {@link #position()} must equal {@code bound}: that
     * equality is how a shared reader knows a query catching up has arrived. A bound this reader is
     * already at or past reads nothing. A record at or after the bound is not consumed; the next poll
     * delivers it.
     *
     * @return records delivered or rejected, as {@link #poll} counts them
     */
    int pollBefore(RecordSink sink, int maxRecords, SourceOffset bound);
}
