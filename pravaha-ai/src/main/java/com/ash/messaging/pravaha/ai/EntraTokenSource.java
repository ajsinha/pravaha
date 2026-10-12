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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Microsoft Entra ID tokens for Azure OpenAI, over the provider's own HTTP client (so the proxy and TLS
 * settings apply) and no SDK. Two flows: {@code client_secret} (the client-credentials grant with
 * {@code tenant-id}, {@code client-id} and {@code client-secret} as an {@code ${ENV}}) and
 * {@code workload_identity} (the same grant with a federated token read from {@code federated-token-file}).
 * {@code auto} takes the secret if there is one, else the federated file. <b>Managed identity is not
 * built.</b> A token is cached until a minute before it expires.
 */
final class EntraTokenSource {

    private static final String ASSERTION = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private final JsonHttp http;
    private final String mode;
    private final String tenant;
    private final String clientId;
    private final String secret;
    private final String federated;
    private final String scope;
    private final String authority;
    private final ReentrantLock lock = new ReentrantLock();
    private String token = "";
    private long expiresAtMillis;

    EntraTokenSource(JsonHttp http, ProviderConfig c) {
        this.http = http;
        this.mode = c.setting("entra-mode", "auto").toLowerCase(Locale.ROOT);
        this.tenant = c.setting("tenant-id", "");
        this.clientId = c.setting("client-id", "");
        this.secret = c.setting("client-secret", "");
        this.federated = c.setting("federated-token-file", "");
        this.scope = c.setting("entra-scope", "https://cognitiveservices.azure.com/.default");
        this.authority = c.setting("entra-authority", "https://login.microsoftonline.com")
                .replaceAll("/+$", "");
    }

    String effectiveMode() {
        if ("auto".equals(mode)) {
            return !secret.isEmpty()
                    ? "client_secret"
                    : !federated.isEmpty() ? "workload_identity" : "managed_identity";
        }
        return mode;
    }

    LlmProvider.Readiness check() {
        String m = effectiveMode();
        if ("managed_identity".equals(m)) {
            return new LlmProvider.Readiness(
                    false, "entra: managed identity is not built; give client-secret or federated-token-file");
        }
        if (tenant.isEmpty() || clientId.isEmpty()) {
            return new LlmProvider.Readiness(
                    false, "entra: tenant-id and client-id are required (AZURE_TENANT_ID, AZURE_CLIENT_ID)");
        }
        if ("client_secret".equals(m) && secret.isEmpty()) {
            return new LlmProvider.Readiness(false, "entra: client-secret (an ${ENV} placeholder) is required");
        }
        if ("workload_identity".equals(m) && federated.isEmpty()) {
            return new LlmProvider.Readiness(false, "entra: federated-token-file is required");
        }
        return new LlmProvider.Readiness(true, "entra (" + m + ")");
    }

    /** A token, fetched again a minute before it expires. A lock, not {@code synchronized}: virtual threads call this. */
    String token() {
        lock.lock();
        try {
            return fetchToken();
        } finally {
            lock.unlock();
        }
    }

    private String fetchToken() {
        if (!token.isEmpty() && System.currentTimeMillis() < expiresAtMillis - 60_000) {
            return token;
        }
        LlmProvider.Readiness r = check();
        if (!r.ok()) {
            throw new LlmException(LlmException.Kind.CONFIG, r.detail());
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", clientId);
        form.put("scope", scope);
        if ("client_secret".equals(effectiveMode())) {
            form.put("client_secret", secret);
        } else {
            try {
                form.put("client_assertion_type", ASSERTION);
                form.put(
                        "client_assertion",
                        Files.readString(Path.of(federated), StandardCharsets.UTF_8)
                                .strip());
            } catch (IOException e) {
                throw new LlmException(LlmException.Kind.CONFIG, "entra: the federated token file cannot be read");
            }
        }
        JsonNode j;
        try {
            j = http.postForm(authority + "/" + tenant + "/oauth2/v2.0/token", form, Duration.ofSeconds(30));
        } catch (LlmException e) {
            throw new LlmException(
                    e.kind(),
                    "entra: the token request failed (" + e.detail().replaceFirst("^the model endpoint ", "")
                            + "); check tenant-id, client-id and the secret");
        }
        if (!j.path("access_token").isTextual()) {
            throw new LlmException(LlmException.Kind.FAILED, "entra: the token answer is unreadable");
        }
        token = j.get("access_token").asText();
        expiresAtMillis =
                System.currentTimeMillis() + (long) (j.path("expires_in").asDouble(3600) * 1000);
        return token;
    }
}
