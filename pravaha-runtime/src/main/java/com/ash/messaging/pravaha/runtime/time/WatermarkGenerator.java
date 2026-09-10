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

/**
 * How one source partition decides how far its event time has progressed.
 *
 * <p>A watermark is a claim: "no record with an event time below this will arrive from me again".
 * Everything time-based in the engine -- when a window fires, when state can be released, what
 * counts as late -- rests on that claim, and every strategy here is a different bet about how wrong
 * the claim may be.
 *
 * <p>Per partition, never per stream. A partition is the only place where the ordering properties
 * are actually known; combining first and generating afterwards would take the worst behaviour of
 * any partition and impose it on all of them.
 */
public interface WatermarkGenerator {

    /** Nothing has been seen yet. Distinct from a real watermark of zero. */
    long NOT_YET = Long.MIN_VALUE;

    /** Observes a record's event time. */
    void observe(long eventTimeNanos);

    /** The current claim, or {@link #NOT_YET}. */
    long watermark();

    /**
     * The default: allow for {@code d} of out-of-orderness.
     *
     * <p>The watermark trails the highest event time seen by {@code d}, which is a direct statement
     * of how much reordering the source is expected to produce. Setting it too low silently drops
     * records as late; setting it too high delays every window by the difference. It is the one
     * tuning knob in streaming that people most often set once and never revisit, so the late-record
     * counter exists to tell them when it was wrong.
     */
    static WatermarkGenerator boundedOutOfOrderness(long outOfOrdernessNanos) {
        if (outOfOrdernessNanos < 0) {
            throw new IllegalArgumentException("out-of-orderness must not be negative, got " + outOfOrdernessNanos);
        }
        return new WatermarkGenerator() {
            private long maxSeen = NOT_YET;

            @Override
            public void observe(long eventTimeNanos) {
                if (maxSeen == NOT_YET || eventTimeNanos > maxSeen) {
                    maxSeen = eventTimeNanos;
                }
            }

            @Override
            public long watermark() {
                return maxSeen == NOT_YET ? NOT_YET : maxSeen - outOfOrdernessNanos;
            }
        };
    }

    /**
     * For a partition that is provably ordered.
     *
     * <p>The watermark is the last event time seen, with no allowance at all. Correct for a Kafka
     * partition written by a single producer, or a Delta version's rows read in file order; wrong
     * and silently lossy for anything merged from several writers. Declared rather than detected,
     * because detection would mean waiting to see whether reordering happens, and by then the
     * records it would have dropped are gone.
     */
    static WatermarkGenerator ascending() {
        return boundedOutOfOrderness(0);
    }

    /**
     * Driven by the source's own progress signals.
     *
     * <p>Some sources say where they are -- a CDC feed's log position, a file feed's completion
     * marker -- and a claim from the source beats any inference drawn from the records. The
     * generator only advances when told, so a source that stops signalling stops the watermark,
     * which is the correct behaviour and is why idle detection is mandatory alongside it.
     */
    static PunctuatedWatermarkGenerator punctuated() {
        return new PunctuatedWatermarkGenerator();
    }

    /** A generator that advances only when the source declares progress. */
    final class PunctuatedWatermarkGenerator implements WatermarkGenerator {
        private long declared = NOT_YET;

        @Override
        public void observe(long eventTimeNanos) {
            // Records do not move a punctuated watermark. That is the entire point: the source is
            // the authority, and inferring progress from records here would quietly reintroduce the
            // guess this strategy was chosen to avoid.
        }

        /** Called by the source when it declares progress. Never moves backwards. */
        public void declare(long watermarkNanos) {
            if (declared == NOT_YET || watermarkNanos > declared) {
                declared = watermarkNanos;
            }
        }

        @Override
        public long watermark() {
            return declared;
        }
    }
}
