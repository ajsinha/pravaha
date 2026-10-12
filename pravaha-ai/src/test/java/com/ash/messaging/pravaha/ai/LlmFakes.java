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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;

/**
 * Fake vendor HTTP servers on loopback (an ephemeral port) that speak each API well enough for the provider
 * tests: no real network, ever. Ported from Drishti's {@code FakeLlmServer} and the Python SDK's
 * {@code tests/llm_fakes.py}, which keeps the same rules: the last user text is echoed ({@code echo: <text>}),
 * {@code weather} asks for the tool {@code get_weather} with {@code {"city":"Oslo"}}, {@code boom} is HTTP 500
 * (whose body echoes "secret prompt echo", which must never reach a message), {@code limit} is HTTP 429,
 * {@code slow} sleeps two seconds (a timeout test), and a request without the right credential is HTTP 401.
 * APIs: {@code openai} (and every preset), {@code azure}, {@code azure-entra}, {@code anthropic},
 * {@code gemini}, {@code cohere}, {@code ollama}, {@code bedrock}.
 */
final class LlmFakes implements AutoCloseable {

    static final String KEY = "k-123";
    static final ObjectMapper JSON = new ObjectMapper();

    /** One request the fake received. */
    record Seen(String method, String path, String body, Headers headers) {
        String header(String name) {
            return String.valueOf(headers.getFirst(name));
        }

        JsonNode json() throws IOException {
            return JSON.readTree(body);
        }
    }

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final String api;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile Predicate<Seen> signatureCheck = s -> true;

    LlmFakes(String api) throws IOException {
        this.api = api;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    static LlmFakes start(String api) throws IOException {
        return new LlmFakes(api);
    }

    List<Seen> seen() {
        return seen;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A check a request must pass (SigV4 verification); a failure is HTTP 403. */
    void requireSignature(Predicate<Seen> check) {
        this.signatureCheck = check;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange x) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = x.getRequestURI().getRawPath();
        String query = x.getRequestURI().getRawQuery();
        Seen s = new Seen(x.getRequestMethod(), path + (query == null ? "" : "?" + query), body, x.getRequestHeaders());
        seen.add(s);
        int status;
        JsonNode out;
        try {
            Object[] r = answer(s, path);
            status = (Integer) r[0];
            out = (JsonNode) r[1];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = 500;
            out = JSON.createObjectNode().put("error", "interrupted");
        } catch (IOException | RuntimeException e) {
            status = 400;
            out = JSON.createObjectNode().put("error", "bad request");
        }
        byte[] bytes = JSON.writeValueAsBytes(out);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private boolean authorised(Headers h) {
        String auth = h.getFirst("Authorization");
        return switch (api) {
            case "openai", "cohere" -> ("Bearer " + KEY).equals(auth);
            case "azure" -> KEY.equals(h.getFirst("api-key"));
            case "anthropic" -> KEY.equals(h.getFirst("x-api-key")) && h.getFirst("anthropic-version") != null;
            case "gemini" -> KEY.equals(h.getFirst("x-goog-api-key"));
            case "azure-entra" -> "Bearer entra-token-1".equals(auth);
            case "bedrock" -> auth != null && auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/");
            default -> true;
        };
    }

    private Object[] answer(Seen s, String p) throws IOException, InterruptedException {
        if (p.endsWith("/oauth2/v2.0/token")) {
            return ok(JSON.createObjectNode()
                    .put("access_token", "entra-token-1")
                    .put("expires_in", 3600)
                    .put("token_type", "Bearer"));
        }
        if (!authorised(s.headers())) {
            return new Object[] {
                401,
                JSON.createObjectNode().set("error", JSON.createObjectNode().put("message", "bad credential"))
            };
        }
        if (!signatureCheck.test(s)) {
            return new Object[] {403, JSON.createObjectNode().put("message", "signature does not match")};
        }
        JsonNode j = s.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(s.body());
        return switch (api) {
            case "openai", "azure", "azure-entra" -> openai(p, j);
            case "anthropic" -> anthropic(j);
            case "gemini" -> gemini(p, j);
            case "cohere" -> cohere(p, j);
            case "ollama" -> ollama(p, j);
            default -> bedrock(p, j);
        };
    }

    private static Object[] ok(JsonNode n) {
        return new Object[] {200, n};
    }

    private static ArrayNode vec() {
        ArrayNode a = JSON.createArrayNode();
        a.add(0.1).add(0.2).add(0.3).add(0.4);
        return a;
    }

    /** boom, limit, slow; null when the text is plain. */
    private static Object @Nullable [] gate(String text) throws InterruptedException {
        if (text.contains("boom")) {
            return new Object[] {
                500,
                JSON.createObjectNode().set("error", JSON.createObjectNode().put("message", "secret prompt echo"))
            };
        }
        if (text.contains("limit")) {
            return new Object[] {
                429,
                JSON.createObjectNode().set("error", JSON.createObjectNode().put("message", "slow down"))
            };
        }
        if (text.contains("slow")) {
            Thread.sleep(2000);
        }
        return null;
    }

    private static String lastUser(JsonNode messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode m = messages.get(i);
            if ("user".equals(m.path("role").asText("user"))) {
                JsonNode c = m.path("content");
                if (c.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    c.forEach(x -> sb.append(x.path("text").asText("")));
                    return sb.toString();
                }
                return c.asText("");
            }
        }
        return "";
    }

    private static ObjectNode toolCallArgs() {
        return JSON.createObjectNode().put("city", "Oslo");
    }

    private Object[] openai(String p, JsonNode j) throws InterruptedException {
        if (p.endsWith("/models")) {
            ArrayNode d = JSON.createArrayNode();
            d.addObject().put("id", "served-model");
            d.addObject().put("id", "served-embed");
            return ok(JSON.createObjectNode().set("data", d));
        }
        if (p.endsWith("/embeddings")) {
            ArrayNode d = JSON.createArrayNode();
            for (int i = 0; i < j.path("input").size(); i++) {
                d.addObject().put("index", i).set("embedding", vec());
            }
            return ok(JSON.createObjectNode().set("data", d));
        }
        String text = lastUser(j.path("messages"));
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ObjectNode msg = JSON.createObjectNode().put("role", "assistant");
        String fin = "stop";
        if (text.contains("weather")) {
            msg.putNull("content");
            msg.putArray("tool_calls")
                    .addObject()
                    .put("id", "c1")
                    .put("type", "function")
                    .set(
                            "function",
                            JSON.createObjectNode()
                                    .put("name", "get_weather")
                                    .put("arguments", toolCallArgs().toString()));
            fin = "tool_calls";
        } else {
            msg.put("content", "echo: " + text);
        }
        ObjectNode r = JSON.createObjectNode()
                .put("id", "x")
                .put("model", j.path("model").asText());
        r.putArray("choices")
                .addObject()
                .put("index", 0)
                .put("finish_reason", fin)
                .set("message", msg);
        r.putObject("usage").put("prompt_tokens", 5).put("completion_tokens", 3);
        return ok(r);
    }

    private Object[] anthropic(JsonNode j) throws InterruptedException {
        String text = lastUser(j.path("messages"));
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ObjectNode r = JSON.createObjectNode()
                .put("id", "msg_1")
                .put("model", j.path("model").asText())
                .put("type", "message");
        ArrayNode content = r.putArray("content");
        if (text.contains("weather")) {
            content.addObject()
                    .put("type", "tool_use")
                    .put("id", "c1")
                    .put("name", "get_weather")
                    .set("input", toolCallArgs());
            r.put("stop_reason", "tool_use");
        } else {
            content.addObject().put("type", "text").put("text", "echo: " + text);
            r.put("stop_reason", "end_turn");
        }
        r.putObject("usage").put("input_tokens", 5).put("output_tokens", 3);
        return ok(r);
    }

    private Object[] gemini(String p, JsonNode j) throws InterruptedException {
        if (p.endsWith("/models")) {
            ArrayNode m = JSON.createArrayNode();
            ObjectNode a = m.addObject().put("name", "models/served-model");
            a.putArray("supportedGenerationMethods").add("generateContent");
            ObjectNode b = m.addObject().put("name", "models/served-embed");
            b.putArray("supportedGenerationMethods").add("embedContent");
            return ok(JSON.createObjectNode().set("models", m));
        }
        if (p.endsWith(":batchEmbedContents")) {
            ArrayNode e = JSON.createArrayNode();
            j.path("requests").forEach(x -> e.addObject().set("values", vec()));
            return ok(JSON.createObjectNode().set("embeddings", e));
        }
        JsonNode contents = j.path("contents");
        StringBuilder sb = new StringBuilder();
        contents.get(contents.size() - 1)
                .path("parts")
                .forEach(x -> sb.append(x.path("text").asText("")));
        String text = sb.toString();
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ArrayNode parts = JSON.createArrayNode();
        if (text.contains("weather")) {
            parts.addObject()
                    .set(
                            "functionCall",
                            JSON.createObjectNode().put("name", "get_weather").set("args", toolCallArgs()));
        } else {
            parts.addObject().put("text", "echo: " + text);
        }
        ObjectNode r = JSON.createObjectNode();
        ObjectNode cand = r.putArray("candidates").addObject().put("finishReason", "STOP");
        cand.putObject("content").put("role", "model").set("parts", parts);
        r.putObject("usageMetadata").put("promptTokenCount", 5).put("candidatesTokenCount", 3);
        return ok(r);
    }

    private Object[] cohere(String p, JsonNode j) throws InterruptedException {
        if (p.endsWith("/models")) {
            ArrayNode m = JSON.createArrayNode();
            m.addObject().put("name", "served-model").putArray("endpoints").add("chat");
            m.addObject().put("name", "served-embed").putArray("endpoints").add("embed");
            return ok(JSON.createObjectNode().set("models", m));
        }
        if (p.endsWith("/embed")) {
            ArrayNode f = JSON.createArrayNode();
            j.path("texts").forEach(x -> f.add(vec()));
            return ok(JSON.createObjectNode()
                    .set("embeddings", JSON.createObjectNode().set("float", f)));
        }
        String text = lastUser(j.path("messages"));
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ObjectNode msg = JSON.createObjectNode().put("role", "assistant");
        String fin = "COMPLETE";
        if (text.contains("weather")) {
            msg.putArray("tool_calls")
                    .addObject()
                    .put("id", "c1")
                    .put("type", "function")
                    .set(
                            "function",
                            JSON.createObjectNode()
                                    .put("name", "get_weather")
                                    .put("arguments", toolCallArgs().toString()));
            fin = "TOOL_CALL";
        } else {
            msg.putArray("content").addObject().put("type", "text").put("text", "echo: " + text);
        }
        ObjectNode r = JSON.createObjectNode().put("finish_reason", fin);
        r.set("message", msg);
        r.putObject("usage").putObject("tokens").put("input_tokens", 5).put("output_tokens", 3);
        return ok(r);
    }

    private Object[] ollama(String p, JsonNode j) throws InterruptedException {
        if (p.endsWith("/api/tags")) {
            ArrayNode m = JSON.createArrayNode();
            m.addObject().put("name", "llama3.2:latest");
            m.addObject().put("name", "nomic-embed-text:latest");
            return ok(JSON.createObjectNode().set("models", m));
        }
        if (p.endsWith("/api/embed")) {
            ArrayNode e = JSON.createArrayNode();
            j.path("input").forEach(x -> e.add(vec()));
            return ok(JSON.createObjectNode().set("embeddings", e));
        }
        String text = lastUser(j.path("messages"));
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ObjectNode msg = JSON.createObjectNode().put("role", "assistant");
        if (text.contains("weather")) {
            msg.put("content", "");
            msg.putArray("tool_calls")
                    .addObject()
                    .set(
                            "function",
                            JSON.createObjectNode().put("name", "get_weather").set("arguments", toolCallArgs()));
        } else {
            msg.put("content", "echo: " + text);
        }
        ObjectNode r = JSON.createObjectNode()
                .put("model", j.path("model").asText())
                .put("done", true)
                .put("done_reason", "stop")
                .put("prompt_eval_count", 5)
                .put("eval_count", 3);
        r.set("message", msg);
        return ok(r);
    }

    private Object[] bedrock(String p, JsonNode j) throws InterruptedException {
        if (p.endsWith("/foundation-models")) {
            ArrayNode m = JSON.createArrayNode();
            m.addObject()
                    .put("modelId", "anthropic.claude-sonnet-5-5")
                    .putArray("outputModalities")
                    .add("TEXT");
            m.addObject()
                    .put("modelId", "amazon.titan-embed-text-v2:0")
                    .putArray("outputModalities")
                    .add("EMBEDDING");
            return ok(JSON.createObjectNode().set("modelSummaries", m));
        }
        if (p.endsWith("/invoke")) {
            if (j.has("texts")) {
                ArrayNode f = JSON.createArrayNode();
                j.path("texts").forEach(x -> f.add(vec()));
                return ok(JSON.createObjectNode()
                        .set("embeddings", JSON.createObjectNode().set("float", f)));
            }
            return ok(JSON.createObjectNode().set("embedding", vec()));
        }
        JsonNode msgs = j.path("messages");
        StringBuilder sb = new StringBuilder();
        msgs.get(msgs.size() - 1)
                .path("content")
                .forEach(b -> sb.append(b.path("text").asText("")));
        String text = sb.toString();
        Object[] g = gate(text);
        if (g != null) {
            return g;
        }
        ArrayNode blocks = JSON.createArrayNode();
        String stop = "end_turn";
        if (text.contains("weather")) {
            blocks.addObject()
                    .set(
                            "toolUse",
                            JSON.createObjectNode()
                                    .put("toolUseId", "c1")
                                    .put("name", "get_weather")
                                    .set("input", toolCallArgs()));
            stop = "tool_use";
        } else {
            blocks.addObject().put("text", "echo: " + text);
        }
        ObjectNode r = JSON.createObjectNode().put("stopReason", stop);
        r.putObject("output").putObject("message").put("role", "assistant").set("content", blocks);
        r.putObject("usage").put("inputTokens", 5).put("outputTokens", 3);
        return ok(r);
    }
}
