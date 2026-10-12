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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.ai.LlmException;
import com.ash.messaging.pravaha.ai.ProviderConfig;
import com.ash.messaging.pravaha.ai.ProviderConfigLoader;
import com.ash.messaging.pravaha.ai.ProviderFactories;
import com.ash.messaging.pravaha.ai.ProviderFactory;

/**
 * The provider registry: types to {@link LLMProviderFactory}. The shipped types are there from the start;
 * {@link #register} adds or replaces one (Python's {@code register_provider}).
 */
public final class LLMProviders {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, LLMProviderFactory> FACTORIES = new ConcurrentHashMap<>();

    static {
        for (ProviderFactory f : ProviderFactories.discover(List.of())) {
            FACTORIES.putIfAbsent(f.type().toLowerCase(Locale.ROOT), wrap(f, System::getenv));
        }
    }

    private LLMProviders() {}

    /** Adds or replaces the factory of {@code factory.type()}. */
    public static void register(LLMProviderFactory factory) {
        FACTORIES.put(factory.type().toLowerCase(Locale.ROOT), factory);
    }

    /** Removes a type. */
    public static void unregister(String type) {
        FACTORIES.remove(type.toLowerCase(Locale.ROOT));
    }

    /** Type to factory, sorted. */
    public static Map<String, LLMProviderFactory> registered() {
        return new TreeMap<>(FACTORIES);
    }

    /** Python's {@code LLMProvider.from_settings(name, config)} for {@code type}. */
    public static LLMProvider fromSettings(@Nullable String type, String name, Map<String, Object> config) {
        LLMProviderFactory f = FACTORIES.get(type == null ? "" : type.toLowerCase(Locale.ROOT));
        if (f == null) {
            throw new LlmException(
                    LlmException.Kind.CONFIG,
                    "no provider type '" + type + "' is installed (have: "
                            + registered().keySet() + ")");
        }
        return f.fromSettings(name, config);
    }

    /** A wire-level factory as a canonical one; the settings block is the same one a provider file holds. */
    public static LLMProviderFactory wrap(ProviderFactory wire, Function<String, @Nullable String> env) {
        return new LLMProviderFactory() {
            @Override
            public String type() {
                return wire.type();
            }

            @Override
            public LLMProvider fromSettings(String name, Map<String, Object> config) {
                JsonNode node = JSON.valueToTree(config);
                if (node instanceof ObjectNode o && !o.hasNonNull("type")) {
                    o.put("type", wire.type());
                }
                ProviderConfig c = ProviderConfigLoader.fromNode(name, node, env);
                return new ProviderBase(c, wire.create(c));
            }
        };
    }
}
