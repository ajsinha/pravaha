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
package com.ash.messaging.pravaha.runtime.ingest;

/**
 * When to pause a source, and when to let it go again.
 *
 * <p>Two watermarks rather than one, and the gap between them is the whole point. A single threshold
 * makes a source that is exactly at capacity pause and resume on alternate polls, which costs more
 * than the backpressure saves and produces a metric that looks like a fault. Pausing at 80 % and
 * resuming at 50 % means a pause is followed by real drainage before anything restarts (design
 * section 13.5).
 *
 * <p>The defaults are the design's. They are configuration because the right gap depends on how
 * expensive a pause is for the source: a Kafka consumer pause is nearly free, an Aerospike scan
 * throttle is not.
 *
 * @param highWatermark inbox fill, 0 to 1, at which the source is paused
 * @param lowWatermark inbox fill at which it is resumed; must be below the high watermark
 */
public record BackpressurePolicy(double highWatermark, double lowWatermark) {

    public BackpressurePolicy {
        if (highWatermark <= 0 || highWatermark > 1) {
            throw new IllegalArgumentException("high watermark must be in (0, 1], got " + highWatermark);
        }
        if (lowWatermark < 0 || lowWatermark >= highWatermark) {
            throw new IllegalArgumentException("low watermark must be below the high watermark, got " + lowWatermark
                    + " against " + highWatermark + ". Equal watermarks make a source at capacity pause and "
                    + "resume on alternate polls, which costs more than the backpressure saves.");
        }
    }

    /** Design section 13.5's defaults: pause at 80 %, resume at 50 %. */
    public static BackpressurePolicy defaults() {
        return new BackpressurePolicy(0.8, 0.5);
    }
}
