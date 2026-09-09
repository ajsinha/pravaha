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
 * A relational computation, in the form the incremental lift is defined over.
 *
 * <p>Deliberately just {@code ZSet -> ZSet}. Keeping the batch form this simple is what makes the
 * lift mechanical: {@link IncrementalQuery} is derived from a {@code Query} by rule rather than
 * hand-written, and the oracle can then check the derivation against the original for arbitrary
 * inputs (design section 9.3).
 */
@FunctionalInterface
public interface Query<I, O> {

    /** Evaluates over a whole relation. */
    ZSet<O> evaluate(ZSet<I> input);
}
