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
 * PUSHPARTIAL-1: each running query takes a push independently. One that cannot apply it no longer
 * leaves the others applied and unpublished; they commit it, and the push says who has it.
 */
class PartlyAppliedPushTest {

    private static PravahaEngine engine() {
        PravahaEngine engine = PravahaEngine.create(Configuration.builder().build());
        engine.declareStream("w", "id:INT64,x:INT64");
        engine.start();
        return engine;
    }

    @Test
    void theHealthyQueriesCommitAndThePushNamesWhoHasTheRows() {
        try (PravahaEngine engine = engine()) {
            engine.register("bad", "SELECT id, x - 1 AS y FROM w", "id");
            engine.register("good", "SELECT id, x FROM w", "id");
            assertThatThrownBy(() -> engine.push("w", new Object[] {1L, Long.MIN_VALUE}))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8105")
                    .hasMessageContaining("[good]")
                    .hasMessageContaining("'bad'")
                    .hasMessageContaining("Do not retry");
            // Committed, so visible now -- not hidden until some later push commits it.
            assertThat(engine.find("good").orElseThrow().view().scan()).hasSize(1);
            assertThat(engine.find("bad").orElseThrow().state()).isEqualTo(QueryState.FAILED);
            // The next push reaches the running query only, and is answered.
            assertThat(engine.push("w", new Object[] {2L, 5L})).isEqualTo(1);
            assertThat(engine.find("good").orElseThrow().view().scan()).hasSize(2);
        }
    }

    @Test
    void whenNoQueryTookThePushTheFailureIsReportedUnchanged() {
        try (PravahaEngine engine = engine()) {
            engine.register("bad", "SELECT id, x - 1 AS y FROM w", "id");
            assertThatThrownBy(() -> engine.push("w", new Object[] {1L, Long.MIN_VALUE}))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-3010")
                    .hasMessageNotContaining("PRV-8105");
        }
    }
}
