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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * {@code type: bedrock}: Amazon Bedrock over its REST API, signed with SigV4 by hand ({@link SigV4}): the
 * Converse API for chat ({@code POST /model/{id}/converse}), InvokeModel for embeddings ({@code POST
 * /model/{id}/invoke}: Titan one text per call, Cohere a batch), {@code GET /foundation-models} on the
 * control plane for the live model list. Drishti used the AWS SDK; Pravaha does not add it.
 *
 * <p>Credentials: {@code aws-access-key-id} / {@code aws-secret-access-key} / {@code aws-session-token} as
 * {@code ${ENV}} placeholders, else {@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY} /
 * {@code AWS_SESSION_TOKEN} in the environment. <b>The rest of the AWS credential chain (profiles, SSO,
 * instance and container roles, web identity) is not built</b>: give keys, or a role's temporary keys
 * through the environment. Settings: {@code region} ({@code AWS_REGION} / {@code AWS_DEFAULT_REGION}, then
 * us-east-1), {@code base-url} (an endpoint override: a VPC endpoint, or a fake in a test),
 * {@code control-url} (the same for the control plane), {@code embedding-input-type} (search_document,
 * Cohere models), {@code proxy.*}, {@code tls.*}, {@code timeout}, {@code retries}. Guardrails are not built.
 */
public final class BedrockProvider extends HttpProvider {

    private final String region;
    private final String controlBase;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final Clock clock = Clock.systemUTC();

    BedrockProvider(ProviderConfig c) {
        this(c, System::getenv);
    }

    BedrockProvider(ProviderConfig c, Function<String, @Nullable String> env) {
        super(c, runtimeBase(c), Set.of(Capability.TOOL_CALLS, Capability.JSON_MODE));
        this.region = c.setting("region", "us-east-1");
        String control = c.setting("control-url", "");
        this.controlBase =
                (control.isEmpty() ? "https://bedrock." + region + ".amazonaws.com" : control).replaceAll("/+$", "");
        String key = c.setting("aws-access-key-id", "");
        String secret = c.setting("aws-secret-access-key", "");
        if (key.isEmpty() != secret.isEmpty()) {
            throw new LlmException(
                    LlmException.Kind.CONFIG,
                    "aws-access-key-id and aws-secret-access-key go together (as ${ENV} placeholders)");
        }
        if (key.isEmpty()) {
            key = orEmpty(env.apply("AWS_ACCESS_KEY_ID"));
            secret = orEmpty(env.apply("AWS_SECRET_ACCESS_KEY"));
            this.sessionToken = orEmpty(env.apply("AWS_SESSION_TOKEN"));
        } else {
            this.sessionToken = c.setting("aws-session-token", "");
        }
        this.accessKey = key;
        this.secretKey = secret;
    }

    private static String orEmpty(@Nullable String s) {
        return s == null ? "" : s.strip();
    }

    private static String runtimeBase(ProviderConfig c) {
        return c.baseUrl().isEmpty()
                ? "https://bedrock-runtime." + c.setting("region", "us-east-1") + ".amazonaws.com"
                : c.baseUrl();
    }

    private Function<byte[], Map<String, String>> signer(String method, String url) {
        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            throw new LlmException(LlmException.Kind.CONFIG, check().detail());
        }
        URI uri = URI.create(url);
        return payload -> SigV4.sign(
                method,
                uri,
                payload,
                "application/json",
                accessKey,
                secretKey,
                sessionToken,
                region,
                "bedrock",
                clock.instant());
    }

    private JsonNode signed(String method, String url, @Nullable JsonNode body, @Nullable Duration timeout) {
        return http.call(method, url, body, signer(method, url), timeout);
    }

    private String modelUrl(String id, String verb) {
        return http.base() + "/model/"
                + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20") + "/" + verb;
    }

    @Override
    public Readiness check() {
        if (accessKey.isEmpty() || secretKey.isEmpty()) {
            return new Readiness(
                    false,
                    "no AWS credentials: set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (or aws-access-key-id / "
                            + "aws-secret-access-key as ${ENV}); profiles and roles are not read");
        }
        return new Readiness(true, "configured for " + region + "; no call is made to find out");
    }

    @Override
    public List<LiveModel> liveModels() {
        JsonNode j = signed("GET", controlBase + "/foundation-models", null, Duration.ofSeconds(10));
        List<LiveModel> out = new ArrayList<>();
        for (JsonNode m : j.path("modelSummaries")) {
            boolean emb = false;
            for (JsonNode x : m.path("outputModalities")) {
                emb |= "EMBEDDING".equals(x.asText());
            }
            if (m.path("modelId").isTextual()) {
                out.add(new LiveModel(m.get("modelId").asText(), emb ? "embedding" : "chat"));
            }
        }
        return out;
    }

    @Override
    public Readiness probe() {
        Readiness r = check();
        if (!r.ok()) {
            return r;
        }
        try {
            return new Readiness(true, region + ": " + liveModels().size() + " foundation models listed");
        } catch (LlmException e) {
            return new Readiness(false, e.detail());
        }
    }

    @Override
    protected ChatResponse chat(ModelInfo info, ChatRequest r) {
        ObjectNode body = JsonHttp.JSON.createObjectNode();
        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : turns(r)) {
            ObjectNode o = msgs.addObject().put("role", assistant(m) ? "assistant" : "user");
            o.putArray("content").addObject().put("text", m.content());
        }
        ObjectNode inf = body.putObject("inferenceConfig");
        inf.put("maxTokens", maxTokens(r, info, 1024));
        Double t = temperature(r, info);
        if (t != null) {
            inf.put("temperature", t);
        }
        String sys = systemText(r);
        if (!sys.isEmpty()) {
            body.putArray("system").addObject().put("text", sys);
        }
        if (!r.tools().isEmpty()) {
            ArrayNode tools = body.putObject("toolConfig").putArray("tools");
            for (ToolSpec s : r.tools()) {
                ObjectNode spec = tools.addObject().putObject("toolSpec").put("name", s.name());
                String description = s.description();
                if (description != null && !description.isEmpty()) {
                    spec.put("description", description);
                }
                spec.putObject("inputSchema").set("json", schema(s));
            }
        }
        JsonNode j = signed("POST", modelUrl(info.id(), "converse"), body, timeout(r));
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode b : j.path("output").path("message").path("content")) {
            if (b.path("text").isTextual()) {
                text.append(b.get("text").asText());
            }
            if (b.has("toolUse")) {
                JsonNode u = b.get("toolUse");
                calls.add(new ToolCall(
                        u.path("toolUseId").asText(""), u.path("name").asText(""), compact(u.path("input"))));
            }
        }
        if (text.isEmpty() && calls.isEmpty()) {
            throw new LlmException(LlmException.Kind.FAILED, "the model endpoint's answer has no text content");
        }
        int in = j.path("usage").path("inputTokens").asInt(0);
        int out = j.path("usage").path("outputTokens").asInt(0);
        String fin =
                switch (j.path("stopReason").asText("end_turn")) {
                    case "max_tokens" -> "length";
                    case "content_filtered", "guardrail_intervened" -> "content_filter";
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
        ArrayNode arrays = JsonHttp.JSON.createArrayNode();
        if (info.id().contains("cohere")) {
            ObjectNode body = JsonHttp.JSON.createObjectNode();
            ArrayNode in = body.putArray("texts");
            texts.forEach(in::add);
            body.put("input_type", config.setting("embedding-input-type", "search_document"));
            JsonNode e =
                    signed("POST", modelUrl(info.id(), "invoke"), body, null).path("embeddings");
            (e.isObject() ? e.path("float") : e).forEach(arrays::add);
        } else {
            for (String t : texts) { // Titan: one text per call
                ObjectNode body = JsonHttp.JSON.createObjectNode().put("inputText", t);
                arrays.add(signed("POST", modelUrl(info.id(), "invoke"), body, null)
                        .path("embedding"));
            }
        }
        return vectors(arrays, texts.size());
    }

    /** {@code type: bedrock}. */
    public static final class Factory implements ProviderFactory {
        @Override
        public String type() {
            return "bedrock";
        }

        @Override
        public LlmProvider create(ProviderConfig config) {
            return new BedrockProvider(config);
        }
    }
}
