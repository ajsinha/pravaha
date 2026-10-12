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

import com.ash.messaging.pravaha.ai.llm.Canonical.PravahaRequest;

/**
 * POST /v1/embeddings, typed. {@code input} is a list of texts.
 *
 * @param model the model
 * @param input the texts
 * @param encodingFormat float or base64
 * @param dimensions a smaller vector, where the model allows
 * @param user the end user
 * @param pravaha the extension
 */
public record EmbeddingsRequest(
        String model,
        List<String> input,
        @Nullable String encodingFormat,
        @Nullable Integer dimensions,
        @Nullable String user,
        @Nullable PravahaRequest pravaha) {

    public EmbeddingsRequest(
            @Nullable String model,
            @Nullable List<String> input,
            @Nullable String encodingFormat,
            @Nullable Integer dimensions,
            @Nullable String user,
            @Nullable PravahaRequest pravaha) {
        this.model = model == null || model.isBlank() ? "embedding" : model;
        this.input = input == null ? List.of() : List.copyOf(input);
        this.encodingFormat = encodingFormat;
        this.dimensions = dimensions;
        this.user = user;
        this.pravaha = pravaha;
    }

    /** A request of texts. */
    public static EmbeddingsRequest of(@Nullable String model, List<String> input) {
        return new EmbeddingsRequest(model, input, null, null, null, null);
    }

    /** The texts. */
    public List<String> texts() {
        return input;
    }
}
