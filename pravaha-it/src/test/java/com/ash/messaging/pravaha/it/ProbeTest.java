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
package com.ash.messaging.pravaha.it;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.registry.Subscription;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewChange;

class ProbeTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .eventTime("event_time")
            .build();

    private static final Principal DANA = new Principal("dana", "acme", Set.of("analyst"), Map.of());
    private static final long SECOND = 1_000_000_000L;

    @Test
    void probeGlobalPair() {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN);
                RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery q = registry.register("t", "SELECT SUM(amount) AS total FROM txn", List.of(0), DANA);
            List<List<ViewChange>> batches = new ArrayList<>();
            try (Subscription s = q.subscribe(b -> batches.add(List.copyOf(b)))) {
                feed(arena, q, "u1", 300, 0);
                q.commit();
                q.commit();
                feed(arena, q, "u1", 50, 0);
                q.commit();
                q.commit();
            }
            for (int i = 0; i < batches.size(); i++) {
                System.out.println("PROBE gbatch" + i + " " + render(batches.get(i)));
            }
            System.out.println("PROBE gview="
                    + q.view().scan().stream().map(java.util.Arrays::toString).toList());
        }
    }

    @Test
    void probeWindowed() {
        ViewCatalog views = new ViewCatalog();
        try (QueryRegistry registry = new QueryRegistry(views, TXN);
                RowArena arena = new RowArena(MemoryAccess.best(), 1 << 20, 8)) {
            RegisteredQuery q = registry.register(
                    "w",
                    "SELECT user_id, window_start, SUM(amount) AS total FROM "
                            + "TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) "
                            + "GROUP BY user_id, window_start, window_end",
                    List.of(0, 1),
                    DANA);
            List<List<ViewChange>> batches = new ArrayList<>();
            try (Subscription s = q.subscribe(b -> batches.add(List.copyOf(b)))) {
                for (String u : List.of("u1", "u2", "u3")) {
                    feed(arena, q, u, 100, 1 * SECOND);
                    feed(arena, q, u, 101, 2 * SECOND);
                    feed(arena, q, u, 102, 3 * SECOND);
                    feed(arena, q, u, 103, 4 * SECOND);
                }
                q.advanceWatermark(11 * SECOND);
            }
            for (int i = 0; i < batches.size(); i++) {
                System.out.println("PROBE wbatch" + i + " " + render(batches.get(i)));
            }
        }
    }

    private static String render(List<ViewChange> changes) {
        StringBuilder sb = new StringBuilder();
        for (ViewChange c : changes) {
            sb.append(c.weight()).append(java.util.Arrays.toString(c.values())).append(' ');
        }
        return sb.toString();
    }

    private static void feed(RowArena arena, RegisteredQuery query, String user, long amount, long t) {
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(256));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user);
        writer.setLong(1, amount);
        writer.setLong(2, t);
        writer.weight(1L).eventTimestampNanos(t).sequence(t).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        query.awaitApplied(java.time.Duration.ofSeconds(10));
    }
}
