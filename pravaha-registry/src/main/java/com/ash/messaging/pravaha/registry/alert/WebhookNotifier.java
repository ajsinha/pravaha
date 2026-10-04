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
package com.ash.messaging.pravaha.registry.alert;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

import com.ash.messaging.pravaha.api.ConfigurationException;
import com.ash.messaging.pravaha.api.plugin.HealthStatus;
import com.ash.messaging.pravaha.api.plugin.Notification;
import com.ash.messaging.pravaha.api.plugin.NotifierPlugin;
import com.ash.messaging.pravaha.api.plugin.PluginContext;
import com.ash.messaging.pravaha.api.plugin.Version;

/**
 * {@code webhook}: each notification as an HTTP {@code POST} of JSON, signed (ADR-057).
 *
 * <p><strong>What is sent.</strong> The body is {@link Notification#toJson()} ({@code format: json}, the
 * default) or Slack's incoming-webhook shape, {@code {"text": "<summary>"}} ({@code format: slack}).
 * Headers: {@code Idempotency-Key} -- the same on every attempt, across retries and restarts, so a
 * receiver de-duplicates on it; {@code X-Pravaha-Event} (FIRED, CLEARED, REMINDER); {@code
 * X-Pravaha-Attempt}; {@code X-Pravaha-Timestamp} (seconds since the epoch); and {@code
 * X-Pravaha-Signature: sha256=<hex>}, the HMAC-SHA256 of {@code <timestamp>.<body>} under the channel's
 * secret. A receiver recomputes it and compares in constant time, and refuses a timestamp far from its
 * clock, which is what stops a captured request being replayed.
 *
 * <p><strong>Secrets are never configuration</strong> (ADR-052): the binding names where the secret is --
 * {@code secret-env: NAME} or {@code secret-file: /path} -- and an option called {@code secret}, {@code
 * token} or {@code password} is refused. A URL that is itself a credential (Slack's are) is given the
 * same way: {@code url-env} or {@code url-file}. Signing is required unless the binding says {@code
 * signing: none}.
 *
 * <p><strong>Retries.</strong> A timeout, a network failure, a 5xx, 408 or 429 is retried with backoff
 * ({@code retries}, default 4; {@code backoff}, default 500ms, doubling to {@code max-backoff}, 30s); any
 * other 4xx is the receiver refusing the request and is not. What is still undelivered is the engine's to
 * retry later. Timeouts: {@code connect-timeout} 5s, {@code timeout} 10s per attempt.
 */
public final class WebhookNotifier implements NotifierPlugin {

    /** Options that would put a secret in configuration. */
    private static final java.util.Set<String> REFUSED = java.util.Set.of("secret", "token", "password", "key");

    private final Function<String, String> environment;
    private @Nullable HttpClient client;
    private @Nullable URI url;
    private byte @Nullable [] secret;
    private String format = "json";
    private int retries = 4;
    private Duration backoff = Duration.ofMillis(500);
    private Duration maxBackoff = Duration.ofSeconds(30);
    private Duration timeout = Duration.ofSeconds(10);
    private Duration connectTimeout = Duration.ofSeconds(5);
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String channel = "webhook";

    public WebhookNotifier() {
        this(System::getenv);
    }

    /** With the environment read through {@code environment}: for a test. */
    public WebhookNotifier(Function<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public Version version() {
        return Version.apiVersion();
    }

    @Override
    public void configure(PluginContext context) throws ConfigurationException {
        channel = context.instanceName();
        Map<String, String> options = context.config();
        for (String key : options.keySet()) {
            if (REFUSED.contains(key.toLowerCase(Locale.ROOT))) {
                throw misconfigured("'" + key + "' would put a secret into the configuration, which never holds "
                        + "one (ADR-052). Say where it is instead: secret-env: <VARIABLE> or secret-file: <path>");
            }
        }
        String written = reference(options, "url", "url-env", "url-file", "the URL to POST to");
        if (written == null) {
            throw misconfigured("needs url (or url-env / url-file when the URL is itself a credential)");
        }
        try {
            url = URI.create(written.strip());
        } catch (IllegalArgumentException e) {
            throw misconfigured("its URL is not a URL");
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw misconfigured("its URL must be http or https, not '" + scheme + "'");
        }
        String signing = options.getOrDefault("signing", "hmac-sha256").strip().toLowerCase(Locale.ROOT);
        String key = reference(options, null, "secret-env", "secret-file", "the signing secret");
        if (signing.equals("hmac-sha256")) {
            if (key == null || key.isEmpty()) {
                throw misconfigured("signs every request and has no secret: set secret-env: <VARIABLE> or "
                        + "secret-file: <path>, or signing: none for a receiver that cannot verify a signature");
            }
            secret = key.getBytes(StandardCharsets.UTF_8);
        } else if (!signing.equals("none")) {
            throw misconfigured("signing is hmac-sha256 or none, not '" + signing + "'");
        }
        format = options.getOrDefault("format", "json").strip().toLowerCase(Locale.ROOT);
        if (!format.equals("json") && !format.equals("slack")) {
            throw misconfigured("format is json or slack, not '" + format + "'");
        }
        retries = integer(options, "retries", retries);
        backoff = duration(options, "backoff", backoff);
        maxBackoff = duration(options, "max-backoff", maxBackoff);
        timeout = duration(options, "timeout", timeout);
        connectTimeout = duration(options, "connect-timeout", connectTimeout);
        options.forEach((k, v) -> {
            if (k.startsWith("header.")) {
                headers.put(k.substring("header.".length()), v);
            }
        });
        for (String header : headers.keySet()) {
            String lower = header.toLowerCase(Locale.ROOT);
            if (lower.contains("auth")
                    || lower.contains("token")
                    || lower.contains("secret")
                    || lower.contains("key")
                    || lower.contains("cookie")) {
                throw misconfigured("would send the header '" + header + "' from configuration, and a credential is "
                        + "never configuration (ADR-052); the request is authenticated by its signature");
            }
        }
    }

    @Override
    public void open() {
        client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Delivery send(Notification notification) {
        String body =
                format.equals("slack") ? "{\"text\":" + Notification.jsonString(notification.summary()) + "}" : null;
        long wait = backoff.toMillis();
        String last = "";
        int attempt = 0;
        while (true) {
            attempt++;
            Notification sent = notification.attempt(attempt);
            String payload = body != null ? body : sent.toJson();
            String timestamp = Long.toString(java.time.Instant.now().getEpochSecond());
            HttpRequest.Builder request = HttpRequest.newBuilder(url)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Pravaha-Alerts/1")
                    .header("Idempotency-Key", notification.idempotencyKey())
                    .header("X-Pravaha-Event", notification.kind())
                    .header("X-Pravaha-Alert", notification.alert())
                    .header("X-Pravaha-Attempt", Integer.toString(attempt))
                    .header("X-Pravaha-Timestamp", timestamp)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            if (secret != null) {
                request.header("X-Pravaha-Signature", "sha256=" + sign(secret, timestamp + "." + payload));
            }
            headers.forEach(request::header);
            boolean retryable;
            try {
                HttpResponse<Void> response = java.util.Objects.requireNonNull(client, "configured")
                        .send(request.build(), HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return Delivery.delivered(attempt, "HTTP " + status);
                }
                last = "HTTP " + status;
                retryable = status >= 500 || status == 408 || status == 429;
            } catch (IOException e) {
                // Never the URL: it can be a credential (url-env exists for that reason).
                last = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage())
                                .replace(
                                        java.util.Objects.requireNonNull(url, "configured")
                                                .toString(),
                                        "<url>");
                retryable = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Delivery.failed(attempt, "interrupted");
            }
            if (!retryable || attempt > retries) {
                return Delivery.failed(attempt, last + (attempt > 1 ? " after " + attempt + " attempts" : ""));
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Delivery.failed(attempt, last + "; interrupted");
            }
            wait = Math.min(wait * 2, maxBackoff.toMillis());
        }
    }

    /** {@code HMAC-SHA256(secret, text)} in lower-case hex: what {@code X-Pravaha-Signature} carries. */
    public static String sign(byte[] secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    @Override
    public HealthStatus health() {
        return client == null ? HealthStatus.unhealthy("not open") : HealthStatus.healthy();
    }

    @Override
    public void close() {
        client = null;
    }

    /** A value given inline ({@code inline}), from the environment, or from a file -- one of them. */
    private @Nullable String reference(
            Map<String, String> options, @Nullable String inline, String env, String file, String what)
            throws ConfigurationException {
        int given = (inline != null && options.containsKey(inline) ? 1 : 0)
                + (options.containsKey(env) ? 1 : 0)
                + (options.containsKey(file) ? 1 : 0);
        if (given > 1) {
            throw misconfigured("gives " + what + " more than one way; give one");
        }
        if (inline != null && options.containsKey(inline)) {
            return options.get(inline);
        }
        if (options.containsKey(env)) {
            String variable = options.get(env).strip();
            String value = environment.apply(variable);
            if (value == null || value.isBlank()) {
                throw misconfigured(env + " names the environment variable " + variable + ", which is not set");
            }
            return value.strip();
        }
        if (options.containsKey(file)) {
            Path path = Path.of(options.get(file).strip());
            try {
                String value = Files.readString(path).strip();
                if (value.isEmpty()) {
                    throw misconfigured(file + " names " + path + ", which is empty");
                }
                return value;
            } catch (IOException e) {
                throw misconfigured(file + " names " + path + ", which cannot be read ("
                        + e.getClass().getSimpleName() + ")");
            }
        }
        return null;
    }

    private int integer(Map<String, String> options, String key, int fallback) throws ConfigurationException {
        String value = options.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed < 0 || parsed > 20) {
                throw misconfigured(key + " is 0 to 20, not " + parsed);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw misconfigured(key + " is a number, not '" + value + "'");
        }
    }

    private Duration duration(Map<String, String> options, String key, Duration fallback)
            throws ConfigurationException {
        String value = options.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            Duration parsed = AlertOptions.duration(key, value);
            if (parsed.isZero() && !key.contains("backoff")) {
                throw misconfigured(key + " must be more than zero");
            }
            return parsed;
        } catch (com.ash.messaging.pravaha.api.PravahaException e) {
            throw misconfigured(key + " is a duration such as 500ms, 10s or PT10S, not '" + value + "'");
        }
    }

    private ConfigurationException misconfigured(String what) {
        return new ConfigurationException(
                AlertErrors.NOTIFIER_MISCONFIGURED, "the webhook channel '" + channel + "' " + what);
    }
}
