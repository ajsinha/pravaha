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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Flow;

import com.ash.messaging.pravaha.ai.LlmException;
import com.ash.messaging.pravaha.ai.llm.Canonical.EmbeddingData;
import com.ash.messaging.pravaha.ai.llm.Canonical.EmbeddingsUsage;

/** Implements {@link LLMModel} for an embedding model: a subclass writes {@link #embed}; chat is refused. */
public abstract class EmbeddingModel implements LLMModel {

    private final ModelInfo info;

    protected EmbeddingModel(ModelInfo info) {
        this.info = info;
    }

    @Override
    public ModelInfo info() {
        return info;
    }

    /** One vector per text, in order. */
    protected abstract List<float[]> embed(List<String> texts);

    @Override
    public EmbeddingsResponse embeddingsCreate(EmbeddingsRequest request) {
        List<float[]> vectors = embed(request.texts());
        List<EmbeddingData> data = new ArrayList<>();
        int chars = 0;
        for (int i = 0; i < vectors.size(); i++) {
            List<Double> v = new ArrayList<>();
            for (float f : vectors.get(i)) {
                v.add((double) f);
            }
            data.add(new EmbeddingData(null, v, i));
        }
        for (String t : request.texts()) {
            chars += t.length();
        }
        return new EmbeddingsResponse(
                null, data, info.id(), new EmbeddingsUsage((chars + 3) / 4, (chars + 3) / 4), null);
    }

    private LlmException noChat() {
        return new LlmException(
                LlmException.Kind.UNSUPPORTED,
                "model " + info.qualifiedId() + " is an embedding model; it cannot chat");
    }

    @Override
    public ChatCompletion chatCompletionsCreate(ChatCompletionRequest request) {
        throw noChat();
    }

    @Override
    public Iterator<ChatCompletionChunk> chatCompletionsStream(ChatCompletionRequest request) {
        throw noChat();
    }

    @Override
    public Flow.Publisher<ChatCompletionChunk> achatCompletionsStream(ChatCompletionRequest request) {
        throw noChat();
    }
}
