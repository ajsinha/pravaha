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
package com.ash.messaging.pravaha.api.plugin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One notification about one key of one alert (ADR-057).
 *
 * @param idempotencyKey the same on every attempt to deliver this notification, across retries and
 *     restarts: derived from the alert's identity, the key, the episode and the kind
 * @param alert the alert's name
 * @param view the view it watches
 * @param tenant the tenant the alert belongs to
 * @param kind {@code FIRED} (the key's row entered the condition), {@code CLEARED} (it left), or
 *     {@code REMINDER} (still firing, unacknowledged, and {@code resend_every} has passed)
 * @param severity the alert's severity: {@code info}, {@code warning} or {@code critical}
 * @param episode which firing of this key this is about: 1 for the first time it fired, 2 for the next
 * @param key the view's key columns and their values
 * @param row the columns the alert includes, as they stood when it was decided; empty for a clear whose
 *     row has left the view
 * @param since when the key started firing (for a clear, when the episode began)
 * @param at when the engine decided this transition
 * @param attempt the delivery attempt, from 1 -- the same notification on every attempt
 */
public record Notification(
        String idempotencyKey,
        String alert,
        String view,
        String tenant,
        String kind,
        String severity,
        long episode,
        Map<String, Object> key,
        Map<String, Object> row,
        Instant since,
        Instant at,
        int attempt) {

    public Notification {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(alert, "alert");
        Objects.requireNonNull(kind, "kind");
        key = key == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(key));
        row = row == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }

    /** The same notification, as a later attempt. */
    public Notification attempt(int number) {
        return new Notification(
                idempotencyKey, alert, view, tenant, kind, severity, episode, key, row, since, at, number);
    }

    /** One line a person reads: {@code low_stock FIRED (warning) for sku=sku-100, warehouse=LDN}. */
    public String summary() {
        StringBuilder text = new StringBuilder(alert).append(' ').append(kind);
        if (severity != null && !severity.isEmpty()) {
            text.append(" (").append(severity).append(')');
        }
        if (!key.isEmpty()) {
            text.append(" for ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : key.entrySet()) {
                if (!first) {
                    text.append(", ");
                }
                first = false;
                text.append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        return text.toString();
    }

    /**
     * The notification as a JSON object: the body the webhook sends, and what any plugin that needs one
     * can use rather than inventing its own. Stable field names; values as JSON numbers, strings,
     * booleans or null, bytes as base64.
     */
    public String toJson() {
        StringBuilder json = new StringBuilder(256).append('{');
        field(json, "idempotencyKey", idempotencyKey, true);
        field(json, "alert", alert, false);
        field(json, "view", view, false);
        field(json, "tenant", tenant, false);
        field(json, "kind", kind, false);
        field(json, "severity", severity, false);
        field(json, "episode", episode, false);
        field(json, "key", key, false);
        field(json, "row", row, false);
        field(json, "since", since == null ? null : since.toString(), false);
        field(json, "at", at == null ? null : at.toString(), false);
        field(json, "attempt", attempt, false);
        field(json, "summary", summary(), false);
        return json.append('}').toString();
    }

    /** {@code text} as a JSON string literal, quotes included. */
    public static String jsonString(String text) {
        StringBuilder json = new StringBuilder(text.length() + 2);
        string(json, text);
        return json.toString();
    }

    private static void field(StringBuilder json, String name, Object value, boolean first) {
        if (!first) {
            json.append(',');
        }
        string(json, name);
        json.append(':');
        value(json, value);
    }

    private static void value(StringBuilder json, Object value) {
        // Written when this module targeted Java 17 (1.x), before patterns in switch.
        if (value == null) {
            json.append("null");
        } else if (value instanceof Boolean b) {
            json.append(b);
        } else if (value instanceof BigDecimal d) {
            json.append(d.toPlainString());
        } else if ((value instanceof Double d && (d.isNaN() || d.isInfinite()))
                || (value instanceof Float f && (f.isNaN() || f.isInfinite()))) {
            string(json, value.toString());
        } else if (value instanceof Number n) {
            json.append(n);
        } else if (value instanceof byte[] bytes) {
            string(json, Base64.getEncoder().encodeToString(bytes));
        } else if (value instanceof Map<?, ?> map) {
            json.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                string(json, String.valueOf(entry.getKey()));
                json.append(':');
                value(json, entry.getValue());
            }
            json.append('}');
        } else {
            string(json, value.toString());
        }
    }

    private static void string(StringBuilder json, String text) {
        json.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}
