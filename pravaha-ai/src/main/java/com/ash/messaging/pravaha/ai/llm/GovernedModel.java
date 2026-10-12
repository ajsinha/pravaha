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
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.ai.CallContext;
import com.ash.messaging.pravaha.ai.LlmException;
import com.ash.messaging.pravaha.ai.LlmGateway;
import com.ash.messaging.pravaha.ai.LlmRegistry;
import com.ash.messaging.pravaha.ai.llm.Canonical.PravahaRequest;

/**
 * An {@link LLMModel} that every call goes through the AI policy for: masks, audit, per-user and
 * per-feature limits, feature roles, health (the gateway's work), so application code can hold an
 * {@code LLMModel} and never know which provider is behind it. The caller comes from the request's
 * {@code pravaha.context}, else the context this was made with.
 */
public final class GovernedModel extends ChatModel {

    private final LlmGateway gateway;
    private final CallContext defaultContext;
    private final @Nullable String ref;
    private final @Nullable Duration timeout;

    GovernedModel(
            LlmGateway gateway, CallContext context, @Nullable String ref, ModelInfo info, @Nullable Duration timeout) {
        super(info);
        this.gateway = gateway;
        this.defaultContext = context;
        this.ref = ref;
        this.timeout = timeout;
    }

    private CallContext caller(@Nullable PravahaRequest p) {
        CallContext c = p == null ? null : p.context();
        return c != null ? c : defaultContext;
    }

    @Override
    protected ChatCompletion create(ChatCompletionRequest request) {
        CallContext ctx = caller(request.pravaha());
        long t0 = System.nanoTime();
        com.ash.messaging.pravaha.ai.ChatResponse r = gateway.chat(ctx, Convert.toLegacy(request, ref, timeout));
        LlmRegistry.Resolved res = gateway.registry().chat(ref);
        return Convert.fromLegacy(r, res.info(), (System.nanoTime() - t0) / 1_000_000L);
    }

    @Override
    public EmbeddingsResponse embeddingsCreate(EmbeddingsRequest request) {
        gateway.requireEnabled();
        LlmRegistry reg = gateway.registry();
        String target = ref == null || ref.isBlank() ? request.model() : ref;
        Optional<com.ash.messaging.pravaha.ai.EmbeddingModel> m = reg.embedding(target);
        if (m.isEmpty()) {
            throw new LlmException(LlmException.Kind.NOT_FOUND, "no embedding model '" + target + "' is configured");
        }
        List<float[]> v = m.get().embed(request.texts());
        EmbeddingModel shim = new EmbeddingModel(Convert.info(m.get().info())) {
            @Override
            protected List<float[]> embed(List<String> texts) {
                return v;
            }
        };
        return shim.embeddingsCreate(request);
    }
}
