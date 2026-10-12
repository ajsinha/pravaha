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

/**
 * Tokens used by one call and what they cost under the model's configured prices (0 when none are configured).
 *
 * @param promptTokens tokens the prompt counted
 * @param completionTokens tokens the answer counted
 * @param cost the money, in the unit the model's prices are written in
 */
public record Usage(int promptTokens, int completionTokens, double cost) {

    /** No usage. */
    public static final Usage NONE = new Usage(0, 0, 0);

    /** Prompt and answer together. */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
