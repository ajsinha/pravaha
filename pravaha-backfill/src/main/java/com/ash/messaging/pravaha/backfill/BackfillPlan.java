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
package com.ash.messaging.pravaha.backfill;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ash.messaging.pravaha.api.plugin.SourceOffset;

/**
 * What a replacement's feed is to read, and where it is to meet the live stream.
 *
 * <p>Handed from the registry, which knows which version is being replaced and where that version
 * has got to, down to whatever opens readers -- which knows about plugins, partitions and pushdown
 * and nothing about replacements. The seam is the only thing that crosses: a position per partition
 * per stream, taken from the running version between rows.
 *
 * @param job the control and progress an operator holds: the rate, pause, resume, rows done
 * @param splicePositions the running version's position in each stream, in partition order. A
 *     stream it does not read has no entry, and a partition beyond its list has no seam: both mean
 *     "read this one to the end of its history and carry straight on", which is a seam-free case
 * @param readHistory false for {@code backfill = 'none'}: the new version starts at the running
 *     version's positions with empty state instead of replaying history into it
 */
public record BackfillPlan(BackfillJob job, Map<String, List<SourceOffset>> splicePositions, boolean readHistory) {

    public BackfillPlan {
        java.util.Objects.requireNonNull(job, "job");
        Map<String, List<SourceOffset>> copied = new java.util.LinkedHashMap<>();
        splicePositions.forEach((stream, offsets) -> copied.put(stream, List.copyOf(offsets)));
        splicePositions = Map.copyOf(copied);
    }

    /** The seam for one partition of one stream, or empty when this stream has none. */
    public Optional<SourceOffset> spliceFor(String stream, int partition) {
        List<SourceOffset> offsets = splicePositions.get(stream);
        if (offsets == null || partition >= offsets.size()) {
            return Optional.empty();
        }
        return Optional.of(offsets.get(partition));
    }
}
