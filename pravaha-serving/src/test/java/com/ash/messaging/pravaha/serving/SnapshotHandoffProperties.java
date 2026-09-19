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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import com.ash.messaging.pravaha.runtime.exec.RowOutput;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The handoff, over every order of batches, commits and subscribers jqwik can think of (SUB-1).
 *
 * <p>A schedule is a list of steps run in order: a lane batch of well-formed changes (an update is
 * a retraction and an insert; a key may be present twice), a single row applied on its own, a
 * commit, a subscriber attaching, a subscriber leaving. A subscriber attaching between an applied
 * batch and its commit is the case the old wiring lost; a schedule reaches it, and its neighbours,
 * without anybody having to think of them. After a final commit every subscriber still attached
 * must hold exactly the view: its snapshot, then its changes, summed as a Z-set.
 */
class SnapshotHandoffProperties {

    enum Kind {
        BATCH,
        ROW,
        COMMIT,
        SUBSCRIBE,
        LEAVE
    }

    record Step(Kind kind, int key, int value, int size) {}

    @Provide
    Arbitrary<List<Step>> schedules() {
        Arbitrary<Step> step = Combinators.combine(
                        Arbitraries.of(Kind.class),
                        Arbitraries.integers().between(0, 5),
                        Arbitraries.integers().between(0, 3),
                        Arbitraries.integers().between(1, 4))
                .as(Step::new);
        return step.list().ofMinSize(1).ofMaxSize(60);
    }

    @Property(tries = 1000)
    void everySubscriberEndsHoldingTheView(@ForAll("schedules") List<Step> schedule) throws Exception {
        ServedView view = SnapshotHandoffTest.view();
        ViewSink sink = new ViewSink(view, SnapshotHandoffTest.SCHEMA);
        RowOutput lane = sink.laneOutput();
        // The applied state, key to (amount, multiplicity), so every change written is one a keyed
        // view can take: a retraction only of what is there.
        Map<String, long[]> state = new HashMap<>();
        List<SnapshotHandoffTest.Recorder> attached = new ArrayList<>();
        List<AutoCloseable> handles = new ArrayList<>();
        long position = 0;

        for (Step step : schedule) {
            switch (step.kind()) {
                case BATCH -> {
                    position++;
                    for (int i = 0; i < step.size(); i++) {
                        change(lane, state, "k" + ((step.key() + i) % 6), step.value() + i, position);
                    }
                    lane.endOfBatch();
                }
                case ROW -> {
                    position++;
                    RowOutput single = sink::begin;
                    change(single, state, "k" + step.key(), step.value(), position);
                }
                case COMMIT -> sink.commitApplied();
                case SUBSCRIBE -> {
                    SnapshotHandoffTest.Recorder recorder = new SnapshotHandoffTest.Recorder();
                    attached.add(recorder);
                    handles.add(sink.onCommitFromSnapshot(recorder));
                }
                case LEAVE -> {
                    if (!attached.isEmpty()) {
                        int at = step.key() % attached.size();
                        attached.remove(at);
                        handles.remove(at).close();
                    }
                }
            }
        }
        sink.commitApplied();

        Map<List<Object>, Long> expected = SnapshotHandoffTest.zset(view.committedRows());
        for (SnapshotHandoffTest.Recorder recorder : attached) {
            assertThat(recorder.events).isNotEmpty();
            assertThat(recorder.events.get(0).snapshot()).isTrue();
            assertThat(recorder.events.stream().filter(SnapshotHandoffTest.Event::snapshot))
                    .hasSize(1);
            assertThat(recorder.mirror()).isEqualTo(expected);
        }
        for (AutoCloseable handle : handles) {
            handle.close();
        }
    }

    /**
     * One well-formed change to {@code account}: an insert when absent; otherwise, by {@code value},
     * an update, a delete, or the same row once more.
     */
    private static void change(RowOutput out, Map<String, long[]> state, String account, int value, long position) {
        long[] present = state.get(account);
        if (present == null) {
            SnapshotHandoffTest.write(out.begin(), account, value, 1, position);
            state.put(account, new long[] {value, 1});
            return;
        }
        switch (value % 3) {
            case 0 -> {
                SnapshotHandoffTest.write(out.begin(), account, present[0], -present[1], position);
                SnapshotHandoffTest.write(out.begin(), account, present[0] + 100, 1, position);
                state.put(account, new long[] {present[0] + 100, 1});
            }
            case 1 -> {
                SnapshotHandoffTest.write(out.begin(), account, present[0], -1, position);
                if (present[1] == 1) {
                    state.remove(account);
                } else {
                    present[1]--;
                }
            }
            default -> {
                SnapshotHandoffTest.write(out.begin(), account, present[0], 1, position);
                present[1]++;
            }
        }
    }
}
