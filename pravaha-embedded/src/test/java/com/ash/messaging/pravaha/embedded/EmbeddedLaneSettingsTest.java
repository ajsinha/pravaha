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

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.config.Configuration;
import com.ash.messaging.pravaha.common.config.ConfigurationBuilder;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.registry.QueryState;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.runtime.exec.RowTooWideException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CELLBYTES-1: the embedded engine reads {@code pravaha.lane.*} as a server does, and a row wider
 * than a query's inbox cell is refused for that push -- every query on the stream keeps running.
 *
 * <p>It read no lane setting at all, so the cell was 512 bytes whatever was configured, and a
 * 600-character string stopped every query on the stream for good with a refusal whose remedy --
 * "raise pravaha.lane.inbox.cell-bytes" -- could not be applied.
 */
class EmbeddedLaneSettingsTest {

    private static PravahaEngine engine(Map<String, String> settings) {
        ConfigurationBuilder builder = Configuration.builder();
        settings.forEach(builder::set);
        PravahaEngine engine = PravahaEngine.create(builder.build());
        engine.declareStream("s", "id:INT64,t:STRING");
        engine.start();
        engine.register("q", "SELECT id, t FROM s", "id");
        engine.register("lengths", "SELECT id FROM s WHERE id > 0", "id");
        return engine;
    }

    @Test
    void theConfiguredInboxCellIsTheOneTheQueriesGet() {
        try (PravahaEngine engine = engine(Map.of("pravaha.lane.inbox.cell-bytes", "65536"))) {
            engine.push("s", new Object[] {1L, "x".repeat(4_000)});
            assertThat(engine.find("q").orElseThrow().state()).isEqualTo(QueryState.RUNNING);
            assertThat(engine.find("q").orElseThrow().maxRowBytes()).isEqualTo(65_536);
            assertThat(engine.find("q").orElseThrow().view().scan()).hasSize(1);
        }
    }

    @Test
    void aRowWiderThanTheCellIsRefusedForThatPushAndEveryQueryKeepsRunning() {
        try (PravahaEngine engine = engine(Map.of())) {
            engine.push("s", new Object[] {1L, "short"});
            assertThatThrownBy(() -> engine.push("s", new Object[] {2L, "fits"}, new Object[] {3L, "x".repeat(600)}))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-8102")
                    .hasMessageContaining("row 2 of this push")
                    .hasMessageContaining("at most 512 bytes")
                    .hasMessageContaining("pravaha.lane.inbox.cell-bytes")
                    .hasMessageContaining("every query on the stream keeps running");
            for (String name : new String[] {"q", "lengths"}) {
                assertThat(engine.find(name).orElseThrow().state()).as(name).isEqualTo(QueryState.RUNNING);
                assertThat(engine.find(name).orElseThrow().view().scan())
                        .as("nothing of the refused push was delivered to %s", name)
                        .hasSize(1);
            }
            engine.push("s", new Object[] {4L, "after"});
            assertThat(engine.find("q").orElseThrow().view().scan()).hasSize(2);
        }
    }

    @Test
    void aRowHandedToARegisteredQueryDirectlyIsRefusedWithoutFailingIt() {
        // Every caller, not only the engine's push: the registry used to turn the inbox's refusal
        // into a failure of the query.
        try (PravahaEngine engine = engine(Map.of("pravaha.lane.inbox.cell-bytes", "256"));
                RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 1)) {
            RegisteredQuery query = engine.find("q").orElseThrow();
            RowEncoder encoder = new RowEncoder(StreamSchema.builder("s")
                    .field("id", Types.int64())
                    .field("t", Types.string())
                    .build());
            var wide = encoder.write(encoder.validate(new Object[] {1L, "y".repeat(300)}), arena, 1);
            assertThatThrownBy(() -> query.accept("s", wide))
                    .isInstanceOf(RowTooWideException.class)
                    .hasMessageContaining("PRV-3002")
                    .hasMessageContaining("inbox cell of 256 bytes")
                    .hasMessageContaining("the query keeps running");
            assertThat(query.state()).isEqualTo(QueryState.RUNNING);
            engine.push("s", new Object[] {2L, "narrow"});
            assertThat(query.view().scan()).hasSize(1);
        }
    }

    @Test
    void aLaneSettingThatCannotBeUsedRefusesTheStartByName() {
        PravahaEngine engine = PravahaEngine.create(Configuration.builder()
                .set("pravaha.lane.inbox.cell-bytes", "0")
                .build());
        engine.declareStream("s", "id:INT64,t:STRING");
        assertThatThrownBy(engine::start).hasMessageContaining("PRV-8104").hasMessageContaining("pravaha.lane");
    }
}
