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
package com.ash.messaging.pravaha.plugin.pgcdc;

import com.ash.messaging.pravaha.api.ErrorCode;

/** {@code postgres-cdc} error codes. Stable, documented, and never renumbered. */
public final class CdcErrors {

    /** An option is missing, malformed, or asks for something this source cannot do. */
    public static final ErrorCode BAD_CONFIGURATION = new ErrorCode(5110, "PGCDC_BAD_CONFIGURATION");

    /** The database could not be reached, or the driver is not on the classpath. */
    public static final ErrorCode CONNECT_FAILED = new ErrorCode(5111, "PGCDC_CONNECT_FAILED");

    /**
     * The server or the table cannot support change capture as configured: {@code wal_level} is not
     * {@code logical}, the table is not {@code REPLICA IDENTITY FULL}, the publication does not
     * publish updates and deletes, or the slot is missing, invalidated, or for another plugin. The
     * message names the statement that fixes it.
     */
    public static final ErrorCode NOT_CAPTURABLE = new ErrorCode(5112, "PGCDC_NOT_CAPTURABLE");

    /** The table's columns disagree with the declared schema, or a column's type is not mapped. */
    public static final ErrorCode SCHEMA_MISMATCH = new ErrorCode(5113, "PGCDC_SCHEMA_MISMATCH");

    /** A stored offset this plugin did not write. */
    public static final ErrorCode MALFORMED_OFFSET = new ErrorCode(5114, "PGCDC_MALFORMED_OFFSET");

    /**
     * The replication slot has confirmed past the position a restore asked to resume from, so
     * PostgreSQL has released changes the restored state has not seen.
     */
    public static final ErrorCode RESUME_POINT_RELEASED = new ErrorCode(5115, "PGCDC_RESUME_POINT_RELEASED");

    /**
     * The stream carried something this source refuses to turn into rows: a {@code TRUNCATE} of the
     * captured table, a key-only before-image, or a message it cannot decode.
     */
    public static final ErrorCode UNREPRESENTABLE_CHANGE = new ErrorCode(5116, "PGCDC_UNREPRESENTABLE_CHANGE");

    /** The replication stream failed and could not be resumed. */
    public static final ErrorCode STREAM_FAILED = new ErrorCode(5117, "PGCDC_STREAM_FAILED");

    /**
     * The initial snapshot could not be started or read: the temporary slot that pins its point in
     * the log was not created in time (a transaction open since before it blocks that), or the
     * snapshot's own connection or query failed.
     */
    public static final ErrorCode SNAPSHOT_FAILED = new ErrorCode(5118, "PGCDC_SNAPSHOT_FAILED");

    private CdcErrors() {}
}
