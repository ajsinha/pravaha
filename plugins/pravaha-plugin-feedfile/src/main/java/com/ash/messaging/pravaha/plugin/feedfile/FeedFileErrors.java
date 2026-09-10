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
package com.ash.messaging.pravaha.plugin.feedfile;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Feed-file plugin error codes. Stable, documented, and never renumbered. */
public final class FeedFileErrors {

    /** The feed directory is missing or unreadable. */
    public static final ErrorCode DIRECTORY_UNREADABLE = new ErrorCode(5060, "FEEDFILE_DIRECTORY_UNREADABLE");

    /** A schema specification that cannot be parsed. */
    public static final ErrorCode BAD_SCHEMA = new ErrorCode(5061, "FEEDFILE_BAD_SCHEMA");

    /** A record that does not match the declared schema. Names the file and the line. */
    public static final ErrorCode DECODE_FAILED = new ErrorCode(5062, "FEEDFILE_DECODE_FAILED");

    /** A stored offset this plugin did not write, or wrote in an older format. */
    public static final ErrorCode MALFORMED_OFFSET = new ErrorCode(5063, "FEEDFILE_MALFORMED_OFFSET");

    /** A file named by an offset is no longer in the feed directory. */
    public static final ErrorCode FILE_GONE = new ErrorCode(5064, "FEEDFILE_FILE_GONE");

    /** A configuration combination that cannot be honoured. */
    public static final ErrorCode BAD_CONFIGURATION = new ErrorCode(5065, "FEEDFILE_BAD_CONFIGURATION");

    private FeedFileErrors() {}
}
