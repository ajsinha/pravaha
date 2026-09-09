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
package com.ash.messaging.pravaha.connect;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Plugin subsystem error codes. Stable and never renumbered. */
public final class PluginErrors {

    public static final ErrorCode MISSING_SETTING = new ErrorCode(5001, "PLUGIN_MISSING_SETTING");
    public static final ErrorCode NOT_FOUND = new ErrorCode(5010, "PLUGIN_NOT_FOUND");
    public static final ErrorCode INCOMPATIBLE_API = new ErrorCode(5011, "PLUGIN_INCOMPATIBLE_API");
    public static final ErrorCode LOAD_FAILED = new ErrorCode(5012, "PLUGIN_LOAD_FAILED");
    public static final ErrorCode DUPLICATE_NAME = new ErrorCode(5013, "PLUGIN_DUPLICATE_NAME");
    public static final ErrorCode CIRCUIT_OPEN = new ErrorCode(5020, "PLUGIN_CIRCUIT_OPEN");
    public static final ErrorCode CAPABILITY_MISMATCH = new ErrorCode(5030, "PLUGIN_CAPABILITY_MISMATCH");

    private PluginErrors() {}
}
