/*
 * Tutorial 12 (docs/tutorials/12-embedding-pravaha.md): testing the revenue board with @PravahaTest.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
 */
package com.example.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import com.ash.messaging.pravaha.spring.test.PravahaTest;
import com.ash.messaging.pravaha.spring.test.PravahaTester;

/**
 * The slice: the engine from application.yaml (in memory, whatever the file says about checkpoints),
 * the beans with a @PravahaListener -- the board -- and a PravahaTester. No sleeping: a push is
 * committed when it returns, awaitListeners returns when the listener has been handed it, and
 * awaitView when the view's answer holds. Event time only goes forward, and Spring keeps one context
 * -- one engine -- for every test that asks for the same one: each test here gets a fresh engine, so
 * one test's advanced clock cannot make the next one's rows late.
 */
@PravahaTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RevenueBoardTest {

    private static final Instant NINE = Instant.parse("2026-10-01T09:00:00Z");

    @Autowired
    PravahaTester pravaha;

    @Autowired
    RevenueBoard board;

    @Test
    void aClosedMinuteReachesTheListener() {
        pravaha.push("orders",
                new Object[] {1L, "ann-gmbh", "EMEA", 1200L, NINE.plusSeconds(5)},
                new Object[] {2L, "cafe-lyon", "EMEA", 4500L, NINE.plusSeconds(31)});
        pravaha.engine().advanceEventTime("orders", NINE.plusSeconds(70));
        pravaha.awaitListeners("region_revenue");
        assertThat(board.board()).containsEntry("EMEA 2026-10-01T09:01:00Z", 5700L);
    }

    @Test
    void theViewAnswersOncePublished() {
        pravaha.push("orders", new Object[] {3L, "kyoto-ya", "APAC", 800L, NINE.plusSeconds(48)});
        pravaha.engine().advanceEventTime("orders", NINE.plusSeconds(70));
        List<Map<String, Object>> rows =
                pravaha.awaitView("region_revenue", answer -> answer.stream().anyMatch(r -> "APAC".equals(r.get("region"))));
        assertThat(rows).anySatisfy(r -> assertThat(r).containsEntry("revenue", 800L).containsEntry("orders", 1L));
    }
}
