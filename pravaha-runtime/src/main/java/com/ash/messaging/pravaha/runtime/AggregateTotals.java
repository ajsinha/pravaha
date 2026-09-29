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
 * downstream can tell. Every total here is checked; a retraction is an addition at a negative
 * weight, so it is checked the same way.
 *
 * <p>What happens then is what happens to every other runtime refusal: the operator throws {@link
 * RuntimeErrors#AGGREGATE_OVERFLOW}, a continuous query's lane stops and the query moves to {@code
 * FAILED} with its view refusing reads, and a read is refused with the code. Never a wrapped number.
 *
 * <p><strong>A batch is netted in 128 bits; what it commits must fit in 64</strong> (TRANSOVF-1). A
 * {@code SUM} accumulates with {@link #addWeighted(long[], long[], int, long, long)}: the total keeps
 * its 64-bit low word, and a second word -- the <em>excess</em> -- counts how many times {@code 2^64}
 * the true total lies away from what the low word says. The excess is zero whenever the total fits,
 * so a batch that passes {@code 2^63} and comes back ({@code +MAX} then {@code -MAX}) ends with
 * nothing to refuse, and the state that is stored, checkpointed and spilled is still one 64-bit word
 * per accumulator: a committed total has no excess to store. At the end of each batch, and before
 * any total is read, {@link #settled} refuses a nonzero excess -- the committed total does not fit --
 * so a wrapped value is still never served. {@code COUNT} and the non-null counts stay {@link
 * Math#addExact}: a count moves by a row's weight, and no count gets near {@code 2^63}.
 */
public final class AggregateTotals {

    private AggregateTotals() {}

    /**
     * {@code totals[i] + value * weight} in 128 bits: the low word goes back into {@code totals[i]}
     * and whatever the true total carries past it into {@code excess[i]}. Nothing is refused here --
     * a total may leave the range inside a batch and come back -- except a total past 128 bits,
     * which no honest batch reaches; {@link #settled} is what refuses.
     */
    public static void addWeighted(long[] totals, long[] excess, int i, long value, long weight) {
        long total = totals[i];
        long carried = excessOf(total, value, weight);
        totals[i] = total + value * weight;
        if (carried != 0) {
            excess[i] = Math.addExact(excess[i], carried);
        }
    }

    /**
     * What {@code total + value * weight} carries past its 64-bit low word, in units of {@code
     * 2^64}: zero whenever the result fits.
     *
     * <p>The common case is the checked arithmetic SUMWRAP-1 already paid for -- the JIT's
     * overflow-flag intrinsics, measured cheaper than computing the carry every time -- and only a
     * total that leaves the range takes the 128-bit path.
     */
    public static long excessOf(long total, long value, long weight) {
        try {
            Math.addExact(total, Math.multiplyExact(value, weight));
            return 0;
        } catch (ArithmeticException outside) {
            return wideExcessOf(total, value, weight);
        }
    }

    /**
     * {@link #excessOf} the long way. The product is {@code multiplyHigh * 2^64 + unsigned(low)},
     * and {@code unsigned(low)} is {@code signed(low) + 2^64} when the low word's top bit is set;
     * the addition then carries one more {@code +/-2^64} when it overflows as a signed sum.
     */
    public static long wideExcessOf(long total, long value, long weight) {
        long product = value * weight;
        long sum = total + product;
        long carried = Math.multiplyHigh(value, weight) + (product >>> 63);
        if (((total ^ sum) & (product ^ sum)) < 0) {
            carried += sum < 0 ? 1 : -1;
        }
        return carried;
    }

    /**
     * The committed total: {@code total} when {@code excess} is zero, and {@link
     * ArithmeticException} when it is not -- the true total is outside the 64-bit range and {@code
     * total} is only its wrapped low word.
     */
    public static long settled(long total, long excess) {
        if (excess != 0) {
            throw new ArithmeticException("long overflow");
        }
        return total;
    }

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
