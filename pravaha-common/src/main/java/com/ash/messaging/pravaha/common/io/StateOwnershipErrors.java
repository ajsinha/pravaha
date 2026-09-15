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
package com.ash.messaging.pravaha.common.io;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Refusals raised when a node cannot establish that it owns the state it is about to write. */
public final class StateOwnershipErrors {

    /**
     * Another node owns this state directory, or another instance of this node is running.
     *
     * <p>In the 4xxx (state) range because that is what it is about, even though the check lives in
     * {@code pravaha-common} -- the two modules that need it, the registry's checkpoint root and the
     * server's journal, sit either side of {@code pravaha-state}.
     */
    public static final ErrorCode STATE_NOT_OURS = new ErrorCode(4003, "STATE_NOT_OURS");

    /** An ownership marker exists and cannot be read or written, so ownership cannot be established. */
    public static final ErrorCode OWNERSHIP_UNREADABLE = new ErrorCode(4004, "STATE_OWNERSHIP_UNREADABLE");

    private StateOwnershipErrors() {}
}
