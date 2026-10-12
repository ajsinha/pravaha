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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * A provider that speaks JSON over HTTP to one vendor API through {@link JsonHttp}: the shared base of the
 * vendor providers. A subclass implements {@link #chat} (and {@link #embed} where the API has
 * embeddings), and may override {@link #check}, {@link #probe} and {@link #liveModels}.
 */
public abstract class HttpProvider extends BaseProvider {

    protected final JsonHttp http;

    protected HttpProvider(ProviderConfig c, String base, Set<Capability> defaultChatCapabilities) {
        super(c, defaultChatCapabilities);
        this.http = new JsonHttp(c, base);
    }

    /** One answer from the vendor. */
    protected abstract ChatResponse chat(ModelInfo info, ChatRequest request);

    /** One vector per text, where the API has embeddings. */
    protected List<float[]> embed(ModelInfo info, List<String> texts) {
        throw new LlmException(LlmException.Kind.UNSUPPORTED, "this provider type has no embeddings");
    }

    @Override
    public Optional<LlmModel> chatModel(String logicalName) {
        return find(logicalName, false).map(info -> new LlmModel() {
            @Override
            public ModelInfo info() {
                return info;
            }

            @Override
            public ChatResponse chat(ChatRequest r) {
                return HttpProvider.this.chat(info, r);
            }
        });
    }

    @Override
    public Optional<EmbeddingModel> embeddingModel(String logicalName) {
        return find(logicalName, true).map(info -> new EmbeddingModel() {
            @Override
            public ModelInfo info() {
                return info;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return HttpProvider.this.embed(info, texts);
            }
        });
    }

    @Override
    public Readiness probe() {
        Readiness r = check();
        if (!r.ok()) {
            return r;
        }
        try {
            return new Readiness(
                    true, "the model list answered (" + liveModels().size() + " models)");
        } catch (LlmException e) {
            return new Readiness(false, e.detail());
        }
    }

    @Override
    public void close() {
        http.close();
    }

    // ---- helpers

    /** How long a call may take: the request's, else the file's. */
    protected Duration timeout(ChatRequest r) {
        Duration t = r.timeout();
        return t != null ? t : config.timeout();
    }

    /** The non-system turns. */
    protected static List<ChatMessage> turns(ChatRequest r) {
        List<ChatMessage> out = new ArrayList<>();
        for (ChatMessage m : r.messages()) {
            if (m.role() != ChatMessage.Role.SYSTEM) {
                out.add(m);
            }
        }
        return out;
    }

    /** Whether a turn is the assistant's. */
    protected static boolean assistant(ChatMessage m) {
        return m.role() == ChatMessage.Role.ASSISTANT;
    }

    /** A tool's parameters as JSON. */
    protected static JsonNode schema(ToolSpec s) {
        String raw = s.parametersJsonSchema();
        try {
            return JsonHttp.JSON.readTree(raw == null ? "{\"type\":\"object\"}" : raw);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.UNSUPPORTED, "tool '" + s.name() + "' has an unreadable schema");
        }
    }

    /** OpenAI-style function tools ({@code type: function}). */
    protected static ArrayNode functionTools(ChatRequest r) {
        ArrayNode tools = JsonHttp.JSON.createArrayNode();
        for (ToolSpec s : r.tools()) {
            ObjectNode f = tools.addObject().put("type", "function").putObject("function");
            String description = s.description();
            f.put("name", s.name()).put("description", description == null ? "" : description);
            f.set("parameters", schema(s));
        }
        return tools;
    }

    /** A JSON value as compact text, {@code {}} when absent. */
    protected static String compact(@Nullable JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? "{}" : n.toString();
    }

    /** Arrays of numbers as vectors, refusing the wrong count. */
    protected static List<float[]> vectors(@Nullable JsonNode arrays, int expected) {
        List<float[]> out = new ArrayList<>();
        if (arrays != null && arrays.isArray()) {
            for (JsonNode v : arrays) {
                float[] f = new float[v.size()];
                for (int i = 0; i < f.length; i++) {
                    f[i] = (float) v.get(i).asDouble();
                }
                out.add(f);
            }
        }
        if (out.size() != expected) {
            throw new LlmException(
                    LlmException.Kind.FAILED, "the model endpoint returned the wrong number of embeddings");
        }
        return out;
    }
}
