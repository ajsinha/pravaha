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
package com.ash.messaging.pravaha.server.ingest;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.common.queue.WaitStrategy;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** The knobs ADR-036 starts with, and the arithmetic an operator sizes a node by. */
final class LanePropertiesTest {

    @Test
    void theDefaultsAreTheEnginesOwnSoAnUnconfiguredNodeIsUnchanged() {
        LaneConfig configured = new LaneProperties().toLaneConfig();
        LaneConfig library = LaneConfig.defaults()
                .withWaitStrategy(WaitStrategy.Kind.BACKOFF_PARK)
                .withThreads("pravaha-query", true);

        assertThat(configured.batchSize()).isEqualTo(library.batchSize());
        assertThat(configured.inboxCells()).isEqualTo(library.inboxCells());
        assertThat(configured.inboxCellBytes()).isEqualTo(library.inboxCellBytes());
        assertThat(configured.arenaSlabBytes()).isEqualTo(library.arenaSlabBytes());
        assertThat(configured.arenaMaxSlabs()).isEqualTo(library.arenaMaxSlabs());
        assertThat(configured.waitStrategy()).isEqualTo(library.waitStrategy());
    }

    @Test
    void theDefaultCostPerIdleQueryIsTheFiveMegabytesAdr036IsAbout() {
        // The number the target multiplies by. A thousand of these is ~5 GB before a row moves, and
        // it is the wall the stated target hits before the thread-per-query one.
        assertThat(new LaneProperties().idleBytesPerQuery())
                .as("2048 x 512B inbox plus one eager 4 MiB arena slab")
                .isEqualTo(2048L * 512 + 4 * 1024 * 1024);
    }

    @Test
    void sizingForManySmallQueriesCutsTheIdleCostByAnOrderOfMagnitude() {
        // The configuration OPERATIONS.md recommends for a node holding many narrow queries. Asserted
        // rather than described, because a recommendation nobody checked is how a default becomes
        // folklore.
        LaneProperties many = new LaneProperties();
        many.getArena().setSlabBytes(256 * 1024);
        many.getInbox().setCells(256);
        many.getInbox().setCellBytes(256);

        assertThat(many.idleBytesPerQuery()).isEqualTo(256L * 256 + 256 * 1024);
        assertThat(new LaneProperties().idleBytesPerQuery() / many.idleBytesPerQuery())
                .as("the default costs this many times more per idle query")
                .isGreaterThanOrEqualTo(15L);
        assertThat(many.toLaneConfig().arenaSlabBytes()).isEqualTo(256 * 1024);
    }
}
