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

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.ai.llm.Canonical.EmbeddingData;
import com.ash.messaging.pravaha.ai.llm.Canonical.EmbeddingsUsage;
import com.ash.messaging.pravaha.ai.llm.Canonical.ResponsePravaha;

/**
 * The answer to an embeddings request.
 *
 * @param object always {@code list}
 * @param data one per input
 * @param model the model
 * @param usage tokens
 * @param pravaha the extension
 */
public record EmbeddingsResponse(
        String object,
        List<EmbeddingData> data,
        String model,
        EmbeddingsUsage usage,
        @Nullable ResponsePravaha pravaha) {

    public EmbeddingsResponse(
            @Nullable String object,
            @Nullable List<EmbeddingData> data,
            String model,
            @Nullable EmbeddingsUsage usage,
            @Nullable ResponsePravaha pravaha) {
        this.object = object == null ? "list" : object;
        this.data = data == null ? List.of() : List.copyOf(data);
        this.model = model;
        this.usage = usage == null ? new EmbeddingsUsage(0, 0) : usage;
        this.pravaha = pravaha;
    }

    /** One vector per input, in order. */
    public List<float[]> vectors() {
        return data.stream().map(EmbeddingData::vector).toList();
    }
}
