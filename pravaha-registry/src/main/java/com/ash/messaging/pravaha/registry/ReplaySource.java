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
package com.ash.messaging.pravaha.registry;

import java.util.List;
import java.util.Optional;

/**
 * A query's inputs, read one row at a time from a position, by hand (ADR-048).
 *
 * <p>Not a {@link SourceFeed}. A feed is a set of pumps on threads of their own, delivering rows as
 * fast as backpressure allows -- which is what a running query wants and exactly what a debugger
 * cannot use. Stepping "by one row" means the caller decides when the next row exists, so this is
 * pull rather than push, and nothing here runs on a thread the caller does not control.
 *
 * <p><strong>The interleaving is fixed, and that is a decision rather than an accident.</strong>
 * A live query's partitions are read by separate pumps, so the order rows from different partitions
 * reach the lanes is whatever the schedulers did that second, and is not reproducible. This reads
 * the partitions round-robin in a fixed order. That is what makes two runs of the same session give
 * the same answers -- and it means a bug that only appears under one particular interleaving of two
 * partitions may not reproduce here. ADR-048 records the trade.
 */
public interface ReplaySource extends AutoCloseable {

    /** One row, as it was read, with where it came from. */
    record ReplayRow(
            String stream,
            int partition,
            String offset,
            long weight,
            long eventTimeNanos,
            long sequence,
            Object[] values) {

        public ReplayRow {
            values = values == null ? new Object[0] : values.clone();
        }

        @Override
        public Object[] values() {
            return values.clone();
        }
    }

    /**
     * The next row, or empty when every partition has been read to its end.
     *
     * <p>Empty does not mean "no more will ever arrive": a live topic may grow. It means there is
     * nothing to step onto now, which is what a debugger has to report rather than block on.
     */
    Optional<ReplayRow> next();

    /** Where each partition now stands, keyed as a checkpoint's offsets are. */
    java.util.Map<String, String> positions();

    /** The streams this source reads, in plan order. */
    List<String> streams();

    @Override
    void close();
}
