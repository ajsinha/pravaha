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
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KEYEDWT-1 at the sink: {@link ViewSink#onAnswer} hands each commit as the rows that left the
 * answer and the rows that entered it, where {@link ViewSink#onCommit} -- a sink's changelog --
 * hands what was applied. The two differ exactly where summing the changelog drifted from the view:
 * an upsert, and a row retention evicts.
 */
class ViewSinkAnswerTest {

    private static final long SECOND = 1_000_000_000L;

    private static final StreamSchema SCHEMA = StreamSchema.builder("feed")
            .field("event_id", Types.int64())
            .field("payload", Types.string())
            .build();

    @Test
    void anUpsertAndAnEvictionReachTheAnswerButNotTheChangelog() throws Exception {
        ServedView view = new ServedView("feed", SCHEMA, List.of(0), 10_000, Retention.ofAge(Duration.ofSeconds(10)));
        ViewSink sink = new ViewSink(view, SCHEMA);
        List<ViewChange> answer = new CopyOnWriteArrayList<>();
        List<ViewChange> changelog = new CopyOnWriteArrayList<>();
        try (AutoCloseable a = sink.onAnswer((changes, frontier) -> answer.addAll(changes));
                AutoCloseable c = sink.onCommit((changes, frontier) -> changelog.addAll(changes))) {
            sink.begin().setLong(0, 1).setString(1, "one").weight(1).sequence(0).commit();
            sink.commit(0);
            sink.begin()
                    .setLong(0, 1)
                    .setString(1, "uno")
                    .weight(1)
                    .sequence(SECOND)
                    .commit();
            sink.commit(SECOND);
            // Thirty seconds on: event 1 is past the ten-second retention and leaves the view.
            sink.begin()
                    .setLong(0, 3)
                    .setString(1, "three")
                    .weight(1)
                    .sequence(30 * SECOND)
                    .commit();
            sink.commit(30 * SECOND);
        }

        assertThat(changelog)
                .containsExactly(
                        new ViewChange(new Object[] {1L, "one"}, 1),
                        new ViewChange(new Object[] {1L, "uno"}, 1),
                        new ViewChange(new Object[] {3L, "three"}, 1));
        assertThat(answer)
                .containsExactly(
                        new ViewChange(new Object[] {1L, "one"}, 1),
                        new ViewChange(new Object[] {1L, "one"}, -1),
                        new ViewChange(new Object[] {1L, "uno"}, 1),
                        new ViewChange(new Object[] {1L, "uno"}, -1),
                        new ViewChange(new Object[] {3L, "three"}, 1));
        assertThat(view.scan()).hasSize(1);
    }

    @Test
    void aSnapshotFollowerStartsFromTheShownRowsAndDetachesCleanly() throws Exception {
        ServedView view = new ServedView("feed", SCHEMA, List.of(0), 10_000);
        ViewSink sink = new ViewSink(view, SCHEMA);
        sink.begin().setLong(0, 1).setString(1, "one").weight(1).sequence(1).commit();
        sink.begin().setLong(0, 1).setString(1, "uno").weight(1).sequence(2).commit();
        sink.commit(2);

        List<ViewChange> snapshot = new CopyOnWriteArrayList<>();
        AutoCloseable handle = sink.onAnswerFromSnapshot(new ViewChangeListener() {
            @Override
            public void onSnapshot(List<ViewChange> rows, long frontier) {
                snapshot.addAll(rows);
            }

            @Override
            public void onCommit(List<ViewChange> changes, long frontier) {}
        });
        assertThat(snapshot).containsExactly(new ViewChange(new Object[] {1L, "uno"}, 1));
        assertThat(sink.hasListeners()).isTrue();
        assertThat(sink.listenerCount()).isEqualTo(1);
        handle.close();
        assertThat(sink.hasListeners()).isFalse();
    }
}
