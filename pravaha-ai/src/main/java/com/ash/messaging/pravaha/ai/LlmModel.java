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

/** A chat model of one provider: implementations translate a {@link ChatRequest} to their wire format and back. */
public interface LlmModel {

    /** The model as configured. */
    ModelInfo info();

    /**
     * One answer.
     *
     * @throws LlmException when the endpoint fails, is slow, or answers something that is not usable
     */
    ChatResponse chat(ChatRequest request);
}
