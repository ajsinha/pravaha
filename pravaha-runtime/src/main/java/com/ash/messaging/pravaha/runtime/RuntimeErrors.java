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

import com.ash.messaging.pravaha.api.ErrorCode;

/** Runtime error codes. Stable, documented, and never renumbered. */
public final class RuntimeErrors {

    public static final ErrorCode ARENA_EXHAUSTED = new ErrorCode(3001, "RUNTIME_ARENA_EXHAUSTED");
    public static final ErrorCode BACKPRESSURED = new ErrorCode(3002, "RUNTIME_BACKPRESSURED");
    public static final ErrorCode LANE_FAILED = new ErrorCode(3010, "RUNTIME_LANE_FAILED");
    public static final ErrorCode UNSUPPORTED_AGGREGATE = new ErrorCode(3020, "RUNTIME_UNSUPPORTED_AGGREGATE");

    private RuntimeErrors() {}
}
