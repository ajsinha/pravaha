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
/**
 * The second layer: an OpenAI-style interface over the first ({@link com.ash.messaging.pravaha.ai}),
 * ported from Drishti's {@code com.ash.drishti.ai.llm} with its names. {@link
 * com.ash.messaging.pravaha.ai.llm.LLMFactory#model} hands out {@link
 * com.ash.messaging.pravaha.ai.llm.GovernedModel}s (every call through the policy and the gateway);
 * requests and answers are OpenAI's Chat Completions and Embeddings, typed ({@link
 * com.ash.messaging.pravaha.ai.llm.ChatCompletionRequest}, {@link
 * com.ash.messaging.pravaha.ai.llm.ChatCompletion}, {@link com.ash.messaging.pravaha.ai.llm.ChatCompletionChunk}),
 * with Drishti's extension field {@code drishti} named {@code pravaha} here. The Python SDK's
 * {@code pravaha.ai.llm} is the same design.
 */
@org.jspecify.annotations.NullMarked
package com.ash.messaging.pravaha.ai.llm;
