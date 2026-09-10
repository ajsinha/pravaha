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
package com.ash.messaging.pravaha.runtime.adaptive;

/**
 * Holds a latency target by moving the batch size and the linger.
 *
 * <p>A fixed batch size forces a choice nobody should have to make: 512 rows is right at a million
 * records a second and is a 50-millisecond wait at ten. The same query should meet the same latency
 * target at both rates without anybody reconfiguring it, which means the batch size has to be an
 * output of the system rather than an input to it (design section 18.2).
 *
 * <p><strong>It obeys section 18.1's four rules, and they are the interesting part.</strong> An
 * auto-tuner that cannot be understood, bounded or switched off is worse than no auto-tuner, so:
 *
 * <ul>
 *   <li><strong>Observable</strong> -- every decision is a {@link Decision} carrying the observation
 *       that caused it and the value it produced, so a timeline can show why the batch size moved
 *       rather than only that it did.
 *   <li><strong>Bounded</strong> -- minimum and maximum batch size, and a maximum step per
 *       adjustment, so a single anomalous measurement cannot move the setting by an order of
 *       magnitude.
 *   <li><strong>Reversible</strong> -- if the latency gets worse after a change, the change is
 *       undone rather than compounded. Without this a controller chasing a target it cannot reach
 *       walks the setting to a limit and stays there.
 *   <li><strong>Pinnable</strong> -- {@link #pin(int)} freezes the value permanently and every
 *       later observation is recorded and ignored. An operator who has pinned something has made a
 *       decision, and quietly overriding it later is how trust in a controller is lost.
 * </ul>
 *
 * <p>Deliberately not a PID controller. The design says PI, and even that is more than this needs:
 * the signal is a noisy percentile with a slow response, the actuator is an integer with a range of
 * about ten doublings, and proportional gain on a percentile that jumps when a GC pause lands would
 * produce exactly the oscillation the bounds exist to prevent. Multiplicative growth with additive
 * backoff -- the same shape as congestion control, for the same reason -- is more stable here and
 * far easier to explain to somebody looking at a graph at three in the morning.
 */
public final class BatchingController {

    /** What the controller did, and why. Emitted for every observation, including the ones it ignores. */
    public record Decision(int batchSize, long lingerNanos, String reason) {}

    private final BatchingLimits limits;
    private final long targetNanos;

    private int batchSize;
    private long lingerNanos;
    private long lastObservedNanos = -1L;
    private int previousBatchSize;
    private boolean pinned;
    private long observations;
    private long adjustments;
    private long reversions;

    public BatchingController(BatchingLimits limits, long targetLatencyNanos) {
        if (targetLatencyNanos <= 0) {
            throw new IllegalArgumentException("latency target must be positive, got " + targetLatencyNanos);
        }
        this.limits = limits;
        this.targetNanos = targetLatencyNanos;
        this.batchSize = limits.initialBatchSize();
        this.previousBatchSize = batchSize;
        this.lingerNanos = limits.maxLingerNanos();
    }

    /**
     * Feeds the controller one measurement.
     *
     * @param observedLatencyNanos the p99 engine latency since the last observation
     * @param rowsPerSecond the current arrival rate, which decides the linger rather than the batch:
     *     a stream that has gone quiet needs its partial batch flushed, not a smaller one
     */
    public Decision observe(long observedLatencyNanos, double rowsPerSecond) {
        observations++;
        if (pinned) {
            return new Decision(batchSize, lingerNanos, "pinned by an operator; observation recorded and ignored");
        }

        // A rate that has collapsed is not a latency problem and must not be treated as one. Holding
        // a batch open for a stream producing ten rows a second is the latency cliff section 18.2
        // names, and shrinking the batch does not fix it -- flushing does.
        if (rowsPerSecond > 0 && rowsPerSecond < limits.lowRateThreshold()) {
            lingerNanos = limits.minLingerNanos();
            lastObservedNanos = observedLatencyNanos;
            return new Decision(
                    batchSize,
                    lingerNanos,
                    "rate below " + limits.lowRateThreshold()
                            + " rows/s: linger dropped to its floor so a partial batch is not held open");
        }

        if (observedLatencyNanos > targetNanos
                && lastObservedNanos >= 0
                && observedLatencyNanos > lastObservedNanos
                && batchSize != previousBatchSize) {
            // Worse than before, and after a change: undo it rather than compound it.
            int reverted = previousBatchSize;
            previousBatchSize = batchSize;
            batchSize = reverted;
            reversions++;
            lastObservedNanos = observedLatencyNanos;
            return new Decision(
                    batchSize, lingerNanos, "latency worsened after the last change; reverted to " + reverted);
        }

        lastObservedNanos = observedLatencyNanos;
        if (observedLatencyNanos > targetNanos * 0.9) {
            return shrink(observedLatencyNanos);
        }
        if (observedLatencyNanos < targetNanos * 0.6) {
            return grow(observedLatencyNanos);
        }
        return new Decision(batchSize, lingerNanos, "within the target band; unchanged");
    }

    private Decision grow(long observed) {
        if (batchSize >= limits.maxBatchSize()) {
            return new Decision(batchSize, lingerNanos, "latency has headroom but the batch is already at its maximum");
        }
        previousBatchSize = batchSize;
        // Multiplicative, capped by the step limit: doubling reaches a useful size in a few
        // observations, and the cap stops one quiet interval from jumping the setting by an order
        // of magnitude.
        int grown = Math.min(batchSize * 2, batchSize + limits.maxStep());
        batchSize = Math.min(limits.maxBatchSize(), grown);
        adjustments++;
        return new Decision(
                batchSize, lingerNanos, "latency at " + percent(observed) + "% of target: batch grown for throughput");
    }

    private Decision shrink(long observed) {
        if (batchSize <= limits.minBatchSize()) {
            // The batch is not what is costing the latency. Saying so beats shrinking to no effect
            // and reporting an adjustment that changed nothing.
            lingerNanos = limits.minLingerNanos();
            return new Decision(
                    batchSize,
                    lingerNanos,
                    "latency at " + percent(observed)
                            + "% of target but the batch is already at its minimum: the cost is elsewhere");
        }
        previousBatchSize = batchSize;
        // Additive backoff: the response to being over target should be gentler than the response
        // to having headroom, or the controller oscillates across the band.
        batchSize = Math.max(limits.minBatchSize(), batchSize - Math.max(1, batchSize / 4));
        lingerNanos = Math.max(limits.minLingerNanos(), lingerNanos / 2);
        adjustments++;
        return new Decision(
                batchSize, lingerNanos, "latency at " + percent(observed) + "% of target: batch and linger reduced");
    }

    private long percent(long observed) {
        return observed * 100L / targetNanos;
    }

    /** Freezes the batch size. Permanent, by design: an operator who pins has made a decision. */
    public void pin(int fixedBatchSize) {
        this.batchSize = Math.clamp(fixedBatchSize, limits.minBatchSize(), limits.maxBatchSize());
        this.pinned = true;
    }

    public boolean isPinned() {
        return pinned;
    }

    public int batchSize() {
        return batchSize;
    }

    public long lingerNanos() {
        return lingerNanos;
    }

    public long observations() {
        return observations;
    }

    public long adjustments() {
        return adjustments;
    }

    /** How often a change had to be undone. Persistently high means the target is not reachable. */
    public long reversions() {
        return reversions;
    }

    @Override
    public String toString() {
        return "BatchingController[batch=" + batchSize + ", linger=" + lingerNanos / 1000 + "us"
                + (pinned ? ", pinned" : "") + ", adjustments=" + adjustments + ", reversions=" + reversions + "]";
    }
}
