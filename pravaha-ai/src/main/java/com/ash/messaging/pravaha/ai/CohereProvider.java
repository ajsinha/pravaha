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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code type: cohere}: Cohere's v2 API on the JDK client. {@code POST {base-url}/v2/chat} and
 * {@code /v2/embed} with {@code Authorization: Bearer}. Settings: {@code base-url} (https://api.cohere.com,
 * or {@code COHERE_BASE_URL}), {@code chat-path} (/v2/chat), {@code embed-path} (/v2/embed),
 * {@code embedding-input-type} (search_document), the key from {@code api-key: ${COHERE_API_KEY}} (or
 * {@code COHERE_API_KEY} / {@code CO_API_KEY}). Cohere's {@code document} tool-result format is not built.
 */
public final class CohereProvider extends HttpProvider {

    private final String chatPath;
    private final String embedPath;

    CohereProvider(ProviderConfig c) {
        super(
                c,
                c.baseUrl().isEmpty() ? "https://api.cohere.com" : c.baseUrl(),
                Set.of(Capability.TOOL_CALLS, Capability.JSON_MODE));
        this.chatPath = c.setting("chat-path", "/v2/chat");
        this.embedPath = c.setting("embed-path", "/v2/embed");
    }

    private Map<String, String> auth() {
        return config.apiKey().isEmpty() ? Map.of() : Map.of("Authorization", "Bearer " + config.apiKey());
    }

    @Override
    public Readiness check() {
        return config.apiKey().isEmpty()
                ? new Readiness(false, "no api key: set COHERE_API_KEY or api-key: ${COHERE_API_KEY}")
                : new Readiness(true, "configured; no call is made to find out");
    }

    @Override
    public List<LiveModel> liveModels() {
        JsonNode j = http.call("GET", http.base() + "/v1/models", null, auth(), Duration.ofSeconds(5));
        List<LiveModel> out = new ArrayList<>();
        for (JsonNode m : j.path("models")) {
            boolean emb = false;
            for (JsonNode e : m.path("endpoints")) {
                emb |= "embed".equals(e.asText());
            }
            if (m.path("name").isTextual()) {
                out.add(new LiveModel(m.get("name").asText(), emb ? "embedding" : "chat"));
            }
        }
        return out;
    }

    @Override
    protected ChatResponse chat(ModelInfo info, ChatRequest r) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        body.put("model", info.id());
        ArrayNode msgs = body.putArray("messages");
        String sys = systemText(r);
        if (!sys.isEmpty()) {
            msgs.addObject().put("role", "system").put("content", sys);
        }
        for (ChatMessage m : turns(r)) {
            msgs.addObject().put("role", assistant(m) ? "assistant" : "user").put("content", m.content());
        }
        body.put("max_tokens", maxTokens(r, info, 1024));
        body.put("stream", false);
        Double t = temperature(r, info);
        if (t != null) {
            body.put("temperature", t);
        }
        if (r.jsonMode()) {
            body.putObject("response_format").put("type", "json_object");
        }
        if (!r.tools().isEmpty()) {
            body.set("tools", functionTools(r));
        }
        JsonNode j = http.call("POST", http.base() + chatPath, body, auth(), timeout(r));
        JsonNode msg = j.path("message");
        StringBuilder text = new StringBuilder();
        if (msg.path("content").isArray()) {
            for (JsonNode b : msg.get("content")) {
                if ("text".equals(b.path("type").asText("text"))) {
                    text.append(b.path("text").asText(""));
                }
            }
        } else {
            text.append(msg.path("content").asText(""));
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : msg.path("tool_calls")) {
            calls.add(new ToolCall(
                    c.path("id").asText(""),
                    c.path("function").path("name").asText(""),
                    c.path("function").path("arguments").asText("{}")));
        }
        if (text.isEmpty() && calls.isEmpty()) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint's answer has no text content");
        }
        JsonNode tok = j.path("usage").has("tokens")
                ? j.path("usage").get("tokens")
                : j.path("usage").path("billed_units");
        int in = tok.path("input_tokens").asInt(0);
        int out = tok.path("output_tokens").asInt(0);
        String fin =
                switch (j.path("finish_reason").asText("COMPLETE")) {
                    case "MAX_TOKENS" -> "length";
                    case "TOOL_CALL" -> "tool_calls";
                    default -> "stop";
                };
        return new ChatResponse(
                text.toString(),
                calls,
                info.id(),
                calls.isEmpty() ? fin : "tool_calls",
                new Usage(in, out, info.cost(in, out)));
    }

    @Override
    protected List<float[]> embed(ModelInfo info, List<String> texts) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        body.put("model", info.id());
        ArrayNode in = body.putArray("texts");
        texts.forEach(in::add);
        body.put("input_type", config.setting("embedding-input-type", "search_document"));
        body.putArray("embedding_types").add("float");
        JsonNode j = http.call("POST", http.base() + embedPath, body, auth(), null);
        JsonNode e = j.path("embeddings");
        return vectors(e.isObject() ? e.path("float") : e, texts.size());
    }

    /** {@code type: cohere}. */
    public static final class Factory implements ProviderFactory {
        @Override
        public String type() {
            return "cohere";
        }

        @Override
        public LlmProvider create(ProviderConfig config) {
            return new CohereProvider(config);
        }
    }
}
