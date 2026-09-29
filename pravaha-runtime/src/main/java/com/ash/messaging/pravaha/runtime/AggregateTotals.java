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
package com.ash.messaging.pravaha.runtime;

import com.ash.messaging.pravaha.api.PravahaException;

/**
 * The arithmetic of every {@code SUM}, {@code COUNT} and {@code AVG} accumulator, checked.
 *
 * <p>SUMWRAP-1. The accumulators are 64-bit and were added with {@code +=}, so a {@code BIGINT}
 * total -- or a {@code DECIMAL} total's unscaled value -- past {@code 2^63 - 1} wrapped round to a
 * large negative number and was served as the answer, with nothing anywhere to say so. A wrapped
 * total is the worst kind of wrong: it has the right type, it is often plausible, and nothing
 * downstream can tell. Every addition here is {@link Math#addExact} and every weighting {@link
 * Math#multiplyExact}; a retraction is an addition at a negative weight, so it is checked the same
 * way.
 *
 * <p>What happens then is what happens to every other runtime refusal: the operator throws {@link
 * RuntimeErrors#AGGREGATE_OVERFLOW}, a continuous query's lane stops and the query moves to {@code
 * FAILED} with its view refusing reads, and a read is refused with the code. Never a wrapped number.
 *
 * <p>Deliberately not a wider accumulator. A 128-bit sum would carry some totals further, but a
 * {@code BIGINT} output column still has to hold the answer, and the state layout -- off-heap, in
 * checkpoints, spilled -- is 64 bits per accumulator; widening it is a format change for every
 * windowed query to cure a case that is refused cleanly here.
 */
public final class AggregateTotals {

    private AggregateTotals() {}

    /**
     * {@code total + value * weight}, or {@link ArithmeticException} when either step leaves the
     * 64-bit range. Callers catch it where they can name the aggregate, and turn it into {@link
     * #overflow}.
     */
    public static long addWeighted(long total, long value, long weight) {
        return Math.addExact(total, Math.multiplyExact(value, weight));
    }

    /** {@code total + addend}, or {@link ArithmeticException} past the 64-bit range. */
    public static long add(long total, long addend) {
        return Math.addExact(total, addend);
    }

    /**
     * The refusal for a total that left the 64-bit range.
     *
     * @param aggregate the aggregate as the query wrote it, say {@code SUM(amount)}
     */
    public static PravahaException overflow(String aggregate, ArithmeticException cause) {
        return new PravahaException(
                RuntimeErrors.AGGREGATE_OVERFLOW,
                "the running total of " + aggregate + " left the 64-bit range an aggregate accumulates in "
                        + "(beyond +/-9223372036854775807; for a DECIMAL column that is its unscaled value, so "
                        + "the limit is that many units of its last digit). Refused rather than answered with "
                        + "a total that wrapped round to the wrong number. Aggregate a smaller quantity -- "
                        + "scale the column down, or group by a key that splits the total -- or filter out "
                        + "the rows that carry it.",
                cause);
    }
}
