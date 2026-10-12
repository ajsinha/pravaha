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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One configured provider (an account at a vendor, a self-hosted server): the models it serves and what it
 * can do. Built by a {@link ProviderFactory} from one {@code <name>.yaml} in {@code pravaha.ai.dir}.
 * Implementations are thread-safe and hold no per-call state.
 */
public interface LlmProvider extends AutoCloseable {

    /** The provider's name (its file name): the first half of {@code provider/model}. */
    String id();

    /** The provider type (the factory that built it): {@code openai}, {@code anthropic}, ... */
    String type();

    /** What its models can do, together. */
    Set<Capability> capabilities();

    /** The models this provider's file names, chat and embedding. */
    List<ModelInfo> models();

    /** A chat model by logical name. */
    Optional<LlmModel> chatModel(String logicalName);

    /** An embedding model by logical name. */
    default Optional<EmbeddingModel> embeddingModel(String logicalName) {
        return Optional.empty();
    }

    /**
     * A readiness answer: is what the type needs (a key, credentials, a region) there?
     *
     * @param ok whether it is
     * @param detail what, or what is missing
     */
    record Readiness(boolean ok, String detail) {}

    /**
     * A model the endpoint reports now (not one of the file's).
     *
     * @param id its own id
     * @param kind {@code chat} or {@code embedding}
     */
    record LiveModel(String id, String kind) {}

    /** No network: the readiness of the configuration. */
    default Readiness check() {
        return new Readiness(true, "configured; no call is made to find out");
    }

    /** One cheap live call to the endpoint (a model list), when the type has one; the readiness check otherwise. Never throws. */
    default Readiness probe() {
        return check();
    }

    /** The models the endpoint reports now; empty when the API has no listing. */
    default List<LiveModel> liveModels() {
        return List.of();
    }

    /** The soonest TLS certificate expiry sentence when less than 30 days away, else empty. */
    default String tlsNote() {
        return "";
    }

    /** The configured TLS certificates ({@code subject}, {@code notAfter}); empty without {@code tls.*}. */
    default List<Map<String, String>> tlsCertificates() {
        return List.of();
    }

    @Override
    default void close() {}
}
