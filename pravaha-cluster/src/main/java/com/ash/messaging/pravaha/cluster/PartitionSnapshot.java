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
package com.ash.messaging.pravaha.cluster;

import java.util.Map;

/**
 * One virtual partition's state, in transit between nodes.
 *
 * @param partition which partition
 * @param checkpointId the checkpoint this was cut at, so a failed handoff can be reasoned about
 *     against the same timeline as ordinary recovery
 * @param offsets where each input source had been consumed to. These travel with the state because
 *     state and position are one fact: the state <em>is</em> the result of consuming up to here
 * @param operatorState serialised operator state, keyed by operator id
 */
public record PartitionSnapshot(
        int partition, long checkpointId, Map<String, String> offsets, Map<String, byte[]> operatorState) {

    public PartitionSnapshot {
        offsets = Map.copyOf(offsets);
        operatorState = Map.copyOf(operatorState);
    }

    public long sizeBytes() {
        return operatorState.values().stream().mapToLong(bytes -> bytes.length).sum();
    }

    @Override
    public String toString() {
        return "PartitionSnapshot[p" + partition + " @cp" + checkpointId + ", " + operatorState.size() + " operators, "
                + sizeBytes() + " bytes]";
    }
}
