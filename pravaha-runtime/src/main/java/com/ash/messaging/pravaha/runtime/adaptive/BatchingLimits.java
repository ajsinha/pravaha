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
 * The bounds an adaptive controller may not leave.
 *
 * <p>Section 18.1's second rule as a type. A controller without explicit limits is one bad
 * measurement away from a batch size of one or of a million, and both are outages -- so the limits
 * are a constructor argument rather than a constant somebody can forget to apply.
 *
 * @param initialBatchSize where the controller starts, before any measurement exists
 * @param minBatchSize never below this, however bad the latency gets
 * @param maxBatchSize never above this, however much headroom there appears to be
 * @param maxStep the most one adjustment may add, so a single anomalous interval cannot move the
 *     setting by an order of magnitude
 * @param minLingerNanos the floor a partial batch waits before being flushed
 * @param maxLingerNanos the ceiling, which is also design section 13.4's default of 200 microseconds
 * @param lowRateThreshold rows per second below which the linger drops to its floor, because at that
 *     rate holding a batch open is the entire latency
 */
public record BatchingLimits(
        int initialBatchSize,
        int minBatchSize,
        int maxBatchSize,
        int maxStep,
        long minLingerNanos,
        long maxLingerNanos,
        double lowRateThreshold) {

    public BatchingLimits {
        if (minBatchSize < 1) {
            throw new IllegalArgumentException("minimum batch size must be at least 1, got " + minBatchSize);
        }
        if (maxBatchSize < minBatchSize) {
            throw new IllegalArgumentException(
                    "maximum batch size " + maxBatchSize + " is below the minimum " + minBatchSize);
        }
        if (initialBatchSize < minBatchSize || initialBatchSize > maxBatchSize) {
            throw new IllegalArgumentException("initial batch size " + initialBatchSize + " is outside [" + minBatchSize
                    + ", " + maxBatchSize + "]");
        }
        if (maxStep < 1) {
            throw new IllegalArgumentException("maximum step must be at least 1, got " + maxStep);
        }
        if (minLingerNanos < 0 || maxLingerNanos < minLingerNanos) {
            throw new IllegalArgumentException(
                    "linger bounds are inverted: " + minLingerNanos + " to " + maxLingerNanos);
        }
    }

    /** Design sections 13.4 and 18.2's defaults: 512 rows, 200 microseconds of linger. */
    public static BatchingLimits defaults() {
        return new BatchingLimits(512, 1, 8192, 2048, 1_000L, 200_000L, 1_000.0);
    }
}
