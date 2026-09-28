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
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The notification an alert hands a channel (ADR-057). Its JSON is what a webhook signs, so the
 * encoding is pinned byte for byte: a receiver verifies the HMAC over exactly these characters.
 */
class NotificationTest {

    private static Notification fired(Map<String, Object> key, Map<String, Object> row) {
        return new Notification(
                "idem-1",
                "low_stock",
                "inventory.low_stock",
                "acme",
                "FIRED",
                "warning",
                3L,
                key,
                row,
                Instant.parse("2026-09-28T09:15:00Z"),
                Instant.parse("2026-09-28T09:15:02Z"),
                1);
    }

    @Test
    void theJsonCarriesEveryFieldInAFixedOrderWithTheSummaryLast() {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("sku", "400");
        key.put("site", "MAN");
        Notification n = fired(key, Map.of("on_hand", 3L));

        assertThat(n.toJson())
                .isEqualTo("{\"idempotencyKey\":\"idem-1\",\"alert\":\"low_stock\",\"view\":\"inventory.low_stock\","
                        + "\"tenant\":\"acme\",\"kind\":\"FIRED\",\"severity\":\"warning\",\"episode\":3,"
                        + "\"key\":{\"sku\":\"400\",\"site\":\"MAN\"},\"row\":{\"on_hand\":3},"
                        + "\"since\":\"2026-09-28T09:15:00Z\",\"at\":\"2026-09-28T09:15:02Z\",\"attempt\":1,"
                        + "\"summary\":\"low_stock FIRED (warning) for sku=400, site=MAN\"}");
    }

    @Test
    void everyValueTypeIsEncodedAsJsonCanCarryIt() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nothing", null);
        row.put("flag", true);
        row.put("price", new BigDecimal("1E+3"));
        row.put("nan", Double.NaN);
        row.put("inf", Float.POSITIVE_INFINITY);
        row.put("ratio", 0.5d);
        row.put("bytes", new byte[] {1, 2, 3});
        row.put("nested", Map.of("a", 1));
        row.put("text", "q\"b\\n\nr\rt\t" + (char) 0x01 + (char) 0x2028);
        Notification n = fired(Map.of(), row);

        assertThat(n.toJson())
                .contains("\"nothing\":null")
                .contains("\"flag\":true")
                .contains("\"price\":1000")
                .contains("\"nan\":\"NaN\"")
                .contains("\"inf\":\"Infinity\"")
                .contains("\"ratio\":0.5")
                .contains("\"bytes\":\"AQID\"")
                .contains("\"nested\":{\"a\":1}")
                .contains("\"text\":\"q\\\"b\\\\n\\nr\\rt\\t\\u0001\\u2028\"");
    }

    @Test
    void absentOptionalFieldsAreNullAndTheSummaryOmitsWhatIsMissing() {
        Notification n = new Notification("i", "a", null, null, "CLEARED", null, 0L, null, null, null, null, 0);

        assertThat(n.key()).isEmpty();
        assertThat(n.row()).isEmpty();
        assertThat(n.summary()).isEqualTo("a CLEARED");
        assertThat(n.toJson())
                .contains("\"view\":null")
                .contains("\"since\":null")
                .contains("\"at\":null");
    }

    @Test
    void anAttemptIsTheSameNotificationWithOnlyItsNumberChanged() {
        Notification first = fired(Map.of("sku", "1"), Map.of());
        Notification third = first.attempt(3);

        assertThat(third.attempt()).isEqualTo(3);
        assertThat(third.idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(third.key()).isEqualTo(first.key());
    }

    @Test
    void theKeyAndRowAreCopiedAndCannotBeChangedAfterwards() {
        Map<String, Object> key = new LinkedHashMap<>(Map.of("sku", "1"));
        Notification n = fired(key, Map.of());
        key.put("sku", "2");

        assertThat(n.key()).containsEntry("sku", "1");
        assertThatThrownBy(() -> n.key().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theRequiredFieldsAreRequired() {
        assertThatThrownBy(() -> new Notification(null, "a", null, null, "FIRED", null, 0, null, null, null, null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("idempotencyKey");
        assertThatThrownBy(() -> new Notification("i", "a", null, null, null, null, 0, null, null, null, null, 0))
                .hasMessage("kind");
    }

    @Test
    void jsonStringQuotesAndEscapes() {
        assertThat(Notification.jsonString("a\"b")).isEqualTo("\"a\\\"b\"");
    }

    @Test
    void aDeliveryReportsWhetherItArrivedAndNeverCarriesANullDetail() {
        assertThat(NotifierPlugin.Delivery.delivered(2, "202")).satisfies(d -> {
            assertThat(d.delivered()).isTrue();
            assertThat(d.attempts()).isEqualTo(2);
            assertThat(d.detail()).isEqualTo("202");
        });
        assertThat(NotifierPlugin.Delivery.failed(5, null)).satisfies(d -> {
            assertThat(d.delivered()).isFalse();
            assertThat(d.detail()).isEmpty();
        });
    }
}
