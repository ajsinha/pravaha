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
package com.ash.messaging.pravaha.common.queue;

import java.util.concurrent.locks.LockSupport;

/**
 * What a lane thread does when its input queue is empty.
 *
 * <p>This is the CPU-for-latency dial (design section 13.3). Spinning keeps the thread hot and its caches
 * warm, so the next record is picked up in tens of nanoseconds; parking gives the core back but
 * costs a wakeup, typically tens of microseconds. Neither is right for every deployment, which is
 * why it is configuration rather than a decision baked into the runtime.
 *
 * <p>Implementations are stateless and shared. The idle counter is passed in rather than held, so a
 * single instance can serve every lane.
 */
@FunctionalInterface
public interface WaitStrategy {

    /**
     * Called after a poll found nothing.
     *
     * @param idleCount consecutive empty polls, starting at 1. Strategies escalate on this: a queue
     *     that has been empty for a moment is likely to stay empty, and continuing to spin on it
     *     burns a core to no purpose.
     */
    void idle(int idleCount);

    /** Lowest latency, one core burned per lane. For dedicated hardware and latency-critical work. */
    WaitStrategy BUSY_SPIN = idleCount -> Thread.onSpinWait();

    /**
     * The default. Spins briefly, then yields, then parks.
     *
     * <p>Keeps near-spin latency while traffic is flowing and gives the core back when it is not,
     * which is the behaviour a general production deployment wants without being asked.
     */
    @SuppressWarnings("ThreadPriorityCheck") // yielding is the wait strategy being measured or offered
    WaitStrategy SPIN_THEN_YIELD = idleCount -> {
        if (idleCount < 64) {
            Thread.onSpinWait();
        } else if (idleCount < 128) {
            Thread.yield();
        } else {
            LockSupport.parkNanos(1L);
        }
    };

    /** Low CPU. For shared or containerised hosts, and for many low-rate queries. */
    WaitStrategy BACKOFF_PARK = idleCount -> {
        if (idleCount < 8) {
            Thread.onSpinWait();
        } else {
            // Exponential to a millisecond ceiling: long enough to be cheap when genuinely idle,
            // short enough that a resuming stream is not left waiting.
            long nanos = Math.min(1_000_000L, 1_000L << Math.min(10, idleCount - 8));
            LockSupport.parkNanos(nanos);
        }
    };

    /** Lowest CPU, highest latency. Development, and queries below roughly a thousand records a second. */
    WaitStrategy BLOCKING = idleCount -> LockSupport.parkNanos(1_000_000L);

    /** The configured strategies, by name. */
    enum Kind {
        BUSY_SPIN(WaitStrategy.BUSY_SPIN),
        SPIN_THEN_YIELD(WaitStrategy.SPIN_THEN_YIELD),
        BACKOFF_PARK(WaitStrategy.BACKOFF_PARK),
        BLOCKING(WaitStrategy.BLOCKING);

        @SuppressWarnings("ImmutableEnumChecker") // the field is unmodifiable or stateless; it is never changed
        private final WaitStrategy strategy;

        Kind(WaitStrategy strategy) {
            this.strategy = strategy;
        }

        public WaitStrategy strategy() {
            return strategy;
        }
    }
}
