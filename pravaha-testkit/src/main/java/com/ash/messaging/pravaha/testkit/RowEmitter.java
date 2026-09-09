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
package com.ash.messaging.pravaha.testkit;

import com.ash.messaging.pravaha.api.data.RowWriter;

/**
 * Where an operator sends rows.
 *
 * <p>Separated from the operator so a stage never knows whether it is feeding another stage, a sink,
 * or a test assertion. That is the same boundary the real runtime draws, and keeping it here means a
 * test pipeline composes the way a real one does.
 */
public interface RowEmitter {

    /**
     * Begins a row and returns its writer.
     *
     * <p>The caller must finish with {@link RowWriter#commit()} or {@link RowWriter#abort()}.
     */
    RowWriter begin();

    /** Rows emitted so far. */
    int count();
}
