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
package com.ash.messaging.pravaha.server.ai.features;

import com.ash.messaging.pravaha.api.ErrorCode;

/**
 * The plain-English features' own code, beside the AI layer's {@code PRV-1080}..{@code 1087}: Drishti's
 * {@code DRS-4020}, "the model's answer could not be used". A draft, a query or an alert the engine still
 * refuses after one repair turn, or an answer that is not one at all, is refused with it rather than shown
 * or guessed at. Nothing was registered, created or changed.
 */
public final class AiFeatureErrors {

    private AiFeatureErrors() {}

    /** The model's answer could not be used: refused twice by the engine, or not an answer of the shape asked for. */
    public static final ErrorCode ANSWER_UNUSABLE = new ErrorCode(1088, "AI_ANSWER_UNUSABLE");
}
