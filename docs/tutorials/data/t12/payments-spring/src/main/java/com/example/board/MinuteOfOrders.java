/*
 * Tutorial 12 (docs/tutorials/12-embedding-pravaha.md): what the revenue board does when it runs.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
 */
package com.example.board;

import java.time.Instant;
import java.util.Comparator;
import java.util.TreeMap;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.embedded.PravahaEngine;

/** Takes a minute of orders, closes the minute, prints the board and the view, and lets the app exit. */
@Component
class MinuteOfOrders implements CommandLineRunner {

    private final RevenueBoard board;
    private final PravahaEngine engine;

    MinuteOfOrders(RevenueBoard board, PravahaEngine engine) {
        this.board = board;
        this.engine = engine;
    }

    @Override
    public void run(String... args) throws InterruptedException {
        Instant nine = Instant.parse("2026-10-01T09:00:00Z");
        board.recordOrder(1, "ann-gmbh", "EMEA", 1200, nine.plusSeconds(5));
        board.recordOrder(2, "bobco", "AMER", 300, nine.plusSeconds(12));
        board.recordOrder(3, "cafe-lyon", "EMEA", 4500, nine.plusSeconds(31));
        board.recordOrder(4, "kyoto-ya", "APAC", 800, nine.plusSeconds(48));
        System.out.println("4 orders recorded; the view has " + board.current().size() + " rows");
        // Nothing earlier than 09:01:10 is still coming: the 09:00 minute closes.
        engine.advanceEventTime("orders", nine.plusSeconds(70));
        for (int i = 0; i < 200 && board.board().size() < 3; i++) {
            Thread.sleep(25);              // the listener runs on a thread of its own
        }
        System.out.println("the listener's board:");
        new TreeMap<>(board.board()).forEach((key, revenue) -> System.out.println("  " + key + "  " + revenue));
        board.current().stream()
                .sorted(Comparator.comparing(RevenueBoard.RegionRevenue::region))
                .forEach(row -> System.out.println("the view: " + row));
    }
}
