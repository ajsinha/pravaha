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
package com.ash.messaging.pravaha.runtime.lane;

/**
 * Builds one lane's processor.
 *
 * <p>A factory rather than an instance, and that is the whole point. Handing every lane the same
 * {@link LaneProcessor} would share its mutable state across threads and quietly undo the single
 * -writer principle the entire execution model rests on (design section 13.1) -- and it would do so
 * without a compiler error, a test failure, or anything else to notice it by until the aggregates
 * came out wrong under load. Requiring a factory makes the sharing impossible to express by
 * accident.
 */
@FunctionalInterface
public interface LaneProcessorFactory {

    /**
     * Called once per lane, before the lane thread starts.
     *
     * @param context the lane's own arena, id and partition assignment -- shared with nothing
     */
    LaneProcessor create(LaneContext context);
}
