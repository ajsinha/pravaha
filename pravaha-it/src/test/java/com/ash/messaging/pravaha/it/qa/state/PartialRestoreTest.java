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
package com.ash.messaging.pravaha.it.qa.state;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.state.checkpoint.Checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RESTOREPART-1: a restore that fails half-way leaves nothing behind.
 *
 * <p>The registry answers a refused restore by starting the query from its sources, which is sound
 * only if the query then holds nothing. It held whatever had been put back before the refusal, and
 * the replay counted those rows twice. Each case here restores a checkpoint whose later part is
 * refused -- a lane snapshot cut short after its windows, a join snapshot cut short after both its
 * sides, a view that refuses after every lane -- and then feeds the query as a replay would: the
 * answer has to be the new rows alone, and the lane has to be alive to compute it.
 */
class PartialRestoreTest extends StateTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Test
    void aLaneSnapshotRefusedAfterItsWindowsWereReadLeavesNoWindowsBehind() {
        Checkpoint checkpoint = windowedCheckpointHolding(100, 102);
        // The last four bytes are the grouped-aggregate count, read after every windowed aggregate:
        // cut them off and the restore fails with the windows already back.
        @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
        Checkpoint corrupt =
                withLaneState(checkpoint, cutShort(checkpoint.operatorState().get("lane-0"), 4));

        try (RawExecution b = rawWindowed()) {
            assertThatThrownBy(() -> b.execution.restore(corrupt, WAIT))
                    .isInstanceOf(PravahaException.class)
                    .hasMessageContaining("PRV-3010");
            assertThat(b.execution.holdsPartlyRestoredState()).isFalse();

            b.feedAt("u1", 5, 3_000_000_000L);
            assertThat(b.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            b.advanceWatermark(11_000_000_000L);
            // Alive: the refusal was caught on the lane, not left to kill it.
            b.execution.checkHealth();

            assertThat(totalFor(b, "u1"))
                    .as("5 from the replay alone; 207 is 100 + 102 left behind by the refused restore")
                    .isEqualTo(5L);
        }
    }

    @Test
    void aJoinSnapshotRefusedAfterBothSidesWereReadLeavesNeitherSideBehind() {
        RawJoinExecution a = rawJoin();
        a.feedTxn("u1", 300, 0L);
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        Checkpoint checkpoint = a.execution.checkpoint(1, Duration.ofSeconds(5));
        a.abort();
        @SuppressWarnings("NullAway") // a value the case has just put there, or one whose absence should fail it
        Checkpoint corrupt =
                withLaneState(checkpoint, cutShort(checkpoint.operatorState().get("lane-0"), 4));

        try (RawJoinExecution b = rawJoin()) {
            assertThatThrownBy(() -> b.execution.restore(corrupt, WAIT)).isInstanceOf(PravahaException.class);

            b.feedLkp("u1", "gold");
            assertThat(b.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            b.execution.checkHealth();

            assertThat(b.emitted)
                    .as("the txn row came back with the refused restore and joined the replayed lookup")
                    .isEmpty();
        }
    }

    @Test
    void aViewThatRefusesAfterEveryLaneIsRestoredPutsTheLanesBackToo() {
        Checkpoint checkpoint = windowedCheckpointHolding(100, 102);
        Map<String, byte[]> state = new HashMap<>(checkpoint.operatorState());
        state.put(com.ash.messaging.pravaha.runtime.exec.QueryExecution.SERVED_VIEW_STATE, new byte[] {1, 2, 3});
        Checkpoint withView = new Checkpoint(checkpoint.id(), checkpoint.timestampNanos(), checkpoint.offsets(), state);

        try (RawExecution b = rawWindowed()) {
            AtomicReference<byte[]> viewHolds = new AtomicReference<>(new byte[0]);
            b.execution.checkpointingViewWith(viewHolds::get, bytes -> {
                if (bytes.length == 3) {
                    throw new PravahaException(
                            com.ash.messaging.pravaha.state.StateErrors.STATE_UNREADABLE,
                            "a view body this test refuses");
                }
                viewHolds.set(bytes);
            });

            assertThatThrownBy(() -> b.execution.restore(withView, WAIT)).hasMessageContaining("PRV-4002");

            b.feedAt("u1", 5, 3_000_000_000L);
            assertThat(b.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            b.advanceWatermark(11_000_000_000L);
            b.execution.checkHealth();
            assertThat(totalFor(b, "u1")).isEqualTo(5L);
        }
    }

    @Test
    void aRestoreUndoneByItsCallerStartsFromNothingToo() {
        // The registry's own half -- the sinks a checkpoint recorded -- can refuse after the
        // execution's half succeeded; forgetRestoredState is how it puts the execution's back.
        Checkpoint checkpoint = windowedCheckpointHolding(100, 102);

        try (RawExecution b = rawWindowed()) {
            b.execution.restore(checkpoint, WAIT);
            b.execution.forgetRestoredState(WAIT);
            assertThat(b.execution.holdsPartlyRestoredState()).isFalse();

            b.feedAt("u1", 5, 3_000_000_000L);
            assertThat(b.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            b.advanceWatermark(11_000_000_000L);
            assertThat(totalFor(b, "u1")).isEqualTo(5L);
        }
    }

    @Test
    void aCompleteRestoreStillRestoresEverything() {
        Checkpoint checkpoint = windowedCheckpointHolding(100, 102);

        try (RawExecution b = rawWindowed()) {
            b.execution.restore(checkpoint, WAIT);
            b.feedAt("u1", 5, 3_000_000_000L);
            assertThat(b.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
            b.advanceWatermark(11_000_000_000L);
            assertThat(totalFor(b, "u1")).isEqualTo(207L);
        }
    }

    private static Checkpoint windowedCheckpointHolding(long first, long second) {
        RawExecution a = rawWindowed();
        a.feedAt("u1", first, 1_000_000_000L);
        a.feedAt("u1", second, 2_000_000_000L);
        assertThat(a.execution.awaitQuiescent(Duration.ofSeconds(10))).isTrue();
        Checkpoint checkpoint = a.execution.checkpoint(1, Duration.ofSeconds(5));
        a.abort();
        return checkpoint;
    }

    private static Checkpoint withLaneState(Checkpoint checkpoint, byte[] laneState) {
        Map<String, byte[]> state = new HashMap<>(checkpoint.operatorState());
        state.put("lane-0", laneState);
        return new Checkpoint(checkpoint.id(), checkpoint.timestampNanos(), checkpoint.offsets(), state);
    }

    private static byte[] cutShort(byte[] bytes, int by) {
        return Arrays.copyOf(bytes, bytes.length - by);
    }

    private static long totalFor(RawExecution execution, String user) {
        synchronized (execution.emitted) {
            return execution.emitted.stream()
                    .filter(row -> user.equals(row.asString(0)))
                    .mapToLong(row -> row.asLong(1) * row.weight())
                    .sum();
        }
    }
}
