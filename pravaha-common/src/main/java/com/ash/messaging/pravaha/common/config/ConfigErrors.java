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
package com.ash.messaging.pravaha.common.config;

import com.ash.messaging.pravaha.api.ErrorCode;

/** The configuration error codes. Stable, documented, and never renumbered. */
public final class ConfigErrors {

    public static final ErrorCode FILE_UNREADABLE = new ErrorCode(1001, "CONFIG_FILE_UNREADABLE");
    public static final ErrorCode FILE_MALFORMED = new ErrorCode(1002, "CONFIG_FILE_MALFORMED");
    public static final ErrorCode UNRESOLVED_REFERENCE = new ErrorCode(1010, "CONFIG_UNRESOLVED_REFERENCE");
    public static final ErrorCode CIRCULAR_REFERENCE = new ErrorCode(1011, "CONFIG_CIRCULAR_REFERENCE");
    public static final ErrorCode MISSING_REQUIRED = new ErrorCode(1020, "CONFIG_MISSING_REQUIRED");
    public static final ErrorCode NOT_A_NUMBER = new ErrorCode(1021, "CONFIG_NOT_A_NUMBER");
    public static final ErrorCode NOT_A_BOOLEAN = new ErrorCode(1022, "CONFIG_NOT_A_BOOLEAN");
    public static final ErrorCode NOT_A_DURATION = new ErrorCode(1023, "CONFIG_NOT_A_DURATION");
    public static final ErrorCode NOT_A_DATA_SIZE = new ErrorCode(1024, "CONFIG_NOT_A_DATA_SIZE");
    public static final ErrorCode NOT_AN_ENUM = new ErrorCode(1025, "CONFIG_NOT_AN_ENUM");
    public static final ErrorCode OUT_OF_RANGE = new ErrorCode(1026, "CONFIG_OUT_OF_RANGE");

    private ConfigErrors() {}
}
