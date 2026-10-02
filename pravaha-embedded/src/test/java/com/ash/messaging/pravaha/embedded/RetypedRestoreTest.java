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

/**
 * RETYPERESTORE-1: a checkpoint records the output schema it was taken under, and a restart whose
 * query now produces another schema rebuilds from its sources rather than restoring values of the old
 * types into the new columns. A column added to the stream that the query does not select changes
 * nothing, and the state comes back.
 */
class RetypedRestoreTest {

    private static PravahaEngine engine(Path dir, String schema) {
        ConfigurationBuilder builder = Configuration.builder()
                .set("pravaha.registry.journal", dir.resolve("registry.journal").toString())
                .set("pravaha.checkpoint.directory", dir.resolve("checkpoints").toString())
                .set("pravaha.checkpoint.interval", "100ms");
        PravahaEngine engine = PravahaEngine.create(builder.build());
        engine.declareStream("s", schema);
        engine.start();
        return engine;
    }

    private static List<String> rows(PravahaEngine engine) {
        List<String> out = new ArrayList<>();
        engine.find("q")
                .orElseThrow()
                .view()
                .scan()
                .forEach(r -> out.add(r[0] + "=" + r[1] + ":" + r[1].getClass().getSimpleName()));
        out.sort(null);
        return out;
    }

    /** Waits for checkpoints taken after the push: two, so at least one follows it. */
    private static void checkpointed(Path dir) throws Exception {
        // Several intervals (100 ms) after the push, so a checkpoint holding it has been taken.
        Thread.sleep(600);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(dir.resolve("checkpoints"))) {
                if (files.filter(p -> p.getFileName().toString().matches("checkpoint-\\d+\\.bin"))
                                .count()
                        >= 2) {
                    return;
                }
            }
            Thread.sleep(20);
        }
    }

    @Test
    void aCheckpointOfAnotherOutputSchemaIsRebuiltFromNotRestored(@TempDir Path dir) throws Exception {
        try (PravahaEngine engine = engine(dir, "id:INT64,v:INT64")) {
            engine.register("q", "SELECT id, v FROM s", "id");
            engine.push("s", new Object[] {1L, 10L});
            checkpointed(dir);
        }
        try (PravahaEngine engine = engine(dir, "id:INT64,v:STRING")) {
            engine.push("s", new Object[] {2L, "abc"});
            assertThat(engine.find("q").orElseThrow().state()).isEqualTo(QueryState.RUNNING);
            assertThat(rows(engine)).containsExactly("2=abc:String");
            assertThat(engine.find("q").orElseThrow().lastCheckpointFailure())
                    .hasValueSatisfying(failure -> assertThat(failure)
                            .contains("PRV-4095")
                            .contains("v INT64")
                            .contains("v VARCHAR"));
        }
    }

    @Test
    void aColumnTheQueryDoesNotSelectLeavesTheRestoreAsItWas(@TempDir Path dir) throws Exception {
        try (PravahaEngine engine = engine(dir, "id:INT64,v:INT64")) {
            engine.register("q", "SELECT id, v FROM s", "id");
            engine.push("s", new Object[] {1L, 10L});
            checkpointed(dir);
        }
        try (PravahaEngine engine = engine(dir, "id:INT64,v:INT64,extra:STRING")) {
            engine.push("s", new Object[] {2L, 20L, "x"});
            assertThat(rows(engine)).containsExactly("1=10:Long", "2=20:Long");
        }
    }
}
