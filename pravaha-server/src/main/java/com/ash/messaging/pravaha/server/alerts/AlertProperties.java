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
package com.ash.messaging.pravaha.server.alerts;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.registry.alert.AlertService;

/**
 * {@code pravaha.alerts.*}: the node's alert service (ADR-057).
 *
 * <pre>
 * pravaha:
 *   alerts:
 *     enabled: true               # CREATE ALERT and /api/v1/alerts; false answers PRV-8047
 *     journal: ""                 # default: alerts.journal beside the registry journal
 *     recovery-grace: 30s         # after a restart, how long a firing key missing from the restored
 *                                 # answer waits before it may clear
 *     redeliver-after: 60s        # how long an undelivered notification waits before it is sent again
 *     delivery-threads: 2
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "pravaha.alerts")
public class AlertProperties {

    private boolean enabled = true;
    private String journal = "";
    private Duration recoveryGrace = Duration.ofSeconds(30);
    private Duration redeliverAfter = Duration.ofSeconds(60);
    private int deliveryThreads = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJournal() {
        return journal;
    }

    public void setJournal(String journal) {
        this.journal = journal == null ? "" : journal.strip();
    }

    public Duration getRecoveryGrace() {
        return recoveryGrace;
    }

    public void setRecoveryGrace(Duration recoveryGrace) {
        this.recoveryGrace = recoveryGrace == null ? Duration.ZERO : recoveryGrace;
    }

    public Duration getRedeliverAfter() {
        return redeliverAfter;
    }

    public void setRedeliverAfter(Duration redeliverAfter) {
        this.redeliverAfter = redeliverAfter == null ? Duration.ofSeconds(60) : redeliverAfter;
    }

    public int getDeliveryThreads() {
        return deliveryThreads;
    }

    public void setDeliveryThreads(int deliveryThreads) {
        this.deliveryThreads = Math.max(1, deliveryThreads);
    }

    /** The service's settings, evaluating in the background. */
    public AlertService.Settings settings() {
        return new AlertService.Settings(Duration.ofMillis(250), recoveryGrace, redeliverAfter, deliveryThreads, true);
    }
}
