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

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.api.plugin.Notification;
import com.ash.messaging.pravaha.serving.ViewQuery;

import static com.ash.messaging.pravaha.registry.alert.AlertFixture.BUYER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Alerts (ADR-057) over the retail study's low-stock view: a key fires when its row enters the answer
 * and clears when it leaves -- by an update back over the reorder point or a delete -- and the flap
 * controls, snooze, pause and a restart change what is <em>said</em>, never what is true.
 */
@Timeout(60)
class AlertServiceTest {

    @TempDir
    Path dir;

    private AlertFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private AlertFixture start(String options) {
        fixture = new AlertFixture();
        fixture.open(dir.resolve("alerts.journal"));
        fixture.sql(BUYER, "CREATE ALERT low ON low_stock NOTIFY buyers" + options);
        return fixture;
    }

    @Test
    void aKeyFiresWhenItsRowEntersAndClearsWhenAnUpdateOrADeleteTakesItOut() {
        AlertFixture f = start("");
        f.stock("sku-100", "LDN", 40, 10, 1); // above the reorder point: not in the view
        f.tick();
        assertThat(f.sent()).isEmpty();

        f.update("sku-100", "LDN", 40, 9, 10); // the update crosses the reorder point
        f.tick();
        assertThat(f.sent()).containsExactly("FIRED sku-100/LDN");

        f.update("sku-100", "LDN", 9, 8, 10); // still low: the row changed, the key did not enter again
        f.tick();
        assertThat(f.sent()).hasSize(1);

        f.update("sku-100", "LDN", 8, 30, 10); // a delivery: the -1 takes it out
        f.tick();
        assertThat(f.sent()).containsExactly("FIRED sku-100/LDN", "CLEARED sku-100/LDN");

        f.stock("sku-400", "MAN", 3, 4, 1); // opens under its reorder point
        f.tick();
        f.stock("sku-400", "MAN", 3, 4, -1); // deleted
        f.tick();
        assertThat(f.sent()).endsWith("FIRED sku-400/MAN", "CLEARED sku-400/MAN");

        Notification fired = f.channel.accepted.get(0);
        assertThat(fired.alert()).isEqualTo("low");
        assertThat(fired.view()).isEqualTo("low_stock");
        assertThat(fired.severity()).isEqualTo("warning");
        assertThat(fired.episode()).isEqualTo(1);
        assertThat(fired.row()).containsEntry("on_hand", 9L).containsEntry("reorder_point", 10L);
        assertThat(fired.toJson())
                .contains("\"kind\":\"FIRED\"", "\"key\":{\"sku\":\"sku-100\",\"warehouse\":\"LDN\"}");
        assertThat(f.channel.accepted.get(1).idempotencyKey()).isNotEqualTo(fired.idempotencyKey());
    }

    @Test
    void aConditionNarrowsTheViewAndIncludeNarrowsWhatIsSent() {
        fixture = new AlertFixture();
        fixture.open(dir.resolve("alerts.journal"));
        fixture.sql(
                BUYER,
                "CREATE ALERT london ON low_stock WHERE warehouse = 'LDN' AND on_hand < 5 NOTIFY buyers "
                        + "WITH (severity = 'critical', include = (sku, on_hand))");
        fixture.stock("sku-1", "MAN", 1, 5, 1);
        fixture.stock("sku-2", "LDN", 4, 5, 1);
        fixture.stock("sku-3", "LDN", 5, 5, 1);
        fixture.tick();
        assertThat(fixture.sent()).containsExactly("FIRED sku-2/LDN");
        Notification sent = fixture.channel.accepted.get(0);
        assertThat(sent.row()).containsOnlyKeys("sku", "on_hand");
        assertThat(sent.severity()).isEqualTo("critical");
        // sku-3 falls under 5 within LDN: it enters the condition while staying in the view.
        fixture.update("sku-3", "LDN", 5, 2, 5);
        fixture.tick();
        assertThat(fixture.sent()).containsExactly("FIRED sku-2/LDN", "FIRED sku-3/LDN");
    }

    @Test
    void fireAfterAndClearAfterSwallowAFlap() {
        AlertFixture f = start(" WITH (fire_after = '1m', clear_after = '2m')");
        f.stock("sku-1", "LDN", 1, 5, 1);
        f.after(Duration.ofSeconds(30));
        f.stock("sku-1", "LDN", 1, 5, -1); // gone before a minute: never fired
        f.after(Duration.ofMinutes(5));
        assertThat(f.sent()).isEmpty();

        f.stock("sku-1", "LDN", 1, 5, 1);
        f.after(Duration.ofSeconds(59));
        assertThat(f.sent()).isEmpty();
        f.after(Duration.ofSeconds(1));
        assertThat(f.sent()).containsExactly("FIRED sku-1/LDN");

        f.update("sku-1", "LDN", 1, 50, 5); // out...
        f.after(Duration.ofMinutes(1));
        f.update("sku-1", "LDN", 50, 2, 5); // ...and back inside clear_after: still firing, nothing said
        f.after(Duration.ofMinutes(5));
        assertThat(f.sent()).containsExactly("FIRED sku-1/LDN");

        f.update("sku-1", "LDN", 2, 50, 5);
        f.after(Duration.ofMinutes(1));
        assertThat(f.sent()).hasSize(1);
        f.after(Duration.ofMinutes(1));
        assertThat(f.sent()).containsExactly("FIRED sku-1/LDN", "CLEARED sku-1/LDN");
    }

    @Test
    void dedupeFoldsFlapsIntoTheStateAtTheEndOfTheWindow() {
        AlertFixture f = start(" WITH (dedupe = '10m')");
        f.stock("sku-1", "LDN", 1, 5, 1);
        f.tick();
        assertThat(f.sent()).containsExactly("FIRED sku-1/LDN");

        f.update("sku-1", "LDN", 1, 50, 5); // clears at +1m: held back
        f.after(Duration.ofMinutes(1));
        f.update("sku-1", "LDN", 50, 1, 5); // fires again at +2m: the receiver already thinks it is firing
        f.after(Duration.ofMinutes(1));
        f.after(Duration.ofMinutes(10));
        assertThat(f.sent()).containsExactly("FIRED sku-1/LDN");
        AlertStatus.KeyStatus key = f.service.detail(BUYER, "low").keys().get(0);
        assertThat(key.episode()).isEqualTo(2);
        assertThat(key.fired()).isEqualTo(2);

        f.update("sku-1", "LDN", 1, 50, 5); // the window has passed: a clear is said at once
        f.tick();
        assertThat(f.sent()).containsExactly("FIRED sku-1/LDN", "CLEARED sku-1/LDN");
        assertThat(f.channel.accepted.get(1).episode()).isEqualTo(2);
    }

    @Test
    void aSnoozeHoldsNotificationsBackAndSaysWhatChangedWhenItEnds() {
        AlertFixture f = start("");
        f.sql(BUYER, "SNOOZE ALERT low FOR '1h'");
        f.stock("sku-1", "LDN", 1, 5, 1);
        f.stock("sku-2", "LDN", 1, 5, 1);
        f.after(Duration.ofMinutes(10));
        f.stock("sku-2", "LDN", 1, 5, -1); // fired and cleared inside the snooze: never announced
        f.after(Duration.ofMinutes(10));
        assertThat(f.sent()).isEmpty();
        assertThat(f.service.detail(BUYER, "low").alert().state()).isEqualTo("SNOOZED");

        f.after(Duration.ofMinutes(41));
        assertThat(f.sent()).containsExactly("FIRED sku-1/LDN");
        assertThat(f.service.detail(BUYER, "low").alert().state()).isEqualTo("ACTIVE");
    }

    @Test
    void pauseAndResume() {
        AlertFixture f = start("");
        f.stock("sku-1", "LDN", 1, 5, 1);
        f.tick();
        ViewQuery.Result paused = f.sql(BUYER, "PAUSE ALERT low");
        assertThat(paused.rows().get(0)[1]).isEqualTo("PAUSED");

        f.update("sku-1", "LDN", 1, 50, 5); // cleared while paused
        f.stock("sku-2", "MAN", 1, 5, 1); // fired while paused
        f.stock("sku-3", "MAN", 1, 5, 1);
        f.stock("sku-3", "MAN", 1, 5, -1); // fired and cleared while paused
        f.after(Duration.ofMinutes(5));
        assertThat(f.sent()).containsExactly("FIRED sku-1/LDN");

        f.sql(BUYER, "RESUME ALERT low");
        f.tick();
        assertThat(f.sent()).containsExactlyInAnyOrder("FIRED sku-1/LDN", "CLEARED sku-1/LDN", "FIRED sku-2/MAN");
    }

    @Test
    void remindersRepeatUntilAcknowledged() {
        AlertFixture f = start(" WITH (resend_every = '10m')");
        f.stock("sku-1", "LDN", 1, 5, 1);
        f.tick();
        f.after(Duration.ofMinutes(10));
        assertThat(f.sent()).containsExactly("FIRED sku-1/LDN", "REMINDER sku-1/LDN");
        assertThat(f.channel.accepted.get(1).idempotencyKey())
                .isNotEqualTo(f.channel.accepted.get(0).idempotencyKey());

        assertThat(f.service.acknowledge(BUYER, "low", "sku=sku-1, warehouse=LDN"))
                .isEqualTo(1);
        f.after(Duration.ofMinutes(30));
        assertThat(f.sent()).hasSize(2);
        assertThat(f.service.detail(BUYER, "low").keys().get(0).acknowledgedBy())
                .isEqualTo("bea");
    }

    @Test
    void aRestartNeitherReFiresAFiringKeyNorLosesAClear() {
        AlertFixture f = start("");
        f.stock("sku-1", "LDN", 1, 5, 1);
        f.stock("sku-2", "LDN", 1, 5, 1);
        f.tick();
        assertThat(f.sent()).containsExactlyInAnyOrder("FIRED sku-1/LDN", "FIRED sku-2/LDN");

        // sku-2 clears, and the channel is down: the clear is decided, journalled, and owed.
        f.channel.refuse.set(100);
        f.update("sku-2", "LDN", 1, 50, 5);
        f.tick();
        Notification failed = f.channel.attempted.get(f.channel.attempted.size() - 1);
        assertThat(failed.kind()).isEqualTo("CLEARED");
        assertThat(f.service.list(BUYER).get(0).deliveryError()).contains("HTTP 503");

        // The alert service stops (the node restarts); the views stay as they are.
        f.service.close();
        f.channel.refuse.set(0);
        f.channel.accepted.clear();
        f.open(dir.resolve("alerts.journal"));
        f.tick();

        // sku-1 is firing and was told: nothing. sku-2's clear was owed: sent, under the same key.
        assertThat(f.sent()).containsExactly("CLEARED sku-2/LDN");
        assertThat(f.channel.accepted.get(0).idempotencyKey()).isEqualTo(failed.idempotencyKey());
        AlertStatus.Detail detail = f.service.detail(BUYER, "low");
        assertThat(detail.keys()).extracting(AlertStatus.KeyStatus::state).containsExactly("FIRING", "CLEARED");
        assertThat(detail.keys().get(0).episode()).isEqualTo(1);

        // And a clear that happened while it was down -- the row left the view -- is said once it is back.
        f.service.close();
        f.channel.accepted.clear();
        f.update("sku-1", "LDN", 1, 50, 5);
        f.open(dir.resolve("alerts.journal"));
        f.tick();
        assertThat(f.sent()).containsExactly("CLEARED sku-1/LDN");
    }

    @Test
    void aFailedDeliveryIsSentAgainLaterUnderTheSameKey() {
        AlertFixture f = start("");
        f.channel.refuse.set(1);
        f.stock("sku-1", "LDN", 1, 5, 1);
        f.tick();
        assertThat(f.sent()).isEmpty();
        f.after(Duration.ofSeconds(30));
        assertThat(f.channel.attempted).hasSize(1); // waits redeliver-after
        f.after(Duration.ofSeconds(31));
        assertThat(f.sent()).containsExactly("FIRED sku-1/LDN");
        assertThat(f.channel.attempted.get(0).idempotencyKey())
                .isEqualTo(f.channel.accepted.get(0).idempotencyKey());
        AlertStatus.Detail detail = f.service.detail(BUYER, "low");
        assertThat(detail.notifications()).extracting(AlertStatus.Sent::outcome).containsExactly("DELIVERED", "FAILED");
        assertThat(detail.alert().deliveryError()).isNull();
    }

    @Test
    void droppingAViewAnAlertFollowsIsRefusedUntilTheAlertIsDropped() {
        AlertFixture f = start("");
        assertThatThrownBy(() -> f.registry.drop("low_stock"))
                .hasMessageContaining("PRV-8024")
                .hasMessageContaining("ALERT low");
        assertThat(f.sql(BUYER, "DROP ALERT low").rows().get(0)[1]).isEqualTo("DROPPED");
        f.registry.drop("low_stock");
        assertThat(f.registry.find("low_stock")).isEmpty();
    }

    @Test
    void theStatementsAnswerAsRowsAndRefuseWhatCannotBeKept() {
        AlertFixture f = start(" WITH (dedupe = '5m')");
        ViewQuery.Result shown = f.sql(BUYER, "SHOW ALERTS");
        assertThat(shown.rows()).singleElement().satisfies(row -> {
            assertThat(row[0]).isEqualTo("low");
            assertThat(row[1]).isEqualTo("low_stock");
            assertThat(row[2]).isEqualTo("ACTIVE");
            assertThat(row[3]).isEqualTo("FOLLOWING");
        });
        f.sql(BUYER, "ALTER ALERT low SET (severity = 'critical', resend_every = '1h')");
        f.sql(BUYER, "ALTER ALERT low NOTIFY buyers, ops");
        AlertStatus.Summary summary = f.service.list(BUYER).get(0);
        assertThat(summary.severity()).isEqualTo("critical");
        assertThat(summary.channels()).containsExactly("buyers", "ops");
        assertThat(summary.options()).containsEntry("dedupe", "PT5M").containsEntry("resend_every", "PT1H");

        assertThatThrownBy(() -> f.sql(BUYER, "CREATE ALERT low ON low_stock NOTIFY buyers"))
                .hasMessageContaining("PRV-8041");
        f.sql(BUYER, "CREATE ALERT IF NOT EXISTS low ON low_stock NOTIFY buyers");
        assertThatThrownBy(() -> f.sql(BUYER, "CREATE ALERT low_stock ON low_stock NOTIFY buyers"))
                .hasMessageContaining("PRV-8041");
        assertThatThrownBy(() -> f.sql(BUYER, "CREATE ALERT a ON nothing NOTIFY buyers"))
                .hasMessageContaining("PRV-8042");
        assertThatThrownBy(() -> f.sql(BUYER, "CREATE ALERT a ON low_stock WHERE colour = 'red' NOTIFY buyers"))
                .hasMessageContaining("PRV-8042")
                .hasMessageContaining("colour");
        assertThatThrownBy(() -> f.sql(BUYER, "CREATE ALERT a ON low_stock WHERE on_hand < 'many' NOTIFY buyers"))
                .hasMessageContaining("PRV-8042");
        assertThatThrownBy(() -> f.sql(BUYER, "CREATE ALERT a ON low_stock NOTIFY pager"))
                .hasMessageContaining("PRV-8043");
        assertThatThrownBy(() -> f.sql(BUYER, "CREATE ALERT a ON low_stock NOTIFY buyers WITH (volume = 11)"))
                .hasMessageContaining("PRV-8042");
        assertThatThrownBy(() ->
                        f.sql(BUYER, "CREATE ALERT a ON low_stock WHERE on_hand < 1 OR on_hand > 9 " + "NOTIFY buyers"))
                .hasMessageContaining("PRV-2072");
        assertThatThrownBy(() -> f.sql(BUYER, "PAUSE ALERT nothing")).hasMessageContaining("PRV-8040");
        assertThat(f.sql(BUYER, "DROP ALERT IF EXISTS nothing").rows().get(0)[1])
                .isEqualTo("ABSENT");
    }

    @Test
    void withoutAnAlertServiceTheStatementsAreRefusedByName() {
        fixture = new AlertFixture();
        assertThatThrownBy(() -> fixture.sql(BUYER, "SHOW ALERTS")).hasMessageContaining("PRV-8047");
    }

    @Test
    void theBackgroundThreadEvaluatesAndDelivers() {
        fixture = new AlertFixture();
        fixture.open(null, AlertService.Settings.defaults());
        fixture.sql(BUYER, "CREATE ALERT low ON low_stock NOTIFY buyers");
        fixture.stock("sku-1", "LDN", 1, 5, 1);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (fixture.channel.accepted.isEmpty() && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
        }
        assertThat(fixture.sent()).isEqualTo(List.of("FIRED sku-1/LDN"));
    }
}
