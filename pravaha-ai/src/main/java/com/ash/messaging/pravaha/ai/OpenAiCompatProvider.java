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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * {@code type: openai | openai_compatible | groq | together | fireworks | deepseek | xai | openrouter |
 * perplexity | huggingface | vllm | lmstudio | mistral}: the OpenAI Chat Completions wire format on the JDK
 * client. {@code openai} is OpenAI itself (base https://api.openai.com, path {@code /v1/chat/completions});
 * the others are presets (a base URL, the variable a key is read from, a model catalogue:
 * {@code ai/llm_catalog.json}) with path {@code /chat/completions}. Drishti served {@code openai} through
 * Spring AI; Pravaha serves it here, on the same HTTP stack as every other type, and adds no dependency.
 * Settings: {@code base-url}, {@code completions-path} / {@code chat-path}, {@code embeddings-path},
 * {@code max-tokens-param} ({@code max_tokens}, or {@code max_completion_tokens}), {@code live-models},
 * {@code models-path} (/models), {@code header.<Name>}, {@code proxy.*}, {@code tls.*}, {@code retries}.
 * The Azure variant is {@link AzureOpenAiProvider}.
 */
public class OpenAiCompatProvider extends HttpProvider {

    protected final @Nullable JsonNode preset;
    private final String chatPath;
    private final String embedPath;
    private final String tokensParam;
    private final String modelsPath;

    OpenAiCompatProvider(ProviderConfig c) {
        this(
                c,
                base(c),
                LlmCatalog.preset(c.type()) != null ? "/chat/completions" : "/v1/chat/completions",
                LlmCatalog.preset(c.type()) != null ? "/embeddings" : "/v1/embeddings",
                "max_tokens");
    }

    protected OpenAiCompatProvider(ProviderConfig c, String base, String chat, String embed, String tokens) {
        super(c, base, Set.of(Capability.TOOL_CALLS, Capability.JSON_MODE));
        this.preset = LlmCatalog.preset(c.type());
        JsonNode p = preset;
        this.chatPath = c.setting("completions-path", c.setting("chat-path", chat));
        this.embedPath = c.setting("embeddings-path", embed);
        this.tokensParam = c.setting(
                "max-tokens-param",
                p != null && p.has("max-tokens-param")
                        ? p.get("max-tokens-param").asText()
                        : tokens);
        this.modelsPath = c.setting("models-path", "/models");
    }

    private static String base(ProviderConfig c) {
        JsonNode p = LlmCatalog.preset(c.type());
        String b = !c.baseUrl().isEmpty()
                ? c.baseUrl()
                : p != null ? p.path("base-url").asText("") : "https://api.openai.com";
        if (b.isEmpty()) {
            throw new LlmException(
                    LlmException.Kind.CONFIG,
                    "base-url is required for this type (the server's address, for example http://localhost:8000/v1)");
        }
        return b;
    }

    /** The authentication headers. */
    protected Map<String, String> authHeaders() {
        return config.apiKey().isEmpty() ? Map.of() : Map.of("Authorization", "Bearer " + config.apiKey());
    }

    /** Where chat completions go. */
    protected String chatUrl(ModelInfo info) {
        return http.base() + chatPath;
    }

    /** Where embeddings go. */
    protected String embeddingsUrl(ModelInfo info) {
        return http.base() + embedPath;
    }

    /** The {@code model} of the request body (Azure: the deployment). */
    protected String wireId(ModelInfo info) {
        return info.id();
    }

    private boolean keyless() {
        JsonNode p = preset;
        return p == null || !p.path("requires-key").asBoolean(true);
    }

    @Override
    public Readiness check() {
        JsonNode p = preset;
        if (!keyless() && config.apiKey().isEmpty() && p != null) {
            StringBuilder names = new StringBuilder();
            p.path("key-env")
                    .forEach(n -> names.append(names.isEmpty() ? "" : " or ").append(n.asText()));
            return new Readiness(
                    false, "no api key: set " + (names.isEmpty() ? "api-key" : names) + " (or api-key: ${ENV_NAME})");
        }
        return new Readiness(true, "configured; no call is made to find out");
    }

    @Override
    public Readiness probe() {
        Readiness r = check();
        if (!r.ok()) {
            return r;
        }
        try {
            http.call("GET", http.base() + modelsPath, null, authHeaders(), Duration.ofSeconds(5));
            return new Readiness(true, "the model list answered");
        } catch (LlmException e) {
            return new Readiness(false, e.detail());
        }
    }

    @Override
    public List<LiveModel> liveModels() {
        String live = config.setting("live-models", "");
        boolean on = live.isEmpty() ? keyless() && preset != null : "true".equalsIgnoreCase(live);
        if (!on) {
            return List.of();
        }
        JsonNode j = http.call("GET", http.base() + modelsPath, null, authHeaders(), Duration.ofSeconds(5));
        List<LiveModel> out = new ArrayList<>();
        for (JsonNode m : j.path("data")) {
            String id = m.path("id").asText("");
            if (!id.isEmpty()) {
                out.add(new LiveModel(id, id.contains("embed") ? "embedding" : "chat"));
            }
        }
        return out;
    }

    @Override
    protected ChatResponse chat(ModelInfo info, ChatRequest r) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        body.put("model", wireId(info));
        ArrayNode msgs = body.putArray("messages");
        String sys = systemText(r);
        if (!sys.isEmpty()) {
            msgs.addObject().put("role", "system").put("content", sys);
        }
        for (ChatMessage m : turns(r)) {
            msgs.addObject().put("role", assistant(m) ? "assistant" : "user").put("content", m.content());
        }
        body.put(tokensParam, maxTokens(r, info, 1024));
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
        JsonNode j = http.call("POST", chatUrl(info), body, requestHeaders(), timeout(r));
        JsonNode choice = j.path("choices").path(0);
        JsonNode msg = choice.path("message");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : msg.path("tool_calls")) {
            calls.add(new ToolCall(
                    c.path("id").asText(""),
                    c.path("function").path("name").asText(""),
                    c.path("function").path("arguments").asText("{}")));
        }
        if (!msg.path("content").isTextual() && calls.isEmpty()) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint's answer has no message content");
        }
        int in = j.path("usage").path("prompt_tokens").asInt(0);
        int out = j.path("usage").path("completion_tokens").asInt(0);
        return new ChatResponse(
                msg.path("content").asText(""),
                calls,
                j.path("model").asText(info.id()),
                choice.path("finish_reason").asText(""),
                new Usage(in, out, info.cost(in, out)));
    }

    /** Authentication plus the file's headers, per request (Azure's Entra token changes). */
    protected Map<String, String> requestHeaders() {
        return new LinkedHashMap<>(authHeaders());
    }

    @Override
    protected List<float[]> embed(ModelInfo info, List<String> texts) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        body.put("model", wireId(info));
        ArrayNode in = body.putArray("input");
        texts.forEach(in::add);
        JsonNode j = http.call("POST", embeddingsUrl(info), body, requestHeaders(), null);
        List<JsonNode> rows = new ArrayList<>();
        j.path("data").forEach(rows::add);
        rows.sort(Comparator.comparingInt(n -> n.path("index").asInt(0)));
        ArrayNode arrays = JsonHttp.JSON.createArrayNode();
        rows.forEach(n -> arrays.add(n.path("embedding")));
        return vectors(arrays, texts.size());
    }

    /** One factory per OpenAI-compatible type. */
    public static ProviderFactory factory(String type) {
        return new ProviderFactory() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public LlmProvider create(ProviderConfig config) {
                return new OpenAiCompatProvider(config);
            }
        };
    }
}
