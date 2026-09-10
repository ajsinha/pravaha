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
package com.ash.messaging.pravaha.runtime.time;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-lane timers.
 *
 * <p>Three properties, each of which fails in a different way. Firing order wrong means a
 * downstream consumer applies an older window result after a newer one. Rescheduling that
 * accumulates means a session window extended per record leaks one timer per record -- a leak shaped
 * exactly like normal operation. And a watermark that jumps hours forward, which is what a backfill
 * catching up looks like, must not walk the wheel tick by tick to get there.
 */
@Timeout(60)
class TimerWheelTest {

    private static final long MILLI = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static TimerWheel wheel() {
        return new TimerWheel(100 * MILLI, 1024);
    }

    @Test
    void aTimerFiresWhenTheWatermarkReachesIt() {
        TimerWheel wheel = wheel();
        wheel.advanceTo(0);
        wheel.schedule(5 * SECOND, 42L, "window-1");

        assertThat(wheel.advanceTo(4 * SECOND)).as("not yet due").isEmpty();
        List<TimerWheel.Timer> fired = wheel.advanceTo(5 * SECOND);

        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).key()).isEqualTo(42L);
        assertThat(fired.get(0).payload()).isEqualTo("window-1");
        assertThat(wheel.size()).isZero();
    }

    @Test
    void timersFireInDeadlineOrderRegardlessOfSchedulingOrder() {
        // Bucket order is not deadline order. Two windows for one key firing out of order emit their
        // results out of order, and a consumer applying them as they arrive keeps the older one.
        //
        // A hundred timers inside a single 100 ms bucket, with keys scrambled against their
        // deadlines, and sortedness asserted over the whole result. Two earlier versions of this
        // test failed to catch a deleted sort: the first used deadlines in separate buckets, which
        // are visited in tick order anyway, and the second used two keys whose hash order happened
        // to match their deadline order. Depending on a hash's iteration order is depending on luck.
        TimerWheel wheel = wheel();
        wheel.advanceTo(0);
        for (int i = 0; i < 100; i++) {
            long deadline = 5 * SECOND + i * MILLI;
            long scrambledKey = (i * 37L) % 100L;
            wheel.schedule(deadline, scrambledKey, deadline);
        }

        List<TimerWheel.Timer> fired = wheel.advanceTo(6 * SECOND);

        assertThat(fired).hasSize(100);
        assertThat(fired)
                .as("every timer in one bucket, returned in deadline order")
                .isSortedAccordingTo((a, b) -> Long.compare(a.deadlineNanos(), b.deadlineNanos()));
        assertThat(fired.get(0).deadlineNanos()).isEqualTo(5 * SECOND);
        assertThat(fired.get(99).deadlineNanos()).isEqualTo(5 * SECOND + 99 * MILLI);
    }

    @Test
    void reschedulingAKeyReplacesItsTimerRatherThanAddingOne() {
        // A session window extends on every record. One timer per record would be a leak that looks
        // exactly like the system working.
        TimerWheel wheel = wheel();
        wheel.advanceTo(0);
        for (int i = 1; i <= 1_000; i++) {
            wheel.schedule(i * SECOND, 7L, "session-" + i);
        }

        assertThat(wheel.size()).as("one key, one live timer").isEqualTo(1);
        assertThat(wheel.pendingEntries())
                .as("and one entry physically in the wheel. size() dedupes by key and cannot see the "
                        + "difference: seeding \"reschedule adds instead of replaces\" left a thousand "
                        + "stale bucket entries and passed a size() assertion")
                .isEqualTo(1);
        List<TimerWheel.Timer> fired = wheel.advanceTo(2_000 * SECOND);
        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).payload()).isEqualTo("session-1000");
    }

    @Test
    void aCancelledTimerNeverFires() {
        TimerWheel wheel = wheel();
        wheel.advanceTo(0);
        wheel.schedule(5 * SECOND, 42L, "doomed");

        assertThat(wheel.cancel(42L)).isTrue();
        assertThat(wheel.cancel(42L))
                .as("cancelling twice is not an error, just false")
                .isFalse();
        assertThat(wheel.advanceTo(10 * SECOND)).isEmpty();
        assertThat(wheel.cancelledCount()).isEqualTo(1);
    }

    @Test
    void aTimerBeyondOneRotationStillFiresAtTheRightTime() {
        // The wheel covers about 102 seconds. Windows fire within that, but allowed lateness and
        // session gaps can push a deadline well past it, and "mostly correct" is not a property.
        TimerWheel wheel = wheel();
        wheel.advanceTo(0);
        wheel.schedule(3_600 * SECOND, 1L, "an hour out");

        assertThat(wheel.advanceTo(3_599 * SECOND)).isEmpty();
        assertThat(wheel.advanceTo(3_600 * SECOND))
                .extracting(TimerWheel.Timer::payload)
                .containsExactly("an hour out");
    }

    @Test
    void aWatermarkThatJumpsHoursDoesNotWalkTheWheel() {
        // What a backfill catching up looks like: a watermark leaping hours in one advance.
        //
        // Asserted as a bound on ticks walked rather than as elapsed time. The first version gave it
        // a 500 ms budget and the seeded unbounded walk PASSED -- 72 000 empty bucket lookups take
        // under a millisecond, so a timing version only fails at a jump size nobody would think to
        // write. The invariant is that at most one rotation is ever walked.
        TimerWheel wheel = wheel();
        wheel.advanceTo(0);
        wheel.schedule(SECOND, 1L, "early");
        wheel.schedule(7_200 * SECOND, 2L, "much later");

        List<TimerWheel.Timer> fired = wheel.advanceTo(7_200 * SECOND);

        assertThat(fired).extracting(TimerWheel.Timer::payload).containsExactly("early", "much later");
        assertThat(wheel.ticksWalked())
                .as("a two-hour jump at a 100 ms tick is 72 000 ticks; only one rotation may be walked")
                .isLessThanOrEqualTo(2L * 1024);
    }

    @Test
    void aMillionTimersAreCheapToScheduleAndFire() {
        // The reason this is a wheel and not a heap. Not a timing assertion -- the machine is shared
        // -- but the structure has to hold a realistic number without falling over.
        TimerWheel wheel = wheel();
        wheel.advanceTo(0);
        for (int key = 0; key < 1_000_000; key++) {
            wheel.schedule((key % 100) * SECOND + SECOND, key, key);
        }
        assertThat(wheel.size()).isEqualTo(1_000_000);

        List<TimerWheel.Timer> fired = wheel.advanceTo(200 * SECOND);
        assertThat(fired).hasSize(1_000_000);
        assertThat(wheel.size()).isZero();
        assertThat(fired.get(0).deadlineNanos())
                .isLessThanOrEqualTo(fired.get(fired.size() - 1).deadlineNanos());
    }

    @Test
    void aWatermarkGoingBackwardsFiresNothing() {
        // Watermarks do not regress, but a caller may still pass an older value during recovery.
        // Firing on it would re-fire windows that have already fired.
        TimerWheel wheel = wheel();
        wheel.advanceTo(10 * SECOND);
        wheel.schedule(20 * SECOND, 1L, "later");

        assertThat(wheel.advanceTo(5 * SECOND)).isEmpty();
        assertThat(wheel.size()).isEqualTo(1);
    }

    @Test
    void misconfigurationIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new TimerWheel(0, 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimerWheel(MILLI, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("power of two");
    }
}
