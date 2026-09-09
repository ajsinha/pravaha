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
package com.ash.messaging.pravaha.sql;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Planning error codes. Stable, documented, and never renumbered. */
public final class SqlErrors {

    public static final ErrorCode PARSE_FAILED = new ErrorCode(2001, "SQL_PARSE_FAILED");
    public static final ErrorCode VALIDATION_FAILED = new ErrorCode(2002, "SQL_VALIDATION_FAILED");
    public static final ErrorCode UNKNOWN_STREAM = new ErrorCode(2003, "SQL_UNKNOWN_STREAM");
    public static final ErrorCode PLANNING_FAILED = new ErrorCode(2010, "SQL_PLANNING_FAILED");
    public static final ErrorCode UNSUPPORTED_OPERATOR = new ErrorCode(2020, "SQL_UNSUPPORTED_OPERATOR");
    public static final ErrorCode UNSUPPORTED_EXPRESSION = new ErrorCode(2021, "SQL_UNSUPPORTED_EXPRESSION");
    /** The mismatch design section 15.5 exists to catch at registration rather than in production. */
    public static final ErrorCode EMIT_MODE_MISMATCH = new ErrorCode(2041, "SQL_EMIT_MODE_MISMATCH");

    public static final ErrorCode UNBOUNDED_STATE = new ErrorCode(2050, "SQL_UNBOUNDED_STATE");

    private SqlErrors() {}
}
