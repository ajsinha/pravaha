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
        boolean idle;

        Partition(String name, WatermarkGenerator generator, long now) {
            this.name = name;
            this.generator = generator;
            this.lastActivityNanos = now;
        }
    }

    private final List<Partition> partitions = new ArrayList<>();
    private final long idleTimeoutNanos;

    private long current = WatermarkGenerator.NOT_YET;
    private long regressions;
    private long idleExclusions;

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
    public long advance(long nowNanos) {
        long minimum = Long.MAX_VALUE;
        boolean any = false;
        for (Partition partition : partitions) {
            boolean wasIdle = partition.idle;
            partition.idle = nowNanos - partition.lastActivityNanos >= idleTimeoutNanos;
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
            // Everything is idle. The watermark stays where it is rather than jumping to infinity:
            // firing every open window because the source went quiet would turn a lull into a flood
            // of premature results.
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

    @Override
    public String toString() {
        return "WatermarkTracker[" + partitions.size() + " partitions, watermark="
                + (current == WatermarkGenerator.NOT_YET ? "none" : current) + "]";
    }
}
