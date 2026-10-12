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
package com.ash.messaging.pravaha.ai;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * The AI layer's codes, {@code PRV-1080} to {@code PRV-1087}: in the configuration and API range, beside
 * the REST API's own (1050-1056), because every one is either how the node is configured or what a
 * request to {@code /api/v1/ai} answers. One per {@link LlmException.Kind}, and one for the master switch.
 */
public final class AiErrors {

    private AiErrors() {}

    /** {@code pravaha.ai.enabled} is false: no provider file is read and no model is called. */
    public static final ErrorCode DISABLED = new ErrorCode(1080, "AI_DISABLED");

    /** A provider file or a provider's setup is wrong: a literal key, a missing base URL, a type not installed. */
    public static final ErrorCode CONFIG = new ErrorCode(1081, "AI_CONFIG");

    /** No model (or provider) by the name asked for, or several and none named. */
    public static final ErrorCode NOT_FOUND = new ErrorCode(1082, "AI_NOT_FOUND");

    /** The model cannot do what was asked: tools, JSON mode, chat on an embedding model. */
    public static final ErrorCode UNSUPPORTED = new ErrorCode(1083, "AI_UNSUPPORTED");

    /** The policy refused the call: the feature is not open to the caller's roles. */
    public static final ErrorCode POLICY = new ErrorCode(1084, "AI_POLICY");

    /** A per-user limit (per minute, per day, cost per day) or the vendor's own rate limit (HTTP 429). */
    public static final ErrorCode RATE_LIMITED = new ErrorCode(1085, "AI_RATE_LIMITED");

    /** The model endpoint did not answer in time. */
    public static final ErrorCode TIMEOUT = new ErrorCode(1086, "AI_TIMEOUT");

    /** The model endpoint failed or answered something unusable. */
    public static final ErrorCode FAILED = new ErrorCode(1087, "AI_FAILED");

    /** The code of a kind of failure. */
    public static ErrorCode of(LlmException.Kind kind) {
        return switch (kind) {
            case CONFIG -> CONFIG;
            case NOT_FOUND -> NOT_FOUND;
            case UNSUPPORTED -> UNSUPPORTED;
            case POLICY -> POLICY;
            case RATE_LIMITED -> RATE_LIMITED;
            case TIMEOUT -> TIMEOUT;
            case FAILED -> FAILED;
        };
    }
}
