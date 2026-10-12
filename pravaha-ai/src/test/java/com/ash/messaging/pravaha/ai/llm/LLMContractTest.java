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

import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.ai.CallContext;
import com.ash.messaging.pravaha.ai.DefaultLlmPolicy;
import com.ash.messaging.pravaha.ai.LlmException;
import com.ash.messaging.pravaha.ai.LlmGateway;
import com.ash.messaging.pravaha.ai.LlmRegistry;
import com.ash.messaging.pravaha.ai.PolicySettings;
import com.ash.messaging.pravaha.ai.PromptMasker;
import com.ash.messaging.pravaha.ai.ProviderConfig;
import com.ash.messaging.pravaha.ai.ProviderConfigLoader;
import com.ash.messaging.pravaha.ai.ProviderFactories;
import com.ash.messaging.pravaha.ai.llm.Canonical.ChatMessage;
import com.ash.messaging.pravaha.ai.llm.Canonical.PravahaRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared LLM contract ({@code contract/llm_contract.json}) against the mock provider, through the
 * canonical abstractions ({@link LLMProvider}, {@link LLMModel}). The Python SDK runs the same file
 * ({@code tests/test_ai_llm_contract.py}); if the two layers drift, one of the tests fails.
 */
class LLMContractTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static JsonNode contract;
    static LLMProvider provider;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        try (InputStream in = LLMContractTest.class.getResourceAsStream("/contract/llm_contract.json")) {
            contract = JSON.readTree(in);
        }
        JsonNode p = contract.get("provider");
        provider = LLMProviders.fromSettings(
                p.get("type").asText(), p.get("name").asText(), JSON.convertValue(p.get("config"), Map.class));
    }

    static List<ChatMessage> messages(JsonNode m) {
        List<ChatMessage> out = new ArrayList<>();
        m.forEach(x -> out.add(new ChatMessage(x.get(0).asText(), x.get(1).asText(), null, null, null, null)));
        return out;
    }

    @Test
    void theCatalogueListsTheModelsWithTheirKindAndQualifiedId() {
        assertThat(provider.name()).isEqualTo("contract");
        assertThat(provider.active()).isTrue();
        assertThat(provider.models()).hasSameSizeAs(contract.get("catalogue"));
        int i = 0;
        for (JsonNode want : contract.get("catalogue")) {
            ModelInfo got = provider.models().get(i++);
            assertThat(got.id()).isEqualTo(want.get("id").asText());
            assertThat(got.kind()).isEqualTo(want.get("kind").asText());
            assertThat(got.qualifiedId()).isEqualTo(want.get("qualified_id").asText());
        }
        assertThat(provider.health().ok()).isTrue();
        assertThat(provider.describeConfig()).containsEntry("type", "mock").containsEntry("apiKey", "");
        assertThat(LLMFactory.providerTypes()).contains("mock", "openai", "bedrock");
    }

    @Test
    void chatScenariosGiveTheSameAnswersAsInPython() {
        LLMModel model = provider.chatModel(""); // the provider's default
        assertThat(model.info().id()).isEqualTo("chat");
        for (JsonNode s : contract.get("chat")) {
            JsonNode want = s.get("expect");
            ChatCompletionRequest req = ChatCompletionRequest.of("chat", messages(s.get("messages")));
            if (want.has("error")) {
                assertThatThrownBy(() -> model.chatCompletionsCreate(req))
                        .as(s.get("name").asText())
                        .isInstanceOfSatisfying(
                                LlmException.class,
                                e -> assertThat(e.kind().name())
                                        .isEqualTo(want.get("error").asText()));
                continue;
            }
            ChatCompletion c = model.chatCompletionsCreate(req);
            assertThat(c.finishReason())
                    .as(s.get("name").asText())
                    .isEqualTo(want.get("finish_reason").asText());
            if (want.has("text")) {
                assertThat(c.text())
                        .as(s.get("name").asText())
                        .isEqualTo(want.get("text").asText());
            }
            if (want.has("tool")) {
                assertThat(c.toolCalls()).hasSize(1);
                assertThat(c.toolCalls().get(0).function().name())
                        .isEqualTo(want.get("tool").asText());
                assertThat(c.toolCalls().get(0).function().arguments())
                        .isEqualTo(want.get("tool_arguments").asText());
            }
            assertThat(c.usage().totalTokens()).isPositive();
            assertThat(c.pravaha().qualifiedModel()).isEqualTo("contract/chat");
        }
    }

    @Test
    void aStreamIsTheCompletionInChunksAndTheAsyncTwinDeliversThemOnDemand() throws Exception {
        JsonNode s = contract.get("stream");
        LLMModel model = provider.model("chat", "chat");
        Iterator<ChatCompletionChunk> it =
                model.chatCompletionsStream(ChatCompletionRequest.of("chat", messages(s.get("messages"))));
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        it.forEachRemaining(chunks::add);
        assertThat(chunks).hasSize(s.get("chunks").asInt());
        assertThat(String.join(
                        "", chunks.stream().map(ChatCompletionChunk::delta).toList()))
                .isEqualTo(s.get("joined").asText());
        assertThat(chunks.get(chunks.size() - 1).choices().get(0).finishReason())
                .isEqualTo(s.get("last_finish_reason").asText());
        AtomicInteger got = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        model.achatCompletionsStream(ChatCompletionRequest.of("chat", messages(s.get("messages"))))
                .subscribe(new Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(Flow.Subscription sub) {
                        sub.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(ChatCompletionChunk item) {
                        got.incrementAndGet();
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onComplete() {
                        completed.incrementAndGet();
                    }
                });
        assertThat(got.get()).isEqualTo(3);
        assertThat(completed.get()).isEqualTo(1);
        assertThat(model.achatCompletionsCreate(ChatCompletionRequest.of("chat", "", "ping"))
                        .get()
                        .text())
                .isEqualTo("echo: ping");
    }

    @Test
    void embeddingsGiveOneVectorPerInputOfTheConfiguredSize() {
        JsonNode e = contract.get("embeddings");
        List<String> in = new ArrayList<>();
        e.get("input").forEach(x -> in.add(x.asText()));
        EmbeddingsResponse r = provider.embeddingModel("vec").embeddingsCreate(EmbeddingsRequest.of("vec", in));
        assertThat(r.data()).hasSize(e.get("count").asInt());
        assertThat(r.vectors().get(0)).hasSize(e.get("dimensions").asInt());
        assertThat(provider.model("vec", "").info().kind()).isEqualTo("embedding");
    }

    @Test
    void refusalsCarryTheSameKindsAsInPython() {
        for (JsonNode s : contract.get("refusals")) {
            String model = s.get("model").asText();
            assertThatThrownBy(() -> {
                        if ("chat".equals(s.get("call").asText())) {
                            provider.model(model, "")
                                    .chatCompletionsCreate(
                                            ChatCompletionRequest.of(model, List.of(ChatMessage.user("x"))));
                        } else {
                            provider.model(model, "").embeddingsCreate(EmbeddingsRequest.of(model, List.of("x")));
                        }
                    })
                    .as(s.get("name").asText())
                    .isInstanceOfSatisfying(
                            LlmException.class,
                            e -> assertThat(e.kind().name())
                                    .isEqualTo(s.get("error").asText()));
        }
    }

    @Test
    void theFactoryHandsOutGovernedModelsThatGoThroughThePolicy() {
        JsonNode p = contract.get("provider");
        ProviderConfig c = ProviderConfigLoader.fromNode(
                p.get("name").asText(),
                ((com.fasterxml.jackson.databind.node.ObjectNode)
                                p.get("config").deepCopy())
                        .put("type", "mock"),
                k -> null);
        LlmRegistry reg = new LlmRegistry(
                ProviderFactories.discover(List.of()), () -> new ProviderConfigLoader.Loaded(List.of(c), Map.of()), "");
        reg.reload();
        List<DefaultLlmPolicy.AuditRecord> audit = new ArrayList<>();
        PolicySettings ps =
                new PolicySettings(true, List.of(), true, 0, 0, 0, Map.of("secret-feature", Set.of("admin")), Map.of());
        LLMFactory factory = new LLMFactory(
                new LlmGateway(
                        reg,
                        new DefaultLlmPolicy(
                                ps, new PromptMasker(List.of("pin"), List.of()), audit::add, Clock.systemUTC())),
                null);
        LLMModel m = factory.model("default", "chat", new CallContext("ann", Set.of(), "help"));
        assertThat(m).isInstanceOf(GovernedModel.class);
        ChatCompletion c1 = m.chatCompletionsCreate(ChatCompletionRequest.of(null, "", "my pin=1234"));
        assertThat(c1.text()).isEqualTo("echo: my pin=•••");
        assertThat(audit).singleElement().satisfies(a -> assertThat(a.feature()).isEqualTo("help"));
        ChatCompletionRequest asAnother = ChatCompletionRequest.of(null, "", "x")
                .withPravaha(new PravahaRequest(new CallContext("bob", Set.of(), "secret-feature"), "t1", null));
        assertThatThrownBy(() -> m.chatCompletionsCreate(asAnother))
                .isInstanceOfSatisfying(
                        LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.POLICY));
        assertThat(factory.model("vec", "embedding", null)
                        .embeddingsCreate(EmbeddingsRequest.of("vec", List.of("a")))
                        .data())
                .hasSize(1);
        assertThat(factory.chatCompletionsCreate(ChatCompletionRequest.of(null, "", "hello"))
                        .text())
                .isEqualTo("Hi there");
        assertThat(factory.providerNames()).containsExactly("contract");
        assertThat(factory.provider("contract")).isPresent();
        assertThat(factory.provider("nope")).isEmpty();

        LLMFactory off =
                new LLMFactory(new LlmGateway(reg, com.ash.messaging.pravaha.ai.LlmPolicy.none(), () -> false), null);
        assertThatThrownBy(off::model).hasMessageStartingWith("PRV-1080");
    }
}
