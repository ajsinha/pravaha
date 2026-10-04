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
package com.ash.messaging.pravaha.bindings.ingest;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.runtime.exec.QueryExecution;

/**
 * Which recorded offset belongs to which source partition, when a query is restored.
 *
 * <p>A checkpoint keys each source's offset {@code partition-N}, N being the order its pump was
 * created in. A fresh open creates them stream by stream, in partition order, and a restore that
 * does the same meets each offset again at the same N -- until a source gains a partition while the
 * query runs. Its pump is created then, after every other, so a query reading streams A and B that
 * saw A gain a partition recorded A's new partition after B's; a restore creating them in order would
 * hand B's offset to A's new partition. So a checkpoint whose pumps all named their partition also
 * records {@code source-of-partition-N} ({@link QueryExecution#SOURCE_OF_PARTITION_PREFIX}), and a
 * restore reads that first. A checkpoint written before that key existed is read by order, as it
 * always was.
 *
 * <p>A partition with no recorded offset in a restored checkpoint is one the source gained after the
 * checkpoint was taken: {@link #isNew} says so, and the caller reads it from its first record.
 */
final class ResumePositions {

    private final Map<String, String> offsets;
    private final Map<String, @Nullable String> byPartition = new HashMap<>();
    private final boolean restoring;
    private int ordinal;

    private ResumePositions(@Nullable Map<String, String> offsets) {
        this.offsets = offsets == null ? Map.of() : offsets;
        boolean anyOffset = false;
        for (Map.Entry<String, String> entry : this.offsets.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(QueryExecution.SOURCE_OF_PARTITION_PREFIX)) {
                String index = key.substring(QueryExecution.SOURCE_OF_PARTITION_PREFIX.length());
                byPartition.put(entry.getValue(), this.offsets.get("partition-" + index));
            } else if (key.startsWith("partition-")) {
                anyOffset = true;
            }
        }
        this.restoring = anyOffset;
    }

    static ResumePositions of(@Nullable Map<String, String> offsets) {
        return new ResumePositions(offsets);
    }

    /**
     * The offset recorded for {@code partition} of {@code stream}, or null when none was. Call it
     * once for each partition, in the order a fresh open creates them: a checkpoint without partition
     * names is matched by that order.
     */
    @Nullable
    String tokenFor(String stream, int partition) {
        int at = ordinal++;
        if (!byPartition.isEmpty()) {
            return byPartition.get(partition + "/" + stream);
        }
        return offsets.get("partition-" + at);
    }

    /** True when this is a restore and {@code token} -- from {@link #tokenFor} -- is absent. */
    boolean isNew(@Nullable String token) {
        return restoring && token == null;
    }
}
