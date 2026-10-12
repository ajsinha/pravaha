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
import org.jspecify.annotations.Nullable;

/**
 * {@code type: ollama}: a local Ollama server on the JDK client. No key is needed. {@code POST
 * {base-url}/api/chat} (tools with object arguments; ids are made up as {@code call_<n>}), {@code /api/embed},
 * and {@code GET /api/tags} for the models pulled on the host. Settings: {@code base-url}
 * (http://localhost:11434; {@code OLLAMA_HOST} / {@code OLLAMA_BASE_URL} are read, and a bare
 * {@code host:port} gets {@code http://}), {@code keep-alive}, {@code num-ctx}, {@code think},
 * {@code options.<name>} (any Ollama runtime option), an optional {@code api-key} sent as a bearer token
 * (a proxy in front of Ollama), and the file's {@code timeout} (local models can be slow to load).
 */
public final class OllamaProvider extends HttpProvider {

    OllamaProvider(ProviderConfig c) {
        super(c, normalize(c.baseUrl()), Set.of(Capability.TOOL_CALLS, Capability.JSON_MODE));
    }

    /** A base URL with its scheme and port filled in. */
    public static String normalize(@Nullable String url) {
        String u = (url == null || url.isBlank() ? "http://localhost:11434" : url.strip()).replaceAll("/+$", "");
        if (!u.contains("://")) {
            String host = u.startsWith(":") ? "localhost" + u : u;
            u = "http://" + (host.contains(":") ? host : host + ":11434");
        }
        return u;
    }

    private Map<String, String> auth() {
        return config.apiKey().isEmpty() ? Map.of() : Map.of("Authorization", "Bearer " + config.apiKey());
    }

    @Override
    public List<LiveModel> liveModels() {
        JsonNode j = http.call("GET", http.base() + "/api/tags", null, auth(), Duration.ofSeconds(5));
        List<LiveModel> out = new ArrayList<>();
        for (JsonNode m : j.path("models")) {
            String n = m.path("name").asText(m.path("model").asText("")).replaceFirst(":latest$", "");
            if (!n.isEmpty()) {
                out.add(new LiveModel(n, n.contains("embed") ? "embedding" : "chat"));
            }
        }
        return out;
    }

    @Override
    public Readiness probe() {
        try {
            return new Readiness(true, http.base() + ": " + liveModels().size() + " model(s) pulled");
        } catch (LlmException e) {
            return new Readiness(false, http.base() + " is not answering (" + e.detail() + ")");
        }
    }

    private static JsonNode scalar(String v) {
        try {
            return JsonHttp.JSON.getNodeFactory().numberNode(Long.parseLong(v));
        } catch (NumberFormatException e) {
            // not an integer
        }
        try {
            return JsonHttp.JSON.getNodeFactory().numberNode(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            // not a number
        }
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return JsonHttp.JSON.getNodeFactory().booleanNode(Boolean.parseBoolean(v));
        }
        return JsonHttp.JSON.getNodeFactory().textNode(v);
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
        body.put("stream", false);
        ObjectNode o = body.putObject("options");
        config.settings().forEach((k, v) -> {
            if (k.startsWith("options.")) {
                o.set(k.substring(8), scalar(v));
            }
        });
        o.put("num_predict", maxTokens(r, info, 1024));
        Double t = temperature(r, info);
        if (t != null) {
            o.put("temperature", t);
        }
        if (!config.setting("num-ctx", "").isEmpty()) {
            try {
                o.put("num_ctx", Integer.parseInt(config.setting("num-ctx", "0")));
            } catch (NumberFormatException e) {
                throw new LlmException(LlmException.Kind.CONFIG, "num-ctx is not a number");
            }
        }
        if (r.jsonMode()) {
            body.put("format", "json");
        }
        if (!config.setting("keep-alive", "").isEmpty()) {
            body.set("keep_alive", scalar(config.setting("keep-alive", "")));
        }
        if (!config.setting("think", "").isEmpty()) {
            body.put("think", "true".equalsIgnoreCase(config.setting("think", "")));
        }
        if (!r.tools().isEmpty()) {
            body.set("tools", functionTools(r));
        }
        JsonNode j = http.call("POST", http.base() + "/api/chat", body, auth(), timeout(r));
        JsonNode msg = j.path("message");
        List<ToolCall> calls = new ArrayList<>();
        int i = 0;
        for (JsonNode c : msg.path("tool_calls")) {
            calls.add(new ToolCall(
                    "call_" + i++,
                    c.path("function").path("name").asText(""),
                    compact(c.path("function").path("arguments"))));
        }
        String text = msg.path("content").asText("");
        if (text.isEmpty() && calls.isEmpty()) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint's answer has no text content");
        }
        int in = j.path("prompt_eval_count").asInt(0);
        int out = j.path("eval_count").asInt(0);
        String fin =
                calls.isEmpty() ? ("length".equals(j.path("done_reason").asText()) ? "length" : "stop") : "tool_calls";
        return new ChatResponse(
                text, calls, j.path("model").asText(info.id()), fin, new Usage(in, out, info.cost(in, out)));
    }

    @Override
    protected List<float[]> embed(ModelInfo info, List<String> texts) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        body.put("model", info.id());
        ArrayNode in = body.putArray("input");
        texts.forEach(in::add);
        return vectors(
                http.call("POST", http.base() + "/api/embed", body, auth(), null)
                        .path("embeddings"),
                texts.size());
    }

    /** {@code type: ollama}. */
    public static final class Factory implements ProviderFactory {
        @Override
        public String type() {
            return "ollama";
        }

        @Override
        public LlmProvider create(ProviderConfig config) {
            return new OllamaProvider(config);
        }
    }
}
