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

public enum ClusterMode {

    /**
     * One node. No coordination needed, and none is done.
     *
     * <p>The default, and what embedded mode always is.
     */
    SINGLE(false),

    /**
     * Several nodes sharing read load, each holding the whole state.
     *
     * <p>No partition assignment, so no owner to disagree about: a split brain here costs duplicated
     * work and stale reads, not corrupted aggregates. A coordinator without consensus is therefore
     * *acceptable* -- and the consequences are still worth knowing, so they are written down rather
     * than implied.
     */
    REPLICATED(false),

    /**
     * Partitions assigned across nodes, each owning part of the state.
     *
     * <p><strong>Requires consensus.</strong> Two nodes both believing they own a partition means two
     * nodes writing the same aggregate, and the damage is silent, durable, and found later by
     * somebody reconciling numbers. A coordinator that cannot exclude split-brain is refused for this
     * mode rather than warned about.
     */
    PARTITIONED(true);

    private final boolean needsConsensus;

    ClusterMode(boolean needsConsensus) {
        this.needsConsensus = needsConsensus;
    }

    /** Whether this mode can only be served by a coordinator that excludes split-brain. */
    public boolean needsConsensus() {
        return needsConsensus;
    }
}
