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

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QOQAPI-1 and UNCODEDAPI-1: the embedded {@code register} builds on a view as {@code CREATE
 * CONTINUOUS QUERY} does, and what it and {@code push} refuse is refused with a code.
 */
class EmbeddedRegisterApiTest {

    private static PravahaEngine engine() {
        PravahaEngine engine = PravahaEngine.create(Configuration.builder().build());
        engine.declareStream("src", "id:INT64,g:STRING,v:INT64,ts:TIMESTAMP");
        engine.start();
        return engine;
    }

    @Test
    void registerBuildsOnARegisteredView() {
        try (PravahaEngine engine = engine()) {
            engine.register("up", "SELECT id, g, v FROM src", "id");
            engine.register("down", "SELECT g, SUM(v) AS s FROM up GROUP BY g", "g");
            engine.push("src", new Object[] {1L, "a", 2L, Instant.EPOCH}, new Object[] {2L, "a", 3L, Instant.EPOCH});
            // The downstream follows the upstream's commits on its own lane.
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (engine.find("down").orElseThrow().view().scan().isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(engine.find("down").orElseThrow().view().scan())
                    .singleElement()
                    .satisfies(row -> assertThat(row[1]).isEqualTo(5L));
        }
    }

    @Test
    void aKeylessRegistrationIsTheStatementsOwnCodedRefusal() {
        try (PravahaEngine engine = engine()) {
            assertThatThrownBy(() -> engine.register("n", "SELECT COUNT(*) AS c FROM src"))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageStartingWith("PRV-2070")
                    .hasMessageContaining("any of its own columns");
            assertThat(engine.register("n", "SELECT COUNT(*) AS c FROM src", "c"))
                    .isNotNull();
        }
    }

    @Test
    void anInstantPastTheNanosecondRangeIsACodedRefusalOfThePush() {
        try (PravahaEngine engine = engine()) {
            engine.register("stamped", "SELECT id, ts FROM src", "id");
            assertThatThrownBy(
                            () -> engine.push("src", new Object[] {1L, "a", 1L, Instant.parse("3000-01-01T00:00:00Z")}))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageStartingWith("PRV-8102")
                    .hasMessageContaining("'ts'")
                    .hasMessageContaining("2262-04-11");
            assertThat(engine.find("stamped").orElseThrow().view().scan()).isEmpty();
        }
    }
}
