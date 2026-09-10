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
package com.ash.messaging.pravaha.backfill;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Backfill error codes, in the state range because a backfill's failures are all about held rows. */
public final class BackfillErrors {

    /** Changes accumulated during the snapshot faster than the buffer could hold them. */
    public static final ErrorCode BUFFER_FULL = new ErrorCode(4010, "BACKFILL_BUFFER_FULL");

    /** A row arrived with no version, so nothing can be said about whether it is newer. */
    public static final ErrorCode MISSING_VERSION = new ErrorCode(4011, "BACKFILL_MISSING_VERSION");

    /** A key column of a type the splice cannot compare. */
    public static final ErrorCode UNSUPPORTED_KEY = new ErrorCode(4012, "BACKFILL_UNSUPPORTED_KEY");

    /** A stored offset this reader did not write, or one from a different phase layout. */
    public static final ErrorCode MALFORMED_OFFSET = new ErrorCode(4013, "BACKFILL_MALFORMED_OFFSET");

    private BackfillErrors() {}
}
