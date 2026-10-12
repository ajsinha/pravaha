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

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * One provider file, parsed. {@code settings} carries every other key flat ({@code completions-path},
 * {@code mock.rules.0.match}, the shared {@code tls.*} keys), the way connector options do. The API key
 * is already resolved from its {@code ${ENV}} placeholder and is never part of {@link #toString()}.
 *
 * @param name the provider name (the file name without its extension, unless the file says {@code name:})
 * @param type the provider type
 * @param enabled {@code enabled:} in the file, true when absent
 * @param baseUrl the endpoint, or empty for the type's default
 * @param apiKey the resolved key, or empty
 * @param timeout how long a call may take
 * @param retries extra attempts after a 429 or a 5xx
 * @param defaultModel the logical chat model used when a call names none
 * @param settings every other key, flattened
 * @param models the models the file (and the type's catalogue) name
 */
public record ProviderConfig(
        String name,
        String type,
        boolean enabled,
        String baseUrl,
        String apiKey,
        Duration timeout,
        int retries,
        String defaultModel,
        Map<String, String> settings,
        List<ModelConfig> models) {

    /**
     * One model entry.
     *
     * @param name the logical name
     * @param id the vendor's model id
     * @param kind {@code chat} or {@code embedding}
     * @param maxTokens the default answer limit
     * @param temperature the default temperature
     * @param capabilities what it can do; empty is {@code chat} (or {@code embeddings})
     * @param costPer1kInput money per 1,000 prompt tokens
     * @param costPer1kOutput money per 1,000 answer tokens
     */
    public record ModelConfig(
            String name,
            String id,
            String kind,
            @Nullable Integer maxTokens,
            @Nullable Double temperature,
            Set<Capability> capabilities,
            double costPer1kInput,
            double costPer1kOutput) {

        public ModelConfig(
                String name,
                String id,
                @Nullable String kind,
                @Nullable Integer maxTokens,
                @Nullable Double temperature,
                @Nullable Set<Capability> capabilities,
                double costPer1kInput,
                double costPer1kOutput) {
            this.name = name;
            this.id = id;
            this.kind = kind == null || kind.isBlank() ? "chat" : kind.strip().toLowerCase(Locale.ROOT);
            this.maxTokens = maxTokens;
            this.temperature = temperature;
            this.capabilities = capabilities == null || capabilities.isEmpty()
                    ? ("embedding".equals(this.kind) ? Set.of(Capability.EMBEDDINGS) : Set.of(Capability.CHAT))
                    : Set.copyOf(capabilities);
            this.costPer1kInput = costPer1kInput;
            this.costPer1kOutput = costPer1kOutput;
        }
    }

    public ProviderConfig(
            String name,
            String type,
            boolean enabled,
            @Nullable String baseUrl,
            @Nullable String apiKey,
            @Nullable Duration timeout,
            int retries,
            @Nullable String defaultModel,
            @Nullable Map<String, String> settings,
            @Nullable List<ModelConfig> models) {
        this.name = name;
        this.type = type;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(60) : timeout;
        this.retries = Math.max(0, retries);
        this.defaultModel = defaultModel == null ? "" : defaultModel;
        this.settings = settings == null ? Map.of() : Map.copyOf(settings);
        this.models = models == null ? List.of() : List.copyOf(models);
    }

    /** A setting, or {@code dflt} when it is absent or blank. */
    public String setting(String key, String dflt) {
        String v = settings.get(key);
        return v == null || v.isBlank() ? dflt : v;
    }

    @Override
    public String toString() {
        return "ProviderConfig[" + name + ", type=" + type + ", enabled=" + enabled + ", baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey.isEmpty() ? "none" : "set") + ", models=" + models.size() + "]";
    }
}
