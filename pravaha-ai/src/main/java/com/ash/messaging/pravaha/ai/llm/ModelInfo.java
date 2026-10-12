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

import org.jspecify.annotations.Nullable;

/**
 * One model as {@code GET /v1/models} would list it. {@code kind} is chat or embedding.
 *
 * @param id the model's id within its provider
 * @param provider the provider's name
 * @param kind chat or embedding
 * @param capabilities what it can do
 * @param displayName the name to show
 * @param source {@code config} (the file) or {@code live} (the endpoint's own list)
 */
public record ModelInfo(
        String id, String provider, String kind, ModelCapabilities capabilities, String displayName, String source) {

    public ModelInfo(
            String id,
            String provider,
            @Nullable String kind,
            @Nullable ModelCapabilities capabilities,
            @Nullable String displayName,
            @Nullable String source) {
        this.id = id;
        this.provider = provider;
        this.kind = kind == null || kind.isBlank() ? "chat" : kind;
        this.capabilities = capabilities == null ? ModelCapabilities.chatModel() : capabilities;
        this.displayName = displayName == null || displayName.isBlank() ? id : displayName;
        this.source = source == null || source.isBlank() ? "config" : source;
    }

    /** {@code provider/id}. */
    public String qualifiedId() {
        return provider + "/" + id;
    }
}
