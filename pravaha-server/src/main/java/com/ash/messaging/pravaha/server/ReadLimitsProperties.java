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
package com.ash.messaging.pravaha.server;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ash.messaging.pravaha.serving.ReadLimits;

/**
 * {@code pravaha.serving.read.*}: read admission and the read deadline for the node's Flight and
 * PostgreSQL gateways (READADMIT-1). See {@link ReadLimits}; the defaults are its {@code NONE}, written
 * out as literals so that the help pages' check of every stated default reads the same numbers.
 */
@Component
@ConfigurationProperties(prefix = "pravaha.serving.read", ignoreUnknownFields = false)
public class ReadLimitsProperties {

    private int maxConcurrent = 0;
    private int maxQueued = 0;
    private double tenantShare = 1.0;
    private Duration queueTimeout = Duration.ofSeconds(2);
    private Duration deadline = Duration.ZERO;

    /** The limits; throws PRV-1026 naming the setting for a value out of range. */
    public ReadLimits limits() {
        return new ReadLimits(maxConcurrent, maxQueued, tenantShare, queueTimeout, deadline);
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = maxConcurrent;
    }

    public int getMaxQueued() {
        return maxQueued;
    }

    public void setMaxQueued(int maxQueued) {
        this.maxQueued = maxQueued;
    }

    public double getTenantShare() {
        return tenantShare;
    }

    public void setTenantShare(double tenantShare) {
        this.tenantShare = tenantShare;
    }

    public Duration getQueueTimeout() {
        return queueTimeout;
    }

    public void setQueueTimeout(Duration queueTimeout) {
        this.queueTimeout = queueTimeout;
    }

    public Duration getDeadline() {
        return deadline;
    }

    public void setDeadline(Duration deadline) {
        this.deadline = deadline;
    }
}
