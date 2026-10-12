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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.ai.Capability;
import com.ash.messaging.pravaha.ai.ChatRequest;
import com.ash.messaging.pravaha.ai.ChatResponse;
import com.ash.messaging.pravaha.ai.ToolSpec;
import com.ash.messaging.pravaha.ai.llm.Canonical.ChatMessage;
import com.ash.messaging.pravaha.ai.llm.Canonical.Choice;
import com.ash.messaging.pravaha.ai.llm.Canonical.CompletionUsage;
import com.ash.messaging.pravaha.ai.llm.Canonical.FunctionDefinition;
import com.ash.messaging.pravaha.ai.llm.Canonical.ResponsePravaha;
import com.ash.messaging.pravaha.ai.llm.Canonical.ToolCall;

/** Canonical types to and from the wire-level types the providers speak ({@link ChatRequest}, {@link ChatResponse}). One place, so a provider is written once. */
public final class Convert {

    private Convert() {}

    /** The wire-level request for a canonical one. {@code model} is the reference the registry resolves, or null for the default. */
    public static ChatRequest toLegacy(ChatCompletionRequest r, @Nullable String model, @Nullable Duration timeout) {
        List<com.ash.messaging.pravaha.ai.ChatMessage> msgs = new ArrayList<>();
        for (ChatMessage m : r.messages()) {
            String role = m.role() == null ? "user" : m.role();
            com.ash.messaging.pravaha.ai.ChatMessage.Role lr =
                    switch (role) {
                        case "system", "developer" -> com.ash.messaging.pravaha.ai.ChatMessage.Role.SYSTEM;
                        case "assistant" -> com.ash.messaging.pravaha.ai.ChatMessage.Role.ASSISTANT;
                        case "tool" -> com.ash.messaging.pravaha.ai.ChatMessage.Role.TOOL;
                        default -> com.ash.messaging.pravaha.ai.ChatMessage.Role.USER;
                    };
            msgs.add(new com.ash.messaging.pravaha.ai.ChatMessage(lr, m.text()));
        }
        List<ToolSpec> tools = new ArrayList<>();
        List<Canonical.ToolDefinition> defined = r.tools();
        if (defined != null) {
            for (Canonical.ToolDefinition t : defined) {
                FunctionDefinition f = t.function();
                String description = f == null ? null : f.description();
                String parameters = f == null ? null : f.parameters();
                tools.add(new ToolSpec(
                        t.name(),
                        description == null ? "" : description,
                        parameters == null ? "{\"type\":\"object\"}" : parameters));
            }
        }
        Canonical.ResponseFormat format = r.responseFormat();
        boolean json = format != null && !"text".equals(format.type());
        return new ChatRequest(model, "", msgs, tools, r.temperature(), r.maxOutputTokens(), json, timeout);
    }

    /** The same request with the masked prompt a policy returned: the message texts replace the canonical ones, position by position. */
    public static ChatCompletionRequest withTexts(ChatCompletionRequest r, ChatRequest masked) {
        List<ChatMessage> out = new ArrayList<>();
        for (int i = 0; i < r.messages().size(); i++) {
            ChatMessage m = r.messages().get(i);
            String t = i < masked.messages().size() ? masked.messages().get(i).content() : m.text();
            out.add(new ChatMessage(m.role(), t, m.name(), m.toolCalls(), m.toolCallId(), m.refusal()));
        }
        return r.withMessages(out);
    }

    /** A wire-level answer as a canonical completion. */
    public static ChatCompletion fromLegacy(ChatResponse r, com.ash.messaging.pravaha.ai.ModelInfo info, long millis) {
        List<ToolCall> calls = r.toolCalls().stream()
                .map(c -> ToolCall.of(c.id(), c.name(), c.argumentsJson()))
                .toList();
        ChatMessage msg = ChatMessage.assistant(r.text(), calls.isEmpty() ? null : calls);
        String finish = r.finishReason().isBlank() ? (calls.isEmpty() ? "stop" : "tool_calls") : r.finishReason();
        return new ChatCompletion(
                null,
                null,
                System.currentTimeMillis() / 1000,
                r.model().isBlank() ? info.id() : r.model(),
                List.of(new Choice(0, msg, finish)),
                CompletionUsage.of(r.usage().promptTokens(), r.usage().completionTokens()),
                new ResponsePravaha(info.provider(), info.ref(), r.usage().cost(), false, millis, ""));
    }

    /** A wire-level model as a canonical one. */
    public static ModelInfo info(com.ash.messaging.pravaha.ai.ModelInfo m) {
        Set<Capability> c = m.capabilities();
        Integer max = m.maxTokens();
        ModelCapabilities caps = new ModelCapabilities(
                c.contains(Capability.CHAT),
                c.contains(Capability.TOOL_CALLS),
                c.contains(Capability.JSON_MODE),
                false,
                c.contains(Capability.STREAMING),
                c.contains(Capability.EMBEDDINGS),
                0,
                max == null ? 4096 : max,
                m.costPer1kInput() * 1000,
                m.costPer1kOutput() * 1000,
                0,
                true,
                true,
                new HashSet<>(),
                c.contains(Capability.JSON_MODE) ? Boolean.TRUE : null);
        return new ModelInfo(m.name(), m.provider(), m.kind().toLowerCase(Locale.ROOT), caps, m.name(), "config");
    }
}
