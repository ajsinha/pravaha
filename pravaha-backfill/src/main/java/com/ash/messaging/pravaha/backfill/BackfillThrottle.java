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
package com.ash.messaging.pravaha.backfill;

/**
 * How fast a backfill is allowed to read, and how that changes when the store starts to suffer.
 *
 * <p>Design section 16.2. A backfill competes with production traffic on the same storage cluster,
 * and treating it as "just a fast source" is how a streaming rollout takes down a payments system.
 * The ceiling is the blunt control; the adaptive part is what makes running one during business
 * hours a decision somebody can defend.
 *
 * <p>The loop is deliberately asymmetric, in the same shape as the batching controller and for the
 * same reason: <strong>back off fast, recover slowly</strong>. A store whose latency has risen is
 * already in trouble and halving the load is the only response that helps quickly; a store that
 * looks healthy may look that way because the backfill is currently slow, so the way back up is a
 * small step at a time. Symmetric control oscillates -- speed up, hurt the store, back off, speed
 * up -- and an oscillating backfill is worse for the store than a constant one.
 *
 * <p>Three properties this shares with every other controller here. It is <em>observable</em>: every
 * decision has a reason attached, including the decisions to do nothing. It is <em>bounded</em>: the
 * rate never leaves the floor-to-ceiling range, so a pathological latency reading cannot stall the
 * backfill entirely or let it run away. And it is <em>pinnable</em>: an operator who has decided on
 * a number gets to keep it, permanently, because a controller that overrides a human's explicit
 * choice is a controller nobody trusts again.
 */
public final class BackfillThrottle {

    /** What the throttle decided, and why. Returned for every observation, including the quiet ones. */
    public record Decision(long rowsPerSecond, String reason) {}

    /** Halve on trouble. Fast enough to matter within a few observations. */
    private static final double BACKOFF = 0.5;

    /** Ten per cent back up. Roughly seven observations to undo one backoff. */
    private static final double RECOVERY = 1.1;

    private final long ceiling;
    private final long floor;
    private final long targetLatencyNanos;

    private long rowsPerSecond;
    private boolean pinned;
    private long observations;
    private long backoffs;
    private long recoveries;

    /**
     * @param ceiling the configured {@code backfill.rate.limit}. The throttle never exceeds it, so
     *     an operator's number is a ceiling and not a suggestion
     * @param floor the slowest the backfill may be driven. Below this it would never finish, and a
     *     backfill that never finishes is a worse outcome than one that is visibly too slow
     * @param targetLatencyNanos what the store's latency should stay under. Above it, back off
     */
    public BackfillThrottle(long ceiling, long floor, long targetLatencyNanos) {
        if (ceiling < 1) {
            throw new IllegalArgumentException("the rate ceiling must be at least 1 row/s, got " + ceiling);
        }
        if (floor < 1 || floor > ceiling) {
            throw new IllegalArgumentException(
                    "the rate floor must be between 1 and the ceiling (" + ceiling + "), got " + floor);
        }
        if (targetLatencyNanos < 1) {
            throw new IllegalArgumentException("the latency target must be positive, got " + targetLatencyNanos);
        }
        this.ceiling = ceiling;
        this.floor = floor;
        this.targetLatencyNanos = targetLatencyNanos;
        this.rowsPerSecond = ceiling;
    }

    /**
     * Reports what the store's latency looks like now, and gets back the rate to read at.
     *
     * <p>The latency is the <em>store's</em>, not the engine's. A backfill that measured its own
     * throughput would back off when the engine was busy, which is the wrong signal entirely: the
     * point is to protect the database from the backfill, not the backfill from itself.
     */
    public Decision observe(long storeLatencyNanos) {
        observations++;
        if (pinned) {
            return new Decision(rowsPerSecond, "pinned at " + rowsPerSecond + " rows/s by an operator");
        }
        if (storeLatencyNanos > targetLatencyNanos) {
            long reduced = Math.max(floor, (long) (rowsPerSecond * BACKOFF));
            if (reduced == rowsPerSecond) {
                return new Decision(
                        rowsPerSecond,
                        "already at the floor of " + floor + " rows/s and the store is still over its latency "
                                + "target; the backfill is no longer the cause");
            }
            rowsPerSecond = reduced;
            backoffs++;
            return new Decision(
                    rowsPerSecond,
                    "store latency " + storeLatencyNanos / 1_000_000 + " ms is over the "
                            + targetLatencyNanos / 1_000_000 + " ms target; halved to " + rowsPerSecond + " rows/s");
        }
        if (rowsPerSecond >= ceiling) {
            return new Decision(rowsPerSecond, "at the configured ceiling of " + ceiling + " rows/s");
        }
        long raised = Math.min(ceiling, Math.max(rowsPerSecond + 1, (long) (rowsPerSecond * RECOVERY)));
        rowsPerSecond = raised;
        recoveries++;
        return new Decision(
                rowsPerSecond, "store latency is within target; easing back up to " + rowsPerSecond + " rows/s");
    }

    /**
     * Freezes the rate. Permanent, by design.
     *
     * <p>An operator who pins has made a decision, usually during an incident and usually with
     * information the controller does not have. Un-pinning on the controller's own initiative would
     * make that decision temporary without telling anybody.
     */
    public void pin(long fixedRowsPerSecond) {
        if (fixedRowsPerSecond < 1 || fixedRowsPerSecond > ceiling) {
            throw new IllegalArgumentException(
                    "a pinned rate must be between 1 and the ceiling (" + ceiling + "), got " + fixedRowsPerSecond);
        }
        this.rowsPerSecond = fixedRowsPerSecond;
        this.pinned = true;
    }

    public boolean isPinned() {
        return pinned;
    }

    public long rowsPerSecond() {
        return rowsPerSecond;
    }

    /** How many rows may be read in a window of the given length at the current rate. */
    public int budgetFor(long windowNanos) {
        long rows = rowsPerSecond * windowNanos / 1_000_000_000L;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, rows));
    }

    public long observations() {
        return observations;
    }

    public long backoffs() {
        return backoffs;
    }

    public long recoveries() {
        return recoveries;
    }

    @Override
    public String toString() {
        return "BackfillThrottle[" + rowsPerSecond + " rows/s of " + ceiling + (pinned ? ", pinned" : "")
                + ", backoffs=" + backoffs + ", recoveries=" + recoveries + "]";
    }
}
