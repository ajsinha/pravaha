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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.config.ConfigurationBuilder;
import com.ash.messaging.pravaha.registry.QueryState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DLQPROJ-1: with {@code pravaha.dlq.directory} set, a pushed row whose evaluation fails -- a
 * division by zero, an overflow -- before it reaches state goes to the query's dead-letter queue and
 * the query keeps running. Without a directory it stops the query, as before. A failure above state
 * (an overflow in a projection of an aggregate) stops the query either way.
 */
class EmbeddedRowDeadLetterTest {

    private static PravahaEngine engine(Path dlq) {
        ConfigurationBuilder builder = Configuration.builder();
        if (dlq != null) {
            builder.set("pravaha.dlq.directory", dlq.toString());
        }
        PravahaEngine engine = PravahaEngine.create(builder.build());
        engine.declareStream("s", "id:INT64,a:INT64,b:INT64");
        engine.start();
        engine.register("q", "SELECT id, a / b AS r FROM s", "id");
        return engine;
    }

    private static List<String> rows(PravahaEngine engine, String query) {
        List<String> out = new ArrayList<>();
        engine.find(query).orElseThrow().view().scan().forEach(r -> out.add(r[0] + "|" + r[1]));
        out.sort(null);
        return out;
    }

    @Test
    void aRowThatFailsEvaluationIsDeadLetteredAndTheQueryKeepsRunning(@TempDir Path dir) {
        try (PravahaEngine engine = engine(dir.resolve("dlq"))) {
            engine.push(
                    "s",
                    new Object[] {1L, 10L, 2L},
                    new Object[] {2L, 10L, 0L},
                    new Object[] {3L, Long.MIN_VALUE, -1L},
                    new Object[] {4L, 10L, 5L});
            assertThat(engine.find("q").orElseThrow().state()).isEqualTo(QueryState.RUNNING);
            assertThat(rows(engine, "q")).containsExactly("1|5", "4|2");
            assertThat(engine.deadLetters().counts("q").entries()).isEqualTo(2);
            assertThat(engine.deadLetters().page("q", 0, 10).entries())
                    .allSatisfy(entry -> assertThat(entry.letter().code()).isEqualTo("PRV-3027"))
                    .extracting(entry -> entry.letter().reason())
                    .anySatisfy(reason -> assertThat(reason).contains("division by zero"))
                    .anySatisfy(reason -> assertThat(reason).contains("BIGINT overflow"));
        }
    }

    @Test
    void withoutADeadLetterQueueTheRowStopsTheQueryAsBefore() {
        try (PravahaEngine engine = engine(null)) {
            engine.push("s", new Object[] {1L, 10L, 2L});
            assertThatThrownBy(() -> engine.push("s", new Object[] {2L, 10L, 0L}))
                    .hasMessageContaining("division by zero");
            assertThat(engine.find("q").orElseThrow().state()).isEqualTo(QueryState.FAILED);
        }
    }

    @Test
    void aFailureAboveStateStopsTheQueryEvenWithADeadLetterQueue(@TempDir Path dir) throws Exception {
        try (PravahaEngine engine = engine(dir.resolve("dlq"))) {
            // The division is above the aggregate: the state has taken the row, and dropping it
            // could not undo that, so the query stops as before rather than dead-lettering.
            engine.register("total", "SELECT COUNT(*) AS n, SUM(a) / SUM(b) AS r FROM s", "n");
            engine.push("s", new Object[] {1L, 10L, 2L});
            try {
                engine.push("s", new Object[] {1L, 10L, -2L});
            } catch (RuntimeException expected) {
                // The push may or may not see it, by when the aggregate publishes.
            }
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (engine.find("total").orElseThrow().state() != QueryState.FAILED && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(engine.find("total").orElseThrow().state()).isEqualTo(QueryState.FAILED);
            assertThat(engine.deadLetters().counts("total").entries()).isZero();
            assertThat(engine.find("q").orElseThrow().state()).isEqualTo(QueryState.RUNNING);
        }
    }
}
