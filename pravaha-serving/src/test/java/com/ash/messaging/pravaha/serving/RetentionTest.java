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

import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.api.data.StreamSchema;
import com.ash.messaging.pravaha.api.data.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A view forgetting rows it no longer needs.
 *
 * <p>Before this existed, a view over a query that does not aggregate grew with its feed until it
 * hit a ceiling and threw. "The node dies eventually, loudly" is not a design, and the error message
 * had been advising people to add a retention window that did not exist.
 */
class RetentionTest {

    private static final long SECOND = 1_000_000_000L;

    private static final StreamSchema SCHEMA = StreamSchema.builder("trade_feed")
            .field("event_id", Types.int64())
            .field("payload", Types.string())
            .build();

    private static ServedView view(Retention retention) {
        return new ServedView("trade_feed", SCHEMA, List.of(0), 10_000, retention);
    }

    private static void put(ServedView view, long id, long frontier) {
        view.applyValues(new Object[] {id, "payload-" + id}, 1, frontier);
        view.commit(frontier);
    }

    @Test
    void aViewWithNoRetentionChosenStillForgets() {
        // The default, not "forever". A view is bounded only if its key space is bounded, and
        // nothing can tell in advance whether it is -- so the safe default is the one that forgets.
        assertThat(Retention.DEFAULT.isForever()).isFalse();
        assertThat(Retention.DEFAULT.maxAge()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void rowsOlderThanTheWindowAreEvicted() {
        ServedView view = view(Retention.ofAge(Duration.ofSeconds(10)));

        put(view, 1, 0);
        put(view, 2, 5 * SECOND);
        put(view, 3, 30 * SECOND);

        // Events 1 and 2 are more than ten seconds behind the frontier.
        assertThat(view.size()).isEqualTo(1);
        assertThat(view.evicted()).isEqualTo(2);
        assertThat(view.get(3L).found()).isTrue();
        assertThat(view.get(1L).found()).isFalse();
    }

    @Test
    void ageIsMeasuredInEventTimeNotWallClock() {
        ServedView view = view(Retention.ofAge(Duration.ofSeconds(10)));

        put(view, 1, 1_000 * SECOND);
        put(view, 2, 1_005 * SECOND);

        // Both are recent *in the data*, however long ago the test ran them. A policy that consulted
        // the clock would make a replay of yesterday retain different rows than yesterday did.
        assertThat(view.size()).isEqualTo(2);
    }

    @Test
    void theRowBoundEvictsTheLeastRecentlyWrittenFirst() {
        ServedView view = view(Retention.ofRows(3));

        for (long id = 1; id <= 5; id++) {
            put(view, id, id * SECOND);
        }

        assertThat(view.size()).isEqualTo(3);
        assertThat(view.get(1L).found()).isFalse();
        assertThat(view.get(5L).found()).isTrue();
    }

    @Test
    void aKeyThatKeepsBeingUpdatedIsNotEvictedAsStale() {
        ServedView view = view(Retention.ofRows(2));

        put(view, 1, 1 * SECOND);
        put(view, 2, 2 * SECOND);
        put(view, 1, 3 * SECOND); // touched again
        put(view, 3, 4 * SECOND);

        // "Oldest" has to mean least recently *written*, not first inserted, or a hot key would be
        // evicted while stale ones survived.
        assertThat(view.get(1L).found()).isTrue();
        assertThat(view.get(3L).found()).isTrue();
        assertThat(view.get(2L).found()).isFalse();
    }

    @Test
    void aPassThroughFeedNoLongerGrowsWithoutLimit() {
        ServedView view = view(Retention.ofRows(100));

        // The case the whole thing exists for: one new key per event, forever.
        for (long id = 1; id <= 50_000; id++) {
            view.applyValues(new Object[] {id, "p"}, 1, id);
        }
        view.commit(50_000);

        assertThat(view.size()).isEqualTo(100);
        assertThat(view.evicted()).isEqualTo(49_900);
    }

    @Test
    void foreverStillMeansForeverWhenItIsAskedForByName() {
        ServedView view = view(Retention.forever());

        for (long id = 1; id <= 500; id++) {
            put(view, id, id * SECOND);
        }

        assertThat(view.size()).isEqualTo(500);
        assertThat(view.evicted()).isZero();
    }

    @Test
    void theCeilingStillCatchesAViewThatKeepsEverythingAndShouldNot() {
        ServedView small = new ServedView("trade_feed", SCHEMA, List.of(0), 10, Retention.forever());

        assertThatThrownBy(() -> {
                    for (long id = 1; id <= 50; id++) {
                        small.applyValues(new Object[] {id, "p"}, 1, id);
                    }
                    small.commit(50);
                })
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("PRV-4022")
                // The message now tells the truth about which of the two things went wrong.
                .hasMessageContaining("keeps everything");
    }

    @Test
    void aRetentionWindowWiderThanTheCeilingSaysWhichNumberIsWrong() {
        ServedView small = new ServedView("trade_feed", SCHEMA, List.of(0), 10, Retention.ofRows(1_000));

        assertThatThrownBy(() -> {
                    for (long id = 1; id <= 50; id++) {
                        small.applyValues(new Object[] {id, "p"}, 1, id);
                    }
                    small.commit(50);
                })
                .isInstanceOf(PravahaException.class)
                .hasMessageContaining("one of the two numbers is wrong");
    }

    @Test
    void evictionIsCountedSoAnOperatorCanSeeThePolicyWorking() {
        ServedView view = view(Retention.ofRows(2));
        for (long id = 1; id <= 10; id++) {
            put(view, id, id * SECOND);
        }

        // Not an error count -- it is how somebody notices a window shorter than the questions
        // being asked of it.
        assertThat(view.evicted()).isEqualTo(8);
    }
}
