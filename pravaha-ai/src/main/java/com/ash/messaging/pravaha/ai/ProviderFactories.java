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

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** The provider types installed: those given, those listed in {@code META-INF/services}, and the OpenAI-compatible family. */
public final class ProviderFactories {

    private ProviderFactories() {}

    /** The types served by {@link OpenAiCompatProvider}: OpenAI itself, the presets of sajhamcpserver, Mistral and the generic one. */
    static final List<String> OPENAI_COMPATIBLE = List.of(
            "openai",
            "openai_compatible",
            "groq",
            "together",
            "fireworks",
            "deepseek",
            "xai",
            "openrouter",
            "perplexity",
            "huggingface",
            "vllm",
            "lmstudio",
            "mistral");

    /** Every factory: {@code extra} first (it wins a type it shares), then the service-loaded ones, then the OpenAI family and Azure. */
    public static List<ProviderFactory> discover(List<ProviderFactory> extra) {
        List<ProviderFactory> all = new ArrayList<>(extra);
        for (ProviderFactory f : ServiceLoader.load(ProviderFactory.class, ProviderFactories.class.getClassLoader())) {
            all.add(f);
        }
        OPENAI_COMPATIBLE.forEach(t -> all.add(OpenAiCompatProvider.factory(t)));
        all.add(AzureOpenAiProvider.azureFactory());
        return all;
    }
}
