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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** READADMIT-1: the embedded engine reads {@code pravaha.serving.read.*} as a node does. */
class EmbeddedReadLimitsTest {

    @Test
    void aValueOutOfRangeIsRefusedWhileTheHostBuildsTheEngine() {
        Configuration configuration = Configuration.builder()
                .set("pravaha.serving.read.max-concurrent", "-2")
                .build();

        assertThatThrownBy(() -> PravahaEngine.create(configuration))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-1026")
                .hasMessageContaining("pravaha.serving.read.max-concurrent");
    }

    @Test
    void aLimitedEngineStillAnswersAReadWithinIt() {
        Configuration configuration = Configuration.builder()
                .set("pravaha.serving.read.max-concurrent", "2")
                .set("pravaha.serving.read.deadline", "30s")
                .build();
        try (PravahaEngine engine = PravahaEngine.create(configuration)) {
            engine.declareStream("txn", "id:INT64");
            engine.start();
            engine.register("ids", "SELECT id FROM txn", "id");
            engine.push("txn", new Object[] {1L});

            assertThat(engine.query("SELECT id FROM ids").rows()).hasSize(1);
        }
    }
}
