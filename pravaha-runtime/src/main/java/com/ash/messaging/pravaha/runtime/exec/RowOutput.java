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
}
