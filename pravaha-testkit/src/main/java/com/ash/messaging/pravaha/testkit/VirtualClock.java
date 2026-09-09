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
package com.ash.messaging.pravaha.testkit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Time the test controls, in nanoseconds.
 *
 * <p>Exists so that no test ever calls {@code Thread.sleep}. A streaming engine is full of
 * time-dependent behaviour -- watermarks, window firing, session gaps, checkpoint intervals,
 * backoff -- and testing any of it against the wall clock produces suites that are slow when they
 * pass and flaky when they fail. A test that waits 30 seconds for a window to close is a test
 * nobody runs.
 *
 * <p>Time moves only when {@link #advanceBy} or {@link #advanceTo} is called, and never backwards:
 * a clock that can regress would let a test construct a state the engine will never see.
 *
 * <p>Not thread-safe by design. The deterministic scheduler drives everything from one thread, and
 * making the clock concurrent would invite tests to introduce the nondeterminism it exists to
 * remove.
 */
public final class VirtualClock {

    /** 2026-01-01T00:00:00Z. A fixed, readable origin so timestamps in failures are legible. */
    public static final long DEFAULT_ORIGIN_NANOS = 1_767_225_600_000_000_000L;

    private final List<Runnable> onAdvance = new ArrayList<>();
    private long nanos;

    public VirtualClock() {
        this(DEFAULT_ORIGIN_NANOS);
    }

    public VirtualClock(long originNanos) {
        this.nanos = originNanos;
    }

    /** Current time in nanoseconds since epoch. */
    public long nanos() {
        return nanos;
    }

    public long millis() {
        return nanos / 1_000_000L;
    }

    public Instant instant() {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }

    /** Moves time forward and notifies listeners. */
    public VirtualClock advanceBy(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("cannot advance by a negative duration: " + amount);
        }
        return advanceTo(nanos + amount.toNanos());
    }

    public VirtualClock advanceByNanos(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("cannot advance by " + amount + " nanoseconds");
        }
        return advanceTo(nanos + amount);
    }

    /**
     * Moves time to an absolute point.
     *
     * @throws IllegalArgumentException if that would move time backwards. A regressing clock lets a
     *     test construct a state the engine will never encounter, so the failure is worth having.
     */
    public VirtualClock advanceTo(long targetNanos) {
        if (targetNanos < nanos) {
            throw new IllegalArgumentException(
                    "time cannot move backwards: at " + nanos + ", asked for " + targetNanos);
        }
        nanos = targetNanos;
        for (Runnable listener : onAdvance) {
            listener.run();
        }
        return this;
    }

    /** Registers a callback fired after every advance -- how timers and watermarks hook in. */
    public void onAdvance(Runnable listener) {
        onAdvance.add(listener);
    }

    @Override
    public String toString() {
        return "VirtualClock[" + instant() + "]";
    }
}
