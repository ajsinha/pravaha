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

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.ai.CallContext;
import com.ash.messaging.pravaha.ai.LlmGateway;
import com.ash.messaging.pravaha.ai.LlmRegistry;
import com.ash.messaging.pravaha.ai.llm.Canonical.PravahaRequest;

/**
 * The one door application code uses: {@link #model} hands out {@link GovernedModel}s (policy applied),
 * {@link #provider} the configured providers. Mirrors {@code LLMFactory} in the Python SDK's
 * {@code pravaha/ai/llm/factory.py}, whose passthrough methods are here too, for the default model.
 */
public final class LLMFactory {

    private final LlmGateway gateway;
    private final @Nullable Duration timeout;

    public LLMFactory(LlmGateway gateway, @Nullable Duration timeout) {
        this.gateway = gateway;
        this.timeout = timeout;
    }

    /** The gateway every model goes through. */
    public LlmGateway gateway() {
        return gateway;
    }

    /** A governed model by {@code provider/model}, a model name only one provider has, or (blank or {@code default}) the layer's default; {@code kind} is chat or embedding. */
    public LLMModel model(@Nullable String name, String kind, @Nullable CallContext context) {
        gateway.requireEnabled();
        CallContext caller = context == null ? new CallContext(null, null, null) : context;
        String ref = name == null || name.isBlank() || "default".equals(name) ? null : name;
        if ("embedding".equals(kind)) {
            return new GovernedModel(
                    gateway,
                    caller,
                    ref,
                    new ModelInfo(name == null ? "embedding" : name, "", "embedding", null, null, null),
                    timeout);
        }
        LlmRegistry.Resolved r = gateway.registry().chat(ref);
        return new GovernedModel(gateway, caller, ref, Convert.info(r.info()), timeout);
    }

    /** The default chat model, governed. */
    public LLMModel model() {
        return model(null, "chat", null);
    }

    /** A configured provider, as an {@link LLMProvider} (usable ones only). */
    public Optional<LLMProvider> provider(String name) {
        return gateway.registry()
                .provider(name)
                .filter(LlmRegistry.Entry::usable)
                .map(e -> new ProviderBase(e.config(), e.built()));
    }

    /** The usable providers' names. */
    public List<String> providerNames() {
        return gateway.registry().entries().stream()
                .filter(LlmRegistry.Entry::usable)
                .map(e -> e.config().name())
                .toList();
    }

    /** The shipped and registered provider types. */
    public static List<String> providerTypes() {
        return List.copyOf(LLMProviders.registered().keySet());
    }

    // passthrough to the default model, as the caller in the request (pravaha.context) or "anonymous"

    /** POST /v1/chat/completions on the model the request names. */
    public ChatCompletion chatCompletionsCreate(ChatCompletionRequest request) {
        return model(request.model(), "chat", callerOf(request.pravaha())).chatCompletionsCreate(request);
    }

    /** The stream of a completion. */
    public Iterator<ChatCompletionChunk> chatCompletionsStream(ChatCompletionRequest request) {
        return model(request.model(), "chat", callerOf(request.pravaha())).chatCompletionsStream(request);
    }

    /** The async stream of a completion. */
    public Flow.Publisher<ChatCompletionChunk> achatCompletionsStream(ChatCompletionRequest request) {
        return model(request.model(), "chat", callerOf(request.pravaha())).achatCompletionsStream(request);
    }

    /** POST /v1/embeddings on the model the request names. */
    public EmbeddingsResponse embeddingsCreate(EmbeddingsRequest request) {
        return model(request.model(), "embedding", callerOf(request.pravaha())).embeddingsCreate(request);
    }

    private static CallContext callerOf(@Nullable PravahaRequest p) {
        CallContext c = p == null ? null : p.context();
        return c != null ? c : new CallContext(null, null, null);
    }
}
