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
package com.ash.messaging.pravaha.runtime.window;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Session windows.
 *
 * <p>{@link #aRecordArrivingLateBridgesTwoSessionsIntoOne} is the case that separates a correct
 * implementation from one that looks correct. Records arrive out of order, so a record landing
 * between two existing sessions does not join one of them -- it joins them to each other. An
 * implementation that assigns to the nearest session produces two sessions where there is one, with
 * no error, no exception, and no way to notice from the outside.
 */
class SessionWindowsTest {

    private static final long MINUTE = 60_000_000_000L;
    private static final long GAP = 30 * MINUTE;

    @Test
    void consecutiveRecordsWithinTheGapAreOneSession() {
        SessionWindows sessions = new SessionWindows(GAP);
        sessions.record(1L, 0);
        sessions.record(1L, 10 * MINUTE);
        sessions.record(1L, 20 * MINUTE);

        assertThat(sessions.sessionsOf(1L)).singleElement().satisfies(session -> {
            assertThat(session.startNanos()).isZero();
            assertThat(session.endNanos()).isEqualTo(50 * MINUTE);
        });
    }

    @Test
    void aGapLongerThanTheTimeoutStartsANewSession() {
        SessionWindows sessions = new SessionWindows(GAP);
        sessions.record(1L, 0);
        sessions.record(1L, 100 * MINUTE);

        assertThat(sessions.sessionsOf(1L)).hasSize(2);
        assertThat(sessions.openSessions()).isEqualTo(2);
    }

    @Test
    void aRecordArrivingLateBridgesTwoSessionsIntoOne() {
        // A user idle either side of a single click. The click arrives out of order, after both
        // neighbouring sessions already exist, and it does not join the nearer one -- it makes them
        // one session. An implementation that picks the nearest produces two, and nothing anywhere
        // says so.
        SessionWindows sessions = new SessionWindows(GAP);
        sessions.record(7L, 0);
        sessions.record(7L, 50 * MINUTE);
        assertThat(sessions.sessionsOf(7L)).as("two sessions, 20 minutes apart").hasSize(2);

        sessions.record(7L, 25 * MINUTE);

        assertThat(sessions.sessionsOf(7L))
                .as("the bridging record merges them")
                .singleElement()
                .satisfies(session -> {
                    assertThat(session.startNanos()).isZero();
                    assertThat(session.endNanos()).isEqualTo(80 * MINUTE);
                });
        assertThat(sessions.merges()).isPositive();
    }

    @Test
    void mergingIsBoundedToOneSessionOnEachSide() {
        // Not a limitation -- a consequence. Existing sessions are maximal, so consecutive starts
        // are at least one gap apart, and a record reaches exactly one gap forward: at most one
        // existing session can start within its reach, and absorbing that one leaves the next
        // beginning after the absorbed session's end.
        //
        // This test previously claimed a record could bridge three sessions and demonstrated two
        // records bridging one each. Seeding "merge forward only once" passed it, which is how the
        // over-claim was found; the loop it was defending could never run twice.
        SessionWindows sessions = new SessionWindows(10 * MINUTE);
        sessions.record(1L, 0);
        sessions.record(1L, 15 * MINUTE);
        sessions.record(1L, 30 * MINUTE);
        assertThat(sessions.sessionsOf(1L)).hasSize(3);

        sessions.record(1L, 8 * MINUTE);
        assertThat(sessions.sessionsOf(1L))
                .as("one record joins the session before it to the session after it, and no further")
                .hasSize(2);

        sessions.record(1L, 22 * MINUTE);
        assertThat(sessions.sessionsOf(1L)).singleElement().satisfies(session -> {
            assertThat(session.startNanos()).isZero();
            assertThat(session.endNanos()).isEqualTo(40 * MINUTE);
        });
    }

    @Test
    void sessionsThatMerelyTouchAreMerged() {
        // Half-open intervals: a session ending exactly where the next begins has a gap of zero
        // between them, and zero is not longer than the gap.
        //
        // Both directions, because they are separate branches. The first version tested only the
        // backward one -- a record extending an existing session it touches -- and seeding a strict
        // inequality into the forward branch passed, since nothing reached it.
        SessionWindows backwards = new SessionWindows(10 * MINUTE);
        backwards.record(1L, 0);
        backwards.record(1L, 10 * MINUTE);
        assertThat(backwards.sessionsOf(1L))
                .singleElement()
                .satisfies(session -> assertThat(session.endNanos()).isEqualTo(20 * MINUTE));

        // Forward: an existing session begins exactly where the new record's reach ends.
        SessionWindows forwards = new SessionWindows(10 * MINUTE);
        forwards.record(1L, 20 * MINUTE);
        forwards.record(1L, 10 * MINUTE);
        assertThat(forwards.sessionsOf(1L))
                .as("the new record reaches exactly to the existing session's start, so they are one")
                .singleElement()
                .satisfies(session -> {
                    assertThat(session.startNanos()).isEqualTo(10 * MINUTE);
                    assertThat(session.endNanos()).isEqualTo(30 * MINUTE);
                });
    }

    @Test
    void keysAreIndependent() {
        SessionWindows sessions = new SessionWindows(GAP);
        sessions.record(1L, 0);
        sessions.record(2L, 0);
        sessions.record(1L, 10 * MINUTE);

        assertThat(sessions.sessionsOf(1L))
                .singleElement()
                .satisfies(s -> assertThat(s.endNanos()).isEqualTo(40 * MINUTE));
        assertThat(sessions.sessionsOf(2L))
                .singleElement()
                .satisfies(s -> assertThat(s.endNanos()).isEqualTo(30 * MINUTE));
        assertThat(sessions.keyCount()).isEqualTo(2);
    }

    @Test
    void aSessionClosesWhenNoRecordCanExtendItAndIsThenForgotten() {
        // Closing is exactly the statement "no further record can extend this". Removing on close is
        // what bounds state: a key costs its open sessions, not its history.
        SessionWindows sessions = new SessionWindows(GAP);
        sessions.record(1L, 0);
        sessions.record(1L, 100 * MINUTE);

        assertThat(sessions.closedBy(20 * MINUTE)).as("nothing has closed yet").isEmpty();

        List<SessionWindows.Session> closed = sessions.closedBy(40 * MINUTE);
        assertThat(closed).singleElement().satisfies(session -> {
            assertThat(session.startNanos()).isZero();
            assertThat(session.endNanos()).isEqualTo(30 * MINUTE);
        });
        assertThat(sessions.openSessions())
                .as("the closed session is gone, not retained")
                .isEqualTo(1);
        assertThat(sessions.closedBy(40 * MINUTE))
                .as("and does not close twice")
                .isEmpty();
    }

    @Test
    void closedSessionsComeOutInEndOrder() {
        SessionWindows sessions = new SessionWindows(10 * MINUTE);
        sessions.record(2L, 30 * MINUTE);
        sessions.record(1L, 0);
        sessions.record(3L, 15 * MINUTE);

        assertThat(sessions.closedBy(100 * MINUTE))
                .extracting(SessionWindows.Session::key)
                .containsExactly(1L, 3L, 2L);
    }

    @Test
    void theOrderRecordsArriveInDoesNotChangeTheSessions() {
        // The property that matters under replay and reordering: the same records in any order must
        // produce the same sessions. Anything else means a replay disagrees with the original run,
        // which would make the time-travel debugger a liar.
        long[] times = {0, 5 * MINUTE, 40 * MINUTE, 12 * MINUTE, 100 * MINUTE, 55 * MINUTE, 70 * MINUTE};

        SessionWindows inOrder = new SessionWindows(20 * MINUTE);
        for (long time : java.util.Arrays.stream(times).sorted().toArray()) {
            inOrder.record(1L, time);
        }
        List<SessionWindows.Session> expected = inOrder.sessionsOf(1L);

        Random random = new Random(20260910L);
        for (int trial = 0; trial < 200; trial++) {
            List<Long> shuffled = new java.util.ArrayList<>();
            for (long time : times) {
                shuffled.add(time);
            }
            java.util.Collections.shuffle(shuffled, random);

            SessionWindows shuffledSessions = new SessionWindows(20 * MINUTE);
            shuffled.forEach(time -> shuffledSessions.record(1L, time));

            assertThat(shuffledSessions.sessionsOf(1L))
                    .as("arrival order %s produced different sessions", shuffled)
                    .isEqualTo(expected);
        }
    }

    @Test
    void aGapOfZeroIsRefused() {
        assertThatThrownBy(() -> new SessionWindows(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }
}
