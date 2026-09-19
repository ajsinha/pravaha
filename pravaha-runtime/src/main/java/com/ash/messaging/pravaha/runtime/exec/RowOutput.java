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
package com.ash.messaging.pravaha.runtime.exec;

import com.ash.messaging.pravaha.api.data.RowWriter;

/**
 * Where a stage writes its output rows.
 *
 * <p>Separate from {@link RowProcessor} so a stage never knows whether it feeds another stage, a
 * sink, or a test assertion -- the same boundary the lane exchange draws, which is what lets a
 * pipeline be reassembled across lanes without the operators changing.
 */
public interface RowOutput {

    /** Begins a row; the caller must {@code commit()} or {@code abort()} it. */
    RowWriter begin();

    /**
     * Says that every row begun since the last call is one whole unit of the lane's work: an input
     * batch, a watermark advance, a continuous aggregate's emission, end of input.
     *
     * <p>Called on the lane's thread, by the pipeline, at the only points where what it has written
     * is complete. An output that is read from another thread -- a served view, committed on the
     * feed's timer -- makes rows visible here and nowhere else, so that an update's retraction is
     * never seen without its insert (VIEW-1). An output nobody reads concurrently ignores it.
     */
    default void endOfBatch() {}
}
