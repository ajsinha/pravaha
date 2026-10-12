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

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * A model as the administrator named it.
 *
 * @param name the logical name (what callers use)
 * @param provider the provider's name
 * @param id the provider's own model id (what goes on the wire)
 * @param kind {@code chat} or {@code embedding}
 * @param capabilities what it can do
 * @param maxTokens the default answer limit, or null
 * @param temperature the default temperature, or null
 * @param costPer1kInput money per 1,000 prompt tokens
 * @param costPer1kOutput money per 1,000 answer tokens
 */
public record ModelInfo(
        String name,
        String provider,
        String id,
        String kind,
        Set<Capability> capabilities,
        @Nullable Integer maxTokens,
        @Nullable Double temperature,
        double costPer1kInput,
        double costPer1kOutput) {

    public ModelInfo(
            String name,
            String provider,
            String id,
            String kind,
            @Nullable Set<Capability> capabilities,
            @Nullable Integer maxTokens,
            @Nullable Double temperature,
            double costPer1kInput,
            double costPer1kOutput) {
        this.name = name;
        this.provider = provider;
        this.id = id;
        this.kind = kind;
        this.capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.costPer1kInput = costPer1kInput;
        this.costPer1kOutput = costPer1kOutput;
    }

    /** {@code provider/name}: the unambiguous reference. */
    @com.fasterxml.jackson.annotation.JsonProperty("ref")
    public String ref() {
        return provider + "/" + name;
    }

    /** Whether this is an embedding model. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean embedding() {
        return "embedding".equals(kind);
    }

    /** What a call of these token counts costs. */
    public double cost(int promptTokens, int completionTokens) {
        return promptTokens / 1000.0 * costPer1kInput + completionTokens / 1000.0 * costPer1kOutput;
    }
}
