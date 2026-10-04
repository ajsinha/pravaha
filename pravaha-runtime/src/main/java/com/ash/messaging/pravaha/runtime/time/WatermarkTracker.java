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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A lane's watermark: the minimum across its input partitions, with idle partitions excluded.
 *
 * <p>The minimum is obvious and the exclusion is not, which is why design section 15.2 calls idle
 * handling <strong>mandatory</strong> rather than advisable. Without it, one quiet partition -- an
 * Aerospike partition holding a rarely-updated key range, a Kafka partition for a region that trades
 * only in the morning -- pins the lane's watermark at whatever that partition last saw, and every
 * window in the query stops firing. Nothing errors. Records keep arriving, the query keeps
 * accepting them, and no result comes out. It is the single most common streaming production
 * incident, and it looks like a hang rather than a bug.
 *
 * <p>A partition returns to the calculation the moment it produces a record again, so idleness is
 * a temporary exclusion rather than a removal.
 *
 * <p><strong>The watermark never moves backwards.</strong> An idle partition rejoining with an older
 * time, or a rewind after recovery, must not un-fire a window that has already fired -- so the
 * tracker reports the highest watermark it has ever reached. A partition that genuinely regresses is
 * a source-side fault, and it is counted and reportable rather than silently applied.
 *
 * <p>Owned by one lane and confined to its thread, like everything else a lane owns.
 */
public final class WatermarkTracker {

    /** Design section 15.2's default: a partition silent this long stops holding the watermark back. */
    public static final long DEFAULT_IDLE_TIMEOUT_NANOS = 30_000_000_000L;

    private static final class Partition {
        final String name;
        final WatermarkGenerator generator;
        long lastActivityNanos;

        // Volatile because {@link #diagnostics} reads it from whichever thread is scraping, while
        // advance() writes it from the clock's (TIME-8). One writer, so visibility is all that is
        // needed; a lock here would be on the path a watermark tick takes through every lane.
        volatile boolean idle;

        Partition(String name, WatermarkGenerator generator, long now) {
            this.name = name;
            this.generator = generator;
            this.lastActivityNanos = now;
        }
    }

    private final List<Partition> partitions = new ArrayList<>();
    private final long idleTimeoutNanos;

    private long current = WatermarkGenerator.NOT_YET;

    // Volatile for the same reason as Partition.idle: written by advance() on the clock's thread,
    // read by diagnostics() on whichever thread is asking (TIME-8).
    private volatile long regressions;

    private volatile long idleExclusions;

    /**
     * The shortest idle timeout that is not a mistake.
     *
     * <p>Below a second, ordinary jitter excludes partitions: a consumer rebalancing, a GC pause, a
     * source that polls on a timer. Each exclusion jumps the watermark forward, and the rows that
     * were already on their way then arrive behind it and count as late. The data is still correct
     * -- it arrives as a retraction and a correction -- but it is work and latency bought for
     * nothing, and the cause is invisible from the outside.
     */
    public static final Duration MINIMUM_IDLE_TIMEOUT = Duration.ofSeconds(1);

    /**
     * The longest idle timeout this engine will accept.
     *
     * <p>The whole point of excluding an idle partition is that a quiet one must not stop the
     * query. A timeout of hours preserves the letter of that and loses the substance: state grows
     * for the whole period, and a genuinely broken partition is indistinguishable from a merely
     * quiet one for far too long to be operable.
     *
     * <p>Ten minutes is past any reasonable polling interval and well short of the point where
     * memory is the thing that notices first. A source with a longer natural gap than this is a
     * batch, and a batch should not be holding a stream's watermark.
     */
    public static final Duration MAXIMUM_IDLE_TIMEOUT = Duration.ofMinutes(10);

    /**
     * The shortest watermark tick this engine will accept.
     *
     * <p>TIME-11. The tick used to be clamped -- {@code Math.max(1L, period.toMillis())}, first in
     * {@code QueryExecution} and then, after the clock was shared, in {@code SharedClock.every} --
     * so {@code 0s}, {@code PT0.0005S} and {@code -1s} were all accepted and all silently became one
     * millisecond, while the only line that mentions the tick went on printing what the operator
     * asked for. A zero tick is a thousand passes a second over every lane, for ever, on a daemon
     * thread; a negative one is not a period at all and slipped through because
     * {@code tick.compareTo(idleAfter) > 0} is false for a negative.
     *
     * <p>A millisecond is the floor because the timer counts in milliseconds: below it there is no
     * value the engine could honour, and honouring something else is the thing this class refuses
     * to do everywhere else. Anybody who wants finer than a millisecond wants a different clock.
     */
    public static final Duration MINIMUM_TICK = Duration.ofMillis(1);

    /**
     * Refuses a watermark tick this engine cannot honour, with the reason.
     *
     * <p>Here rather than at each caller, so the bounds have one home with the idle timeout's --
     * the principle above applies to both, and a second copy in the server would drift from this
     * one. {@code QueryExecution.generatingWatermarks} calls it so an embedder is refused, and
     * {@code PravahaNode.start} calls it so a configured node fails once at startup rather than
     * once per registration (TIME-5).
     *
     * @throws IllegalArgumentException with a sentence saying what the value would do to a node
     */
    public static void requireTick(Duration tick, Duration idleAfter) {
        if (tick == null || tick.isZero() || tick.isNegative()) {
            throw new IllegalArgumentException("a watermark tick of " + tick + " is not a period. The tick is "
                    + "what advances event time and what makes an idle partition detectable at all, so there "
                    + "is no sensible reading of zero or less; it was accepted and quietly became one "
                    + "millisecond, which is a thousand passes over every lane a second for ever.");
        }
        if (tick.compareTo(MINIMUM_TICK) < 0) {
            throw new IllegalArgumentException("a watermark tick of " + tick + " is below the minimum of "
                    + MINIMUM_TICK + ". The timer counts in milliseconds, so anything finer cannot be "
                    + "honoured -- it was rounded up to a millisecond and reported as what you asked for, "
                    + "which is how a tuned value becomes a mystery later.");
        }
        if (idleAfter != null && tick.compareTo(idleAfter) > 0) {
            throw new IllegalArgumentException("the watermark tick (" + tick + ") is longer than the idle "
                    + "timeout (" + idleAfter + "), so a partition could not be noticed idle until long "
                    + "after it was. Idleness is detected on the tick; the tick has to be the finer of the two.");
        }
    }

    public WatermarkTracker(long idleTimeoutNanos) {
        if (idleTimeoutNanos < MINIMUM_IDLE_TIMEOUT.toNanos()) {
            throw new IllegalArgumentException("an idle timeout of " + Duration.ofNanos(idleTimeoutNanos)
                    + " is below the minimum of " + MINIMUM_IDLE_TIMEOUT
                    + ". Below a second, ordinary jitter -- a rebalance, a GC pause, a source that polls "
                    + "on a timer -- excludes a partition that was merely slow, and the rows already on "
                    + "their way then arrive behind the watermark and count as late.");
        }
        if (idleTimeoutNanos > MAXIMUM_IDLE_TIMEOUT.toNanos()) {
            throw new IllegalArgumentException("an idle timeout of " + Duration.ofNanos(idleTimeoutNanos)
                    + " is above the maximum of " + MAXIMUM_IDLE_TIMEOUT
                    + ". Excluding an idle partition exists so a quiet one cannot stop the query; a "
                    + "timeout this long keeps the letter of that and loses the substance, because state "
                    + "grows for the whole period and a broken partition looks exactly like a quiet one "
                    + "until it expires. A source whose normal gap is longer than this is a batch, and a "
                    + "batch should not be holding a stream's watermark.");
        }
        this.idleTimeoutNanos = idleTimeoutNanos;
    }

    public WatermarkTracker() {
        this(DEFAULT_IDLE_TIMEOUT_NANOS);
    }

    /**
     * Registers an input partition.
     *
     * @param nowNanos the lane's clock, passed in rather than read, so a test can drive time and a
     *     replay is deterministic (the testkit's virtual clock exists for exactly this)
     */
    public void addPartition(String name, WatermarkGenerator generator, long nowNanos) {
        partitions.add(new Partition(name, generator, nowNanos));
    }

    /** Records an event from a partition, which also marks it active again. */
    public void observe(String partitionName, long eventTimeNanos, long nowNanos) {
        for (Partition partition : partitions) {
            if (partition.name.equals(partitionName)) {
                partition.generator.observe(eventTimeNanos);
                partition.lastActivityNanos = nowNanos;
                partition.idle = false;
                return;
            }
        }
        throw new IllegalArgumentException("no partition named '" + partitionName + "' is registered with this lane");
    }

    /**
     * Recomputes the lane watermark.
     *
     * @param nowNanos wall clock, used only for idleness -- never for the watermark itself, which is
     *     event time and must stay independent of how fast the machine is running
     * @return the current lane watermark, or {@link WatermarkGenerator#NOT_YET}
     */
    @SuppressWarnings("NonAtomicVolatileUpdate") // one writer; volatile so that readers on other threads see the count
    public long advance(long nowNanos) {
        long minimum = Long.MAX_VALUE;
        boolean any = false;
        // What the partitions that have delivered rows say, idle or not (SEEDWINDOW-1, below).
        long delivered = Long.MAX_VALUE;
        for (Partition partition : partitions) {
            boolean wasIdle = partition.idle;
            partition.idle = nowNanos - partition.lastActivityNanos >= idleTimeoutNanos;
            long own = partition.generator.watermark();
            if (own != WatermarkGenerator.NOT_YET) {
                delivered = Math.min(delivered, own);
            }
            if (partition.idle) {
                if (!wasIdle) {
                    idleExclusions++;
                }
                continue;
            }
            long watermark = partition.generator.watermark();
            if (watermark == WatermarkGenerator.NOT_YET) {
                // A partition that has produced nothing yet is not idle and not ready: it may still
                // deliver an old record. Holding the watermark back is the conservative choice, and
                // the idle timeout is what stops it holding forever.
                return current;
            }
            any = true;
            minimum = Math.min(minimum, watermark);
        }

        if (!any) {
            // Everything is idle. The watermark does not jump to infinity: firing every open window
            // because the source went quiet would turn a lull into a flood of premature results. It
            // does catch up to what the partitions that delivered rows have themselves said -- never
            // past the lowest of them -- because otherwise the order in which partitions fall idle
            // decides whether there is a watermark at all (SEEDWINDOW-1). A burst into one partition
            // of three, read just after the query starts, went idle on the same tick as the two that
            // never produced anything: no tick ever saw the busy one active with the others excluded,
            // so the watermark stayed NOT_YET and every window stayed open, for good. One more tick
            // between the two and it would have been that partition's own watermark.
            if (delivered != Long.MAX_VALUE && (current == WatermarkGenerator.NOT_YET || delivered > current)) {
                current = delivered;
            }
            return current;
        }
        if (current == WatermarkGenerator.NOT_YET || minimum > current) {
            current = minimum;
        } else if (minimum < current) {
            regressions++;
        }
        return current;
    }

    public long watermark() {
        return current;
    }

    public boolean isIdle(String partitionName) {
        return partitions.stream().anyMatch(p -> p.name.equals(partitionName) && p.idle);
    }

    public int partitionCount() {
        return partitions.size();
    }

    /** How often a partition reported a watermark below the lane's. A source-side fault, counted. */
    public long regressions() {
        return regressions;
    }

    /** How often a partition has been excluded for idleness. The metric that explains a moving watermark. */
    public long idleExclusions() {
        return idleExclusions;
    }

    /**
     * Everything this tracker knows that an operator would ask for, in one read.
     *
     * <p>TIME-8. {@code isIdle}, {@code idleExclusions()} and {@code regressions()} each existed,
     * each were documented -- the second as "the metric that explains a moving watermark" -- and
     * none of them had a caller outside this class. On a live node with a stalled query and a
     * healthy one side by side, no shipped surface distinguished them: {@code pravaha queries}
     * shows name, state, fingerprint and rows in; {@code /actuator/prometheus} had seven
     * {@code pravaha_*} gauges and none of these; {@code /api/v1/status} has none. A quiet
     * partition stopping every window in a query is the commonest streaming incident there is, and
     * it presents as a hang.
     *
     * <p>One method rather than three getters wired up three times: the three numbers are read
     * together or they mislead. An exclusion count with no idle count says a partition went quiet
     * at some point; the pair says whether it is quiet now.
     *
     * @param partitions how many input partitions this lane has
     * @param idleNow how many of them are excluded from the watermark at this moment
     * @param idleExclusions how many times a partition has been excluded since the query started
     * @param regressions how often a partition reported a watermark below the lane's -- a
     *     source-side fault, counted and never silently applied
     */
    public record Diagnostics(int partitions, int idleNow, long idleExclusions, long regressions) {

        /** For a query that derives no watermarks, which is not the same as one with none idle. */
        public static final Diagnostics NONE = new Diagnostics(0, 0, 0, 0);
    }

    /** {@link Diagnostics} for this lane, readable from any thread. */
    public Diagnostics diagnostics() {
        int idle = 0;
        for (Partition partition : partitions) {
            if (partition.idle) {
                idle++;
            }
        }
        return new Diagnostics(partitions.size(), idle, idleExclusions, regressions);
    }

    @Override
    public String toString() {
        return "WatermarkTracker[" + partitions.size() + " partitions, watermark="
                + (current == WatermarkGenerator.NOT_YET ? "none" : current) + "]";
    }
}
