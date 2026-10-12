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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * {@code type: azure_openai}: Azure OpenAI. The OpenAI wire format with Azure's addressing and
 * authentication. Settings: {@code base-url} (the resource, https://&lt;name&gt;.openai.azure.com; also
 * {@code AZURE_OPENAI_ENDPOINT}), {@code api-style} ({@code v1}: /openai/v1/... with the deployment as
 * {@code model}; or {@code deployments}: /openai/deployments/&lt;dep&gt;/...?api-version=), {@code api-version}
 * (2024-10-21), {@code deployments.<model id>: <deployment name>} (each is also a model of this resource),
 * {@code auth} ({@code api_key}: the {@code api-key} header; {@code bearer}; {@code entra}: a Microsoft
 * Entra ID token, see {@link EntraTokenSource}), {@code max-tokens-param} ({@code max_completion_tokens}).
 */
public final class AzureOpenAiProvider extends OpenAiCompatProvider {

    private final String style;
    private final String auth;
    private final @Nullable EntraTokenSource entra;

    AzureOpenAiProvider(ProviderConfig c) {
        super(c, c.baseUrl(), "/chat/completions", "/embeddings", "max_completion_tokens");
        this.style = c.setting("api-style", "v1").toLowerCase(Locale.ROOT);
        this.auth = c.setting("auth", "api_key").toLowerCase(Locale.ROOT);
        if (!List.of("v1", "deployments").contains(style)
                || !List.of("api_key", "bearer", "entra").contains(auth)) {
            throw new LlmException(
                    LlmException.Kind.CONFIG, "api-style is v1 or deployments; auth is api_key, bearer or entra");
        }
        this.entra = "entra".equals(auth) ? new EntraTokenSource(http, c) : null;
    }

    private String deployment(ModelInfo info) {
        return config.setting("deployments." + info.id(), info.id());
    }

    private String urlFor(ModelInfo info, String what) {
        return "v1".equals(style)
                ? http.base() + "/openai/v1/" + what
                : http.base() + "/openai/deployments/" + deployment(info) + "/" + what + "?api-version="
                        + config.setting("api-version", "2024-10-21");
    }

    @Override
    protected String chatUrl(ModelInfo info) {
        return urlFor(info, "chat/completions");
    }

    @Override
    protected String embeddingsUrl(ModelInfo info) {
        return urlFor(info, "embeddings");
    }

    @Override
    protected String wireId(ModelInfo info) {
        return deployment(info);
    }

    @Override
    protected Map<String, String> authHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        EntraTokenSource tokens = entra;
        if (tokens != null) {
            h.put("Authorization", "Bearer " + tokens.token());
        } else if (!config.apiKey().isEmpty()) {
            if ("bearer".equals(auth)) {
                h.put("Authorization", "Bearer " + config.apiKey());
            } else {
                h.put("api-key", config.apiKey());
            }
        }
        return h;
    }

    @Override
    public Readiness check() {
        EntraTokenSource tokens = entra;
        if (tokens != null) {
            return tokens.check();
        }
        return config.apiKey().isEmpty()
                ? new Readiness(false, "no api key: set api-key: ${AZURE_OPENAI_API_KEY} (or auth: entra)")
                : new Readiness(true, "configured; no call is made to find out");
    }

    @Override
    public Readiness probe() {
        return check(); // Azure has no model-list call every key may make
    }

    @Override
    public List<LiveModel> liveModels() {
        return List.of(); // the deployments of the file are this resource's models
    }

    /** {@code type: azure_openai}. */
    public static ProviderFactory azureFactory() {
        return new ProviderFactory() {
            @Override
            public String type() {
                return "azure_openai";
            }

            @Override
            public LlmProvider create(ProviderConfig config) {
                if (config.baseUrl().isEmpty()) {
                    throw new LlmException(
                            LlmException.Kind.CONFIG,
                            "base-url is required: the Azure OpenAI resource, https://<name>.openai.azure.com (or set AZURE_OPENAI_ENDPOINT)");
                }
                return new AzureOpenAiProvider(config);
            }
        };
    }
}
