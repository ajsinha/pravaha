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

/**
 * An operator whose state is a set of rows -- a top-N -- and which writes and reads that state for
 * a checkpoint. {@link InterpretedPipeline} snapshots every one after its aggregates, so a restart
 * resumes it holding what it held rather than empty beside a restored view.
 */
interface HeldRows {

    void writeTo(java.io.DataOutputStream out) throws java.io.IOException;

    void readFrom(java.io.DataInputStream in) throws java.io.IOException;

    /** Distinct rows held, for the state ceiling and for a person asking. */
    long rowsHeld();
}
