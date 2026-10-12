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

/**
 * Implements {@link LLMModel} for a chat model: a subclass writes {@link #create} (Python's {@code _create});
 * streaming is emulated from it unless {@link #stream} is overridden; embeddings are refused.
 */
public abstract class ChatModel implements LLMModel {

    private final ModelInfo info;

    protected ChatModel(ModelInfo info) {
        this.info = info;
    }

    @Override
    public ModelInfo info() {
        return info;
    }

    /** One completion. */
    protected abstract ChatCompletion create(ChatCompletionRequest request);

    /** The stream; the default is the completion cut into role, text and finish chunks. */
    protected Iterator<ChatCompletionChunk> stream(ChatCompletionRequest request) {
        return ChatCompletionChunk.fromCompletion(create(request)).iterator();
    }

    @Override
    public ChatCompletion chatCompletionsCreate(ChatCompletionRequest request) {
        return create(request);
    }

    @Override
    public Iterator<ChatCompletionChunk> chatCompletionsStream(ChatCompletionRequest request) {
        return stream(request);
    }

    @Override
    public Flow.Publisher<ChatCompletionChunk> achatCompletionsStream(ChatCompletionRequest request) {
        List<ChatCompletionChunk> all = new ArrayList<>();
        stream(request).forEachRemaining(all::add);
        return new ListPublisher<>(all);
    }

    @Override
    public EmbeddingsResponse embeddingsCreate(EmbeddingsRequest request) {
        throw new LlmException(
                LlmException.Kind.UNSUPPORTED,
                "model " + info.qualifiedId() + " is a chat model; it has no embeddings");
    }
}
