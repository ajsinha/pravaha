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

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A chat call, independent of any provider. {@code model} is a logical name ({@code provider/model} or a
 * unique model name; null is the registry's default); null numbers mean the model's configured defaults.
 *
 * @param model the logical model, or null for the default
 * @param system the fixed instructions (also accepted as a {@code SYSTEM} message)
 * @param messages the conversation, oldest first
 * @param tools functions the model may ask for (needs the TOOL_CALLS capability)
 * @param temperature the sampling temperature, or null for the model's
 * @param maxTokens the most the answer may be, or null for the model's
 * @param jsonMode ask for a JSON object answer (needs the JSON_MODE capability)
 * @param timeout how long to wait for the whole answer; null is the provider's configured timeout
 */
public record ChatRequest(
        @Nullable String model,
        String system,
        List<ChatMessage> messages,
        List<ToolSpec> tools,
        @Nullable Double temperature,
        @Nullable Integer maxTokens,
        boolean jsonMode,
        @Nullable Duration timeout) {

    public ChatRequest(
            @Nullable String model,
            @Nullable String system,
            @Nullable List<ChatMessage> messages,
            @Nullable List<ToolSpec> tools,
            @Nullable Double temperature,
            @Nullable Integer maxTokens,
            boolean jsonMode,
            @Nullable Duration timeout) {
        this.model = model;
        this.system = system == null ? "" : system;
        this.messages = messages == null ? List.of() : List.copyOf(messages);
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.jsonMode = jsonMode;
        this.timeout = timeout;
    }

    /** A system instruction and one user turn. */
    public static ChatRequest of(
            @Nullable String model,
            @Nullable String system,
            String user,
            @Nullable Integer maxTokens,
            @Nullable Duration timeout) {
        return new ChatRequest(
                model, system, List.of(ChatMessage.user(user)), List.of(), null, maxTokens, false, timeout);
    }

    /** The same request for another model. */
    public ChatRequest withModel(@Nullable String m) {
        return new ChatRequest(m, system, messages, tools, temperature, maxTokens, jsonMode, timeout);
    }

    /** The same request with other text (what a masking policy returns). */
    public ChatRequest withText(String newSystem, List<ChatMessage> newMessages) {
        return new ChatRequest(model, newSystem, newMessages, tools, temperature, maxTokens, jsonMode, timeout);
    }

    /** All text the prompt carries, for size decisions and the audit row. */
    public int promptChars() {
        int n = system.length();
        for (ChatMessage m : messages) {
            n += m.content().length();
        }
        return n;
    }
}
