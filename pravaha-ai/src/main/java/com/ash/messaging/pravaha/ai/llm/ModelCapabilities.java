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

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What a model declares it can do (costs per million tokens, the unit sajhamcpserver uses).
 *
 * @param chat chats
 * @param tools calls tools
 * @param structuredOutput honours a JSON schema
 * @param vision reads images
 * @param streaming streams natively
 * @param embedding embeds
 * @param contextWindow tokens it reads, 0 when unknown
 * @param maxOutputTokens tokens it may answer
 * @param inputCostPerMtok money per million prompt tokens
 * @param outputCostPerMtok money per million answer tokens
 * @param dimensions vector size, for an embedding model
 * @param temperature takes a temperature
 * @param forcedToolChoice takes a forced tool choice
 * @param tags free labels
 * @param jsonMode has JSON mode, or null when unknown
 */
public record ModelCapabilities(
        boolean chat,
        boolean tools,
        boolean structuredOutput,
        boolean vision,
        boolean streaming,
        boolean embedding,
        int contextWindow,
        int maxOutputTokens,
        double inputCostPerMtok,
        double outputCostPerMtok,
        int dimensions,
        boolean temperature,
        boolean forcedToolChoice,
        Set<String> tags,
        @Nullable Boolean jsonMode) {

    public ModelCapabilities(
            boolean chat,
            boolean tools,
            boolean structuredOutput,
            boolean vision,
            boolean streaming,
            boolean embedding,
            int contextWindow,
            int maxOutputTokens,
            double inputCostPerMtok,
            double outputCostPerMtok,
            int dimensions,
            boolean temperature,
            boolean forcedToolChoice,
            @Nullable Set<String> tags,
            @Nullable Boolean jsonMode) {
        this.chat = chat;
        this.tools = tools;
        this.structuredOutput = structuredOutput;
        this.vision = vision;
        this.streaming = streaming;
        this.embedding = embedding;
        this.contextWindow = contextWindow;
        this.maxOutputTokens = maxOutputTokens;
        this.inputCostPerMtok = inputCostPerMtok;
        this.outputCostPerMtok = outputCostPerMtok;
        this.dimensions = dimensions;
        this.temperature = temperature;
        this.forcedToolChoice = forcedToolChoice;
        this.tags = tags == null ? Set.of() : Set.copyOf(tags);
        this.jsonMode = jsonMode;
    }

    /** A plain chat model. */
    public static ModelCapabilities chatModel() {
        return new ModelCapabilities(
                true, false, false, false, true, false, 0, 4096, 0, 0, 0, true, true, Set.of(), null);
    }

    /** What a call of these token counts costs. */
    public double cost(int inputTokens, int outputTokens) {
        return (inputTokens * inputCostPerMtok + outputTokens * outputCostPerMtok) / 1_000_000.0;
    }
}
