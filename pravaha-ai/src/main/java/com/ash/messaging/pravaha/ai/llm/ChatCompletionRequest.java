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
package com.ash.messaging.pravaha.ai.llm;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.ai.llm.Canonical.ChatMessage;
import com.ash.messaging.pravaha.ai.llm.Canonical.PravahaRequest;
import com.ash.messaging.pravaha.ai.llm.Canonical.ResponseFormat;
import com.ash.messaging.pravaha.ai.llm.Canonical.ToolDefinition;

/**
 * POST /v1/chat/completions, typed. The fields are OpenAI's, in camelCase; {@code pravaha} is the
 * extension. {@code stream}, {@code n} and the sampling controls are optional (null: the provider's default).
 *
 * @param model the model, {@code default} for the layer's
 * @param messages the conversation
 * @param tools functions the model may call
 * @param toolChoice auto, none, required or a function's name
 * @param parallelToolCalls whether several calls may come at once
 * @param responseFormat text or JSON
 * @param temperature sampling temperature
 * @param topP nucleus sampling
 * @param maxCompletionTokens answer limit
 * @param maxTokens answer limit (the older name)
 * @param stop stop sequences
 * @param seed a seed
 * @param n how many choices
 * @param stream whether to stream
 * @param user the end user, for the vendor's logs
 * @param metadata free metadata
 * @param reasoningEffort low, medium or high
 * @param extraBody provider-specific fields
 * @param pravaha the extension: the caller, a trace id
 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        @Nullable List<ToolDefinition> tools,
        @Nullable String toolChoice,
        @Nullable Boolean parallelToolCalls,
        @Nullable ResponseFormat responseFormat,
        @Nullable Double temperature,
        @Nullable Double topP,
        @Nullable Integer maxCompletionTokens,
        @Nullable Integer maxTokens,
        @Nullable List<String> stop,
        @Nullable Integer seed,
        @Nullable Integer n,
        @Nullable Boolean stream,
        @Nullable String user,
        @Nullable Map<String, String> metadata,
        @Nullable String reasoningEffort,
        @Nullable Map<String, Object> extraBody,
        @Nullable PravahaRequest pravaha) {

    public ChatCompletionRequest(
            @Nullable String model,
            @Nullable List<ChatMessage> messages,
            @Nullable List<ToolDefinition> tools,
            @Nullable String toolChoice,
            @Nullable Boolean parallelToolCalls,
            @Nullable ResponseFormat responseFormat,
            @Nullable Double temperature,
            @Nullable Double topP,
            @Nullable Integer maxCompletionTokens,
            @Nullable Integer maxTokens,
            @Nullable List<String> stop,
            @Nullable Integer seed,
            @Nullable Integer n,
            @Nullable Boolean stream,
            @Nullable String user,
            @Nullable Map<String, String> metadata,
            @Nullable String reasoningEffort,
            @Nullable Map<String, Object> extraBody,
            @Nullable PravahaRequest pravaha) {
        this.model = model == null || model.isBlank() ? "default" : model;
        this.messages = messages == null ? List.of() : List.copyOf(messages);
        this.tools = tools;
        this.toolChoice = toolChoice;
        this.parallelToolCalls = parallelToolCalls;
        this.responseFormat = responseFormat;
        this.temperature = temperature;
        this.topP = topP;
        this.maxCompletionTokens = maxCompletionTokens;
        this.maxTokens = maxTokens;
        this.stop = stop;
        this.seed = seed;
        this.n = n;
        this.stream = stream;
        this.user = user;
        this.metadata = metadata;
        this.reasoningEffort = reasoningEffort;
        this.extraBody = extraBody;
        this.pravaha = pravaha;
    }

    /** A request of messages. */
    public static ChatCompletionRequest of(@Nullable String model, List<ChatMessage> messages) {
        return new ChatCompletionRequest(
                model, messages, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /** A system instruction (may be empty) and one user turn. */
    public static ChatCompletionRequest of(@Nullable String model, @Nullable String system, String user) {
        return of(
                model,
                system == null || system.isEmpty()
                        ? List.of(ChatMessage.user(user))
                        : List.of(ChatMessage.system(system), ChatMessage.user(user)));
    }

    /** The request built from keyword fields: keys are the record's field names. */
    @SuppressWarnings("unchecked")
    public static ChatCompletionRequest coerce(Map<String, Object> f) {
        return new ChatCompletionRequest(
                (String) f.get("model"),
                (List<ChatMessage>) f.get("messages"),
                (List<ToolDefinition>) f.get("tools"),
                (String) f.get("toolChoice"),
                (Boolean) f.get("parallelToolCalls"),
                (ResponseFormat) f.get("responseFormat"),
                (Double) f.get("temperature"),
                (Double) f.get("topP"),
                (Integer) f.get("maxCompletionTokens"),
                (Integer) f.get("maxTokens"),
                (List<String>) f.get("stop"),
                (Integer) f.get("seed"),
                (Integer) f.get("n"),
                (Boolean) f.get("stream"),
                (String) f.get("user"),
                (Map<String, String>) f.get("metadata"),
                (String) f.get("reasoningEffort"),
                (Map<String, Object>) f.get("extraBody"),
                (PravahaRequest) f.get("pravaha"));
    }

    /** The same request with other messages. */
    public ChatCompletionRequest withMessages(List<ChatMessage> m) {
        return new ChatCompletionRequest(
                model,
                m,
                tools,
                toolChoice,
                parallelToolCalls,
                responseFormat,
                temperature,
                topP,
                maxCompletionTokens,
                maxTokens,
                stop,
                seed,
                n,
                stream,
                user,
                metadata,
                reasoningEffort,
                extraBody,
                pravaha);
    }

    /** The same request with another extension. */
    public ChatCompletionRequest withPravaha(@Nullable PravahaRequest d) {
        return new ChatCompletionRequest(
                model,
                messages,
                tools,
                toolChoice,
                parallelToolCalls,
                responseFormat,
                temperature,
                topP,
                maxCompletionTokens,
                maxTokens,
                stop,
                seed,
                n,
                stream,
                user,
                metadata,
                reasoningEffort,
                extraBody,
                d);
    }

    /** The same request for another model. */
    public ChatCompletionRequest withModel(@Nullable String m) {
        return new ChatCompletionRequest(
                m,
                messages,
                tools,
                toolChoice,
                parallelToolCalls,
                responseFormat,
                temperature,
                topP,
                maxCompletionTokens,
                maxTokens,
                stop,
                seed,
                n,
                stream,
                user,
                metadata,
                reasoningEffort,
                extraBody,
                pravaha);
    }

    /** {@code maxCompletionTokens}, else {@code maxTokens}, else null. */
    public @Nullable Integer maxOutputTokens() {
        return maxCompletionTokens != null ? maxCompletionTokens : maxTokens;
    }

    /** Whether tools are offered. */
    public boolean wantsTools() {
        return tools != null && !tools.isEmpty();
    }
}
