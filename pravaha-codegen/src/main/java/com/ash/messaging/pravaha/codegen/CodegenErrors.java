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
package com.ash.messaging.pravaha.codegen;

import com.ash.messaging.pravaha.api.ErrorCode;

/** Code-generation error codes. Stable and never renumbered. */
public final class CodegenErrors {

    public static final ErrorCode COMPILATION_FAILED = new ErrorCode(3100, "CODEGEN_COMPILATION_FAILED");
    public static final ErrorCode UNSUPPORTED = new ErrorCode(3101, "CODEGEN_UNSUPPORTED_OPERATOR");
    public static final ErrorCode STAGE_TOO_LARGE = new ErrorCode(3102, "CODEGEN_STAGE_TOO_LARGE");

    private CodegenErrors() {}
}
