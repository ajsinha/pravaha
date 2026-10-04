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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Handing one partition from one node to another.
 *
 * <p>ADR-039 item 8, second slice: the sequence is still the whole of the correctness argument, but
 * the argument is no longer only about order. A handoff that does the same six things in a
 * different sequence can lose state, double-count, or leave two nodes writing one aggregate -- and
 * now a handoff that does the six things in the *right* order can still leave two nodes writing one
 * aggregate, if nothing stops a second, concurrent handoff from reaching the same conclusion at the
 * same time. {@link PartitionLeaseCoordinator} is what stops that, and the tests below that build
 * their own {@link InMemoryPartitionLeaseCoordinator} and make two handoffs genuinely disagree over
 * one partition are the ones that could not have passed against the first slice's design at all.
 */
class PartitionHandoffTest {

    /** Records what it was asked to do, in order, so the sequence can be asserted on. */
    private static final class RecordingOwner implements PartitionOwner {
        private final String node;
        private final List<String> calls;
        private @Nullable RuntimeException failOn;
        private @Nullable String failingMethod;
        private @Nullable PartitionSnapshot snapshotToReturn;

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

    /** Builds a lease coordinator with {@code owner} holding {@code move}'s partition, and returns both. */
    private record Fixture(InMemoryPartitionLeaseCoordinator leases, PartitionLease sourceLease) {}

    private static Fixture fixture(Member owner) {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease lease = leases.acquire(MOVE.partition(), owner).orElseThrow();
        return new Fixture(leases, lease);
    }

    @Test
    void theSequenceIsPauseSnapshotRestoreResumeRelease() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(A);
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                new RecordingOwner("a", calls),
                new RecordingOwner("b", calls),
                fixture.leases(),
                fixture.sourceLease(),
                null);

        assertThat(handoff.run(Duration.ofSeconds(5))).isEqualTo(PartitionHandoff.Stage.COMPLETE);

        // Every pair in this order is load-bearing. The source stops before it snapshots, or the
        // snapshot is of a moving target. The target restores before it resumes. The source releases
        // last, because until the target is serving its copy is the only copy.
        assertThat(calls).containsExactly("a.pause", "a.snapshot", "b.restore", "b.resume", "a.release");

        // And the lease itself actually moved -- not merely the local stage flag.
        assertThat(handoff.targetLease()).isPresent();
        assertThat(handoff.targetLease().orElseThrow().owner()).isEqualTo(B);
        assertThat(fixture.leases().isValid(fixture.sourceLease()))
                .as("the source's lease is genuinely gone, not merely believed gone")
                .isFalse();
    }

    @Test
    void theSourceNeverKeepsServingWhileTheTargetStarts() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(A);
        new PartitionHandoff(
                        MOVE,
                        new RecordingOwner("a", calls),
                        new RecordingOwner("b", calls),
                        fixture.leases(),
                        fixture.sourceLease(),
                        null)
                .run(Duration.ofSeconds(5));

        // The window that must not exist: both nodes processing one partition. The source's pause
        // must precede the target's resume with nothing in between that starts it again.
        assertThat(calls.indexOf("a.pause")).isLessThan(calls.indexOf("b.resume"));
        assertThat(calls).doesNotContain("a.resume");
    }

    @Test
    void theSourceKeepsItsCopyUntilTheTargetIsServing() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(A);
        new PartitionHandoff(
                        MOVE,
                        new RecordingOwner("a", calls),
                        new RecordingOwner("b", calls),
                        fixture.leases(),
                        fixture.sourceLease(),
                        null)
                .run(Duration.ofSeconds(5));

        // Release last. Releasing at snapshot time would shorten the handoff and make a failed
        // restore unrecoverable.
        assertThat(calls.indexOf("a.release")).isGreaterThan(calls.indexOf("b.resume"));
        assertThat(calls.get(calls.size() - 1)).isEqualTo("a.release");
    }

    @Test
    void aFailedRestoreRollsBackAndTheSourceCarriesOn() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(A);
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                new RecordingOwner("a", calls),
                new RecordingOwner("b", calls).failing("restore", new IllegalStateException("disk full")),
                fixture.leases(),
                fixture.sourceLease(),
                null);

        assertThat(handoff.run(Duration.ofSeconds(5))).isEqualTo(PartitionHandoff.Stage.ROLLED_BACK);

        // The source still holds state and offsets, so resuming it loses nothing and the move can be
        // retried when whatever broke is fixed.
        assertThat(calls).containsExactly("a.pause", "a.snapshot", "b.restore", "a.resume");
        assertThat(calls).doesNotContain("a.release");

        // And the source's lease was never touched -- it is still exactly what it was before this
        // attempt, which is what "retried later" actually requires.
        assertThat(fixture.leases().isValid(fixture.sourceLease())).isTrue();
    }

    @Test
    void aFailedSnapshotRollsBackWithoutTouchingTheTarget() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(A);
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                new RecordingOwner("a", calls).failing("snapshot", new IllegalStateException("state corrupt")),
                new RecordingOwner("b", calls),
                fixture.leases(),
                fixture.sourceLease(),
                null);

        assertThat(handoff.run(Duration.ofSeconds(5))).isEqualTo(PartitionHandoff.Stage.ROLLED_BACK);
        assertThat(calls).containsExactly("a.pause", "a.snapshot", "a.resume");
    }

    @Test
    void aSnapshotOfTheWrongPartitionIsRefused() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(A);
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                new RecordingOwner("a", calls)
                        .returningSnapshot(new PartitionSnapshot(9, 1, Map.of(), Map.of("agg", new byte[8]))),
                new RecordingOwner("b", calls),
                fixture.leases(),
                fixture.sourceLease(),
                null);

        // Restoring it would file partition 9's state under partition 42's name, and every key in
        // both would then be answered from the wrong state.
        assertThat(handoff.run(Duration.ofSeconds(5))).isEqualTo(PartitionHandoff.Stage.ROLLED_BACK);
        assertThat(calls).doesNotContain("b.restore");
    }

    @Test
    void failureAfterOwnershipMovedIsReportedAsUnrecoverableNotRetried() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(A);
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                new RecordingOwner("a", calls),
                new RecordingOwner("b", calls).failing("resume", new IllegalStateException("out of memory")),
                fixture.leases(),
                fixture.sourceLease(),
                null);

        // Past this point there is no going back: the lease coordinator says b owns it, and a may
        // already have released. Handing it back would be the double-ownership this design exists
        // to avoid.
        assertThatThrownBy(() -> handoff.run(Duration.ofSeconds(5)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9006")
                .hasMessageContaining("recovered from its checkpoint")
                .hasMessageContaining("must not be handed back");
        assertThat(handoff.stage()).isEqualTo(PartitionHandoff.Stage.FAILED);
        assertThat(handoff.targetLease())
                .as("b's lease was genuinely granted before resume failed -- the assignment's own "
                        + "authority already says b owns it, which is exactly why this cannot roll back")
                .isPresent();
    }

    @Test
    void aPartitionThatCannotBeResumedAfterRollbackIsLoud() {
        List<String> calls = new ArrayList<>();
        Fixture fixture = fixture(A);
        RecordingOwner source = new RecordingOwner("a", calls);
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                source,
                new RecordingOwner("b", calls).failing("restore", new IllegalStateException("disk full")),
                fixture.leases(),
                fixture.sourceLease(),
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
        Fixture fixture = fixture(A);
        PartitionHandoff handoff = new PartitionHandoff(
                MOVE,
                new RecordingOwner("a", calls),
                new RecordingOwner("b", calls),
                fixture.leases(),
                fixture.sourceLease(),
                logged::add);

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

        Fixture fixture = fixture(A);
        new PartitionHandoff(
                        MOVE, new RecordingOwner("a", calls), target, fixture.leases(), fixture.sourceLease(), null)
                .run(Duration.ofSeconds(5));

        // State and position are one fact: the state is the result of consuming up to here. A
        // snapshot without offsets leaves the target guessing, and either guess is wrong.
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).offsets()).containsEntry("trades", "offset-500");
        assertThat(restored.get(0).partition()).isEqualTo(42);
    }

    // --- Construction-time refusals -------------------------------------------------------------

    @Test
    void aSourceLeaseNamingTheWrongPartitionIsRefusedAtConstruction() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease wrongPartition = leases.acquire(99, A).orElseThrow();

        assertThatThrownBy(() -> new PartitionHandoff(
                        MOVE,
                        new RecordingOwner("a", new ArrayList<>()),
                        new RecordingOwner("b", new ArrayList<>()),
                        leases,
                        wrongPartition,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names partition 99")
                .hasMessageContaining("this move is for partition 42");
    }

    @Test
    void aSourceLeaseHeldByTheWrongMemberIsRefusedAtConstruction() {
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        // B holds it, but the move says A is the source -- an impossible premise this constructor
        // catches rather than one that would have quietly paused a's partition based on a lease b
        // actually holds.
        PartitionLease heldByB = leases.acquire(MOVE.partition(), B).orElseThrow();

        assertThatThrownBy(() -> new PartitionHandoff(
                        MOVE,
                        new RecordingOwner("a", new ArrayList<>()),
                        new RecordingOwner("b", new ArrayList<>()),
                        leases,
                        heldByB,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is held by b")
                .hasMessageContaining("this move's source is a");
    }

    @Test
    void aStaleSourceLeaseIsRefusedBeforeAnythingPauses() {
        List<String> calls = new ArrayList<>();
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease lease = leases.acquire(MOVE.partition(), A).orElseThrow();
        leases.release(lease); // a's own lease is now stale, exactly as if its session had ended

        PartitionHandoff handoff = new PartitionHandoff(
                MOVE, new RecordingOwner("a", calls), new RecordingOwner("b", calls), leases, lease, null);

        assertThatThrownBy(() -> handoff.run(Duration.ofSeconds(5)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9006")
                .hasMessageContaining("is no longer valid");
        // Refused before the source was ever asked to pause -- there is nothing to roll back
        // because nothing was ever paused on a premise that was already false.
        assertThat(calls).isEmpty();
        assertThat(handoff.stage()).isEqualTo(PartitionHandoff.Stage.FAILED);
    }

    // --- The property this slice exists for: two handoffs that genuinely disagree --------------

    @Test
    void twoHandoffsBuiltFromTheSameSourceLeaseOnlyTheFirstToTransferSucceeds() {
        // Two different PartitionHandoff instances, both built while a genuinely still holds
        // partition 42, aimed at two different targets -- b and c -- the way a rebalance racing a
        // manual retry, or two control loops that both computed a move for the same partition,
        // might. Run sequentially here so the sequence of events is legible; the threaded case
        // below proves the same property holds when nothing serialises them at all.
        Member c = new Member("c", "host-c", 9072);
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease aOwnsIt = leases.acquire(MOVE.partition(), A).orElseThrow();
        PartitionAssignment.PartitionMove toC = new PartitionAssignment.PartitionMove(42, A, c);

        List<String> callsToB = new ArrayList<>();
        List<String> callsToC = new ArrayList<>();
        PartitionHandoff toBHandoff = new PartitionHandoff(
                MOVE, new RecordingOwner("a", callsToB), new RecordingOwner("b", callsToB), leases, aOwnsIt, null);
        PartitionHandoff toCHandoff = new PartitionHandoff(
                toC, new RecordingOwner("a", callsToC), new RecordingOwner("c", callsToC), leases, aOwnsIt, null);

        // The first to actually call run() wins the transfer -- not the order the two
        // PartitionHandoff objects were constructed in, which was identical for both.
        assertThat(toBHandoff.run(Duration.ofSeconds(5))).isEqualTo(PartitionHandoff.Stage.COMPLETE);
        assertThat(callsToB).containsExactly("a.pause", "a.snapshot", "b.restore", "b.resume", "a.release");

        // The second, built from the exact same aOwnsIt, discovers at its own pre-check that the
        // premise it was built on is gone -- transfer() already consumed it -- and is refused
        // before it ever touches the source, rather than pausing a partition on a false premise.
        assertThatThrownBy(() -> toCHandoff.run(Duration.ofSeconds(5)))
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-9006")
                .hasMessageContaining("is no longer valid");
        assertThat(callsToC)
                .as("never even paused -- refused before anything moved")
                .isEmpty();
        assertThat(toCHandoff.stage()).isEqualTo(PartitionHandoff.Stage.FAILED);

        // b, and only b, genuinely owns it now.
        assertThat(toBHandoff.targetLease()).isPresent();
        assertThat(toBHandoff.targetLease().orElseThrow().owner()).isEqualTo(B);
    }

    /** Runs a handoff and returns the stage it reached either way -- COMPLETE, ROLLED_BACK, or, if it
     *  was refused before anything even paused (its premise had already gone stale by the time it
     *  looked), FAILED via the thrown exception's own reporting. All three are safe outcomes for a
     *  loser; only COMPLETE for two different attempts on one partition would be a real defect. */
    private static PartitionHandoff.Stage runToCompletion(PartitionHandoff handoff) {
        try {
            return handoff.run(Duration.ofSeconds(5));
        } catch (PravahaException refused) {
            return handoff.stage();
        }
    }

    @Test
    void twoConcurrentHandoffsForTheSamePartitionOnlyOneEverCompletesUnderRealThreads() throws Exception {
        // Not simulated sequentially like the case above: two real threads, each running a complete
        // PartitionHandoff for the same partition to two different targets, started as close
        // together as this JVM can manage. If fencing were merely local sequencing rather than a
        // real compare-and-swap on the lease coordinator, a scheduling accident could let both
        // believe they won. The two source-side RecordingOwners (sourceForB, sourceForC) are two
        // separate test doubles rather than the one real node "a" would be; what they let this test
        // observe is per-attempt call order, and the property under test -- the lease
        // coordinator, not local sequencing, is what decides the winner -- does not depend on that
        // simplification.
        Member c = new Member("c", "host-c", 9072);
        InMemoryPartitionLeaseCoordinator leases = new InMemoryPartitionLeaseCoordinator();
        PartitionLease aOwnsIt = leases.acquire(MOVE.partition(), A).orElseThrow();

        List<String> callsToB = new ArrayList<>();
        List<String> callsToC = new ArrayList<>();
        PartitionAssignment.PartitionMove toB = MOVE;
        PartitionAssignment.PartitionMove toC = new PartitionAssignment.PartitionMove(42, A, c);

        java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(2);

        RecordingOwner sourceForB = new RecordingOwner("a", callsToB);
        RecordingOwner sourceForC = new RecordingOwner("a", callsToC);
        RecordingOwner targetB = new RecordingOwner("b", callsToB);
        RecordingOwner targetC = new RecordingOwner("c", callsToC);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<PartitionHandoff.Stage> toBFuture = pool.submit(() -> {
                gate.await();
                return runToCompletion(new PartitionHandoff(toB, sourceForB, targetB, leases, aOwnsIt, null));
            });
            java.util.concurrent.Future<PartitionHandoff.Stage> toCFuture = pool.submit(() -> {
                gate.await();
                return runToCompletion(new PartitionHandoff(toC, sourceForC, targetC, leases, aOwnsIt, null));
            });

            PartitionHandoff.Stage stageB = toBFuture.get();
            PartitionHandoff.Stage stageC = toCFuture.get();

            // Exactly one wins, and the loser fails safely -- either it rolled back (it reached the
            // lease-acquire step and lost) or it was refused outright (the winner had already
            // finished, including releasing a's shared lease, before the loser even checked it was
            // still valid). Either loss is safe; two wins would not be.
            List<PartitionHandoff.Stage> outcomes = List.of(stageB, stageC);
            assertThat(outcomes).contains(PartitionHandoff.Stage.COMPLETE);
            assertThat(outcomes)
                    .as("the loser rolled back or was refused outright -- either way, not COMPLETE")
                    .anyMatch(stage ->
                            stage == PartitionHandoff.Stage.ROLLED_BACK || stage == PartitionHandoff.Stage.FAILED);
            assertThat(outcomes.stream()
                            .filter(s -> s == PartitionHandoff.Stage.COMPLETE)
                            .count())
                    .as("never both -- that would be the split-brain this whole slice exists to prevent")
                    .isEqualTo(1);

            boolean bResumed = callsToB.contains("b.resume");
            boolean cResumed = callsToC.contains("c.resume");
            assertThat(bResumed ^ cResumed)
                    .as("exactly one target ever actually resumed serving")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
