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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.ash.messaging.pravaha.common.observe.EngineSpans;

import static com.ash.messaging.pravaha.registry.alert.AlertFixture.BUYER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the alerts count for the node's meters: keys fired and cleared per alert, notifications
 * delivered, failed and retried per channel with their time, what is owed, and journal writes that
 * failed -- and the span each delivery is traced under when a tracer is installed.
 */
@Timeout(60)
class AlertStatisticsTest {

    @TempDir
    Path dir;

    private AlertFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private AlertFixture start() {
        fixture = new AlertFixture();
        fixture.open(dir.resolve("alerts.journal"));
        fixture.sql(BUYER, "CREATE ALERT low ON low_stock NOTIFY buyers");
        return fixture;
    }

    @Test
    void firesAndClearsAreCountedPerAlertAndKind() {
        AlertFixture f = start();
        AlertStatistics counted = f.service.statistics();
        assertThat(counted.transitions("low", AlertStatistics.FIRED)).isZero();

        f.stock("sku-1", "LDN", 1, 5, 1);
        f.tick();
        assertThat(counted.transitions("low", AlertStatistics.FIRED)).isEqualTo(1);
        assertThat(f.service.firingByAlert()).containsEntry("low", 1);

        f.stock("sku-1", "LDN", 1, 5, -1);
        f.tick();
        assertThat(counted.transitions("low", AlertStatistics.CLEARED)).isEqualTo(1);
        assertThat(f.service.firingByAlert()).containsEntry("low", 0);
        assertThat(counted.alertsCounted()).containsExactly("low");

        AlertStatistics.Channel buyers = counted.channel("buyers");
        assertThat(buyers.delivered()).isEqualTo(2);
        assertThat(buyers.failed()).isZero();
        assertThat(buyers.sends()).isEqualTo(2);
        assertThat(buyers.totalNanos()).isPositive();
    }

    @Test
    void aRefusedNotificationIsAFailureThenARetryAndIsOwedMeanwhile() {
        AlertFixture f = start();
        f.channel.refuse.set(1);
        f.stock("sku-1", "LDN", 1, 5, 1);
        f.tick();

        AlertStatistics.Channel buyers = f.service.statistics().channel("buyers");
        assertThat(buyers.failed()).isEqualTo(1);
        assertThat(buyers.retries()).isZero();
        assertThat(f.service.owed()).isEqualTo(1);

        f.after(Duration.ofMinutes(2)); // past redeliver-after: sent again, and accepted
        assertThat(buyers.delivered()).isEqualTo(1);
        assertThat(buyers.retries()).isEqualTo(1);
        assertThat(f.service.owed()).isZero();
    }

    @Test
    void aDroppedAlertsCountsAreForgotten() {
        AlertFixture f = start();
        f.stock("sku-1", "LDN", 1, 5, 1);
        f.tick();
        f.sql(BUYER, "DROP ALERT low");
        assertThat(f.service.statistics().alertsCounted()).isEmpty();
        assertThat(f.service.firingByAlert()).isEmpty();
    }

    @Test
    void aJournalThatCannotBeWrittenIsCountedAndTheDecisionIsNotMade() throws Exception {
        AlertFixture f = start();
        Path journal = dir.resolve("alerts.journal");
        // Replace the journal with a directory: every append after this fails.
        Files.delete(journal);
        Files.createDirectory(journal);
        f.stock("sku-1", "LDN", 1, 5, 1);
        assertThatThrownBy(f::tick).isInstanceOf(RuntimeException.class);
        assertThat(f.service.statistics().journalFailures()).isPositive();
    }

    @Test
    void eachDeliveryIsTracedUnderTheAlertTheChannelAndTheKindWhenATracerIsInstalled() {
        List<String> spans = new ArrayList<>();
        EngineSpans.Backend recording = (name, attributes) -> {
            spans.add(name + " " + new java.util.TreeMap<>(attributes));
            return EngineSpans.NO_SPAN;
        };
        EngineSpans.install(recording);
        try {
            AlertFixture f = start();
            f.stock("sku-1", "LDN", 1, 5, 1);
            f.tick();
        } finally {
            EngineSpans.uninstall(recording);
        }
        assertThat(spans)
                .contains("pravaha.alert.notify "
                        + new java.util.TreeMap<>(Map.of(
                                "pravaha.alert", "low",
                                "pravaha.alert.channel", "buyers",
                                "pravaha.alert.kind", "FIRED")));
    }
}
