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
package com.ash.messaging.pravaha.it.qa.streaming;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;
import com.ash.messaging.pravaha.registry.QueryRegistry;
import com.ash.messaging.pravaha.registry.RegisteredQuery;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.serving.ViewCatalog;
import com.ash.messaging.pravaha.serving.ViewQuery;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A continuous query answering while its stream is still open.
 *
 * <p>The distinction this file exists for: a query that produces the right answer <em>after</em> its
 * input ends is a batch query with extra steps. Round 1 recorded that a registered aggregate emitted
 * only at end of input, and a stream has no end -- so the canonical continuous query ingested
 * everything and published nothing, while reporting RUNNING throughout.
 *
 * <p>Nothing here ever closes the stream. Every assertion is made with the source still open and
 * more rows still to come, which is the only way to tell the two apart.
 */
@Timeout(90)
class ContinuousQueryAnswerTest {

    private static final StreamSchema TXN = StreamSchema.builder("txn")
            .field("txn_id", Types.int64())
            .field("user_id", Types.string())
            .field("amount", Types.int64())
            .field("event_time", Types.timestamp())
            .build();

    private ViewCatalog views;
    private QueryRegistry registry;
    private RowArena arena;

    @BeforeEach
    void start() {
        views = new ViewCatalog();
        registry = new QueryRegistry(views, TXN);
        arena = new RowArena(MemoryAccess.best(), 1 << 20, 8);
    }

    @AfterEach
    void stop() {
        registry.close();
        arena.close();
    }

    /** Pushes one row and commits, the way a client feeding a registered query does. */
    private void push(String name, long id, String user, long amount, long weight) {
        push(name, id, user, amount, weight, id);
    }

    private void push(String name, long id, String user, long amount, long weight, long eventTime) {
        RegisteredQuery query = registry.require(name);
        RowLayout layout = RowLayout.of(TXN);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        BinaryRowView view = new BinaryRowView(layout);
        long handle = arena.allocate(layout.rowSize(64));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setLong(0, id);
        writer.setString(1, user);
        writer.setLong(2, amount);
        writer.setLong(3, eventTime);
        writer.weight(weight).eventTimestampNanos(eventTime).sequence(id).commit();
        arena.trimTo(handle, writer.sizeSoFar());
        query.accept(view.wrap(arena.regionOf(handle), arena.offsetOf(handle)));
        // The lane applies on its own thread, so committing first would publish a frontier the row
        // has not reached and tell a reader about a change it cannot see.
        query.awaitApplied(Duration.ofSeconds(10));
        query.commit();
    }

    private List<String> read(String sql) {
        List<String> rows = new ArrayList<>();
        new ViewQuery(views).execute(sql).rows().forEach(row -> rows.add(row[0] + "=" + row[1]));
        rows.sort(String::compareTo);
        return rows;
    }

    private static final long SECOND = 1_000_000_000L;

    private static final String WINDOWED = "SELECT user_id, SUM(amount) AS total FROM txn "
            + "GROUP BY TUMBLE(event_time, INTERVAL '1' SECOND), user_id";

    /** Moves event time on, the way a source's watermark does. The push path advances it explicitly. */
    private void advanceTo(String name, long nanos) {
        registry.require(name).advanceWatermark(nanos);
        registry.require(name).commit();
    }

    @Test
    void cq001_aKeyedAggregateAnswersWhileTheStreamIsStillOpen() {
        // An unwindowed GROUP BY is refused on purpose (PRV-2050): its key space has no bound, so
        // its state grows for ever. A keyed continuous aggregate is therefore a windowed one, and
        // what makes it continuous is that a *later row* closes the window -- not the end of input,
        // which a stream does not have.
        registry.register("totals", WINDOWED, List.of(0), Principal.ANONYMOUS);

        push("totals", 1, "ann", 100, 1, 100_000_000L);
        push("totals", 2, "bob", 250, 1, 200_000_000L);
        push("totals", 3, "ann", 50, 1, 300_000_000L);
        assertThat(read("SELECT user_id, total FROM totals"))
                .as("the second is not over; publishing now would publish a half-assembled total")
                .isEmpty();

        // A row in the next second, and nothing else. No close, no flush, no end of input.
        push("totals", 4, "cat", 900, 1, 1_500_000_000L);
        advanceTo("totals", 1_500_000_000L);

        assertThat(read("SELECT user_id, total FROM totals"))
                .as("the first second's answer, published because time moved on and while more rows are coming")
                .containsExactly("ann=150", "bob=250");
    }

    @Test
    void cq002_aRetractionRevisesAWindowBeforeItCloses() {
        registry.register("totals", WINDOWED, List.of(0), Principal.ANONYMOUS);

        push("totals", 1, "ann", 100, 1, 100_000_000L);
        push("totals", 2, "ann", 50, 1, 200_000_000L);
        // The same row again with weight -1: a correction is a retraction plus an insert, and this
        // is the retraction half arriving on its own, before the window has been published.
        push("totals", 2, "ann", 50, -1, 200_000_000L);

        push("totals", 3, "cat", 900, 1, 1_500_000_000L);
        advanceTo("totals", 1_500_000_000L);

        assertThat(read("SELECT user_id, total FROM totals"))
                .as("100, not 150: an aggregate that ignored the weight would carry the retracted row")
                .containsExactly("ann=100");
    }

    @Test
    void cq003_aGlobalAggregateEmitsWithoutAnEndOfInput() {
        registry.register(
                "overall", "SELECT COUNT(*) AS n, SUM(amount) AS total FROM txn", List.of(0), Principal.ANONYMOUS);

        push("overall", 1, "ann", 100, 1);
        assertThat(new ViewQuery(views)
                        .execute("SELECT n, total FROM overall").rows().stream()
                                .map(row -> row[0] + "/" + row[1])
                                .toList())
                .as("a global aggregate that only emitted at end of input would be empty here for ever")
                .containsExactly("1/100");

        push("overall", 2, "bob", 250, 1);
        assertThat(new ViewQuery(views)
                        .execute("SELECT n, total FROM overall").rows().stream()
                                .map(row -> row[0] + "/" + row[1])
                                .toList())
                .as("one row, revised -- not two rows, one per commit")
                .containsExactly("2/350");
    }

    @Test
    void cq004_aFilteredContinuousQueryKeepsFilteringAsRowsArrive() {
        registry.register("big", "SELECT txn_id, amount FROM txn WHERE amount > 100", List.of(0), Principal.ANONYMOUS);

        push("big", 1, "ann", 100, 1);
        assertThat(read("SELECT txn_id, amount FROM big"))
                .as("100 is not > 100, and a continuous filter must hold on the first row as on the thousandth")
                .isEmpty();

        push("big", 2, "bob", 250, 1);
        push("big", 3, "cat", 50, 1);
        assertThat(read("SELECT txn_id, amount FROM big")).containsExactly("2=250");
    }
}
