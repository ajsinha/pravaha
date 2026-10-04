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
package com.ash.messaging.pravaha.serving;

import java.time.Duration;

import com.ash.messaging.pravaha.api.PravahaException;
import com.ash.messaging.pravaha.common.config.ConfigErrors;
import com.ash.messaging.pravaha.common.config.Configuration;

/**
 * {@code pravaha.serving.read.*}: how many reads a node runs at once, how many wait, each tenant's
 * share, and how long one read may run (READADMIT-1).
 *
 * <p>{@link ReadAdmission} and the read deadline existed, and a gateway took them with {@code
 * admitting(admission, deadline)}, but nothing on a node or an embedded engine ever passed them: every
 * read was admitted, with no deadline, so {@code PRV-4026} to {@code PRV-4029} could not occur on a
 * node. These are the settings that do pass them, validated when the node or engine is built.
 *
 * <p>The defaults keep that behaviour -- {@code max-concurrent: 0} admits every read, and {@code
 * deadline: 0s} sets none -- because a limit that appears on upgrade is an outage nobody configured.
 *
 * @param maxConcurrent reads run at once; 0 for no limit
 * @param maxQueued reads that may wait for a permit beyond those; 0 refuses at once ({@code PRV-4026})
 * @param tenantShare the fraction of {@code maxConcurrent} one tenant may hold, in (0, 1]
 * @param queueTimeout how long a queued read waits before {@code PRV-4027}
 * @param deadline how long one read may run before {@code PRV-4029}; zero for none
 */
public record ReadLimits(
        int maxConcurrent, int maxQueued, double tenantShare, Duration queueTimeout, Duration deadline) {

    /** The settings' common prefix. */
    public static final String PREFIX = "pravaha.serving.read.";

    /** Every read admitted, none timed: the behaviour before these settings existed, and the default. */
    public static final ReadLimits NONE = new ReadLimits(0, 0, 1.0, Duration.ofSeconds(2), Duration.ZERO);

    /** Refuses a value that cannot mean what it says, naming the setting ({@code PRV-1026}). */
    public ReadLimits {
        if (maxConcurrent < 0) {
            throw outOfRange("max-concurrent", maxConcurrent, "0 (no limit) or more");
        }
        if (maxQueued < 0) {
            throw outOfRange("max-queued", maxQueued, "0 (refuse at once past max-concurrent) or more");
        }
        if (!(tenantShare > 0.0) || tenantShare > 1.0) {
            throw outOfRange(
                    "tenant-share",
                    tenantShare,
                    "a fraction above 0 and at most 1; 1 lets one tenant hold every permit");
        }
        if (queueTimeout == null || queueTimeout.isNegative()) {
            throw outOfRange("queue-timeout", queueTimeout, "zero or more");
        }
        if (deadline == null || deadline.isNegative()) {
            throw outOfRange("deadline", deadline, "zero (no deadline) or more");
        }
    }

    /** Read from an engine's configuration, each setting defaulting to {@link #NONE}'s. */
    public static ReadLimits from(Configuration configuration) {
        return new ReadLimits(
                configuration.getInt(PREFIX + "max-concurrent", NONE.maxConcurrent()),
                configuration.getInt(PREFIX + "max-queued", NONE.maxQueued()),
                configuration.getDouble(PREFIX + "tenant-share", NONE.tenantShare()),
                configuration.getDuration(PREFIX + "queue-timeout", NONE.queueTimeout()),
                configuration.getDuration(PREFIX + "deadline", NONE.deadline()));
    }

    /**
     * A new admission for these limits, {@link ReadAdmission#UNLIMITED} without one. Ask once and give
     * the same object to every gateway: the limit is the node's, not each gateway's.
     */
    public ReadAdmission admission() {
        return maxConcurrent == 0
                ? ReadAdmission.UNLIMITED
                : new ReadAdmission(maxConcurrent, maxQueued, tenantShare, queueTimeout);
    }

    /** The startup line's words for these limits. */
    public String describe() {
        String admitted = maxConcurrent == 0
                ? "every read admitted (pravaha.serving.read.max-concurrent=0)"
                : "at most " + maxConcurrent + " at once, " + maxQueued + " waiting up to " + queueTimeout.toMillis()
                        + " ms, " + Math.max(1, (int) Math.ceil(maxConcurrent * tenantShare)) + " per tenant";
        return "reads: " + admitted + "; "
                + (deadline.isZero() ? "no read deadline" : "a read deadline of " + deadline.toMillis() + " ms");
    }

    private static PravahaException outOfRange(String setting, Object value, String expected) {
        return new PravahaException(
                ConfigErrors.OUT_OF_RANGE, PREFIX + setting + " is " + value + "; it must be " + expected + ".");
    }
}
