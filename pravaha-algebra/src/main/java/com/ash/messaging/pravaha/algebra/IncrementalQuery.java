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
package com.ash.messaging.pravaha.algebra;

/**
 * The incremental form of a {@link Query}.
 *
 * <p>The contract, and the property the oracle checks:
 *
 * <pre>
 *     Q(S + dS)  ==  Q(S) + Qd(dS, S)
 * </pre>
 *
 * <p>Every implementation must satisfy it for every {@code S} and {@code dS}. That is a
 * machine-checkable statement about the *whole operator set*, generated rather than hand-written --
 * and it is what a retract-stream engine cannot have, since its correctness rests on the test cases
 * someone thought to write (design section 9.7).
 *
 * <p>Stateful implementations exist; {@code state} is the accumulated input, supplied so a bilinear
 * or non-linear operator can consult it. Linear operators ignore it entirely, which is precisely
 * what makes them free to run incrementally.
 */
@FunctionalInterface
public interface IncrementalQuery<I, O> {

    /**
     * Computes the change in the result caused by a change in the input.
     *
     * @param delta the change to the input
     * @param state the input as it stood before the change; ignored by linear operators
     */
    ZSet<O> evaluateDelta(ZSet<I> delta, ZSet<I> state);
}
