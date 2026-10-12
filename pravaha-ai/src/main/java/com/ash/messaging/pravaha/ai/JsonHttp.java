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
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP part the JSON providers share (Drishti's {@code JsonHttp}): one JDK client per provider with
 * the file's {@code tls.*} and {@code proxy.*}, JSON and form calls, retries, and the error mapping. A
 * status becomes an {@link LlmException} <b>without the body</b> (a body can echo a prompt): 429 is
 * {@code RATE_LIMITED}, a timeout is {@code TIMEOUT}, everything else {@code FAILED} with a hint for the
 * usual causes (401/403 key or permission, 404 path or model). 429 and 5xx are retried {@code retries} times.
 */
public final class JsonHttp implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 8_000_000;

    private final ProviderConfig config;
    private final HttpClient client;
    private final String base;

    public JsonHttp(ProviderConfig c, String base) {
        this.config = c;
        this.base = base.replaceAll("/+$", "");
        try {
            URI.create(this.base.isEmpty() ? "http://localhost" : this.base);
        } catch (IllegalArgumentException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "base-url is not a valid URL");
        }
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(c.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1);
        ProviderTls.proxy(c).ifPresent(b::proxy);
        ProviderTls.context(c).ifPresent(b::sslContext);
        this.client = b.build();
    }

    /** The endpoint, without a trailing slash. */
    public String base() {
        return base;
    }

    /** The headers every call carries: {@code header.<Name>} from the file. */
    public Map<String, String> fileHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        config.settings().forEach((k, v) -> {
            if (k.startsWith("header.")) {
                h.put(k.substring(7), v);
            }
        });
        return h;
    }

    /** A JSON call. {@code body} may be null (GET). */
    public JsonNode call(
            String method,
            String url,
            @Nullable JsonNode body,
            Map<String, String> headers,
            @Nullable Duration timeout) {
        return call(method, url, body, payload -> headers, timeout);
    }

    /**
     * A JSON call whose authentication depends on the bytes sent (SigV4) or on the moment (a token):
     * {@code headers} is asked once per attempt.
     */
    public JsonNode call(
            String method,
            String url,
            @Nullable JsonNode body,
            Function<byte[], Map<String, String>> headers,
            @Nullable Duration timeout) {
        byte[] payload;
        try {
            payload = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.FAILED, "the request could not be written");
        }
        return send(
                method,
                url,
                body == null ? null : payload,
                p -> {
                    Map<String, String> h = new LinkedHashMap<>();
                    h.put("Content-Type", "application/json");
                    h.put("Accept", "application/json");
                    h.putAll(fileHeaders());
                    h.putAll(headers.apply(p));
                    return h;
                },
                timeout);
    }

    /** A form POST ({@code application/x-www-form-urlencoded}), for token endpoints. */
    public JsonNode postForm(String url, Map<String, String> form, Duration timeout) {
        String payload = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        Map<String, String> h =
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json");
        return send("POST", url, payload.getBytes(StandardCharsets.UTF_8), p -> h, timeout);
    }

    /** The status as an exception: what the caller shows, without the body. */
    public static LlmException statusError(int code) {
        String hint =
                switch (code) {
                    case 401 -> "authentication failed: check the api key and where it is read from";
                    case 403 -> "access denied: the key lacks permission for this model or endpoint";
                    case 404 -> "not found: check base-url, the path and the model id";
                    case 408 -> "the endpoint timed out";
                    case 413 -> "the request is too large";
                    case 429 -> "rate limited: slow down, raise the account limit or add retries";
                    default -> null;
                };
        return new LlmException(
                code == 429 ? LlmException.Kind.RATE_LIMITED : LlmException.Kind.FAILED,
                "the model endpoint answered HTTP " + code + (hint == null ? "" : " (" + hint + ")"));
    }

    private JsonNode send(
            String method,
            String url,
            byte @Nullable [] payload,
            Function<byte[], Map<String, String>> headers,
            @Nullable Duration timeout) {
        LlmException last = null;
        byte[] bytes = payload == null ? new byte[0] : payload;
        for (int attempt = 0; attempt <= config.retries(); attempt++) {
            try {
                HttpRequest.Builder b =
                        HttpRequest.newBuilder(URI.create(url)).timeout(timeout != null ? timeout : config.timeout());
                headers.apply(bytes).forEach(b::header);
                b.method(
                        method,
                        payload == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(payload));
                HttpResponse<InputStream> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                byte[] answer;
                try (InputStream in = resp.body()) {
                    answer = in.readNBytes(MAX_BODY);
                }
                int code = resp.statusCode();
                if (code / 100 != 2) {
                    last = statusError(code);
                    if (code < 500 && code != 429) {
                        throw last;
                    }
                    continue;
                }
                JsonNode j = JSON.readTree(answer);
                if (j == null || !j.isObject()) {
                    last = new LlmException(
                            LlmException.Kind.FAILED, "the model endpoint answered something unreadable");
                    continue;
                }
                return j;
            } catch (HttpTimeoutException | java.net.SocketTimeoutException e) {
                last = new LlmException(LlmException.Kind.TIMEOUT, "the model endpoint did not answer in time", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException(LlmException.Kind.FAILED, "the model call was interrupted", e);
            } catch (IOException e) {
                last = new LlmException(
                        LlmException.Kind.FAILED,
                        "the model endpoint could not be reached or answered something unreadable ("
                                + e.getClass().getSimpleName() + ")");
            } catch (IllegalArgumentException e) {
                throw new LlmException(LlmException.Kind.CONFIG, "base-url is not a valid URL");
            }
        }
        throw last != null ? last : new LlmException(LlmException.Kind.FAILED, "the model call failed");
    }

    @Override
    public void close() {
        client.close();
    }
}
