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
package com.ash.messaging.pravaha.plugin.iceberg;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Iceberg plugin error codes. Stable, documented, and never renumbered. */
public final class IcebergErrors {

    /**
     * An {@code iceberg-sink} binding that cannot be honoured as written: no {@code path}, a schema
     * that does not parse or has a type Iceberg cannot hold, an unknown mode, a bad key.
     */
    public static final ErrorCode SINK_BAD_CONFIGURATION = new ErrorCode(5140, "ICEBERG_SINK_BAD_CONFIGURATION");

    /** The Iceberg table at {@code path} is not the table the binding's {@code schema} describes. */
    public static final ErrorCode SINK_TABLE_MISMATCH = new ErrorCode(5141, "ICEBERG_SINK_TABLE_MISMATCH");

    /** Writing, staging or committing a sink's files failed, or a value cannot be stored exactly. */
    public static final ErrorCode SINK_WRITE_FAILED = new ErrorCode(5142, "ICEBERG_SINK_WRITE_FAILED");

    private IcebergErrors() {}
}
