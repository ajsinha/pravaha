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
package com.ash.messaging.pravaha.plugin.mysqlcdc;

import com.ash.messaging.pravaha.api.ErrorCode;

/** {@code mysql-cdc} error codes. Stable, documented, and never renumbered. */
public final class MySqlCdcErrors {

    /** An option is missing, malformed, or asks for something this source cannot do yet. */
    public static final ErrorCode BAD_CONFIGURATION = new ErrorCode(5150, "MYCDC_BAD_CONFIGURATION");

    /** The server could not be reached, or refused the credentials. */
    public static final ErrorCode CONNECT_FAILED = new ErrorCode(5151, "MYCDC_CONNECT_FAILED");

    /**
     * The server cannot support change capture: the binary log is off, {@code binlog_format} is not
     * {@code ROW}, {@code binlog_row_image} is not {@code FULL}, transaction compression is on, or the
     * user lacks {@code REPLICATION SLAVE} or {@code REPLICATION CLIENT}. The message names the fix.
     */
    public static final ErrorCode NOT_CAPTURABLE = new ErrorCode(5152, "MYCDC_NOT_CAPTURABLE");

    /** The table is missing, or one of its columns has a type this source does not map. */
    public static final ErrorCode SCHEMA_MISMATCH = new ErrorCode(5153, "MYCDC_SCHEMA_MISMATCH");

    /** A stored offset this plugin did not write. */
    public static final ErrorCode MALFORMED_OFFSET = new ErrorCode(5154, "MYCDC_MALFORMED_OFFSET");

    /**
     * The binlog file a restore asks to resume from has been purged from the server; for a GTID
     * position, transactions after it have been.
     */
    public static final ErrorCode RESUME_POINT_PURGED = new ErrorCode(5155, "MYCDC_RESUME_POINT_PURGED");

    /**
     * The binary log carried something this source refuses to turn into rows: a {@code TRUNCATE} or
     * a change of shape of the captured table, or an event it cannot decode.
     */
    public static final ErrorCode UNREPRESENTABLE_CHANGE = new ErrorCode(5156, "MYCDC_UNREPRESENTABLE_CHANGE");

    /** The binlog stream failed and reconnecting did not bring it back. */
    public static final ErrorCode STREAM_FAILED = new ErrorCode(5157, "MYCDC_STREAM_FAILED");

    /**
     * A GTID checkpoint holds transactions the server has not executed: a replica that has not caught
     * up with the server the checkpoint was read from, or another server altogether.
     */
    public static final ErrorCode RESUME_POINT_AHEAD = new ErrorCode(5158, "MYCDC_RESUME_POINT_AHEAD");

    private MySqlCdcErrors() {}
}
