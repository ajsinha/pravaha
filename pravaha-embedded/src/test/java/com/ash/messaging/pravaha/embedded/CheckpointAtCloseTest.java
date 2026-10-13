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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EMBEDCKPT-1: a clean close takes a last checkpoint, so rows pushed after the last periodic one
 * come back on reopen. The interval is an hour, so no periodic checkpoint ever holds them.
 */
class CheckpointAtCloseTest {

    private static PravahaEngine engine(Path dir) {
        PravahaEngine engine = PravahaEngine.create(Configuration.builder()
                .set("pravaha.registry.journal", dir.resolve("registry.journal").toString())
                .set("pravaha.checkpoint.directory", dir.resolve("checkpoints").toString())
                .set("pravaha.checkpoint.interval", "1h")
                .build());
        engine.declareStream("s", "id:INT64,v:INT64");
        engine.start();
        return engine;
    }

    private static List<String> rows(PravahaEngine engine) {
        List<String> out = new ArrayList<>();
        engine.find("q").orElseThrow().view().scan().forEach(r -> out.add(r[0] + "=" + r[1]));
        out.sort(null);
        return out;
    }

    @Test
    void rowsPushedBeforeACleanCloseAreThereOnReopen(@TempDir Path dir) {
        try (PravahaEngine engine = engine(dir)) {
            engine.register("q", "SELECT id, v FROM s", "id");
            engine.push("s", new Object[] {1L, 10L});
            engine.push("s", new Object[] {2L, 20L});
        }
        try (PravahaEngine engine = engine(dir)) {
            assertThat(rows(engine)).containsExactly("1=10", "2=20");
        }
    }
}
