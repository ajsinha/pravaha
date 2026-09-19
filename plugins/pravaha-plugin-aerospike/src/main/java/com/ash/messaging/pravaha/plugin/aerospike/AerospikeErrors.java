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
package com.ash.messaging.pravaha.plugin.aerospike;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Aerospike plugin error codes. Stable, documented, and never renumbered. */
public final class AerospikeErrors {

    /** The cluster could not be reached, or refused the connection. */
    public static final ErrorCode CONNECT_FAILED = new ErrorCode(5080, "AEROSPIKE_CONNECT_FAILED");

    /** A scan, read or write failed. */
    public static final ErrorCode OPERATION_FAILED = new ErrorCode(5081, "AEROSPIKE_OPERATION_FAILED");

    /** A bin holds a type this plugin will not guess at. */
    public static final ErrorCode UNSUPPORTED_TYPE = new ErrorCode(5082, "AEROSPIKE_UNSUPPORTED_TYPE");

    /** A configuration that cannot be honoured -- including a strategy this build cannot run. */
    public static final ErrorCode BAD_CONFIGURATION = new ErrorCode(5083, "AEROSPIKE_BAD_CONFIGURATION");

    /** A stored offset this plugin did not write. */
    public static final ErrorCode MALFORMED_OFFSET = new ErrorCode(5084, "AEROSPIKE_MALFORMED_OFFSET");

    /**
     * With {@code deletes: detect}, a partition holds more rows than {@code deletes.max.keys}.
     * Numbered outside 5080-5084 because that range is full.
     */
    public static final ErrorCode DELETE_STATE_FULL = new ErrorCode(5120, "AEROSPIKE_DELETE_STATE_FULL");

    /** With {@code deletes: detect}, the remembered rows could not be written, or a restore could not read them back. */
    public static final ErrorCode DELETE_STATE_FAILED = new ErrorCode(5121, "AEROSPIKE_DELETE_STATE_FAILED");

    private AerospikeErrors() {}
}
