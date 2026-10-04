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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ash.messaging.pravaha.api.data.RowWriter;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;
import com.ash.messaging.pravaha.runtime.exec.RowOutput;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A subscription that starts from the view's state and misses nothing after it (SUB-1).
 *
 * <p>The loss this exists to rule out: a commit's audience is fixed when its first batch is applied
 * (STRM-11), so a subscriber attaching while a commit is in flight is not told about it, and the
 * committed state it reads beside the subscription does not contain it either. The first test holds
 * that moment open and shows the plain subscription losing the commit; the second shows the snapshot
 * handoff keeping it.
 */
@Timeout(60)
class SnapshotHandoffTest {

    static final StreamSchema SCHEMA = StreamSchema.builder("balances")
            .field("account", Types.string())
            .field("amount", Types.int64())
            .build();

    static ServedView view() {
        return new ServedView("balances", SCHEMA, List.of(0), 100_000);
    }

    static void write(RowWriter writer, String account, long amount, long weight, long position) {
        writer.setString(0, account)
                .setLong(1, amount)
                .weight(weight)
                .sequence(position)
                .commit();
    }

    @Test
    void theCommitInFlightReachesNeitherAPlainSubscriptionNorTheReadBesideIt() throws Exception {
        // What SUB-1 recorded, kept as the statement of why a plain subscription is gapful: the
        // rows are applied, the subscriber attaches, the read happens, then the commit.
        ServedView view = view();
        ViewSink sink = new ViewSink(view, SCHEMA);
        write(sink.begin(), "a", 10, 1, 1);

        List<ViewChange> heard = new ArrayList<>();
        try (AutoCloseable _ = sink.onCommit((changes, frontier) -> heard.addAll(changes))) {
            List<Object[]> read = view.scan();
            sink.commitApplied();

            assertThat(read)
                    .as("the read was of the committed state, before the rows")
                    .isEmpty();
            assertThat(heard)
                    .as("and the commit was staged for an audience fixed before")
                    .isEmpty();
            assertThat(view.scan()).as("while the view has the row").hasSize(1);
        }
    }

    @Test
    void aSnapshotSubscriptionAttachedMidCommitIsHandedTheViewThatCommitProduced() throws Exception {
        ServedView view = view();
        ViewSink sink = new ViewSink(view, SCHEMA);
        write(sink.begin(), "a", 10, 1, 1);
        sink.commitApplied();
        write(sink.begin(), "b", 20, 1, 2);

        Recorder recorder = new Recorder();
        try (AutoCloseable _ = sink.onCommitFromSnapshot(recorder)) {
            assertThat(recorder.events)
                    .as("a commit is in flight, so the snapshot waits for its end")
                    .isEmpty();
            assertThat(sink.awaitingSnapshot()).isTrue();
            assertThat(sink.listenerCount())
                    .as("a waiting subscriber still counts")
                    .isEqualTo(1);

            sink.commitApplied();
            assertThat(sink.awaitingSnapshot()).isFalse();
            write(sink.begin(), "a", 10, -1, 3);
            write(sink.begin(), "a", 15, 1, 3);
            sink.commitApplied();
        }

        assertThat(recorder.events).extracting(Event::snapshot).containsExactly(true, false);
        assertThat(recorder.events.get(0).frontier()).isEqualTo(2L);
        assertThat(recorder.mirror()).isEqualTo(zset(view.committedRows()));
        assertThat(recorder.mirror()).containsOnlyKeys(List.of("a", 15L), List.of("b", 20L));
    }

    @Test
    void withNoCommitInFlightTheSnapshotIsTakenAtOnceAndTheNextCommitIsDeliveredWhole() throws Exception {
        ServedView view = view();
        ViewSink sink = new ViewSink(view, SCHEMA);
        write(sink.begin(), "a", 10, 1, 1);
        sink.commitApplied();

        Recorder recorder = new Recorder();
        try (AutoCloseable _ = sink.onCommitFromSnapshot(recorder)) {
            assertThat(recorder.events).hasSize(1);
            assertThat(recorder.events.get(0).snapshot()).isTrue();
            assertThat(recorder.events.get(0).frontier()).isEqualTo(1L);

            RowOutput lane = sink.laneOutput();
            write(lane.begin(), "a", 10, -1, 2);
            write(lane.begin(), "a", 11, 1, 2);
            lane.endOfBatch();
            sink.commitApplied();
        }

        assertThat(recorder.events).hasSize(2);
        assertThat(recorder.events.get(1).changes()).hasSize(2);
        assertThat(recorder.mirror()).isEqualTo(zset(view.committedRows()));
    }

    @Test
    void anEmptyViewStillSendsASnapshotAndARowPresentTwiceArrivesWithItsWeight() throws Exception {
        ServedView view = view();
        ViewSink sink = new ViewSink(view, SCHEMA);
        Recorder empty = new Recorder();
        try (AutoCloseable _ = sink.onCommitFromSnapshot(empty)) {
            assertThat(empty.events).singleElement().satisfies(event -> {
                assertThat(event.snapshot()).isTrue();
                assertThat(event.changes()).isEmpty();
            });
        }

        write(sink.begin(), "a", 10, 1, 1);
        write(sink.begin(), "a", 10, 1, 2);
        sink.commitApplied();
        Recorder twice = new Recorder();
        try (AutoCloseable _ = sink.onCommitFromSnapshot(twice)) {
            write(sink.begin(), "a", 10, -1, 3);
            sink.commitApplied();
        }
        assertThat(twice.events.get(0).changes()).containsExactly(new ViewChange(new Object[] {"a", 10L}, 2));
        assertThat(view.scan())
                .as("one retraction of a row present twice leaves it")
                .hasSize(1);
        assertThat(twice.mirror()).isEqualTo(zset(view.committedRows()));
    }

    @Test
    void closedBeforeItsSnapshotItHearsNothingAndIsNoLongerCounted() throws Exception {
        ServedView view = view();
        ViewSink sink = new ViewSink(view, SCHEMA);
        write(sink.begin(), "a", 10, 1, 1);
        Recorder recorder = new Recorder();
        AutoCloseable handle = sink.onCommitFromSnapshot(recorder);
        handle.close();
        assertThat(sink.listenerCount()).isZero();

        sink.commitApplied();
        write(sink.begin(), "b", 1, 1, 2);
        sink.commitApplied();

        assertThat(recorder.events).isEmpty();
    }

    @Test
    void aListenerThatKnowsNothingOfSnapshotsIsHandedTheRowsAsItsFirstBatch() throws Exception {
        ServedView view = view();
        ViewSink sink = new ViewSink(view, SCHEMA);
        write(sink.begin(), "a", 10, 1, 1);
        sink.commitApplied();

        List<List<ViewChange>> batches = new ArrayList<>();
        try (AutoCloseable _ = sink.onCommitFromSnapshot((changes, frontier) -> batches.add(changes))) {
            write(sink.begin(), "b", 20, 1, 2);
            sink.commitApplied();
        }
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).containsExactly(new ViewChange(new Object[] {"a", 10L}, 1));
    }

    /**
     * Subscribers attaching at arbitrary moments while a lane applies and two threads commit, as the
     * feed's timer and a reader do: every subscriber's snapshot plus its changes is the view.
     */
    @Test
    void everySubscriberAttachingDuringConcurrentCommitsEndsWithTheView() throws Exception {
        for (int round = 0; round < 20; round++) {
            ServedView view = view();
            ViewSink sink = new ViewSink(view, SCHEMA);
            AtomicBoolean stop = new AtomicBoolean();
            RowOutput lane = sink.laneOutput();

            Thread laneThread = Thread.ofPlatform().daemon().start(() -> {
                Map<String, Long> state = new HashMap<>();
                long position = 0;
                ThreadLocalRandom random = ThreadLocalRandom.current();
                while (!stop.get()) {
                    int rows = 1 + random.nextInt(4);
                    position++;
                    for (int i = 0; i < rows; i++) {
                        String account = "k" + random.nextInt(40);
                        Long old = state.get(account);
                        if (old != null) {
                            write(lane.begin(), account, old, -1, position);
                        }
                        if (old == null || random.nextInt(4) > 0) {
                            long amount = random.nextLong(1_000);
                            write(lane.begin(), account, amount, 1, position);
                            state.put(account, amount);
                        } else {
                            state.remove(account);
                        }
                    }
                    lane.endOfBatch();
                }
            });
            Runnable committing = () -> {
                while (!stop.get()) {
                    sink.commitApplied();
                    Thread.onSpinWait();
                }
            };
            Thread timer = Thread.ofPlatform().daemon().start(committing);
            Thread reader = Thread.ofPlatform().daemon().start(committing);

            List<Recorder> recorders = new CopyOnWriteArrayList<>();
            List<AutoCloseable> handles = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                Thread.sleep(ThreadLocalRandom.current().nextInt(3));
                Recorder recorder = new Recorder();
                recorders.add(recorder);
                handles.add(sink.onCommitFromSnapshot(recorder));
            }
            Thread.sleep(5);
            stop.set(true);
            laneThread.join();
            timer.join();
            reader.join();
            sink.commitApplied();

            Map<List<Object>, Long> expected = zset(view.committedRows());
            for (Recorder recorder : recorders) {
                assertThat(recorder.events)
                        .as("round %d: a snapshot, first and once", round)
                        .isNotEmpty();
                assertThat(recorder.events.get(0).snapshot()).isTrue();
                assertThat(recorder.events.stream().filter(Event::snapshot)).hasSize(1);
                assertThat(recorder.mirror())
                        .as("round %d: snapshot plus changes is the view", round)
                        .isEqualTo(expected);
            }
            for (AutoCloseable handle : handles) {
                handle.close();
            }
        }
    }

    /** One delivery: the snapshot, or one commit's changes. */
    record Event(boolean snapshot, List<ViewChange> changes, long frontier) {}

    /** A listener that keeps everything it is told, and the Z-set it adds up to. */
    static final class Recorder implements ViewChangeListener {

        final List<Event> events = new CopyOnWriteArrayList<>();

        @Override
        public void onSnapshot(List<ViewChange> rows, long frontier) {
            events.add(new Event(true, List.copyOf(rows), frontier));
        }

        @Override
        public void onCommit(List<ViewChange> changes, long frontier) {
            events.add(new Event(false, List.copyOf(changes), frontier));
        }

        Map<List<Object>, Long> mirror() {
            List<ViewChange> all = new ArrayList<>();
            events.forEach(event -> all.addAll(event.changes()));
            return zset(all);
        }
    }

    /** Rows to their summed weight, zeros dropped. */
    static Map<List<Object>, Long> zset(List<ViewChange> changes) {
        Map<List<Object>, Long> sums = new HashMap<>();
        for (ViewChange change : changes) {
            sums.merge(Arrays.asList(change.values()), change.weight(), Long::sum);
        }
        sums.values().removeIf(weight -> weight == 0);
        return sums;
    }
}
