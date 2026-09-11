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
package com.ash.messaging.pravaha.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Handing one partition from one node to another.
 *
 * <p>The tests are mostly about <em>order</em>, because order is the whole correctness argument. A
 * handoff that does the same six things in a different sequence can lose state, double-count, or
 * leave two nodes writing one aggregate, and only the last of those is loud.
 */
class PartitionHandoffTest {

    /** Records what it was asked to do, in order, so the sequence can be asserted on. */
    private static final class RecordingOwner implements PartitionOwner {
        private final String node;
        private final List<String> calls;
        private RuntimeException failOn;
        private String failingMethod;
        private PartitionSnapshot snapshotToReturn;

        RecordingOwner(String node, List<String> calls) {
            this.node = node;
            this.calls = calls;
        }

        RecordingOwner failing(String method, RuntimeException failure) {
            this.failingMethod = method;
            this.failOn = failure;
            return this;
        }

        RecordingOwner returningSnapshot(PartitionSnapshot snapshot) {
            this.snapshotToReturn = snapshot;
            return this;
        }

        private void record(String method) {
            calls.add(node + "." + method);
            if (method.equals(failingMethod)) {
                throw failOn;
            }
        }

        @Override
        public void pause(int partition) {
            record("pause");
        }

        @Override
        public PartitionSnapshot snapshot(int partition) {
            record("snapshot");
            return snapshotToReturn != null
                    ? snapshotToReturn
                    : new PartitionSnapshot(partition, 7, Map.of("trades", "offset-500"), Map.of("agg", new byte[64]));
        }

        @Override
        public void restore(PartitionSnapshot snapshot) {
            record("restore");
        }

        @Override
        public void resume(int partition) {
            record("resume");
        }

        @Override
        public void release(int partition) {
            record("release");
        }
    }

    private static final Member A = new Member("a", "host-a", 9070);
    private static final Member B = new Member("b", "host-b", 9071);
    private static final PartitionAssignment.PartitionMove MOVE = new PartitionAssignment.PartitionMove(42, A, B);

    @Test
    void theSequenceIsPauseSnapshotRestoreResumeRelease() {
        List<String> calls = new ArrayList<>();
        PartitionHandoff handoff =
                new PartitionHandoff(MOVE, new RecordingOwner("a", calls), new RecordingOwner("b", calls), null);

        assertThat(handoff.run(Duration.ofSeconds(5))).isEqualTo(PartitionHandoff.Stage.COMPLETE);

        // Every pair in this order is load-bearing. The source stops before it snapshots, or the
        // snapshot is of a moving target. The target restores before it resumes. The source releases
        // last, because until the target is serving its copy is the only copy.
        assertThat(calls).containsExactly("a.pause", "a.snapshot", "b.restore", "b.resume", "a.release");
    }

    @Test
    void theSourceNeverKeepsServingWhileTheTargetStarts() {
        List<String> calls = new ArrayList<>();
        new PartitionHandoff(MOVE, new RecordingOwner("a", calls), new RecordingOwner("b", calls), null)
                .run(Duration.ofSeconds(5));

        // The window that must not exist: both nodes processing one partition. The source's pause
        // must precede the target's resume with nothing in between that starts it again.
        assertThat(calls.indexOf("a.pause")).isLessThan(calls.indexOf("b.resume"));
        assertThat(calls).doesNotContain("a.resume");
    }

    @Test
    void theSourceKeepsItsCopyUntilTheTargetIsServing() {
        List<String> calls = new ArrayList<>();
        new PartitionHandoff(MOVE, new RecordingOwner("a", calls), new RecordingOwner("b", calls), null)
                .run(Duration.ofSeconds(5));

        // Release last. Releasing at snapshot time would shorten the handoff and make a failed
        // restore unrecoverable.
        assertThat(calls.indexOf("a.release")).isGreaterThan(calls.indexOf("b.resume"));
        assertThat(calls.get(calls.size() - 1)).isEqualTo("a.release");
    }

    @Test
    void aFailedRestoreRollsBackAndTheSourceCarriesOn() {
        List<String> calls = new ArrayList<>();
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                new RecordingOwner("a", calls),
                new RecordingOwner("b", calls).failing("restore", new IllegalStateException("disk full")),
                null);

        assertThat(handoff.run(Duration.ofSeconds(5))).isEqualTo(PartitionHandoff.Stage.ROLLED_BACK);

        // The source still holds state and offsets, so resuming it loses nothing and the move can be
        // retried when whatever broke is fixed.
        assertThat(calls).containsExactly("a.pause", "a.snapshot", "b.restore", "a.resume");
        assertThat(calls).doesNotContain("a.release");
    }

    @Test
    void aFailedSnapshotRollsBackWithoutTouchingTheTarget() {
        List<String> calls = new ArrayList<>();
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                new RecordingOwner("a", calls).failing("snapshot", new IllegalStateException("state corrupt")),
                new RecordingOwner("b", calls),
                null);

        assertThat(handoff.run(Duration.ofSeconds(5))).isEqualTo(PartitionHandoff.Stage.ROLLED_BACK);
        assertThat(calls).containsExactly("a.pause", "a.snapshot", "a.resume");
    }

    @Test
    void aSnapshotOfTheWrongPartitionIsRefused() {
        List<String> calls = new ArrayList<>();
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                new RecordingOwner("a", calls)
                        .returningSnapshot(new PartitionSnapshot(9, 1, Map.of(), Map.of("agg", new byte[8]))),
                new RecordingOwner("b", calls),
                null);

        // Restoring it would file partition 9's state under partition 42's name, and every key in
        // both would then be answered from the wrong state.
        assertThat(handoff.run(Duration.ofSeconds(5))).isEqualTo(PartitionHandoff.Stage.ROLLED_BACK);
        assertThat(calls).doesNotContain("b.restore");
    }

    @Test
    void failureAfterOwnershipMovedIsReportedAsUnrecoverableNotRetried() {
        List<String> calls = new ArrayList<>();
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                new RecordingOwner("a", calls),
                new RecordingOwner("b", calls).failing("resume", new IllegalStateException("out of memory")),
                null);

        // Past this point there is no going back: the assignment says b owns it, and a may already
        // have released. Handing it back would be the double-ownership this design exists to avoid.
        assertThatThrownBy(() -> handoff.run(Duration.ofSeconds(5)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9006")
                .hasMessageContaining("recovered from its checkpoint")
                .hasMessageContaining("must not be handed back");
        assertThat(handoff.stage()).isEqualTo(PartitionHandoff.Stage.FAILED);
    }

    @Test
    void aPartitionThatCannotBeResumedAfterRollbackIsLoud() {
        List<String> calls = new ArrayList<>();
        RecordingOwner source = new RecordingOwner("a", calls);
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                source,
                new RecordingOwner("b", calls).failing("restore", new IllegalStateException("disk full")),
                null);
        source.failing("resume", new IllegalStateException("lane gone"));

        // Nobody is serving this partition now. That is worth an exception rather than a log line.
        assertThatThrownBy(() -> handoff.run(Duration.ofSeconds(5)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("is not being served");
        assertThat(handoff.stage()).isEqualTo(PartitionHandoff.Stage.FAILED);
    }

    @Test
    void goingOverThePauseBudgetIsReportedRatherThanAborted() {
        List<String> calls = new ArrayList<>();
        List<String> logged = new ArrayList<>();
        PartitionHandoff handoff =
                new PartitionHandoff(MOVE, new RecordingOwner("a", calls), new RecordingOwner("b", calls), logged::add);

        // A zero budget is always exceeded. Aborting a handoff that is already past the rollback
        // point because it was slow would be worse than being slow.
        assertThat(handoff.run(Duration.ZERO)).isEqualTo(PartitionHandoff.Stage.COMPLETE);
        assertThat(logged).anySatisfy(line -> assertThat(line).contains("over the 0ms budget"));
    }

    @Test
    void offsetsTravelWithTheState() {
        List<String> calls = new ArrayList<>();
        List<PartitionSnapshot> restored = new ArrayList<>();
        RecordingOwner recording = new RecordingOwner("b", calls);
        PartitionOwner target = new PartitionOwner() {
            @Override
            public void pause(int partition) {
                recording.pause(partition);
            }

            @Override
            public PartitionSnapshot snapshot(int partition) {
                return recording.snapshot(partition);
            }

            @Override
            public void restore(PartitionSnapshot snapshot) {
                restored.add(snapshot);
                recording.restore(snapshot);
            }

            @Override
            public void resume(int partition) {
                recording.resume(partition);
            }

            @Override
            public void release(int partition) {
                recording.release(partition);
            }
        };

        new PartitionHandoff(MOVE, new RecordingOwner("a", calls), target, null).run(Duration.ofSeconds(5));

        // State and position are one fact: the state is the result of consuming up to here. A
        // snapshot without offsets leaves the target guessing, and either guess is wrong.
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).offsets()).containsEntry("trades", "offset-500");
        assertThat(restored.get(0).partition()).isEqualTo(42);
    }
}
