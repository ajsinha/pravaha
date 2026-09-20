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
import com.ash.messaging.pravaha.runtime.ingest.BackpressurePolicy;
import com.ash.messaging.pravaha.runtime.lane.LaneConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void theDefaultCostPerIdleQueryIsTheInboxAndNothingElse() {
        // This asserted the inbox plus a 4 MiB arena slab, which was true while RowArena allocated
        // its first slab in the constructor and false the moment W9-6 made it lazy. Because the
        // test agreed with the code, nothing caught that PravahaNode had begun logging 5,120 KiB
        // per idle query where NodeScaleTest measures 1,024 -- an operator sizing from that line
        // would have budgeted five times what they needed (DOCS-9).
        //
        // An idle query has never written a row, so it has no slab at all.
        assertThat(new LaneProperties().idleBytesPerQuery())
                .as("a 2048 x 512B inbox, and nothing else, which is what NodeScaleTest measures")
                .isEqualTo(2048L * 512);
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

        assertThat(many.idleBytesPerQuery())
                .as("the inbox alone, since an idle query allocates no slab")
                .isEqualTo(256L * 256);
        assertThat(new LaneProperties().idleBytesPerQuery() / many.idleBytesPerQuery())
                .as("the default costs this many times more per idle query")
                .isGreaterThanOrEqualTo(15L);
        assertThat(many.idleBytesPerQuery())
                .as(
                        "and a thousand sized like this hold %d MiB between them, not gigabytes",
                        many.idleBytesPerQuery() * 1000 / (1024 * 1024))
                .isLessThan(128L * 1024);
        assertThat(many.toLaneConfig().arenaSlabBytes()).isEqualTo(256 * 1024);
    }

    @Test
    void theBackpressureWatermarksDefaultToTheDesignsAndCanBeSet() {
        // BackpressurePolicy's javadoc has said since it was written that the gap between the
        // watermarks is configuration -- "a Kafka consumer pause is nearly free, an Aerospike scan
        // throttle is not" -- and no key bound it, so every node ran 0.8/0.5 whatever its YAML
        // said. TROUBLESHOOTING sent an operator diagnosing a paused source to
        // pravaha.lane.backpressure.high-watermark, which nothing read.
        assertThat(new LaneProperties().getBackpressure().policy()).isEqualTo(BackpressurePolicy.defaults());

        LaneProperties patient = new LaneProperties();
        patient.getBackpressure().setHighWatermark(0.95);
        patient.getBackpressure().setLowWatermark(0.9);

        assertThat(patient.getBackpressure().policy()).isEqualTo(new BackpressurePolicy(0.95, 0.9));
    }

    @Test
    void awatermarkPairThatCannotWorkIsRefusedAtStartupNamingItsKey() {
        // Not clamped into something that runs. A low watermark at or above the high one makes a
        // saturated source pause and resume on alternate polls, which costs more than the
        // backpressure saves -- and a node that quietly repaired the pair would run slower than
        // the unconfigured one for a reason nothing in any log would name.
        LaneProperties crossed = new LaneProperties();
        crossed.getBackpressure().setHighWatermark(0.5);
        crossed.getBackpressure().setLowWatermark(0.5);

        assertThatThrownBy(() -> crossed.getBackpressure().policy())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pravaha.lane.backpressure.high-watermark=0.5")
                .hasMessageContaining("pravaha.lane.backpressure.low-watermark=0.5");

        LaneProperties impossible = new LaneProperties();
        impossible.getBackpressure().setHighWatermark(1.5);

        assertThatThrownBy(() -> impossible.getBackpressure().policy())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pravaha.lane.backpressure.high-watermark=1.5");
    }
}
