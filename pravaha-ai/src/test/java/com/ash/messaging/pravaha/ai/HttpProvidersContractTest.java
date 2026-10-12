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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import com.ash.messaging.pravaha.ai.llm.ChatCompletion;
import com.ash.messaging.pravaha.ai.llm.ChatCompletionChunk;
import com.ash.messaging.pravaha.ai.llm.ChatCompletionRequest;
import com.ash.messaging.pravaha.ai.llm.EmbeddingsRequest;
import com.ash.messaging.pravaha.ai.llm.LLMModel;
import com.ash.messaging.pravaha.ai.llm.ProviderBase;

import static com.ash.messaging.pravaha.ai.llm.Canonical.ChatMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared HTTP contract ({@code contract/llm_contract_http.json}) against every provider it lists, each
 * talking to a fake of its own API. The Python SDK runs the same file ({@code tests/test_ai_providers.py});
 * if the two layers drift, one of them fails.
 */
class HttpProvidersContractTest {

    @Test
    void everyProviderInTheContractAnswersTheSameScenariosTheSameWay() throws Exception {
        JsonNode contract;
        try (InputStream in = HttpProvidersContractTest.class.getResourceAsStream("/contract/llm_contract_http.json")) {
            contract = LlmFakes.JSON.readTree(in);
        }
        int checked = 0;
        for (JsonNode p : contract.get("providers")) {
            try (LlmFakes fake = LlmFakes.start(p.get("fake").asText())) {
                var node = (com.fasterxml.jackson.databind.node.ObjectNode)
                        p.get("config").deepCopy();
                node.put("type", p.get("type").asText())
                        .put("base-url", fake.url())
                        .put("timeout", "5s");
                if (p.get("type").asText().equals("bedrock")) {
                    node.put("control-url", fake.url());
                }
                ProviderConfig c =
                        ProviderConfigLoader.fromNode(contract.get("name").asText(), node, HttpProvidersTest.ENV::get);
                LlmProvider wire = ProviderFactories.discover(List.of()).stream()
                        .filter(f -> f.type().equals(c.type()))
                        .findFirst()
                        .orElseThrow()
                        .create(c);
                ProviderBase provider = new ProviderBase(c, wire);
                String label = p.get("label").asText();
                assertThat(provider.models())
                        .as(label)
                        .hasSize(contract.get("catalogue").size());
                LLMModel model = provider.chatModel("");
                for (JsonNode s : contract.get("chat")) {
                    JsonNode want = s.get("expect");
                    ChatCompletionRequest req = ChatCompletionRequest.of("chat", messages(s.get("messages")));
                    if (want.has("error")) {
                        assertThatThrownBy(() -> model.chatCompletionsCreate(req))
                                .as(label + ": " + s.get("name").asText())
                                .isInstanceOfSatisfying(
                                        LlmException.class,
                                        e -> assertThat(e.kind().name())
                                                .isEqualTo(want.get("error").asText()));
                        continue;
                    }
                    ChatCompletion cc = model.chatCompletionsCreate(req);
                    assertThat(cc.finishReason())
                            .as(label + ": " + s.get("name").asText())
                            .isEqualTo(want.get("finish_reason").asText());
                    if (want.has("text")) {
                        assertThat(cc.text())
                                .as(label)
                                .isEqualTo(want.get("text").asText());
                    }
                    if (want.has("tool")) {
                        assertThat(cc.toolCalls()).as(label).hasSize(1);
                        assertThat(cc.toolCalls().get(0).function().name())
                                .isEqualTo(want.get("tool").asText());
                        assertThat(LlmFakes.JSON.readTree(
                                        cc.toolCalls().get(0).function().arguments()))
                                .isEqualTo(LlmFakes.JSON.readTree(
                                        want.get("tool_arguments").asText()));
                    }
                    checked++;
                }
                JsonNode stream = contract.get("stream");
                List<ChatCompletionChunk> chunks = new ArrayList<>();
                model.chatCompletionsStream(ChatCompletionRequest.of("chat", messages(stream.get("messages"))))
                        .forEachRemaining(chunks::add);
                assertThat(chunks).as(label).hasSize(stream.get("chunks").asInt());
                assertThat(String.join(
                                "",
                                chunks.stream().map(ChatCompletionChunk::delta).toList()))
                        .isEqualTo(stream.get("joined").asText());
                if (p.path("embeddings").asBoolean(true)) {
                    JsonNode e = contract.get("embeddings");
                    List<String> texts = new ArrayList<>();
                    e.get("input").forEach(x -> texts.add(x.asText()));
                    var r = provider.embeddingModel("vec").embeddingsCreate(EmbeddingsRequest.of("vec", texts));
                    assertThat(r.data()).as(label).hasSize(e.get("count").asInt());
                    assertThat(r.vectors().get(0))
                            .as(label)
                            .hasSize(e.get("dimensions").asInt());
                }
                for (JsonNode s : contract.get("refusals")) {
                    String m = s.get("model").asText();
                    assertThatThrownBy(() -> {
                                if ("chat".equals(s.get("call").asText())) {
                                    provider.model(m, "")
                                            .chatCompletionsCreate(
                                                    ChatCompletionRequest.of(m, List.of(ChatMessage.user("x"))));
                                } else {
                                    provider.model(m, "").embeddingsCreate(EmbeddingsRequest.of(m, List.of("x")));
                                }
                            })
                            .as(label + ": " + s.get("name").asText())
                            .isInstanceOfSatisfying(
                                    LlmException.class,
                                    e -> assertThat(e.kind().name())
                                            .isEqualTo(s.get("error").asText()));
                }
                provider.close();
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(contract.get("providers").size() * 3);
    }

    static List<ChatMessage> messages(JsonNode m) {
        List<ChatMessage> out = new ArrayList<>();
        m.forEach(x -> out.add(new ChatMessage(x.get(0).asText(), x.get(1).asText(), null, null, null, null)));
        return out;
    }
}
