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
package com.ash.messaging.pravaha.plugin.jdbc;

import com.ash.messaging.pravaha.api.ErrorCode;

/** JDBC plugin error codes. Stable, documented, and never renumbered. */
public final class JdbcErrors {

    /** The database could not be reached, or the credentials were refused. */
    public static final ErrorCode CONNECT_FAILED = new ErrorCode(5070, "JDBC_CONNECT_FAILED");

    /** A query failed, or a result could not be read. */
    public static final ErrorCode QUERY_FAILED = new ErrorCode(5071, "JDBC_QUERY_FAILED");

    /** A SQL type this plugin does not map to a Pravaha type. Names the column. */
    public static final ErrorCode UNSUPPORTED_TYPE = new ErrorCode(5072, "JDBC_UNSUPPORTED_TYPE");

    /** A stored offset this plugin did not write. */
    public static final ErrorCode MALFORMED_OFFSET = new ErrorCode(5073, "JDBC_MALFORMED_OFFSET");

    /** A configuration that cannot be honoured. */
    public static final ErrorCode BAD_CONFIGURATION = new ErrorCode(5074, "JDBC_BAD_CONFIGURATION");

    private JdbcErrors() {}
}
