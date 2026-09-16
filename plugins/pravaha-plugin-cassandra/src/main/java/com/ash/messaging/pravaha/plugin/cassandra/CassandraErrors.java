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
package com.ash.messaging.pravaha.plugin.cassandra;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Cassandra plugin error codes. Stable, documented, and never renumbered. */
public final class CassandraErrors {

    /** The cluster could not be reached, or refused the connection. */
    public static final ErrorCode CONNECT_FAILED = new ErrorCode(5085, "CASSANDRA_CONNECT_FAILED");

    /** A CQL statement failed against the server. */
    public static final ErrorCode OPERATION_FAILED = new ErrorCode(5086, "CASSANDRA_OPERATION_FAILED");

    /** A column holds a CQL type this plugin will not guess at. */
    public static final ErrorCode UNSUPPORTED_TYPE = new ErrorCode(5087, "CASSANDRA_UNSUPPORTED_TYPE");

    /** A configuration that cannot be honoured -- including a strategy this build cannot run. */
    public static final ErrorCode BAD_CONFIGURATION = new ErrorCode(5088, "CASSANDRA_BAD_CONFIGURATION");

    /** A stored offset this plugin did not write. */
    public static final ErrorCode MALFORMED_OFFSET = new ErrorCode(5089, "CASSANDRA_MALFORMED_OFFSET");

    private CassandraErrors() {}
}
