/*
 * Tutorial 12 (docs/tutorials/12-embedding-pravaha.md): a Spring service with a maintained view inside it.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
 * PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.
 */
package com.example.board;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.ash.messaging.pravaha.spring.PravahaListener;
import com.ash.messaging.pravaha.spring.PravahaTemplate;

/** Each region's revenue per minute: orders pushed in, the board kept up to date by a listener. */
@Service
public class RevenueBoard {

    /** A row of region_revenue, matched by column name (window_end is windowEnd). */
    public record RegionRevenue(String region, Instant windowEnd, long revenue, long orders) {}

    private final PravahaTemplate pravaha;
    /** What the listener has been told: region and minute to revenue. */
    private final Map<String, Long> board = new ConcurrentHashMap<>();

    public RevenueBoard(PravahaTemplate pravaha) {
        this.pravaha = pravaha;
    }

    /** The intake path: one row per order, in the stream's column order. */
    public void recordOrder(long orderId, String customer, String region, long amount, Instant at) {
        pravaha.push("orders", new Object[] {orderId, customer, region, amount, at});
    }

    /** Every committed change of the view, off the engine's thread; a correction is -1 then +1. */
    @PravahaListener(query = "region_revenue")
    void onRevenue(RegionRevenue row, boolean retraction) {
        String key = row.region() + " " + row.windowEnd();
        if (retraction) {
            board.remove(key);
        } else {
            board.put(key, row.revenue());
        }
    }

    /** What the listener has built. */
    public Map<String, Long> board() {
        return Map.copyOf(board);
    }

    /** The same answer, read from the view itself. */
    public List<RegionRevenue> current() {
        return pravaha.query(RegionRevenue.class,
                "SELECT region, window_end, revenue, orders FROM region_revenue");
    }
}
