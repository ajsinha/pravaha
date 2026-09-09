/*
 * Project Pravaha -- Ask once. Answer always.
 *
 * Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
