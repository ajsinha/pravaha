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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code type: anthropic}: the Anthropic Messages API over the JDK HTTP client. Settings: {@code base-url}
 * (default https://api.anthropic.com), {@code messages-path} (/v1/messages), {@code anthropic-version}
 * (2023-06-01). Drishti's ran its own HTTP code and answered every status as {@code FAILED}; here it is a
 * {@link HttpProvider} like every other type, so a 429 is {@code RATE_LIMITED}, 429 and 5xx are retried
 * {@code retries} times, and {@code proxy.*} applies.
 */
public final class AnthropicProvider extends HttpProvider {

    private final String messagesPath;

    AnthropicProvider(ProviderConfig c) {
        super(c, c.baseUrl().isEmpty() ? "https://api.anthropic.com" : c.baseUrl(), Set.of(Capability.TOOL_CALLS));
        this.messagesPath = c.setting("messages-path", "/v1/messages");
    }

    private Map<String, String> auth() {
        String version = config.setting("anthropic-version", "2023-06-01");
        return config.apiKey().isEmpty()
                ? Map.of("anthropic-version", version)
                : Map.of("anthropic-version", version, "x-api-key", config.apiKey());
    }

    @Override
    public Readiness check() {
        return config.apiKey().isEmpty()
                ? new Readiness(false, "no api key: set api-key: ${ANTHROPIC_API_KEY}")
                : new Readiness(true, "configured; no call is made to find out");
    }

    @Override
    protected ChatResponse chat(ModelInfo info, ChatRequest r) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        body.put("model", info.id());
        body.put("max_tokens", maxTokens(r, info, 1024));
        Double t = temperature(r, info);
        if (t != null) {
            body.put("temperature", t);
        }
        String sys = systemText(r);
        if (!sys.isEmpty()) {
            body.put("system", sys);
        }
        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : turns(r)) {
            msgs.addObject().put("role", assistant(m) ? "assistant" : "user").put("content", m.content());
        }
        if (!r.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec s : r.tools()) {
                String description = s.description();
                ObjectNode o = tools.addObject();
                o.put("name", s.name()).put("description", description == null ? "" : description);
                o.set("input_schema", schema(s));
            }
        }
        JsonNode j = http.call("POST", http.base() + messagesPath, body, auth(), timeout(r));
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : j.path("content")) {
            String type = block.path("type").asText();
            if ("text".equals(type) && block.path("text").isTextual()) {
                text.append(block.path("text").asText());
            } else if ("tool_use".equals(type)) {
                calls.add(new ToolCall(
                        block.path("id").asText(), block.path("name").asText(), compact(block.path("input"))));
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint's answer has no text content");
        }
        int in = j.path("usage").path("input_tokens").asInt(0);
        int out = j.path("usage").path("output_tokens").asInt(0);
        String stop = j.path("stop_reason").asText("");
        String finish =
                switch (stop) {
                    case "end_turn", "stop_sequence" -> "stop";
                    case "max_tokens" -> "length";
                    case "tool_use" -> "tool_calls";
                    default -> stop;
                };
        return new ChatResponse(
                text.toString(),
                calls,
                j.path("model").asText(info.id()),
                finish,
                new Usage(in, out, info.cost(in, out)));
    }

    /** {@code type: anthropic}. */
    public static final class Factory implements ProviderFactory {
        @Override
        public String type() {
            return "anthropic";
        }

        @Override
        public LlmProvider create(ProviderConfig config) {
            return new AnthropicProvider(config);
        }
    }
}
