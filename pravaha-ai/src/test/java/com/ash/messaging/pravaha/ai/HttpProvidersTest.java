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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ash.messaging.pravaha.ai.llm.ProviderBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every HTTP provider type against its fake vendor server on loopback (Drishti's {@code HttpProvidersTest}):
 * the request (path, authentication header, body fields), the reply (text, tool call, usage, cost), the
 * error mapping (401 and 500 FAILED without the body, 429 RATE_LIMITED, a slow answer TIMEOUT), embeddings,
 * the live model list, readiness without a key, and a configuration description with every secret masked.
 * No real network.
 */
class HttpProvidersTest {

    static final Map<String, String> ENV = Map.of(
            "THE_KEY", LlmFakes.KEY,
            "AKID", "AKIDEXAMPLE",
            "ASECRET", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "CLIENT_SECRET", "cs-1");

    static final List<String> PRESETS = List.of(
            "groq",
            "together",
            "fireworks",
            "deepseek",
            "xai",
            "openrouter",
            "perplexity",
            "huggingface",
            "vllm",
            "lmstudio",
            "mistral",
            "openai_compatible");

    /** A provider of {@code type} talking to {@code url}, with one chat model {@code m} and one embedding model {@code v}. */
    static LlmProvider make(String type, String url, Map<String, Object> extra) {
        ObjectNode n = LlmFakes.JSON.createObjectNode();
        n.put("type", type)
                .put("base-url", url)
                .put("timeout", "1s")
                .put("catalogue", false)
                .put("default-model", "m");
        if (!type.equals("ollama") && !type.equals("bedrock")) {
            n.put("api-key", "${THE_KEY}");
        }
        if (type.equals("bedrock")) {
            n.put("aws-access-key-id", "${AKID}")
                    .put("aws-secret-access-key", "${ASECRET}")
                    .put("control-url", url);
        }
        var models = n.putArray("models");
        models.addObject()
                .put("name", "m")
                .put("id", "the-model")
                .put("cost-per-1k-input", 1.0)
                .put("cost-per-1k-output", 2.0);
        models.addObject()
                .put("name", "v")
                .put("id", type.equals("bedrock") ? "amazon.titan-embed-text-v2:0" : "the-embed")
                .put("kind", "embedding");
        extra.forEach((k, v) -> n.set(k, LlmFakes.JSON.valueToTree(v)));
        ProviderConfig c = ProviderConfigLoader.fromNode("p", n, ENV::get);
        ProviderFactory f = ProviderFactories.discover(List.of()).stream()
                .filter(x -> x.type().equals(type))
                .findFirst()
                .orElseThrow();
        return f.create(c);
    }

    static ChatResponse ask(LlmProvider p, String text) {
        return p.chatModel("m").orElseThrow().chat(ChatRequest.of(null, "be brief", text, null, null));
    }

    static String fakeFor(String type) {
        return switch (type) {
            case "azure_openai" -> "azure";
            case "anthropic", "gemini", "cohere", "ollama", "bedrock" -> type;
            default -> "openai";
        };
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "openai",
                "groq",
                "mistral",
                "vllm",
                "openai_compatible",
                "azure_openai",
                "anthropic",
                "gemini",
                "cohere",
                "ollama",
                "bedrock"
            })
    void everyTypeEchoesCallsToolsAndMapsErrorsWithoutTheBody(String type) throws Exception {
        try (LlmFakes s = LlmFakes.start(fakeFor(type))) {
            LlmProvider p = make(type, s.url(), Map.of());
            ChatResponse r = ask(p, "ping");
            assertThat(r.text()).as(type).isEqualTo("echo: ping");
            assertThat(r.usage().promptTokens()).isEqualTo(5);
            assertThat(r.usage().completionTokens()).isEqualTo(3);
            assertThat(r.usage().cost()).isEqualTo(0.011, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(r.finishReason()).isEqualTo("stop");

            ChatRequest tools = new ChatRequest(
                    null,
                    "",
                    List.of(ChatMessage.user("weather in Oslo")),
                    List.of(new ToolSpec(
                            "get_weather",
                            "the weather",
                            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")),
                    null,
                    null,
                    false,
                    null);
            ChatResponse t = p.chatModel("m").orElseThrow().chat(tools);
            assertThat(t.toolCalls()).as(type).singleElement().satisfies(c -> {
                assertThat(c.name()).isEqualTo("get_weather");
                assertThat(LlmFakes.JSON
                                .readTree(c.argumentsJson())
                                .path("city")
                                .asText())
                        .isEqualTo("Oslo");
            });
            assertThat(t.finishReason()).isEqualTo("tool_calls");
            assertThat(s.seen().get(s.seen().size() - 1).body()).contains("get_weather");

            assertThatThrownBy(() -> ask(p, "boom")).as(type).isInstanceOfSatisfying(LlmException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmException.Kind.FAILED);
                assertThat(e.detail()).contains("HTTP 500").doesNotContain("secret prompt");
            });
            assertThatThrownBy(() -> ask(p, "limit")).as(type).isInstanceOfSatisfying(LlmException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmException.Kind.RATE_LIMITED);
                assertThat(e.errorCode()).isEqualTo(AiErrors.RATE_LIMITED);
                assertThat(e.detail()).contains("HTTP 429");
            });
            assertThatThrownBy(() -> ask(p, "slow")).as(type).isInstanceOfSatisfying(LlmException.class, e -> {
                assertThat(e.kind()).isEqualTo(LlmException.Kind.TIMEOUT);
                assertThat(e.errorCode()).isEqualTo(AiErrors.TIMEOUT);
            });
            assertThat(String.valueOf(p.check().ok()))
                    .as(type + " readiness: " + p.check().detail())
                    .isEqualTo("true");
            p.close();
        }
    }

    @Test
    void theOpenAiRequestHasTheChatCompletionsShapeAndTheKeyTravelsOnlyInTheHeader() throws Exception {
        try (LlmFakes s = LlmFakes.start("openai")) {
            LlmProvider p = make("openai", s.url(), Map.of("header", Map.of("X-Team", "red")));
            ask(p, "ping");
            LlmFakes.Seen seen = s.seen().get(0);
            JsonNode j = seen.json();
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.path()).isEqualTo("/v1/chat/completions");
            assertThat(seen.header("Authorization")).isEqualTo("Bearer " + LlmFakes.KEY);
            assertThat(seen.header("X-Team")).isEqualTo("red");
            assertThat(j.path("model").asText()).isEqualTo("the-model");
            assertThat(j.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(j.path("messages").get(1).path("content").asText()).isEqualTo("ping");
            assertThat(j.path("max_tokens").asInt()).isEqualTo(1024);
            assertThat(j.path("stream").asBoolean(true)).isFalse();
            assertThat(seen.body()).doesNotContain(LlmFakes.KEY);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "groq",
                "together",
                "fireworks",
                "deepseek",
                "xai",
                "openrouter",
                "perplexity",
                "huggingface",
                "vllm",
                "lmstudio",
                "mistral",
                "openai_compatible"
            })
    void everyOpenAiCompatiblePresetPostsToChatCompletionsWithABearerKey(String type) throws Exception {
        try (LlmFakes s = LlmFakes.start("openai")) {
            ask(make(type, s.url(), Map.of()), "ping");
            assertThat(s.seen().get(0).path()).isEqualTo("/chat/completions");
            assertThat(s.seen().get(0).header("Authorization")).isEqualTo("Bearer " + LlmFakes.KEY);
            assertThat(s.seen().get(0).json().has("max_tokens")).isTrue();
        }
    }

    @Test
    void aMaxTokensParamSettingNamesTheLimitAndJsonModeAsksForAnObject() throws Exception {
        try (LlmFakes s = LlmFakes.start("openai")) {
            LlmProvider p = make("openai_compatible", s.url(), Map.of("max-tokens-param", "max_completion_tokens"));
            p.chatModel("m")
                    .orElseThrow()
                    .chat(new ChatRequest(null, "", List.of(ChatMessage.user("x")), List.of(), 0.2, 9, true, null));
            JsonNode j = s.seen().get(0).json();
            assertThat(j.path("max_completion_tokens").asInt()).isEqualTo(9);
            assertThat(j.has("max_tokens")).isFalse();
            assertThat(j.path("temperature").asDouble()).isEqualTo(0.2);
            assertThat(j.path("response_format").path("type").asText()).isEqualTo("json_object");
        }
    }

    @Test
    void anthropicSpeaksTheMessagesApi() throws Exception {
        try (LlmFakes s = LlmFakes.start("anthropic")) {
            ask(make("anthropic", s.url(), Map.of()), "hi");
            LlmFakes.Seen seen = s.seen().get(0);
            assertThat(seen.path()).isEqualTo("/v1/messages");
            assertThat(seen.header("x-api-key")).isEqualTo(LlmFakes.KEY);
            assertThat(seen.header("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(seen.json().path("system").asText()).isEqualTo("be brief");
            assertThat(seen.json().path("max_tokens").asInt()).isEqualTo(1024);
        }
    }

    @Test
    void azureAddressesTheDeploymentAndSendsTheApiKeyHeader() throws Exception {
        try (LlmFakes s = LlmFakes.start("azure")) {
            LlmProvider p = make(
                    "azure_openai",
                    s.url(),
                    Map.of("api-style", "deployments", "deployments", Map.of("the-model", "dep-1")));
            ask(p, "ping");
            LlmFakes.Seen seen = s.seen().get(0);
            assertThat(seen.path()).isEqualTo("/openai/deployments/dep-1/chat/completions?api-version=2024-10-21");
            assertThat(seen.header("api-key")).isEqualTo(LlmFakes.KEY);
            assertThat(seen.json().path("model").asText()).isEqualTo("dep-1");
            assertThat(seen.json().has("max_completion_tokens")).isTrue();
        }
    }

    @Test
    void azureWithEntraFetchesATokenOnceAndSendsItAsABearer() throws Exception {
        try (LlmFakes s = LlmFakes.start("azure-entra")) {
            LlmProvider p = make(
                    "azure_openai",
                    s.url(),
                    Map.of(
                            "auth",
                            "entra",
                            "tenant-id",
                            "t1",
                            "client-id",
                            "c1",
                            "client-secret",
                            "${CLIENT_SECRET}",
                            "entra-authority",
                            s.url()));
            assertThat(p.check().ok()).isTrue();
            ask(p, "ping");
            ask(p, "pong");
            assertThat(s.seen())
                    .extracting(LlmFakes.Seen::path)
                    .filteredOn(x -> x.endsWith("/token"))
                    .hasSize(1);
            LlmFakes.Seen token = s.seen().get(0);
            assertThat(token.path()).isEqualTo("/t1/oauth2/v2.0/token");
            assertThat(token.body()).contains("grant_type=client_credentials").contains("client_secret=cs-1");
            assertThat(s.seen().get(1).header("Authorization")).isEqualTo("Bearer entra-token-1");
        }
        LlmProvider noSecret = make(
                "azure_openai",
                "https://x.openai.azure.com",
                Map.of("auth", "entra", "tenant-id", "t1", "client-id", "c1"));
        assertThat(noSecret.check().ok()).isFalse();
        assertThat(noSecret.check().detail()).contains("managed identity is not built");
    }

    @Test
    void geminiPutsTheKeyInItsHeaderAndTheModelInThePath() throws Exception {
        try (LlmFakes s = LlmFakes.start("gemini")) {
            ask(make("gemini", s.url(), Map.of()), "ping");
            LlmFakes.Seen seen = s.seen().get(0);
            assertThat(seen.path()).isEqualTo("/v1beta/models/the-model:generateContent");
            assertThat(seen.header("x-goog-api-key")).isEqualTo(LlmFakes.KEY);
            assertThat(seen.json()
                            .path("systemInstruction")
                            .path("parts")
                            .get(0)
                            .path("text")
                            .asText())
                    .isEqualTo("be brief");
            assertThat(seen.json()
                            .path("generationConfig")
                            .path("maxOutputTokens")
                            .asInt())
                    .isEqualTo(1024);
        }
        assertThatThrownBy(() -> make("gemini", "https://x", Map.of("platform", "vertex")))
                .isInstanceOfSatisfying(
                        LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.CONFIG));
    }

    @Test
    void cohereAndOllamaSpeakTheirOwnPaths() throws Exception {
        try (LlmFakes s = LlmFakes.start("cohere")) {
            ask(make("cohere", s.url(), Map.of()), "ping");
            assertThat(s.seen().get(0).path()).isEqualTo("/v2/chat");
            assertThat(s.seen().get(0).header("Authorization")).isEqualTo("Bearer " + LlmFakes.KEY);
        }
        try (LlmFakes s = LlmFakes.start("ollama")) {
            ask(make("ollama", s.url(), Map.of("options", Map.of("top_k", 7), "keep-alive", "5m")), "ping");
            JsonNode j = s.seen().get(0).json();
            assertThat(s.seen().get(0).path()).isEqualTo("/api/chat");
            assertThat(j.path("options").path("top_k").asInt()).isEqualTo(7);
            assertThat(j.path("options").path("num_predict").asInt()).isEqualTo(1024);
            assertThat(j.path("keep_alive").asText()).isEqualTo("5m");
        }
        assertThat(OllamaProvider.normalize("gpu-box")).isEqualTo("http://gpu-box:11434");
        assertThat(OllamaProvider.normalize(":9999")).isEqualTo("http://localhost:9999");
    }

    @Test
    void bedrockSignsEveryRequestAndTheSignatureCoversTheBodySent() throws Exception {
        try (LlmFakes s = LlmFakes.start("bedrock")) {
            s.requireSignature(seen -> {
                String date = seen.header("x-amz-date");
                Instant at = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
                        .withZone(ZoneOffset.UTC)
                        .parse(date, Instant::from);
                Map<String, String> again = SigV4.sign(
                        seen.method(),
                        URI.create(s.url() + seen.path()),
                        seen.body().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "application/json",
                        "AKIDEXAMPLE",
                        Objects.requireNonNull(ENV.get("ASECRET"), "the test sets it"),
                        "",
                        "us-east-1",
                        "bedrock",
                        at);
                return Objects.requireNonNull(again.get("Authorization"), "the provider sent one")
                        .equals(seen.header("Authorization"));
            });
            LlmProvider p = make("bedrock", s.url(), Map.of());
            assertThat(ask(p, "ping").text()).isEqualTo("echo: ping");
            LlmFakes.Seen seen = s.seen().get(0);
            assertThat(seen.path()).isEqualTo("/model/the-model/converse");
            assertThat(seen.header("Authorization"))
                    .contains("/us-east-1/bedrock/aws4_request")
                    .contains("SignedHeaders=content-type;host;x-amz-date");
            assertThat(seen.json().path("system").get(0).path("text").asText()).isEqualTo("be brief");
            assertThat(seen.json().path("inferenceConfig").path("maxTokens").asInt())
                    .isEqualTo(1024);
            assertThat(p.liveModels()).extracting(LlmProvider.LiveModel::kind).containsExactly("chat", "embedding");
            List<float[]> v = p.embeddingModel("v").orElseThrow().embed(List.of("a", "b"));
            assertThat(v).hasSize(2);
            assertThat(s.seen())
                    .extracting(LlmFakes.Seen::path)
                    .contains("/model/amazon.titan-embed-text-v2%3A0/invoke");
        }
        LlmProvider noKeys = new BedrockProvider(
                ProviderConfigLoader.fromNode(
                        "b",
                        LlmFakes.JSON.createObjectNode().put("type", "bedrock").put("catalogue", false),
                        ENV::get),
                k -> null);
        assertThat(noKeys.check().ok()).isFalse();
        assertThat(noKeys.check().detail()).contains("AWS_ACCESS_KEY_ID");
    }

    @Test
    void embeddingsAndLiveModelsWhereTheApiHasThem() throws Exception {
        for (String type : List.of("openai_compatible", "gemini", "cohere", "ollama")) {
            try (LlmFakes s = LlmFakes.start(fakeFor(type))) {
                LlmProvider p = make(type, s.url(), Map.of("live-models", "true"));
                List<float[]> v = p.embeddingModel("v").orElseThrow().embed(List.of("a", "bb"));
                assertThat(v).as(type).hasSize(2);
                assertThat(v.get(0)).as(type).hasSize(4);
                assertThat(p.liveModels()).as(type).hasSize(2);
                assertThat(p.probe().ok()).as(type).isTrue();
            }
        }
        try (LlmFakes s = LlmFakes.start("anthropic")) {
            LlmProvider p = make("anthropic", s.url(), Map.of());
            assertThat(p.embeddingModel("v")).isPresent();
            assertThatThrownBy(() -> p.embeddingModel("v").orElseThrow().embed(List.of("a")))
                    .isInstanceOfSatisfying(
                            LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.UNSUPPORTED));
        }
    }

    @Test
    void aWrongKeyIsAFailureThatSaysWhereToLookAndRetriesAreOnlyFor429And5xx() throws Exception {
        try (LlmFakes s = LlmFakes.start("openai")) {
            ObjectNode n = LlmFakes.JSON
                    .createObjectNode()
                    .put("type", "openai_compatible")
                    .put("base-url", s.url())
                    .put("api-key", "${WRONG}")
                    .put("retries", 2);
            n.putArray("models").addObject().put("name", "m");
            LlmProvider p = OpenAiCompatProvider.factory("openai_compatible")
                    .create(ProviderConfigLoader.fromNode("p", n, Map.of("WRONG", "nope")::get));
            assertThatThrownBy(() -> ask(p, "ping")).isInstanceOfSatisfying(LlmException.class, e -> {
                assertThat(e.detail()).contains("HTTP 401").contains("authentication failed");
                assertThat(e.detail()).doesNotContain("nope");
            });
            assertThat(s.seen()).hasSize(1);
            ObjectNode ok = n.deepCopy().put("api-key", "${THE_KEY}");
            LlmProvider q = OpenAiCompatProvider.factory("openai_compatible")
                    .create(ProviderConfigLoader.fromNode("p", ok, ENV::get));
            assertThatThrownBy(() -> ask(q, "boom"));
            assertThat(s.seen()).hasSize(4); // the 500 is tried three times
        }
    }

    @Test
    void readinessWithoutAKeyNamesTheVariableAndTheDescriptionMasksSecrets() {
        ObjectNode n = LlmFakes.JSON.createObjectNode().put("type", "groq");
        ProviderConfig none = ProviderConfigLoader.fromNode("g", n, Map.<String, String>of()::get);
        LlmProvider p = ProviderFactories.discover(List.of()).stream()
                .filter(f -> f.type().equals("groq"))
                .findFirst()
                .orElseThrow()
                .create(none);
        assertThat(p.check().ok()).isFalse();
        assertThat(p.check().detail()).contains("GROQ_API_KEY");
        assertThat(p.models()).isNotEmpty(); // the catalogue
        ProviderConfig keyed = ProviderConfigLoader.fromNode("g", n.deepCopy().put("api-key", "${THE_KEY}"), ENV::get);
        var described = new ProviderBase(
                        keyed,
                        ProviderFactories.discover(List.of()).stream()
                                .filter(f -> f.type().equals("groq"))
                                .findFirst()
                                .orElseThrow()
                                .create(keyed))
                .describeConfig();
        assertThat(described.get("apiKey")).isEqualTo("********");
        assertThat(described.toString()).doesNotContain(LlmFakes.KEY);
        assertThat(described.get("baseUrl")).isEqualTo("https://api.groq.com/openai/v1");
    }

    @Test
    void tlsAndProxySettingsUseTheConnectorVocabularyAndRefuseWhatTheyCannotHonour() {
        assertThatThrownBy(() -> make("openai", "https://x", Map.of("tls", Map.of("verify-hostname", false))))
                .isInstanceOfSatisfying(
                        LlmException.class, e -> assertThat(e.detail()).contains("verify-hostname"));
        assertThatThrownBy(() -> make("openai", "https://x", Map.of("tls", Map.of("ca-certificate", "/x.pem"))))
                .isInstanceOfSatisfying(
                        LlmException.class, e -> assertThat(e.detail()).contains("tls.ca"));
        assertThatThrownBy(() -> make("openai", "https://x", Map.of("tls", Map.of("ca", "/no/such/ca.pem"))))
                .isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> make(
                        "openai", "https://x", Map.of("proxy", Map.of("url", "http://proxy:3128", "username", "u"))))
                .isInstanceOfSatisfying(
                        LlmException.class, e -> assertThat(e.detail()).contains("proxy authentication"));
        assertThat(make("openai", "https://x", Map.of("proxy", Map.of("url", "http://proxy:3128"))))
                .isNotNull();
        assertThatThrownBy(() -> make("openai", "https://x", Map.of("proxy", Map.of("password", "literal"))))
                .isInstanceOfSatisfying(
                        LlmException.class, e -> assertThat(e.detail()).contains("placeholder"));
        assertThatThrownBy(() -> make("openai_compatible", "", Map.of()))
                .isInstanceOfSatisfying(
                        LlmException.class, e -> assertThat(e.detail()).contains("base-url is required"));
    }

    @Test
    void theCatalogueGivesEachPresetABaseUrlAndModels() {
        for (String t : PRESETS) {
            assertThat(LlmCatalog.preset(t)).as(t).isNotNull();
        }
        assertThat(LlmCatalog.models("groq")).isNotEmpty();
        assertThat(LlmCatalog.logicalName("openai/gpt-oss-20b")).isEqualTo("openai_gpt-oss-20b");
        assertThat(LlmCatalog.defaultModel("groq", "chat")).isEqualTo("llama-3.3-70b-versatile");
        Set<String> types = Set.copyOf(ProviderFactories.discover(List.of()).stream()
                .map(ProviderFactory::type)
                .toList());
        assertThat(types)
                .contains("mock", "openai", "anthropic", "gemini", "cohere", "ollama", "bedrock", "azure_openai")
                .containsAll(PRESETS);
    }

    @Test
    void theSlowFakeIsAlsoATimeoutThroughTheGatewaysOwnDeadline() throws Exception {
        try (LlmFakes s = LlmFakes.start("openai")) {
            LlmProvider p = make("openai", s.url(), Map.of("timeout", "30s"));
            LlmRegistry reg = new LlmRegistry(
                    List.of(new ProviderFactory() {
                        @Override
                        public String type() {
                            return "openai";
                        }

                        @Override
                        public LlmProvider create(ProviderConfig config) {
                            return p;
                        }
                    }),
                    () -> new ProviderConfigLoader.Loaded(
                            List.of(ProviderConfigLoader.fromNode(
                                    "p",
                                    LlmFakes.JSON
                                            .createObjectNode()
                                            .put("type", "openai")
                                            .put("catalogue", false)
                                            .set(
                                                    "models",
                                                    LlmFakes.JSON
                                                            .createArrayNode()
                                                            .add(LlmFakes.JSON
                                                                    .createObjectNode()
                                                                    .put("name", "m"))),
                                    ENV::get)),
                            Map.of()),
                    "");
            reg.reload();
            LlmGateway gw = new LlmGateway(reg, LlmPolicy.none());
            assertThatThrownBy(() -> gw.chat(
                            new CallContext("a", Set.of(), "t"),
                            ChatRequest.of(null, "", "slow", 5, Duration.ofMillis(300))))
                    .isInstanceOfSatisfying(
                            LlmException.class, e -> assertThat(e.timeout()).isTrue());
            assertThat(reg.health(reg.provider("p").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.DOWN);
        }
    }
}
