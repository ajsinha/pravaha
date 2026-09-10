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
package com.ash.messaging.pravaha.serving;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.RowView;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.common.arena.RowArena;
import com.ash.messaging.pravaha.common.memory.MemoryAccess;
import com.ash.messaging.pravaha.common.row.BinaryRowView;
import com.ash.messaging.pravaha.common.row.BinaryRowWriter;
import com.ash.messaging.pravaha.common.row.RowLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a query's answer without a second system in the call.
 *
 * <p>The behaviours that matter here are not about storage. They are about what a read is promised:
 * that a consistent read never sees half a batch, that the answer says how stale it is rather than
 * implying it is fresh, and that a question the view cannot answer honestly is refused rather than
 * answered with the nearest thing to hand.
 */
class ServedViewTest {

    private static final StreamSchema SCHEMA = StreamSchema.builder("user_volume")
            .field("user_id", Types.string())
            .field("total", Types.int64())
            .build();

    private final RowArena arena = new RowArena(MemoryAccess.best(), 1 << 16, 8);

    @AfterEach
    void tearDown() {
        arena.close();
    }

    private static ServedView view() {
        return new ServedView("user_volume", SCHEMA, List.of(0), 1000);
    }

    @Test
    void aCommittedChangeIsReadable() {
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);

        ViewResult result = view.get("u1");

        assertThat(result.found()).isTrue();
        assertThat(result.values().orElseThrow()[1]).isEqualTo(100L);
        assertThat(result.logicalFrontier()).isEqualTo(10);
        assertThat(result.frontierComplete()).isTrue();
    }

    @Test
    void aConsistentReadNeverSeesHalfABatch() {
        // The property the two maps exist for. Between commits the view is mid-update, and a
        // consistent read that saw it would be comparing a number that never existed at any point
        // in the input.
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);

        view.apply(row("u1", 999, 1), 20);

        assertThat(view.get("u1").values().orElseThrow()[1])
                .as("an uncommitted change was visible to a consistent read")
                .isEqualTo(100L);
        assertThat(view.get(new Consistency.Latest(), Duration.ZERO, "u1")
                        .values()
                        .orElseThrow()[1])
                .as("a latest read is supposed to see it")
                .isEqualTo(999L);
    }

    @Test
    void staleneessComesBackWithTheAnswer() {
        // Every streaming system is eventually consistent. The difference is whether the caller can
        // find out by how much, and therefore decide, rather than assuming freshness it has no
        // basis for.
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);
        view.apply(row("u1", 200, 1), 45);

        ViewResult result = view.get("u1");

        assertThat(result.logicalFrontier()).isEqualTo(10);
        assertThat(result.stalenessNanos()).isEqualTo(35);
        assertThat(result.frontierComplete()).isTrue();
    }

    @Test
    void aLatestReadSaysWhenItMayIncludeUncommittedWork() {
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);

        assertThat(view.get(new Consistency.Latest(), Duration.ZERO, "u1").frontierComplete())
                .as("nothing is pending, so even a latest read is as of a committed frontier")
                .isTrue();

        view.apply(row("u2", 5, 1), 20);

        assertThat(view.get(new Consistency.Latest(), Duration.ZERO, "u1").frontierComplete())
                .as("with a batch in flight, a latest read may include work that could still be revised")
                .isFalse();
    }

    @Test
    void aMissStillSaysWhatItWasAMissAsOf() {
        // The useful half of a miss. "Not there" is not an answer; "not there as of frontier 10" is.
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);

        ViewResult result = view.get("nobody");

        assertThat(result.found()).isFalse();
        assertThat(result.logicalFrontier()).isEqualTo(10);
    }

    @Test
    void aNegativeWeightRemovesTheKey() {
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);

        view.apply(row("u1", 100, -1), 20);

        assertThat(view.get("u1").found())
                .as("the removal must not be visible before it commits")
                .isTrue();
        view.commit(20);
        assertThat(view.get("u1").found()).isFalse();
        assertThat(view.size()).isZero();
    }

    @Test
    void aPendingRemovalIsVisibleToALatestRead() {
        // Whatever the committed map still says: the overlay is newer by definition, and a tombstone
        // in it means the key is gone.
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);
        view.apply(row("u1", 100, -1), 20);

        assertThat(view.get(new Consistency.Latest(), Duration.ZERO, "u1").found())
                .isFalse();
    }

    @Test
    void anUpdateIsARetractionAndAnInsertAndNeedsNoUpdatePath() {
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);

        view.apply(row("u1", 100, -1), 20);
        view.apply(row("u1", 250, 1), 20);
        view.commit(20);

        assertThat(view.get("u1").values().orElseThrow()[1]).isEqualTo(250L);
        assertThat(view.size()).isEqualTo(1);
    }

    @Test
    void anAtLeastReadReturnsOnceTheFrontierArrives() throws Exception {
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);

        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            view.apply(row("u1", 777, 1), 60);
            view.commit(60);
        });
        writer.start();

        ViewResult result = view.get(new Consistency.AtLeast(60), Duration.ofSeconds(10), "u1");
        writer.join();

        assertThat(result.values().orElseThrow()[1]).isEqualTo(777L);
        assertThat(result.logicalFrontier()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void anAtLeastReadGivesUpRatherThanHanging() {
        // A source going quiet is normal, not exceptional, and an unbounded wait here is
        // indistinguishable from a hung engine.
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);

        assertThatThrownBy(() -> view.get(new Consistency.AtLeast(9999), Duration.ofMillis(50), "u1"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4021")
                .hasMessageContaining("may be idle, or behind");
    }

    @Test
    void anAsOfReadIsRefusedRatherThanAnsweredWithThePresent() {
        // The worst possible response to an audit question is today's number presented as
        // yesterday's, so the view says where the past actually lives instead.
        ServedView view = view();
        view.apply(row("u1", 100, 1), 10);
        view.commit(10);

        assertThatThrownBy(() -> view.get(new Consistency.AsOf(5), Duration.ZERO, "u1"))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4020")
                .hasMessageContaining("the past lives in checkpoints");
    }

    @Test
    void aViewKeyedOnSomethingUnboundedFailsWithTheCount() {
        // The same failure as an unbounded GROUP BY, arriving by a different route.
        ServedView view = new ServedView("small", SCHEMA, List.of(0), 3);
        for (int i = 0; i < 10; i++) {
            view.apply(row("u" + i, i, 1), i);
        }

        assertThatThrownBy(() -> view.commit(10))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4022")
                .hasMessageContaining("holds 10 keys");
    }

    @Test
    void aFrontierGoingBackwardsIsRefused() {
        ServedView view = view();
        view.commit(10);

        assertThatThrownBy(() -> view.commit(5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("went backwards");
    }

    @Test
    void aViewWithoutAKeyIsRefused() {
        assertThatThrownBy(() -> new ServedView("x", SCHEMA, List.of(), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a stream with extra steps");
    }

    @Test
    void aScanSeesTheCommittedRowsAndNotThePendingOnes() {
        ServedView view = view();
        view.apply(row("u1", 1, 1), 10);
        view.apply(row("u2", 2, 1), 10);
        view.commit(10);
        view.apply(row("u3", 3, 1), 20);

        assertThat(view.scan()).hasSize(2);
    }

    /** One row of the view's schema, with the given Z-set weight. */
    private RowView row(String user, long total, long weight) {
        RowLayout layout = RowLayout.of(SCHEMA);
        BinaryRowWriter writer = new BinaryRowWriter(layout);
        long handle = arena.allocate(layout.rowSize(128));
        writer.begin(arena.regionOf(handle), arena.offsetOf(handle));
        writer.setString(0, user)
                .setLong(1, total)
                .weight(weight)
                .eventTimestampNanos(total)
                .sequence(total)
                .commit();
        arena.trimTo(handle, writer.sizeSoFar());
        return new BinaryRowView(layout).wrap(arena.regionOf(handle), arena.offsetOf(handle));
    }
}
