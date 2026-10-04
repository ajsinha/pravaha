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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.plugin.Notification;
import com.ash.messaging.pravaha.api.plugin.NotifierPlugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code webhook} against a real HTTP server on loopback: the body is the notification's JSON, the
 * signature verifies under the secret the binding names, a 500 is retried with backoff under the same
 * idempotency key, a 400 is not, and a secret written into the configuration is refused.
 */
@Timeout(60)
class WebhookNotifierTest {

    private static final String SECRET = "s3cret-for-tests";

    /** One request as the receiver saw it. */
    record Received(Map<String, List<String>> headers, String body) {
        @Nullable
        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0))
                    .findFirst()
                    .orElse(null);
        }
    }

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Deque<Integer> statuses = new ArrayDeque<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(Map.copyOf(exchange.getRequestHeaders()), body));
            int status;
            synchronized (statuses) {
                status = statuses.isEmpty() ? 200 : statuses.poll();
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private Notifiers channel(Map<String, String> options) {
        return Notifiers.none()
                .bind(new Notifiers.Binding("hook", "webhook", options))
                .open();
    }

    private static Notification notification() {
        return new Notification(
                "0123456789abcdef0123456789abcdef",
                "low",
                "low_stock",
                "acme",
                "FIRED",
                "warning",
                1,
                keyOf(),
                Map.of("on_hand", 9L),
                Instant.parse("2026-06-01T09:05:00Z"),
                Instant.parse("2026-06-01T09:05:00Z"),
                1);
    }

    @Test
    void theBodyIsSignedAndTheSignatureVerifiesUnderTheSecret() throws Exception {
        Path secret = dir.resolve("hook.secret");
        Files.writeString(secret, SECRET + "\n");
        Notifiers notifiers = channel(Map.of("url", url(), "secret-file", secret.toString()));
        NotifierPlugin.Delivery delivery = notifiers.send("hook", notification());
        assertThat(delivery.delivered()).isTrue();

        Received request = received.get(0);
        assertThat(request.body()).contains("\"alert\":\"low\"", "\"kind\":\"FIRED\"", "\"sku\":\"sku-100\"");
        assertThat(request.header("Idempotency-Key")).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(request.header("X-Pravaha-Event")).isEqualTo("FIRED");
        assertThat(request.header("Content-Type")).isEqualTo("application/json");
        // What a receiver does: HMAC-SHA256 over "<timestamp>.<body>", compared in constant time.
        String timestamp = request.header("X-Pravaha-Timestamp");
        String expected = "sha256="
                + WebhookNotifier.sign(SECRET.getBytes(StandardCharsets.UTF_8), timestamp + "." + request.body());
        assertThat(MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        java.util.Objects.requireNonNull(request.header("X-Pravaha-Signature"))
                                .getBytes(StandardCharsets.UTF_8)))
                .isTrue();
        // Under another secret it does not verify.
        assertThat(request.header("X-Pravaha-Signature"))
                .isNotEqualTo("sha256="
                        + WebhookNotifier.sign(
                                "other".getBytes(StandardCharsets.UTF_8), timestamp + "." + request.body()));
    }

    @Test
    void aServerErrorIsRetriedWithBackoffUnderTheSameIdempotencyKey() {
        statuses.add(500);
        statuses.add(503);
        Notifiers notifiers = new Notifiers()
                .bind("hook", configured(Map.of("url", url(), "secret-env", "HOOK_SECRET", "backoff", "10ms")))
                .open();
        NotifierPlugin.Delivery delivery = notifiers.send("hook", notification());
        assertThat(delivery.delivered()).isTrue();
        assertThat(delivery.attempts()).isEqualTo(3);
        assertThat(received).hasSize(3);
        assertThat(received)
                .extracting(r -> r.header("Idempotency-Key"))
                .containsOnly("0123456789abcdef0123456789abcdef");
        assertThat(received).extracting(r -> r.header("X-Pravaha-Attempt")).containsExactly("1", "2", "3");
    }

    @Test
    void aRefusalIsNotRetriedAndRetriesEndAtTheirLimit() {
        statuses.add(400);
        Notifiers notifiers = new Notifiers()
                .bind(
                        "hook",
                        configured(
                                Map.of("url", url(), "secret-env", "HOOK_SECRET", "backoff", "10ms", "retries", "2")))
                .open();
        NotifierPlugin.Delivery refused = notifiers.send("hook", notification());
        assertThat(refused.delivered()).isFalse();
        assertThat(refused.detail()).isEqualTo("HTTP 400");
        assertThat(received).hasSize(1);

        statuses.add(500);
        statuses.add(500);
        statuses.add(500);
        NotifierPlugin.Delivery failed = notifiers.send("hook", notification());
        assertThat(failed.delivered()).isFalse();
        assertThat(failed.detail()).isEqualTo("HTTP 500 after 3 attempts");
        assertThat(received).hasSize(4);
    }

    @Test
    void slackFormatSendsText() {
        Notifiers notifiers = channel(Map.of("url", url(), "signing", "none", "format", "slack"));
        assertThat(notifiers.send("hook", notification()).delivered()).isTrue();
        assertThat(received.get(0).body())
                .isEqualTo("{\"text\":\"low FIRED (warning) for sku=sku-100, warehouse=LDN\"}");
        assertThat(received.get(0).header("X-Pravaha-Signature")).isNull();
    }

    @Test
    void secretsAreNeverConfiguration() {
        assertThatThrownBy(() -> channel(Map.of("url", url(), "secret", SECRET)))
                .hasMessageContaining("PRV-8046")
                .hasMessageContaining("secret-env")
                .hasMessageNotContaining(SECRET);
        assertThatThrownBy(() -> channel(Map.of("url", url())))
                .hasMessageContaining("PRV-8046")
                .hasMessageContaining("no secret");
        assertThatThrownBy(() -> channel(Map.of("url", url(), "secret-env", "PRAVAHA_NO_SUCH_VARIABLE_FOR_TESTS")))
                .hasMessageContaining("not set");
        assertThatThrownBy(() -> channel(Map.of("url", url(), "signing", "none", "header.Authorization", "Bearer x")))
                .hasMessageContaining("credential");
        assertThatThrownBy(() -> channel(Map.of("url", "ftp://example.test", "signing", "none")))
                .hasMessageContaining("http or https");
        assertThatThrownBy(() -> Notifiers.none()
                        .bind(new Notifiers.Binding("x", "carrier-pigeon", Map.of()))
                        .open())
                .hasMessageContaining("PRV-8046")
                .hasMessageContaining("carrier-pigeon");
    }

    private static Map<String, Object> keyOf() {
        Map<String, Object> key = new java.util.LinkedHashMap<>();
        key.put("sku", "sku-100");
        key.put("warehouse", "LDN");
        return key;
    }

    private static NotifierPlugin configured(Map<String, String> options) {
        WebhookNotifier webhook = new WebhookNotifier(name -> name.equals("HOOK_SECRET") ? SECRET : null);
        try {
            webhook.configure(new com.ash.messaging.pravaha.api.plugin.PluginContext() {
                @Override
                public Map<String, String> config() {
                    return options;
                }

                @Override
                public String instanceName() {
                    return "hook";
                }
            });
        } catch (com.ash.messaging.pravaha.api.ConfigurationException e) {
            throw new AssertionError(e);
        }
        webhook.open();
        return webhook;
    }
}
