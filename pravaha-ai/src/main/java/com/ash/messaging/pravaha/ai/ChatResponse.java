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

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The answer: its text, any tool calls the model asked for, why it stopped, and what it cost.
 *
 * @param text the answer's text, never null
 * @param toolCalls the calls the model asked for
 * @param model the model that answered, as the provider named it
 * @param finishReason {@code stop}, {@code length}, {@code tool_calls}, {@code content_filter}, or the provider's own word
 * @param usage tokens and cost
 */
public record ChatResponse(String text, List<ToolCall> toolCalls, String model, String finishReason, Usage usage) {

    public ChatResponse(
            @Nullable String text,
            @Nullable List<ToolCall> toolCalls,
            @Nullable String model,
            @Nullable String finishReason,
            @Nullable Usage usage) {
        this.text = text == null ? "" : text;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.model = model == null ? "" : model;
        this.finishReason = finishReason == null ? "" : finishReason;
        this.usage = usage == null ? Usage.NONE : usage;
    }
}
