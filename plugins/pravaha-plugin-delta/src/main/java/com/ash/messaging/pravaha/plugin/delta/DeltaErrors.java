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
package com.ash.messaging.pravaha.plugin.delta;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Delta plugin error codes. Stable, documented, and never renumbered. */
public final class DeltaErrors {

    /** The table is not there, or the path is not a Delta table at all. */
    public static final ErrorCode TABLE_UNREADABLE = new ErrorCode(5050, "DELTA_TABLE_UNREADABLE");

    /** A Delta column type this plugin does not map. Named, with the column, never silently dropped. */
    public static final ErrorCode UNSUPPORTED_TYPE = new ErrorCode(5051, "DELTA_UNSUPPORTED_TYPE");

    /** A stored offset that this plugin did not write, or wrote in an older format. */
    public static final ErrorCode MALFORMED_OFFSET = new ErrorCode(5052, "DELTA_MALFORMED_OFFSET");

    /** A file the log still references has been removed from disk -- typically by {@code VACUUM}. */
    public static final ErrorCode FILE_VACUUMED = new ErrorCode(5053, "DELTA_FILE_VACUUMED");

    /** Reading the table failed for a reason Kernel reported. */
    public static final ErrorCode READ_FAILED = new ErrorCode(5054, "DELTA_READ_FAILED");

    /** A table feature whose semantics this plugin cannot honour, such as deletion vectors. */
    public static final ErrorCode UNSUPPORTED_FEATURE = new ErrorCode(5055, "DELTA_UNSUPPORTED_FEATURE");

    private DeltaErrors() {}
}
