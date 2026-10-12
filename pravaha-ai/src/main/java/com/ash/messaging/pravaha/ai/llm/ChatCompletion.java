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
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.ai.llm.Canonical.Choice;
import com.ash.messaging.pravaha.ai.llm.Canonical.CompletionUsage;
import com.ash.messaging.pravaha.ai.llm.Canonical.ResponsePravaha;
import com.ash.messaging.pravaha.ai.llm.Canonical.ToolCall;

/**
 * A chat completion, typed: OpenAI's response plus the {@code pravaha} extension.
 *
 * @param id its id
 * @param object always {@code chat.completion}
 * @param created seconds since the epoch
 * @param model the model that answered
 * @param choices the choices
 * @param usage tokens
 * @param pravaha who answered, what it cost, how long it took
 */
public record ChatCompletion(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        @Nullable CompletionUsage usage,
        @Nullable ResponsePravaha pravaha) {

    public ChatCompletion(
            @Nullable String id,
            @Nullable String object,
            long created,
            String model,
            @Nullable List<Choice> choices,
            @Nullable CompletionUsage usage,
            @Nullable ResponsePravaha pravaha) {
        this.id = id == null || id.isBlank()
                ? "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24)
                : id;
        this.object = object == null ? "chat.completion" : object;
        this.created = created;
        this.model = model;
        this.choices = choices == null ? List.of() : List.copyOf(choices);
        this.usage = usage;
        this.pravaha = pravaha;
    }

    /** The first choice's message text. */
    public String text() {
        return choices.isEmpty() ? "" : choices.get(0).message().text();
    }

    /** The first choice's tool calls. */
    public List<ToolCall> toolCalls() {
        if (choices.isEmpty()) {
            return List.of();
        }
        List<ToolCall> calls = choices.get(0).message().toolCalls();
        return calls == null ? List.of() : calls;
    }

    /** Why the first choice stopped. */
    public String finishReason() {
        if (choices.isEmpty()) {
            return "stop";
        }
        String f = choices.get(0).finishReason();
        return f == null ? "stop" : f;
    }

    /** The same completion with another extension. */
    public ChatCompletion withPravaha(@Nullable ResponsePravaha d) {
        return new ChatCompletion(id, object, created, model, choices, usage, d);
    }
}
