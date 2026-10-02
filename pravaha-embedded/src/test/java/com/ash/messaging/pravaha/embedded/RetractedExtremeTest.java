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
package com.ash.messaging.pravaha.embedded;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.registry.QueryState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MINRETRACT-1: a retraction no query on the stream can take refuses the call, before any row is
 * delivered, and every query keeps running. It used to stop the MIN query for good.
 */
class RetractedExtremeTest {

    @Test
    void aRetractionReachingAMinIsRefusedAndNothingStops() {
        try (PravahaEngine engine = PravahaEngine.create(Configuration.builder().build())) {
            engine.declareStream("w", "k:STRING,v:INT64");
            engine.start();
            engine.register("lowest", "SELECT MIN(v) AS m FROM w", "m");
            engine.register("total", "SELECT SUM(v) AS s FROM w", "s");
            engine.push("w", new Object[] {"a", 1L}, new Object[] {"a", 2L});

            assertThatThrownBy(() -> engine.retract("w", new Object[] {"a", 1L}))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageStartingWith("PRV-8102")
                    .hasMessageContaining("'lowest'")
                    .hasMessageContaining("MIN(v)")
                    .hasMessageContaining("Nothing in this retract was delivered");

            assertThat(engine.find("lowest").orElseThrow().state()).isEqualTo(QueryState.RUNNING);
            assertThat(engine.find("total").orElseThrow().state()).isEqualTo(QueryState.RUNNING);
            assertThat(engine.find("total").orElseThrow().view().scan())
                    .singleElement()
                    .satisfies(row -> assertThat(row[0]).isEqualTo(3L));
        }
    }

    @Test
    void aRetractionOnAStreamWithoutAnExtremeIsDelivered() {
        try (PravahaEngine engine = PravahaEngine.create(Configuration.builder().build())) {
            engine.declareStream("w", "k:STRING,v:INT64");
            engine.start();
            engine.register("total", "SELECT SUM(v) AS s FROM w", "s");
            engine.push("w", new Object[] {"a", 1L}, new Object[] {"a", 2L});
            assertThat(engine.retract("w", new Object[] {"a", 1L})).isEqualTo(1);
            assertThat(engine.find("total").orElseThrow().view().scan())
                    .singleElement()
                    .satisfies(row -> assertThat(row[0]).isEqualTo(2L));
        }
    }
}
