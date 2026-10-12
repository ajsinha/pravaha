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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code type: gemini}: Google's Gemini API (Google AI Studio) on the JDK client.
 * {@code POST {base-url}/{api-version}/models/{id}:generateContent} with the key in {@code x-goog-api-key};
 * embeddings by {@code :batchEmbedContents}. Settings: {@code base-url}
 * (https://generativelanguage.googleapis.com, or {@code GEMINI_BASE_URL}), {@code api-version} (v1beta),
 * {@code schema-mode} ({@code json_schema}: tools as {@code parametersJsonSchema}; {@code openapi}: the
 * OpenAPI subset as {@code parameters}), {@code embedding-task-type} (RETRIEVAL_DOCUMENT), the key from
 * {@code api-key: ${GEMINI_API_KEY}} (or {@code GEMINI_API_KEY} / {@code GOOGLE_API_KEY}). <b>Vertex AI
 * ({@code platform: vertex}) is not built.</b>
 */
public final class GeminiProvider extends HttpProvider {

    private static final Set<String> OPENAPI_DROP =
            Set.of("$schema", "additionalProperties", "$id", "$defs", "definitions", "default", "examples");

    private final String version;
    private final boolean openapi;

    GeminiProvider(ProviderConfig c) {
        super(
                c,
                c.baseUrl().isEmpty() ? "https://generativelanguage.googleapis.com" : c.baseUrl(),
                Set.of(Capability.TOOL_CALLS, Capability.JSON_MODE));
        if (!"ai_studio".equalsIgnoreCase(c.setting("platform", "ai_studio"))) {
            throw new LlmException(
                    LlmException.Kind.CONFIG,
                    "platform: vertex is not built; use the Gemini API (AI Studio) with an api key");
        }
        this.version = c.setting("api-version", "v1beta");
        this.openapi = "openapi".equalsIgnoreCase(c.setting("schema-mode", "json_schema"));
    }

    private Map<String, String> auth() {
        return config.apiKey().isEmpty() ? Map.of() : Map.of("x-goog-api-key", config.apiKey());
    }

    private String modelUrl(String id, String verb) {
        return http.base() + "/" + version + "/models/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + ":" + verb;
    }

    @Override
    public Readiness check() {
        return config.apiKey().isEmpty()
                ? new Readiness(false, "no api key: set GEMINI_API_KEY or api-key: ${GEMINI_API_KEY}")
                : new Readiness(true, "configured; no call is made to find out");
    }

    @Override
    public List<LiveModel> liveModels() {
        JsonNode j = http.call("GET", http.base() + "/" + version + "/models", null, auth(), Duration.ofSeconds(5));
        List<LiveModel> out = new ArrayList<>();
        for (JsonNode m : j.path("models")) {
            String n = m.path("name").asText("").replaceFirst("^models/", "");
            boolean emb = false;
            for (JsonNode g : m.path("supportedGenerationMethods")) {
                emb |= "embedContent".equals(g.asText()) || "batchEmbedContents".equals(g.asText());
            }
            if (!n.isEmpty()) {
                out.add(new LiveModel(n, emb ? "embedding" : "chat"));
            }
        }
        return out;
    }

    private static JsonNode openapiSchema(JsonNode s) {
        if (s.isArray()) {
            ArrayNode a = JsonHttp.JSON.createArrayNode();
            s.forEach(x -> a.add(openapiSchema(x)));
            return a;
        }
        if (!s.isObject()) {
            return s;
        }
        ObjectNode o = JsonHttp.JSON.createObjectNode();
        s.properties().forEach(e -> {
            if (!OPENAPI_DROP.contains(e.getKey())) {
                o.set(e.getKey(), openapiSchema(e.getValue()));
            }
        });
        return o;
    }

    @Override
    protected ChatResponse chat(ModelInfo info, ChatRequest r) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        for (ChatMessage m : turns(r)) {
            ObjectNode c = contents.addObject().put("role", assistant(m) ? "model" : "user");
            c.putArray("parts").addObject().put("text", m.content());
        }
        ObjectNode gen = body.putObject("generationConfig");
        gen.put("maxOutputTokens", maxTokens(r, info, 1024));
        Double t = temperature(r, info);
        if (t != null) {
            gen.put("temperature", t);
        }
        if (r.jsonMode()) {
            gen.put("responseMimeType", "application/json");
        }
        String sys = systemText(r);
        if (!sys.isEmpty()) {
            body.putObject("systemInstruction").putArray("parts").addObject().put("text", sys);
        }
        if (!r.tools().isEmpty()) {
            ArrayNode decls = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (ToolSpec s : r.tools()) {
                String description = s.description();
                ObjectNode d = decls.addObject()
                        .put("name", s.name())
                        .put("description", description == null ? "" : description);
                d.set(openapi ? "parameters" : "parametersJsonSchema", openapi ? openapiSchema(schema(s)) : schema(s));
            }
        }
        JsonNode j = http.call("POST", modelUrl(info.id(), "generateContent"), body, auth(), timeout(r));
        JsonNode cand = j.path("candidates").path(0);
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode p : cand.path("content").path("parts")) {
            if (p.path("text").isTextual()) {
                text.append(p.get("text").asText());
            }
            if (p.has("functionCall")) {
                calls.add(new ToolCall(
                        "call_" + calls.size(),
                        p.get("functionCall").path("name").asText(""),
                        compact(p.get("functionCall").path("args"))));
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            String block = j.path("promptFeedback").path("blockReason").asText("");
            throw new LlmException(
                    LlmException.Kind.FAILED,
                    block.isEmpty()
                            ? "the model endpoint's answer has no text content"
                            : "the model endpoint blocked the prompt (" + block + ")");
        }
        int in = j.path("usageMetadata").path("promptTokenCount").asInt(0);
        int out = j.path("usageMetadata").path("candidatesTokenCount").asInt(0);
        String fin =
                switch (cand.path("finishReason").asText("STOP")) {
                    case "MAX_TOKENS" -> "length";
                    case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter";
                    default -> "stop";
                };
        return new ChatResponse(
                text.toString(),
                calls,
                j.path("modelVersion").asText(info.id()),
                calls.isEmpty() ? fin : "tool_calls",
                new Usage(in, out, info.cost(in, out)));
    }

    @Override
    protected List<float[]> embed(ModelInfo info, List<String> texts) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        ArrayNode reqs = body.putArray("requests");
        for (String t : texts) {
            ObjectNode q = reqs.addObject().put("model", "models/" + info.id());
            q.putObject("content").putArray("parts").addObject().put("text", t);
            q.put("taskType", config.setting("embedding-task-type", "RETRIEVAL_DOCUMENT"));
        }
        JsonNode j = http.call("POST", modelUrl(info.id(), "batchEmbedContents"), body, auth(), null);
        ArrayNode arrays = JsonHttp.JSON.createArrayNode();
        j.path("embeddings").forEach(e -> arrays.add(e.path("values")));
        return vectors(arrays, texts.size());
    }

    /** {@code type: gemini}. */
    public static final class Factory implements ProviderFactory {
        @Override
        public String type() {
            return "gemini";
        }

        @Override
        public LlmProvider create(ProviderConfig config) {
            return new GeminiProvider(config);
        }
    }
}
