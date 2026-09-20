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
package com.ash.messaging.pravaha.serving;

import java.util.Arrays;

/**
 * One row of a view changing, with the weight that says how.
 *
 * <p>A weight of {@code +1} is a row appearing, {@code -1} is a row being withdrawn, and larger
 * magnitudes are several of either. A correction -- late data arriving and changing a window that had
 * already been published -- arrives as a retraction of the old row followed by an insert of the new
 * one, which is the same arithmetic as everything else in the engine rather than a special case a
 * subscriber has to recognise (design section 9.2).
 *
 * <p>Consumers that only want the current value can ignore negative weights and overwrite by key.
 * Consumers that are maintaining their own aggregate must apply the weight, or their total will
 * drift from the view's the first time a window is corrected.
 *
 * <p><strong>A weight of zero is not a change</strong> (STRM-1). {@code docs/CONCEPTS.md} §4 states
 * the Z-set rule the view implements: a row is present exactly while its weights sum positive, so
 * adding zero to that sum moves nothing and {@link ServedView} correctly applies nothing. What was
 * wrong was that such a row was still <em>delivered</em>, and {@link #isRetraction()} being
 * {@code weight < 0} told a subscriber it was an insertion -- so the consumption model this class
 * recommends one paragraph above, "ignore negative weights and overwrite by key", wrote the
 * zero-weight row's values into a copy of a view that had not changed. {@link ViewSink} now stages
 * no zero-weight change, so the change stream and the view agree exactly, and {@link #isRetraction()}
 * and {@link #isInsertion()} are both false for one that is constructed directly.
 */
public record ViewChange(Object[] values, long weight) {

    public ViewChange {
        values = values == null ? new Object[0] : values.clone();
    }

    @Override
    public Object[] values() {
        return values.clone();
    }

    /** Whether this withdraws a row rather than adding one. */
    public boolean isRetraction() {
        return weight < 0;
    }

    /**
     * Whether this adds a row rather than withdrawing one.
     *
     * <p>Not {@code !isRetraction()}, which is the trap STRM-1 records: a weight of zero is neither,
     * and asking the negative question answered "insertion" for a change that changes nothing.
     */
    public boolean isInsertion() {
        return weight > 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ViewChange change && weight == change.weight && Arrays.equals(values, change.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values) * 31 + Long.hashCode(weight);
    }

    @Override
    public String toString() {
        return (weight < 0 ? "-" : "+") + Math.abs(weight) + Arrays.toString(values);
    }
}
